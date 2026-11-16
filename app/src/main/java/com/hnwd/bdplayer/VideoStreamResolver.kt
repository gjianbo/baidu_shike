package com.hnwd.bdplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal data class VideoStream(val uri: Uri, val playlist: ByteArray)

/**
 * 取址/转码尚未就绪的**可恢复**状态：调用方应重试，而不是直接报错。
 *
 * 两类触发：
 * ① `errno` / `error_code` = 31341（HLS 转码任务排队中）；
 * ② HTTP 200 但返回体不是可播放的 m3u8 —— 冷文件首次取址时百度可能先返回占位内容
 *    或「尚未生成完」的清单（无 `#EXT-X-ENDLIST`），再请求一次才给完整清单；
 *    以及非 200 但未给出明确错误码的空错误体。
 *
 * 真机实测：这类失败「再试一次就正常」，所以必须自动重试，不能弹「无法原生播放」。
 */
internal class TranscodingPending : IllegalStateException("网盘仍在转码，请稍后重试。")

/** 纯逻辑：m3u8 是否是可播放的点播清单。顶层函数便于 JVM 单测直接调用（不用碰 Handler）。 */
internal fun isPlaybackPlaylist(body: String): Boolean =
    body.startsWith("#EXTM3U") && body.lineSequence().any {
        it.startsWith("#EXT-X-STREAM-INF:") || it.trimEnd() == "#EXT-X-ENDLIST"
    }

/** 文档 §12：错误码速查，把取址失败翻成人话 / 可恢复状态。顶层纯函数，便于单测。 */
internal fun playlistError(code: Int, body: String): IllegalStateException {
    val json = runCatching { JSONObject(body) }.getOrNull()
    val errno = json?.optInt("errno")
    val errorCode = json?.optInt("error_code")
    val reportedCode = errno?.takeUnless { it == 0 } ?: errorCode?.takeUnless { it == 0 }
    return when {
        errno == -6 -> IllegalStateException("请重新登录百度网盘后再试。")
        errno == -20 || errorCode == -20 -> {
            val message = json?.optString("show_msg").orEmpty().trim().take(60)
            val detail = if (message.isEmpty()) "" else " · 网盘提示：$message"
            // 音频/视频、DISK(/api/streaming)/GROUP(/mbox/msg/streaming) 共用本解析器，
            // 文案不能写死媒体类型或接口路径，否则会在别的链路串台。
            IllegalStateException("百度网盘拒绝了播放取址请求（错误码 -20，通常需验证码）$detail")
        }
        errorCode == 31304 -> IllegalStateException("该文件类型不支持此转码档位，请换档位或改用原始文件。")
        errno == 31341 || errorCode == 31341 -> TranscodingPending()
        errorCode == 31023 -> IllegalStateException("网盘取址参数有误，请重新打开。")
        code == 401 || code == 403 -> IllegalStateException("播放地址已过期或无权访问，请重新打开（地址约 8 小时有效）。")
        // 非 200 但没给明确错误码：同样按「尚未就绪」处理，交给调用方重试。
        code != 200 -> if (reportedCode == null) TranscodingPending()
                       else IllegalStateException("播放清单请求失败（HTTP $code）。")
        reportedCode != null -> IllegalStateException("百度网盘未返回可播放的点播清单（错误码 $reportedCode）。")
        // 200 但清单未就绪：占位内容 / 未生成完的 m3u8 / 非标准 JSON。
        else -> TranscodingPending()
    }
}

internal class VideoStreamResolver {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var worker: Future<*>? = null
    private var requestId = 0

    fun start(uri: Uri, userAgent: String, onResult: (Result<VideoStream>) -> Unit) {
        cancel()
        val id = requestId
        val cookie = CookieManager.getInstance().getCookie(uri.toString())
            ?.takeIf { it.isNotBlank() }
            ?: CookieManager.getInstance().getCookie("https://${PanApi.HOST}")
        worker = executor.submit {
            val result = runCatching { fetch(uri, userAgent, cookie) }
            handler.post {
                if (id != requestId) return@post
                cancel()
                onResult(result)
            }
        }
        handler.postDelayed({
            if (id != requestId) return@postDelayed
            cancel()
            onResult(Result.failure(IllegalStateException("获取播放清单超时。")))
        }, 15_000)
    }

    fun cancel() {
        requestId++
        worker?.cancel(true)
        worker = null
        handler.removeCallbacksAndMessages(null)
    }

    fun close() {
        cancel()
        executor.shutdownNow()
    }

    private fun fetch(uri: Uri, userAgent: String, cookie: String?): VideoStream {
        val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Referer", "https://pan.baidu.com/disk/main")
            val code = connection.responseCode
            if (code != 200) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw playlistError(code, errorBody)
            }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                val output = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    check(output.length + count <= MAX_PLAYLIST_CHARS) { "播放清单大小异常。" }
                    output.append(buffer, 0, count)
                }
                output.toString()
            }
            if (!isPlaybackPlaylist(body)) throw playlistError(200, body)
            return VideoStream(uri, body.toByteArray(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_PLAYLIST_CHARS = 1024 * 1024
    }
}
