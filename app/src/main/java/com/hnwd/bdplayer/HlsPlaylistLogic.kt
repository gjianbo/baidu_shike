package com.hnwd.bdplayer

import java.net.URI

/**
 * m3u8 → 「可下载分片清单」的纯解析层。
 *
 * 存在理由：百度对群组共享库里的视频**不返回原文件直链**
 * （`/api/sharedownload` 的 `list` 会被换成一段加密串，见
 * `docs/百度网盘群组视频下载失败-分析与修复.md`），
 * 唯一能拿到真实字节的通路是 `/mbox/msg/streaming` 下发的 HLS 转码流。
 * 播放侧（`VideoStreamResolver`）把 m3u8 直接喂 ExoPlayer；下载侧必须自己
 * 把分片拉下来并拼接，所以先在 JVM 里把清单解析干净，便于单测覆盖。
 *
 * 三种清单形态都必须认：
 * ① master（`#EXT-X-STREAM-INF` + 子清单地址）→ 选一路再拉一次；
 * ② media（`#EXTINF` + 分片绝对地址）；百度实测是这一种；
 * ③ 带 `#EXT-X-MAP` 初始化段 / `#EXT-X-KEY` 加密段的变体 → 初始化段要一起下，
 *    加密段当前不支持，交给调用方给出明确提示，而不是静默产出坏文件。
 */
internal data class HlsPlan(
    val segments: List<String>,
    val initSegment: String?,
    val keyMethod: String?,
    val durationSeconds: Double,
) {
    val encrypted: Boolean get() = keyMethod != null
    val count: Int get() = segments.size
}

/**
 * 把清单里的相对地址解析成绝对地址。
 *
 * 百度实测给的是自带签名的绝对地址（`expires=8h`），相对地址只做兜底；
 * 解析失败返回 null，让调用方丢掉这一行，而不是塞进一个必然 404 的地址。
 */
internal fun absoluteHlsUrl(baseUrl: String, reference: String): String? {
    val ref = reference.trim()
    if (ref.isEmpty()) return null
    if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
    return runCatching { URI(baseUrl).resolve(ref).toString() }.getOrNull()
}

internal fun isMasterPlaylist(body: String): Boolean =
    body.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

/**
 * master 清单里挑最高码率的一路。
 *
 * 只按 `BANDWIDTH` 比较：百度从不给 `RESOLUTION`，用分辨率选会全部落空；
 * 缺 `BANDWIDTH` 的条目按 0 处理，保证「只有一个子清单」时仍然能选中。
 */
internal fun pickHlsVariant(body: String, baseUrl: String): String? {
    val lines = body.lineSequence().map { it.trim() }.toList()
    var best: Pair<Long, String>? = null
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            val bandwidth = BANDWIDTH.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            var cursor = index + 1
            while (cursor < lines.size && (lines[cursor].isEmpty() || lines[cursor].startsWith("#"))) cursor++
            val uri = lines.getOrNull(cursor)?.let { absoluteHlsUrl(baseUrl, it) }
            if (uri != null && (best == null || bandwidth > best.first)) best = bandwidth to uri
            index = cursor
        }
        index++
    }
    return best?.second
}

/** media 清单 → 分片列表；分片为空（占位清单 / 未生成完）返回 null，交由调用方重试。 */
internal fun parseHlsPlan(body: String, baseUrl: String): HlsPlan? {
    val segments = mutableListOf<String>()
    var initSegment: String? = null
    var keyMethod: String? = null
    var duration = 0.0
    for (raw in body.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("#")) {
            when {
                line.startsWith("#EXTINF:") -> {
                    duration += line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attributeValue(line, "METHOD")
                    if (!method.isNullOrEmpty() && !method.equals("NONE", ignoreCase = true)) keyMethod = method
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = attributeValue(line, "URI")
                    if (!uri.isNullOrEmpty()) initSegment = absoluteHlsUrl(baseUrl, uri)
                }
            }
            continue
        }
        absoluteHlsUrl(baseUrl, line)?.let(segments::add)
    }
    if (segments.isEmpty()) return null
    return HlsPlan(
        segments = segments,
        initSegment = initSegment,
        keyMethod = keyMethod,
        durationSeconds = duration,
    )
}

private val BANDWIDTH = Regex("BANDWIDTH=(\\d+)")

/** 取 `#EXT-X-KEY` / `#EXT-X-MAP` 这类标签里的属性值；引号可有可无，值本身不含逗号。 */
private fun attributeValue(line: String, name: String): String? =
    Regex("(?:^|[,:])\\s*" + Regex.escape(name) + "\\s*=\\s*\"?([^\",]*)")
        .find(line)?.groupValues?.getOrNull(1)?.trim()
