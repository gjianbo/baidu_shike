package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri

/**
 * 网页登录弹窗。独立 WebView + 应用级 CookieManager；
 * 登录成功跳回 pan.baidu.com 后关闭，由主界面重新确认账号。
 */
internal class LoginDialog(
    private val host: Activity,
    userAgent: String,
    private val onLoggedIn: () -> Unit,
) : Dialog(host) {
    private val web = WebView(host)
    private lateinit var urlText: TextView
    private lateinit var progress: ProgressBar
    private var done = false
    private var started = false
    private var loginRouteKicked = false

    init {
        setContentView(host.layoutInflater.inflate(R.layout.dialog_login, null, false))
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setCanceledOnTouchOutside(false)
        setOnDismissListener {
            web.stopLoading()
            web.destroy()
        }
        urlText = findViewById(R.id.loginUrl)
        progress = findViewById(R.id.loginProgress)
        val container = findViewById<FrameLayout>(R.id.loginWebContainer)
        web.setBackgroundColor(Color.WHITE)
        container.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = false
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = userAgent
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val hostName = uri.host.orEmpty()
                return uri.scheme != "https" || (hostName != "baidu.com" && !hostName.endsWith(".baidu.com"))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                updateUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                updateUrl(url)
                val address = url.toUri()
                if (!done && address.scheme == "https" && address.host == PanApi.HOST) {
                    done = true
                    CookieManager.getInstance().flush()
                    dismiss()
                    onLoggedIn()
                    return
                }
                // 百度登录页首屏有时不挂载路由，只渲染背景；切一次 hash 路由即可恢复
                if (!done && !loginRouteKicked && address.host == "wappass.baidu.com" &&
                    url.contains("password_login")
                ) {
                    loginRouteKicked = true
                    view.postDelayed({
                        if (!done && isShowing) {
                            view.evaluateJavascript("document.querySelectorAll('input').length") { count ->
                                if (!done && count?.trim('"')?.toIntOrNull() == 0) {
                                    view.evaluateJavascript(
                                        "location.hash='#/sms_login';setTimeout(function(){location.hash='#/password_login';},150);",
                                        null,
                                    )
                                }
                            }
                        }
                    }, 600)
                }
            }
        }
        findViewById<ImageView>(R.id.loginClose).setOnClickListener { dismiss() }
        findViewById<ImageView>(R.id.loginReload).setOnClickListener {
            progress.visibility = View.VISIBLE
            web.reload()
        }
        // 弹窗布局完成后再加载，百度登录页在 0 尺寸 WebView 中初始化会渲染空白
        web.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (!started && view.width > 0 && view.height > 0) {
                    started = true
                    view.removeOnLayoutChangeListener(this)
                    web.loadUrl(PanApi.login)
                }
            }
        })
    }

    private fun updateUrl(raw: String) {
        val address = raw.toUri()
        urlText.text = address.host ?: raw
    }
}
