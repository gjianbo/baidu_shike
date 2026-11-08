package com.hnwd.bdplayer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.pdf.viewer.fragment.PdfViewerFragment
import java.io.File

/**
 * 独立的 PDF 阅读页。故意不复用 MainActivity：
 * PdfViewerFragment 要求 AppCompatActivity + Theme.AppCompat，
 * 而主界面是平台 Activity + 手写浅色主题，改它会影响全部现有 UI。
 */
class PdfReaderActivity : AppCompatActivity() {
    private var tempFile: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_URI)
        tempFile = intent.getStringExtra(EXTRA_FILE)
        val name = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uri = raw?.let { Uri.parse(it) }
        if (uri == null || tempFile?.let { PdfCache.retain(File(it)) } != true) {
            Toast.makeText(this, "临时文档已失效，请从列表重新打开。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 渲染发生在 isolatedProcess 服务中，按包名显式授予读取权限
        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        window.statusBarColor = getColor(R.color.bg)
        window.navigationBarColor = getColor(R.color.bg)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(18), dp(10), dp(12), dp(10))
        }
        val titleView = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.ink))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "关闭"
            setTextColor(getColor(R.color.accentInk))
            textSize = 12.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            setOnClickListener { finish() }
        }
        bar.addView(titleView)
        bar.addView(close)
        val container = FrameLayout(this).apply { id = R.id.pdf_container }
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            bar.setPadding(dp(18) + safe.left, dp(10) + safe.top, dp(12) + safe.right, dp(10))
            container.setPadding(0, 0, 0, safe.bottom)
            insets
        }

        // 固定容器 id，保证旋转等配置变更后 Fragment 能被正确恢复
        // 官方指南（API 31 + ext 13）与 release notes（backport 到 28）口径不一致，
        // 低版本设备可能直接抛 UnsupportedOperationException，这里兜住避免闪退
        try {
            val fragment = supportFragmentManager.findFragmentByTag(TAG) as? PdfViewerFragment
                ?: PdfViewerFragment().also {
                    supportFragmentManager.beginTransaction().replace(container.id, it, TAG).commitNow()
                }
            fragment.documentUri = uri
        } catch (error: UnsupportedOperationException) {
            Toast.makeText(this, "当前系统版本不支持应用内 PDF 阅读", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        // 退出即删；isFinishing 用于排除配置变更导致的重建
        if (isFinishing) {
            intent.getStringExtra(EXTRA_URI)?.let {
                revokeUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            tempFile?.let { PdfCache.discard(File(it)) }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "pdf_uri"
        const val EXTRA_TITLE = "pdf_title"
        const val EXTRA_FILE = "pdf_file"
        private const val TAG = "pdf_viewer"
    }
}
