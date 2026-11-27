package com.hnwd.bdplayer

import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class CollectorSitesActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            val light = !ThemeStore.isDark(this@CollectorSitesActivity)
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        val root = centeredContent(layoutInflater.inflate(R.layout.activity_extensions, null))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<TextView>(R.id.extensionsTitle).text = "平台登录与 Cookie"
        findViewById<View>(R.id.extensionsBack).setOnClickListener { finish() }
        list = findViewById(R.id.extensionList)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        for (site in CollectorSite.entries) {
            val session = CollectorSiteSessions.read(this, site)
            val status = when {
                session == null -> "未配置 · 使用游客身份"
                session.cookies == null -> "Cookie 已过期 · 使用游客身份"
                session.origin == CollectorSiteSessions.Origin.WEB -> "已保存网页会话"
                else -> "Cookie 已配置"
            }
            val row = layoutInflater.inflate(R.layout.item_collector_site, list, false)
            row.findViewById<TextView>(R.id.siteTitle).text = site.title
            row.findViewById<TextView>(R.id.siteStatus).text =
                if (site.minExtensionVersion > 1 && !ExtensionDownload.available(this, site.name)) {
                    "$status · 下载需更新分享下载扩展"
                } else status
            row.findViewById<View>(R.id.siteLogin).apply {
                visibility = if (site.loginUrl != null) View.VISIBLE else View.GONE
                setOnClickListener { CollectorSiteLogin.show(this@CollectorSitesActivity, site) { render() } }
            }
            row.findViewById<View>(R.id.siteCookies).setOnClickListener {
                editCookies(this, site) { render() }
            }
            list.addView(row)
        }
    }

    internal companion object {
        fun editCookies(activity: android.app.Activity, site: CollectorSite, onChanged: () -> Unit) {
            val field = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                transformationMethod = PasswordTransformationMethod.getInstance()
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                minLines = 4
                maxLines = 8
                hint = "粘贴本平台的 Netscape cookies.txt 内容"
            }
            val container = FrameLayout(activity).apply {
                val padding = activity.resources.getDimensionPixelSize(R.dimen.page_pad)
                setPadding(padding, 0, padding, 0)
                addView(field, FrameLayout.LayoutParams(-1, -2))
            }
            val builder = AlertDialog.Builder(activity)
                .setTitle("${site.title} Cookie")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
            if (CollectorSiteSessions.read(activity, site) != null) builder.setNeutralButton("清除配置") { _, _ ->
                CollectorSiteSessions.clear(activity, site)
                onChanged()
            }
            val dialog = builder.create()
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    CollectorSiteSessions.save(activity, site, CollectorSiteSessions.Origin.MANUAL, field.text.toString())
                }
                result.onSuccess {
                    field.text.clear()
                    dialog.dismiss()
                    onChanged()
                }.onFailure { error ->
                    val message = if (error is IllegalArgumentException) error.message else null
                    Toast.makeText(activity, message ?: "保存 Cookie 失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
