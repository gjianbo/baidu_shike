package com.hnwd.bdplayer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CollectorSiteLogin {
    private val chromiumVersion = Regex("""\bChrome/(\d+(?:\.\d+){3})""")
    private const val RENDERED_STATE_CHECK = """
        (() => {
          const body = document.body;
          const text = body && body.innerText ? body.innerText.trim() : '';
          const controls = document.querySelectorAll(
            'input, textarea, button, a, [role="button"], [contenteditable="true"]'
          ).length;
          return JSON.stringify({
            textLength: text.length,
            controls
          });
        })()
    """
    private const val AUTO_OPEN_LOGIN = """
        (() => {
          if (window.__shikeLoginOpened) return false;
          const labels = new Set([
            '登录', '登录/注册', '立即登录', 'Sign in', 'Log in'
          ]);
          const elements = Array.from(
            document.querySelectorAll('a, button, [role="button"]')
          );
          const target = elements.find((element) => {
            const text = (element.innerText || element.textContent || '').trim();
            return labels.has(text) && !element.disabled && element.getClientRects().length > 0;
          });
          if (!target) return false;
          window.__shikeLoginOpened = true;
          target.click();
          return true;
        })()
    """

    fun show(activity: Activity, task: CollectorTask, onSession: (String) -> Unit) {
        val site = CollectorSite.fromPlatform(task.platform) ?: return
        show(activity, site, task.url, "保存并重试", onSession)
    }

    fun show(
        activity: Activity, site: CollectorSite, mediaUrl: String? = null,
        confirmLabel: String = "保存会话", onSession: (String) -> Unit,
    ) {
        fun importCookies() {
            CollectorSitesActivity.editCookies(activity, site) {
                CollectorSiteSessions.read(activity, site)?.cookies?.let(onSession)
            }
        }
        val loginUrl = site.loginUrl
        if (loginUrl == null) {
            importCookies()
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            AlertDialog.Builder(activity).setMessage("当前 Android WebView 无法读取网页 Cookie。")
                .setNegativeButton("取消", null)
                .setPositiveButton("手动导入") { _, _ -> importCookies() }.show()
            return
        }
        val notice = TextView(activity).apply {
            val padding = activity.resources.getDimensionPixelSize(R.dimen.page_pad)
            setPadding(padding, 0, padding, padding)
            visibility = View.GONE
        }
        fun showNotice(message: String) {
            notice.text = message
            notice.visibility = View.VISIBLE
        }
        val web = WebView(activity).apply {
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            val version = chromiumVersion.find(settings.userAgentString)?.groupValues?.get(1)
                ?: "130.0.0.0"
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$version Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setBackgroundColor(Color.WHITE)
            webChromeClient = WebChromeClient()
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                private var lastError = ""
                private var pageGeneration = 0

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (request.url.scheme != "https") return true
                    val host = request.url.host.orEmpty().lowercase(Locale.ROOT)
                    if (allowsNavigation(site, host)) return false
                    if (site == CollectorSite.XIAOHONGSHU &&
                        (host == "rednote.com" || host.endsWith(".rednote.com"))
                    ) {
                        showNotice("小红书已跳转到国际站。若能在桌面浏览器登录国内站，可手动导入该站 Cookie。")
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    notice.visibility = View.GONE
                    lastError = ""
                    pageGeneration++
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val generation = pageGeneration
                    fun isCurrentPage(): Boolean =
                        view.isAttachedToWindow && generation == pageGeneration && view.url == url
                    if (site in AUTO_LOGIN_SITES && !url.contains("/login", ignoreCase = true)) {
                        view.postDelayed({
                            if (isCurrentPage()) {
                                view.evaluateJavascript(AUTO_OPEN_LOGIN, null)
                            }
                        }, 1_500)
                        view.postDelayed({
                            if (isCurrentPage()) {
                                view.evaluateJavascript(AUTO_OPEN_LOGIN, null)
                            }
                        }, 4_500)
                    }
                    view.postDelayed({
                        if (!isCurrentPage()) return@postDelayed
                        view.evaluateJavascript(RENDERED_STATE_CHECK) { result ->
                            val state = decodeRenderedState(result)
                            if (isCurrentPage() && state != null && state.controls == 0 && state.textLength < 20) {
                                showNotice(
                                    "登录页暂时没有显示内容，请稍后重试或手动导入 Cookie。" +
                                        lastError.takeIf(String::isNotBlank)?.let { "\n诊断：$it" }.orEmpty()
                                )
                            }
                        }
                    }, 10_000)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        lastError = "${request.url.host.orEmpty()} · WebView ${error.errorCode}"
                        showNotice("登录网页加载失败，请稍后再试或手动导入 Cookie。")
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) {
                        lastError = "${request.url.host.orEmpty()} HTTP ${response.statusCode}"
                        showNotice("站点暂时无法打开登录网页，可手动导入 Cookie。")
                    }
                }
            }
        }
        val metrics = activity.resources.displayMetrics
        val height = minOf((metrics.heightPixels * 0.72f).toInt(), (720 * metrics.density).toInt())
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(notice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(web, LayoutParams(LayoutParams.MATCH_PARENT, height))
        }
        // AlertController 在 show() 后仍按内部最小高度测量自定义内容；直接给 WebView
        // 固定高度会留下大块空白，导致页面首屏控件被挤出可视区。
        web.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        container.minimumHeight = height
        val dialog = AlertDialog.Builder(activity)
            .setTitle("登录 ${site.title}")
            .setView(container)
            .setNegativeButton("稍后处理", null)
            .setNeutralButton("手动导入") { opened, _ ->
                opened.dismiss()
                importCookies()
            }
            .setPositiveButton(confirmLabel, null)
            .create()
        dialog.setOnDismissListener {
            web.stopLoading()
            container.removeView(web)
            web.destroy()
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setLayout(LayoutParams.MATCH_PARENT, (height * 1.18f).toInt().coerceAtMost(metrics.heightPixels))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cookies = runCatching { cookies(site, mediaUrl) }.getOrElse {
                showNotice("无法读取网页 Cookie，请确认登录已完成或改用手动导入。")
                null
            }
            if (cookies.isNullOrEmpty()) {
                android.widget.Toast.makeText(activity, "尚未读取到站点会话，请完成登录后重试", android.widget.Toast.LENGTH_LONG).show()
            } else {
                runCatching { CollectorSiteSessions.save(activity, site, CollectorSiteSessions.Origin.WEB, cookies) }
                    .onSuccess {
                        notice.visibility = View.GONE
                        dialog.dismiss()
                        onSession(cookies)
                    }.onFailure {
                        android.widget.Toast.makeText(activity, "保存站点会话失败", android.widget.Toast.LENGTH_LONG).show()
                    }
            }
        }
        web.loadUrl(loginUrl)
    }

    private fun allowsNavigation(site: CollectorSite, host: String): Boolean =
        site.allows(host) || (site == CollectorSite.WEIBO && host == "login.sina.com.cn") ||
            (site == CollectorSite.YOUTUBE && (
                host == "accounts.google.com" || host.endsWith(".accounts.google.com") ||
                    host == "ssl.gstatic.com" || host.endsWith(".ssl.gstatic.com") ||
                    host == "www.gstatic.com" || host.endsWith(".www.gstatic.com")
                ))

    private fun decodeRenderedState(raw: String): RenderedState? = runCatching {
        val json = org.json.JSONObject(org.json.JSONTokener(raw).nextValue().toString())
        RenderedState(
            textLength = json.optInt("textLength"),
            controls = json.optInt("controls"),
        )
    }.getOrNull()

    private data class RenderedState(
        val textLength: Int,
        val controls: Int,
    )

    private fun cookies(site: CollectorSite, mediaUrl: String?): String? {
        val manager = CookieManager.getInstance()
        val media = mediaUrl?.let(Uri::parse)
        val urls = if (media != null && media.scheme == "https" && site.allows(media.host.orEmpty()))
            site.cookieUrls + media.toString() else site.cookieUrls
        val rows = linkedMapOf<List<String>, String>()
        for (url in urls) {
            val uri = Uri.parse(url)
            val origin = uri.host ?: continue
            for (cookie in CookieManagerCompat.getCookieInfo(manager, url)) {
                val parts = cookie.split(';').map(String::trim)
                val pair = parts.first().split('=', limit = 2)
                if (pair.size != 2 || pair[0].isBlank()) continue
                val attributes = parts.drop(1).associate {
                    it.substringBefore('=').lowercase(Locale.ROOT) to it.substringAfter('=', "")
                }
                val rawDomain = attributes["domain"]?.lowercase(Locale.ROOT)
                val domain = rawDomain?.removePrefix(".") ?: origin
                if (!site.allows(domain) || (domain != origin && !origin.endsWith(".$domain"))) continue
                val path = attributes["path"]?.takeIf { it.startsWith('/') }
                    ?: uri.path.orEmpty().substringBeforeLast('/', "").ifEmpty { "/" }
                val expiry = attributes["expires"]?.let {
                    runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
                } ?: 0
                val domainField = (if ("httponly" in attributes) "#HttpOnly_" else "") +
                    if (rawDomain == null) domain else ".$domain"
                val fields = listOf(domainField, if (rawDomain == null) "FALSE" else "TRUE", path,
                    if ("secure" in attributes) "TRUE" else "FALSE", expiry.toString(), pair[0], pair[1])
                if (fields.any { field -> field.any { it == '\t' || it == '\r' || it == '\n' || it == '\u0000' } }) continue
                rows[listOf(domain, path, pair[0])] = fields.joinToString("\t")
            }
        }
        if (rows.isEmpty()) return null
        return rows.values.joinToString("\n", prefix = "# Netscape HTTP Cookie File\n", postfix = "\n")
            .takeIf { it.length <= 32_768 }
    }

    private val AUTO_LOGIN_SITES = setOf(
        CollectorSite.DOUYIN,
        CollectorSite.WEIBO,
        CollectorSite.XIAOHONGSHU,
        CollectorSite.YOUTUBE,
    )
}
