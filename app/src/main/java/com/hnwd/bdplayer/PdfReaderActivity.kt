package com.hnwd.bdplayer

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
            Toast.makeText(this, "文档缓存已失效，请从列表重新打开。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // 渲染发生在 isolatedProcess 服务中，按包名显式授予读取权限
        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val barColor = getColor(R.color.bg)
        val darkIcons = androidx.core.graphics.ColorUtils.calculateLuminance(barColor) > 0.179
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(barColor)
            accessibilityPaneTitle = "文档阅读"
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(12), dp(4))
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(getColor(R.color.ink2))
            contentDescription = "返回"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setBackgroundResource(R.drawable.bg_icon_ripple)
            setOnClickListener { finish() }
        }
        val titleView = TextView(this).apply {
            text = name
            setTextColor(getColor(R.color.ink))
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val container = FrameLayout(this).apply { id = R.id.pdf_container }
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.line))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            root.setPadding(safe.left, safe.top, safe.right, safe.bottom)
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
        // 配置变更后阅读器仍在使用同一份缓存。
        if (isFinishing) {
            intent.getStringExtra(EXTRA_URI)?.let {
                revokeUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            tempFile?.let { PdfCache.release(File(it)) }
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
