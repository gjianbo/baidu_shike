package com.hnwd.bdplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal data class VideoStream(val uri: Uri, val playlist: ByteArray, val userAgent: String)

internal class VideoStreamResolver {
    private data class Candidate(val uri: Uri, val referer: String?)

    private class Request(val entry: PanEntry, val onResult: (Result<VideoStream>) -> Unit) {
        var worker: Future<*>? = null
        var lastError: Throwable? = null
        val candidates = ArrayDeque<Candidate>()
        val observed = mutableSetOf<Uri>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var active: Request? = null
    private var canReadPageResponse = false
    private var observer: ScriptHandler? = null

    fun attach(web: WebView) {
        canReadPageResponse = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!canReadPageResponse) return
        WebViewCompat.addWebMessageListener(web, "GroupVideoPlaylist", setOf(ORIGIN)) { view, message, origin, mainFrame, _ ->
            if (!mainFrame || origin.toString() != ORIGIN) return@addWebMessageListener
            val current = active ?: return@addWebMessageListener
            val payload = message.data?.takeIf { it.length <= 2 * MAX_PLAYLIST_CHARS } ?: return@addWebMessageListener
            val data = runCatching { JSONObject(payload) }.getOrNull() ?: return@addWebMessageListener
            val uri = Uri.parse(data.optString("url"))
            if (!PanApi.isVideoPlaylist(uri) || !matches(current, uri)) return@addWebMessageListener
            val body = data.optString("body")
            if (!isVod(body)) return@addWebMessageListener
            finish(current, Result.success(VideoStream(uri, body.toByteArray(Charsets.UTF_8), view.settings.userAgentString)))
        }
    }

    fun start(web: WebView, entry: PanEntry, onResult: (Result<VideoStream>) -> Unit) {
        cancel()
        val request = Request(entry, onResult)
        active = request
        if (canReadPageResponse) {
            observer = WebViewCompat.addDocumentStartJavaScript(web, PLAYLIST_OBSERVER, setOf(ORIGIN))
        }
        handler.postDelayed({
            finish(request, Result.failure(request.lastError ?: IllegalStateException("获取视频播放清单超时。")))
        }, 18_000)
    }

    fun observe(request: WebResourceRequest, userAgent: () -> String) {
        val uri = request.url
        if (request.method != "GET" || !PanApi.isVideoPlaylist(uri)) return
        val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
        handler.post {
            if (canReadPageResponse) return@post
            val current = active ?: return@post
            if (!matches(current, uri)) return@post
            if (current.observed.size < 8 && current.observed.add(uri)) {
                current.candidates.addLast(Candidate(uri, referer))
                fetchNext(current, userAgent())
            }
        }
    }

    private fun fetchNext(request: Request, agent: String) {
        if (request.worker != null) return
        val candidate = request.candidates.removeFirstOrNull() ?: return
        val cookie = CookieManager.getInstance().getCookie(candidate.uri.toString())
        request.worker = executor.submit {
            val result = runCatching { fetch(candidate.uri, agent, candidate.referer, cookie) }
            handler.post {
                if (active !== request) return@post
                request.worker = null
                result.onSuccess { finish(request, result) }.onFailure {
                    request.lastError = it
                    fetchNext(request, agent)
                }
            }
        }
    }

    fun cancel() {
        observer?.remove()
        observer = null
        active?.worker?.cancel(true)
        active = null
        handler.removeCallbacksAndMessages(null)
    }

    fun close() {
        cancel()
        executor.shutdownNow()
    }

    private fun finish(request: Request, result: Result<VideoStream>) {
        if (active !== request) return
        cancel()
        request.onResult(result)
    }

    private fun fetch(uri: Uri, userAgent: String, referer: String?, cookie: String?): VideoStream {
        val connection = URL(uri.toString()).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
            if (!referer.isNullOrEmpty()) connection.setRequestProperty("Referer", referer)
            val code = connection.responseCode
            check(code == 200) { "播放清单请求失败（HTTP $code）。" }
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
            check(isVod(body)) {
                "网盘未返回可试播的点播清单。"
            }
            return VideoStream(uri, body.toByteArray(Charsets.UTF_8), userAgent)
        } finally {
            connection.disconnect()
        }
    }

    private fun matches(request: Request, uri: Uri): Boolean =
        uri.getQueryParameter("fs_id") == request.entry.fileId &&
            uri.getQueryParameter("msg_id") == request.entry.messageId &&
            uri.getQueryParameter("to") == request.entry.gid

    private fun isVod(body: String): Boolean =
        body.length <= MAX_PLAYLIST_CHARS && body.startsWith("#EXTM3U") &&
            body.lineSequence().any { it.trimEnd() == "#EXT-X-ENDLIST" }

    private companion object {
        const val ORIGIN = "https://pan.baidu.com"
        const val MAX_PLAYLIST_CHARS = 1024 * 1024
        val PLAYLIST_OBSERVER = """
            (() => {
                if (window !== window.top || location.pathname !== '/mbox/streampage') return;
                const send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function (...args) {
                    this.addEventListener('load', () => {
                        if (this.status !== 200 || !this.responseURL.startsWith(location.origin + '/mbox/msg/streaming?')) return;
                        if (this.responseType !== '' && this.responseType !== 'text') return;
                        const body = this.responseText;
                        if (body.length <= 1048576 && body.startsWith('#EXTM3U')) {
                            GroupVideoPlaylist.postMessage(JSON.stringify({url: this.responseURL, body}));
                        }
                    }, {once: true});
                    return send.apply(this, args);
                };
            })();
        """.trimIndent()
    }
}
