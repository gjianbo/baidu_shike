package com.hnwd.bdplayer

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CollectorSiteLogin {
    fun show(activity: Activity, task: CollectorTask, onSession: (String) -> Unit) {
        val site = CollectorSite.fromPlatform(task.platform) ?: return
        show(activity, site, task.url, "保存并重试", onSession)
    }

    fun show(
        activity: Activity, site: CollectorSite, mediaUrl: String? = null,
        confirmLabel: String = "保存会话", onSession: (String) -> Unit,
    ) {
        val loginUrl = site.loginUrl
        if (loginUrl == null) {
            CollectorSitesActivity.editCookies(activity, site) {
                CollectorSiteSessions.read(activity, site)?.cookies?.let(onSession)
            }
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO)) {
            AlertDialog.Builder(activity).setMessage("当前 Android WebView 无法交接站点会话，请先更新 WebView。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val web = WebView(activity).apply {
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && (request.url.scheme != "https" ||
                        !allowsNavigation(site, request.url.host.orEmpty().lowercase(Locale.ROOT)))
            }
        }
        val metrics = activity.resources.displayMetrics
        val container = FrameLayout(activity).apply {
            val height = minOf((metrics.heightPixels * 0.65f).toInt(), (640 * metrics.density).toInt())
            addView(web, FrameLayout.LayoutParams(-1, height))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("登录 ${site.title}")
            .setView(container)
            .setNegativeButton("稍后处理", null)
            .setPositiveButton(confirmLabel, null)
            .create()
        dialog.setOnDismissListener {
            web.stopLoading()
            web.destroy()
        }
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cookies = runCatching { cookies(site, mediaUrl) }.getOrNull()
            if (cookies.isNullOrEmpty()) {
                android.widget.Toast.makeText(activity, "尚未读取到站点会话，请完成登录后重试", android.widget.Toast.LENGTH_LONG).show()
            } else {
                runCatching { CollectorSiteSessions.save(activity, site, CollectorSiteSessions.Origin.WEB, cookies) }
                    .onSuccess {
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
        site.allows(host) || (site == CollectorSite.WEIBO && host == "login.sina.com.cn")

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
}
