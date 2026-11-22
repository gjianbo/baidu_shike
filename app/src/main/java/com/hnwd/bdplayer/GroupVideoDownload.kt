package com.hnwd.bdplayer

import android.content.Context
import androidx.core.net.toUri
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 群组视频的「能下载」通路：HLS 转码流分片下载 + 拼接。
 *
 * 背景（见 `docs/百度网盘群组视频下载失败-分析与修复.md`）：群组共享库里的视频，
 * `POST /api/sharedownload` 会返回 `errno:0` 但 `list` 是一段加密串，**拿不到原文件直链**
 * —— 网页端同样拿不到。而 `/mbox/msg/streaming` 下发的 HLS 转码流是真实字节：
 *
 * - 主清单（m3u8）需要登录 Cookie，走 [PlaylistFetcher] 的完整重试语义；
 * - 分片自带签名（`expires=8h`）、**不需要 Cookie**，只需 `User-Agent: pan.baidu.com`。
 *
 * 所以下载 = 取清单 → 逐片拉取 → TS 分片归一化 → 顺序写进暂存文件 →
 * 交给 [DownloadedFileStore] 发布。
 * 产物是转码副本：无 `#EXT-X-MAP` 初始化段时是 MPEG-TS（分片拼接），有初始化段时
 * 是 fragmented MP4；文件名与 MIME 由 `DownloadLogic.playlistFileName` 按容器决定。
 * TS 分片必须先过 [HlsTsNormalizer] 把 continuity_counter 接成一条连续流，
 * 否则播放器会在每个分片边界重同步音视频时钟（画面和声音不同步）。
 * `M3U8_AUTO_480` 是自适应档，同一文件多次取址可能拿到不同码率（时长一致、体积不同）。
 * 原始 mp4 只能靠「转存到我的网盘」后走 `/api/download`。
 */
internal object GroupVideoDownload {
    /** 转码流也不可用（排队中 / 加密 / 清单形态不认识）：调用方据此回落到「请先转存」。 */
    class Unsupported(message: String) : IOException(message)

    class Cancelled : IOException("已取消下载")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 取到可下载的分片清单；失败一律抛 [Unsupported] 并带可读原因。 */
    fun plan(entry: PanEntry, userAgent: String, cookie: String?): HlsPlan {
        val stream = PlaylistFetcher.fetch(PanApi.videoPlaylist(entry), userAgent, cookie)
        var body = String(stream.playlist, Charsets.UTF_8)
        var base = stream.uri.toString()
        // master 清单要先选一路再拉子清单；百度多数情况直接给 media 列表。
        if (isMasterPlaylist(body)) {
            val child = pickHlsVariant(body, base)
                ?: throw Unsupported("网盘返回的清晰度清单里没有可用分片，请稍后重试")
            val nested = PlaylistFetcher.fetch(child.toUri(), userAgent, cookie)
            body = String(nested.playlist, Charsets.UTF_8)
            base = nested.uri.toString()
            if (isMasterPlaylist(body)) throw Unsupported("网盘返回了多层清晰度清单，暂不支持")
        }
        val parsed = parseHlsPlan(body, base)
            ?: throw Unsupported("网盘仍在转码该视频，请稍后重试")
        if (parsed.encrypted) {
            throw Unsupported("该视频的转码分片已加密（${parsed.keyMethod}），当前版本暂不支持下载")
        }
        return parsed
    }

    /**
     * 逐片下载并顺序写入暂存文件，返回写入字节数。
     *
     * 单片失败重试 2 次后整条失败：HLS 分片是幂等 GET，重试安全；
     * 但不再无限重试，否则一个大分片挂住会让整个任务一直占着并发槽。
     *
     * 只有 MPEG-TS 分片（无 `#EXT-X-MAP`）才做 CC 归一化；fMP4 分片是 moof 盒，
     * 按 188 字节 TS 包改字节会直接把文件改坏。
     */
    fun download(
        context: Context,
        stagingName: String,
        plan: HlsPlan,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val file = DownloadedFileStore.stagedFile(context, stagingName)
        file.parentFile?.mkdirs()
        file.delete()
        var written = 0L
        // 一个任务一个实例：CC 要跨全部分片累计，不能每片各起一个。
        val normalizer = if (plan.initSegment == null) HlsTsNormalizer() else null
        BufferedOutputStream(file.outputStream(), 128 * 1024).use { output ->
            plan.initSegment?.let { init ->
                if (isCancelled()) throw Cancelled()
                // fMP4 初始化段是 box 结构，不做 TS 归一化。
                val bytes = fetchSegment(init, 0, isCancelled)
                output.write(bytes)
                written += bytes.size
                onProgress(written)
            }
            plan.segments.forEachIndexed { index, url ->
                if (isCancelled()) throw Cancelled()
                val bytes = fetchSegment(url, index, isCancelled)
                normalizer?.normalize(bytes)
                output.write(bytes)
                written += bytes.size
                onProgress(written)
            }
            output.flush()
        }
        return file.length()
    }

    private fun fetchSegment(url: String, index: Int, isCancelled: () -> Boolean): ByteArray {
        var last: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            // 单片读超时最长 30s × 3 次：只在分片间查取消会让「取消」等近 90s 才生效。
            if (isCancelled()) throw Cancelled()
            if (attempt > 0) Thread.sleep(300L * attempt)
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", PanApi.DLINK_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("分片响应为空")
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) throw IOException("分片内容为空")
                    return bytes
                }
            } catch (error: IOException) {
                last = error
            }
        }
        throw IOException("第 ${index + 1} 个分片下载失败：${last?.message.orEmpty()}")
    }

    private const val MAX_ATTEMPTS = 3
}
