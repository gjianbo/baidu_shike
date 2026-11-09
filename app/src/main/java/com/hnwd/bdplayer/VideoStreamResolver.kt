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

internal class AudioTranscodingPending : IllegalStateException("音频转码准备中，请稍后重试。")

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

    private fun isPlaybackPlaylist(body: String): Boolean =
        body.startsWith("#EXTM3U") && body.lineSequence().any {
            it.startsWith("#EXT-X-STREAM-INF:") || it.trimEnd() == "#EXT-X-ENDLIST"
        }

    /** 文档 §12：错误码速查，把取址失败翻成人话（只改提示，不改播放链）。 */
    private fun playlistError(code: Int, body: String): IllegalStateException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val errno = json?.optInt("errno")
        val errorCode = json?.optInt("error_code")
        return IllegalStateException(when {
            errno == -6 -> "请重新登录百度网盘后再试。"
            errno == -20 || errorCode == -20 -> {
                val message = json.optString("show_msg").trim().take(60)
                val detail = if (message.isEmpty()) "" else " · 网盘提示：$message"
                "百度网盘拒绝了视频取址请求（错误码 -20 · 接口 /mbox/msg/streaming）$detail"
            }
            errorCode == 31304 -> "该文件类型不支持此转码档位，请换档位或改用原始文件。"
            errno == 31341 || errorCode == 31341 -> return AudioTranscodingPending()
            errorCode == 31023 -> "网盘取址参数有误，请重新打开。"
            code == 401 || code == 403 -> "播放地址已过期或无权访问，请重新打开（地址约 8 小时有效）。"
            code != 200 -> "播放清单请求失败（HTTP $code）。"
            else -> "网盘未返回可播放的点播清单。"
        })
    }

    private companion object {
        const val MAX_PLAYLIST_CHARS = 1024 * 1024
    }
}
