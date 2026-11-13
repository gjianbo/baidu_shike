package com.hnwd.bdplayer

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.InputType
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import androidx.core.net.toUri

/**
 * 媒体取址层需要的最小宿主能力。
 *
 * 音频 / PDF / 图片三条链都从这里拿「可播放或可下载的地址」，
 * 因此本接口刻意只留状态栏、可见性、一次请求与一段 WebView 脚本执行。
 */
internal interface MediaLinkHost {
    val hostActivity: Activity

    fun setStatus(message: String)
    /** lane 为 AUDIO 时的错误出口，走音频页的提示而不是主界面状态栏。 */
    fun failAudio(message: String)
    fun isVisible(): Boolean
    fun userAgent(): String
    /** 把取到的地址写进地址缓存（成功后由调用方直接使用）。 */
    fun saveAddress(key: String, link: Uri)

    /** 在隐藏 WebView 里执行脚本（下载签名依赖网盘页的 sign 函数）。 */
    fun evaluateJavascript(script: String, onResult: (String?) -> Unit)
    fun runOnUiThread(action: () -> Unit)

    fun fetchJson(
        url: String,
        post: Boolean = false,
        form: String? = null,
        statusHint: String? = null,
        lane: RequestLane,
        onCancelled: () -> Unit = {},
        onFailure: () -> Unit = {},
        onCaptcha: ((JSONObject, () -> Boolean) -> Unit)? = null,
        onCurrentResult: ((JSONObject, () -> Boolean) -> Unit)? = null,
        onResult: (JSONObject) -> Unit,
    )
}

/**
 * 媒体地址解析：群组走 /api/sharedownload，我的网盘走 /api/download（需现场算签名），
 * errno -20 时弹百度验证码并带答案重提。
 *
 * 三条不变式：
 * 1. 每次回调都先问 `isCurrent()`，账号或播放代次变了就丢弃结果。
 * 2. 我的网盘的 dlink 是 302 跳 CDN 的临时地址，必须再走一次请求取出真正的 CDN 地址。
 * 3. 验证码同一时刻只允许存在一个（[captchaDialog]），lane 不匹配不关别人的弹窗。
 */
internal class MediaLinkSection(private val host: MediaLinkHost) {
    private val activity: Activity get() = host.hostActivity
    private var captchaDialog: Pair<RequestLane, AlertDialog>? = null

    /** 页面不可见 / 销毁时无条件收起验证码弹窗。 */
    fun dismissAnyCaptcha() {
        captchaDialog?.second?.dismiss()
    }

    fun requestDiskLink(
        entry: PanEntry,
        bdstoken: String,
        lane: RequestLane,
        isCurrent: () -> Boolean,
        onFailure: () -> Unit,
        onReady: (Uri) -> Unit,
    ) {
        val fail = { if (isCurrent()) onFailure() }
        fun stop(message: String) {
            if (!isCurrent()) return
            if (linkErrorTarget(lane) == LinkErrorTarget.AUDIO) {
                host.failAudio(message)
            } else {
                onFailure()
                host.setStatus(message)
            }
        }
        host.fetchJson(PanApi.diskDownloadSession(), lane = lane, onCancelled = fail, onFailure = fail) { json ->
            if (!isCurrent()) return@fetchJson
            val session = json.getJSONObject("result")
            val timestamp = session.getString("timestamp")
            val vip = session.getString("is_vip")
            val script = diskSignScript(
                session.getString("sign1"), session.getString("sign2"), session.getString("sign3"),
            )
            host.evaluateJavascript(script) { raw ->
                if (!isCurrent()) return@evaluateJavascript
                val sign = parseSignResult(raw) ?: run {
                    stop("无法生成下载签名，请重新登录后重试。")
                    return@evaluateJavascript
                }
                fun submit(verification: String = "") {
                    host.fetchJson(
                        PanApi.diskDownload(entry, bdstoken, sign, timestamp, vip) + verification,
                        lane = lane, onCancelled = fail, onFailure = fail,
                        onCaptcha = { captchaJson, current ->
                            if (!host.isVisible()) {
                                stop("需要输入网盘验证码，请返回应用后重试。")
                            } else {
                                val message = captchaPrompt(verification.isNotEmpty())
                                val captcha = PanApi.parseMediaCaptcha(captchaJson)
                                if (captcha == null) {
                                    stop("网盘要求输入验证码，请在网页版网盘打开一次后重试。")
                                } else {
                                    showCaptcha(
                                        lane, captcha, message, { current() && isCurrent() }, ::stop,
                                    ) { answer ->
                                        submit("&" + PanApi.mediaCaptchaForm(captcha, answer))
                                    }
                                }
                            }
                        },
                    ) { download ->
                        if (!isCurrent()) return@fetchJson
                        val link = runCatching { PanApi.parseDiskDownloadLink(download) }.getOrElse {
                            stop(it.message ?: "网盘未返回有效的原始文件地址。")
                            return@fetchJson
                        }
                        val cookies = CookieManager.getInstance()
                        val cookie = cookies.getCookie(link.toString()) ?: cookies.getCookie("https://pan.baidu.com")
                        val userAgent = host.userAgent()
                        AppIo.execute {
                            val cdn = runCatching {
                                val connection = URL(link.toString()).openConnection() as HttpURLConnection
                                try {
                                    connection.instanceFollowRedirects = false
                                    connection.connectTimeout = 6_000
                                    connection.readTimeout = 6_000
                                    connection.setRequestProperty("User-Agent", userAgent)
                                    if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
                                    check(connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP)
                                    connection.getHeaderField("Location").orEmpty().toUri()
                                        .also { check(PanApi.isCachedFileLink(it)) }
                                } finally {
                                    connection.disconnect()
                                }
                            }.getOrNull()
                            host.runOnUiThread {
                                if (!isCurrent()) return@runOnUiThread
                                if (cdn == null) fail() else onReady(cdn)
                            }
                        }
                    }
                }
                submit()
            }
        }
    }

    fun resolve(
        entry: PanEntry,
        viewer: String,
        lane: RequestLane = RequestLane.LIST,
        onFailure: () -> Unit = {},
        onResult: (Uri) -> Unit,
    ) {
        val form = PanApi.mediaLinkBody(entry, viewer)
        fun stop(message: String) {
            when (linkErrorTarget(lane)) {
                LinkErrorTarget.STATUS -> host.setStatus(message)
                LinkErrorTarget.AUDIO -> host.failAudio(message)
            }
            onFailure()
        }

        fun submit(verification: String = "") {
            host.fetchJson(
                PanApi.mediaLinkRequest(), post = true, form = form + verification, lane = lane,
                onFailure = onFailure,
                onCaptcha = { json, isCurrent ->
                    if (!host.isVisible()) {
                        stop("需要输入网盘验证码，请返回应用后重试。")
                    } else {
                        val message = captchaPrompt(verification.isNotEmpty())
                        fun show(captcha: PanApi.MediaCaptcha, current: () -> Boolean) {
                            showCaptcha(lane, captcha, message, current, ::stop) { answer ->
                                submit("&" + PanApi.mediaCaptchaForm(captcha, answer))
                            }
                        }
                        val captcha = PanApi.parseMediaCaptcha(json)
                        if (captcha != null) show(captcha, isCurrent)
                        else host.fetchJson(
                            PanApi.mediaCaptchaRequest(), lane = lane,
                            statusHint = "正在获取网盘验证码…",
                            onFailure = { stop("无法获取网盘验证码，请重试。") },
                            onCurrentResult = { result, current ->
                                val challenge = PanApi.parseMediaCaptcha(result)
                                if (challenge == null) stop("网盘未返回可用的验证码，请重试。")
                                else show(challenge, current)
                            },
                        ) {}
                    }
                },
            ) { json ->
                val link = PanApi.parseMediaLink(json, entry)
                host.saveAddress(mediaCacheKey(viewer, entry), link)
                onResult(link)
            }
        }
        submit()
    }

    internal fun showCaptcha(
        lane: RequestLane,
        captcha: PanApi.MediaCaptcha,
        message: String,
        isCurrent: () -> Boolean,
        onCancelled: (String) -> Unit,
        cookie: String? = null,
        onAnswer: (String) -> Unit,
    ) {
        if (captchaBlocked({ host.isVisible() }, { captchaDialog != null })) {
            onCancelled("需要输入网盘验证码，请稍后重试。")
            return
        }
        val image = ImageView(activity).apply {
            contentDescription = "百度网盘验证码图片"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val answer = EditText(activity).apply {
            hint = "验证码"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val views = activity.column().apply {
            setPadding(activity.dp(24), 0, activity.dp(24), 0)
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(72)))
            addView(answer)
        }
        var submitted = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("百度网盘验证码")
            .setMessage("正在加载验证码图片…")
            .setView(views)
            .setPositiveButton("验证并重试", null)
            .setNegativeButton("取消", null)
            .create()
        captchaDialog = lane to dialog
        dialog.setOnDismissListener {
            if (captchaDialog?.second === dialog) captchaDialog = null
            if (!submitted && isCurrent()) onCancelled("已取消网盘验证。")
        }
        dialog.show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.isEnabled = false
        submit.setOnClickListener {
            if (!isCurrent()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val value = answer.text.toString().trim()
            val answerError = captchaAnswerError(value)
            if (answerError != null) {
                answer.error = answerError
                return@setOnClickListener
            }
            submitted = true
            captchaDialog = null
            dialog.dismiss()
            onAnswer(value)
        }

        val url = captcha.image.toString()
        val requestCookie = cookie ?: CookieManager.getInstance().getCookie(url)
        val userAgent = host.userAgent()
        AppIo.execute {
            val bitmap = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", userAgent)
                    if (!requestCookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", requestCookie)
                    check(connection.responseCode == 200)
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            host.runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (!isCurrent()) {
                    dialog.dismiss()
                } else if (bitmap == null) {
                    dialog.setMessage("验证码图片加载失败，请关闭后重试。")
                } else {
                    image.setImageBitmap(bitmap)
                    dialog.setMessage(message)
                    submit.isEnabled = true
                }
            }
        }
    }

    fun dismissCaptcha(lane: RequestLane) {
        val dialog = captchaDialog?.takeIf { shouldDismissCaptcha(it.first, lane) }?.second ?: return
        captchaDialog = null
        dialog.dismiss()
    }
}
