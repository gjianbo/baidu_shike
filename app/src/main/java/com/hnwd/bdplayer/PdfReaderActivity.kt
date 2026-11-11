package com.hnwd.bdplayer

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPoint
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri

/**
 * 独立的 PDF 阅读页。故意不复用 MainActivity：
 * PdfViewerFragment 要求 AppCompatActivity + Theme.AppCompat，
 * 而主界面是平台 Activity + 手写浅色主题，改它会影响全部现有 UI。
 */
class PdfReaderActivity : AppCompatActivity() {
    private var tempFile: String? = null
    private lateinit var viewer: OutlinePdfViewerFragment
    private lateinit var outlineButton: TextView
    private lateinit var outlinePanel: LinearLayout
    private lateinit var outlineBack: OnBackPressedCallback
    private var outlineDialog: AlertDialog? = null
    private var outline = emptyList<PdfOutlineEntry>()
    private var outlineRequested = false
    private var outlineOpen = false
    private var wideOutline = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_URI)
        tempFile = intent.getStringExtra(EXTRA_FILE)
        val name = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uri = raw?.let { it.toUri() }
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
        outlineOpen = savedInstanceState?.getBoolean(STATE_OUTLINE_OPEN) == true
        outlineButton = TextView(this).apply {
            text = "目录"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.ink))
            setBackgroundResource(R.drawable.bg_icon_ripple)
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                outlineOpen = !outlineOpen
                updateOutlineVisibility()
            }
        }
        bar.addView(outlineButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        val container = FrameLayout(this).apply { id = R.id.pdf_container }
        outlinePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            accessibilityPaneTitle = "文档目录"
            visibility = View.GONE
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(container, LinearLayout.LayoutParams(0, -1, 1f))
            addView(outlinePanel, LinearLayout.LayoutParams(dp(300), -1))
        }
        outlineBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                outlineOpen = false
                updateOutlineVisibility()
            }
        }
        onBackPressedDispatcher.addCallback(this, outlineBack)
        body.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val wide = right - left >= dp(EXPANDED_WINDOW_DP)
            if (wide != wideOutline) {
                wideOutline = wide
                updateOutlineVisibility()
            }
        }
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.line))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val page = centeredContent(root)
        setContentView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }

        // 固定容器 id，保证旋转等配置变更后 Fragment 能被正确恢复
        // 官方指南（API 31 + ext 13）与 release notes（backport 到 28）口径不一致，
        // 低版本设备可能直接抛 UnsupportedOperationException，这里兜住避免闪退
        try {
            viewer = supportFragmentManager.findFragmentByTag(TAG) as? OutlinePdfViewerFragment
                ?: OutlinePdfViewerFragment().also {
                    supportFragmentManager.beginTransaction().replace(container.id, it, TAG).commitNow()
                }
            viewer.documentUri = uri
        } catch (error: UnsupportedOperationException) {
            Toast.makeText(this, "当前系统版本不支持应用内 PDF 阅读", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    internal fun loadOutline() {
        if (outlineRequested) return
        val file = tempFile?.let(::File) ?: return
        outlineRequested = true
        lifecycleScope.launch {
            outline = withContext(Dispatchers.IO) {
                runCatching { readPdfOutline(applicationContext, file) }
                    .getOrDefault(emptyList())
            }
            if (outline.isEmpty()) return@launch
            outlineButton.visibility = View.VISIBLE
            outlinePanel.addView(TextView(this@PdfReaderActivity).apply {
                text = "文档目录"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.ink))
                setPaddingRelative(dp(16), 0, dp(16), 0)
                isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(-1, dp(48)))
            outlinePanel.addView(outlineList(), LinearLayout.LayoutParams(-1, 0, 1f))
            updateOutlineVisibility()
        }
    }

    private fun outlineList(): ListView = ListView(this).apply {
        divider = null
        adapter = object : BaseAdapter() {
            override fun getCount() = outline.size
            override fun getItem(position: Int) = outline[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = getItem(position).page != null
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val entry = getItem(position)
                return ((convertView as? TextView) ?: TextView(this@PdfReaderActivity).apply {
                    minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    textSize = 14f
                }).apply {
                    val indent = entry.level.coerceAtMost(6) * 12
                    setPaddingRelative(dp(16 + indent), dp(8), dp(16), dp(8))
                    setTextColor(getColor(if (entry.page != null) R.color.ink else R.color.ink3))
                    typeface = if (entry.page == null) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    text = entry.page?.let { "${entry.title}  ·  第 ${it + 1} 页" } ?: entry.title
                }
            }
        }
        setOnItemClickListener { _, _, position, _ ->
            val destination = outline[position].page ?: return@setOnItemClickListener
            if (!wideOutline) {
                outlineOpen = false
                updateOutlineVisibility()
            }
            viewer.jumpToPage(destination)
        }
    }

    private fun updateOutlineVisibility() {
        if (outline.isEmpty()) return
        val showPanel = outlineOpen && wideOutline
        outlinePanel.visibility = if (showPanel) View.VISIBLE else View.GONE
        outlineBack.isEnabled = showPanel
        outlineButton.isSelected = outlineOpen
        outlineButton.contentDescription = if (outlineOpen) "关闭目录" else "打开目录"

        if (outlineOpen && !wideOutline) {
            if (outlineDialog != null) return
            outlineDialog = AlertDialog.Builder(this)
                .setTitle("文档目录")
                .setView(outlineList())
                .setNegativeButton("关闭", null)
                .create().also { dialog ->
                    dialog.setOnDismissListener {
                        if (outlineDialog === dialog) {
                            outlineDialog = null
                            outlineOpen = false
                            updateOutlineVisibility()
                        }
                    }
                    dialog.show()
                }
        } else {
            val dialog = outlineDialog
            outlineDialog = null
            dialog?.dismiss()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_OUTLINE_OPEN, outlineOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        outlineDialog?.setOnDismissListener(null)
        outlineDialog?.dismiss()
        // 配置变更后阅读器仍在使用同一份缓存。
        if (isFinishing) {
            intent.getStringExtra(EXTRA_URI)?.let {
                revokeUriPermission(it.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        private const val STATE_OUTLINE_OPEN = "pdf_outline_open"
    }
}

class OutlinePdfViewerFragment : PdfViewerFragment() {
    // The pinned PDF viewer uses this hook to decide whether to show its edit action.
    @android.annotation.SuppressLint("RestrictedApi")

    override fun checkAnnotationIntentResolvability(uri: Uri): Boolean = false

    private var activePdfView: PdfView? = null

    @OptIn(androidx.pdf.ExperimentalPdfApi::class)

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        activePdfView = pdfView
    }

    override fun onDestroyView() {
        activePdfView = null
        super.onDestroyView()
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        (activity as? PdfReaderActivity)?.loadOutline()
    }

    internal fun jumpToPage(page: Int) {
        val view = activePdfView ?: return
        // scrollToPosition centers the supplied point, so offset by half the viewport.
        view.scrollToPosition(PdfPoint(page, 0f, view.height / (2f * view.zoom)))
    }
}
