package com.hnwd.bdplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.core.net.toUri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Resolve a fresh link using the selected provider's current session. */
internal object DownloadLinkResolver {
    data class Link(val uri: Uri, val cookie: String? = null)
    class VerificationRequired(val payload: JSONObject) :
        IOException("百度网盘需要验证码，请在传输中心点击验证")
    class Failure(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    fun resolve(
        context: Context, task: DownloadTask, session: TransferSession = TransferSession.capture(task.provider),
        verification: String = "",
    ): Link {
        val entry = requireNotNull(task.entry)
        require(session.provider == task.provider && entry.provider == task.provider)
        val ua = task.userAgent
        return when (DownloadLogic.routeOf(entry)) {
            DownloadRoute.QUARK -> {
                TransferSession.requireAccount(task.provider, task.viewer, session.quarkAccount())
                val drive = QuarkPersonalDrive.forTransfer(session, ua)
                try {
                    drive.downloadForTransfer(QuarkFile(entry.fileId, entry.name, false))
                } catch (error: Exception) {
                    if (error.message?.contains("23018") == true) {
                        throw Failure("当前夸克账号无法下载此文件（23018）")
                    }
                    throw Failure("无法获取夸克下载地址，请检查登录和网络后重试")
                }
            }
            DownloadRoute.BAIDU_GROUP -> {
                val viewer = json(PanApi.viewer(), ua, session.cookie).getJSONObject("result").getString("uk")
                TransferSession.requireAccount(task.provider, task.viewer, viewer)
                Link(PanApi.parseMediaLink(
                    json(PanApi.mediaLinkRequest(), ua, session.cookie, PanApi.mediaLinkBody(entry, viewer) + verification),
                    entry,
                ))
            }
            DownloadRoute.BAIDU_DISK -> {
                val account = json(PanApi.diskDownloadSession(includeAccount = true), ua, session.cookie)
                    .getJSONObject("result")
                TransferSession.requireAccount(task.provider, task.viewer, account.getString("uk"))
                val script = diskSignScript(
                    account.getString("sign1"), account.getString("sign2"), account.getString("sign3"),
                )
                val sign = evaluateSignature(context, script)
                val url = PanApi.diskDownload(
                    entry, account.getString("bdstoken"), sign,
                    account.getString("timestamp"), account.getString("is_vip"),
                )
                val raw = PanApi.parseDiskDownloadLink(json(url + verification, ua, session.cookie))
                Link(probeDiskLink(raw, ua, session.cookie))
            }
        }
    }

    fun captcha(task: DownloadTask, session: TransferSession, challenge: VerificationRequired): PanApi.MediaCaptcha? =
        PanApi.parseMediaCaptcha(challenge.payload)
            ?: if (DownloadLogic.routeOf(requireNotNull(task.entry)) == DownloadRoute.BAIDU_GROUP) {
                PanApi.parseMediaCaptcha(json(PanApi.mediaCaptchaRequest(), task.userAgent, session.cookie))
            } else null

    private fun json(url: String, userAgent: String, cookie: String, form: String? = null): JSONObject {
        val request = request(url, userAgent, cookie)
            .header("X-Requested-With", "XMLHttpRequest")
        if (form != null) {
            request.post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        }
        client.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw Failure("百度网盘取址失败（HTTP ${response.code}）")
            val data = JSONObject(response.body?.string().orEmpty())
            when (val errno = data.optInt("errno", -1)) {
                0 -> return data
                -20 -> throw VerificationRequired(data)
                -6 -> throw Failure("请先登录百度网盘")
                else -> throw Failure("百度网盘取址失败（错误码 $errno）")
            }
        }
    }

    private fun request(url: String, userAgent: String, cookie: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Referer", DownloadLogic.BAIDU_REFERER)
            .header("Cookie", cookie)

    private fun probeDiskLink(uri: Uri, userAgent: String, cookie: String): Uri {
        val probe = request(uri.toString(), userAgent, cookie).header("Range", "bytes=0-0").build()
        client.newCall(probe).execute().use { response ->
            if (response.isRedirect) {
                val target = response.header("Location").orEmpty().toUri()
                if (PanApi.isCachedFileLink(target)) return target
            }
            if (response.code in listOf(200, 206) &&
                response.header("Content-Type").orEmpty().startsWith("application/json", ignoreCase = true).not()
            ) return uri
            val code = if (response.code in 400..499) runCatching {
                JSONObject(response.peekBody(1024).string()).optInt("error_code")
            }.getOrNull()?.takeIf { it != 0 } else null
            val detail = if (code == null) "HTTP ${response.code}" else "HTTP ${response.code} / $code"
            throw Failure("百度网盘拒绝了原文件下载（$detail）")
        }
    }

    /** The same sign2 script as media playback, evaluated with a local page and no network access. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun evaluateSignature(context: Context, script: String): String {
        val result = CompletableFuture<String?>()
        var web: WebView? = null
        main.post {
            if (!result.isDone) {
                runCatching {
                    val view = WebView(context)
                    web = view
                    view.apply {
                        settings.javaScriptEnabled = true
                        settings.blockNetworkLoads = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (!result.isDone) view.evaluateJavascript(script) {
                                    result.complete(parseSignResult(it))
                                }
                            }
                        }
                        loadDataWithBaseURL(
                            "https://pan.baidu.com/", "<html></html>", "text/html", "UTF-8", null,
                        )
                    }
                }.onFailure { result.completeExceptionally(it) }
            }
        }
        return try {
            result.get(8, TimeUnit.SECONDS) ?: throw Failure("无法生成百度网盘下载签名")
        } finally {
            result.cancel(false)
            main.post { web?.destroy() }
        }
    }
}
