package com.hnwd.bdplayer

import android.graphics.Typeface
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import kotlin.math.roundToInt

/**
 * 独立的纯文本阅读页（txt / md）。不复用 MainActivity：
 * 与 [PdfReaderActivity] 一样保持主界面平台 Activity + 手写浅色主题不受影响。
 *
 * 文件由文档链路下载到本地缓存，通过 [EXTRA_FILE] 传入；本页只负责解码渲染与缓存生命周期。
 * md 按源码显示，不做 Markdown 渲染（首版口径）。
 */
class TextReaderActivity : AppCompatActivity() {
    private val cache by lazy { PreviewCache.get(this) }
    private var cachedFile: File? = null
    private lateinit var body: ListView
    private lateinit var message: TextView
    private var textSizeSp = DEFAULT_TEXT_SIZE_SP

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)
        val name = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (file == null || !cache.retain(file)) {
            Toast.makeText(this, "文档缓存已失效，请从列表重新打开。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        cachedFile = file
        textSizeSp = savedInstanceState?.getFloat(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP) ?: DEFAULT_TEXT_SIZE_SP

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
            accessibilityPaneTitle = "文本阅读"
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
        val sizeButton = TextView(this).apply {
            text = "字号"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.ink))
            setBackgroundResource(R.drawable.bg_icon_ripple)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = "调整字号"
            setOnClickListener { switchTextSize() }
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(sizeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.line))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        message = TextView(this).apply {
            textSize = this@TextReaderActivity.textSizeSp
            setTextColor(getColor(R.color.ink))
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setText(R.string.text_reader_loading)
        }
        body = ListView(this).apply {
            setBackgroundColor(getColor(R.color.surface))
            divider = null
            clipToPadding = false
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setItemsCanFocus(true)
            emptyView = message
        }
        val reader = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.surface))
            addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(reader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val page = centeredContent(root)
        setContentView(page)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }

        loadText(file)
    }

    /** 解码放后台线程：大文本 + GB18030 兜底解码会卡住主线程。 */
    private fun loadText(file: File) {
        AppIo.execute {
            val document = runCatching {
                val text = decodeDocumentText(file.readBytes())
                text to documentTextChunks(text)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                document.onSuccess { (text, chunks) ->
                    message.setText(R.string.text_reader_empty)
                    body.adapter = TextAdapter(text, chunks)
                }.onFailure {
                    message.text = "无法读取文档内容，请重新打开。"
                }
            }
        }
    }

    private fun switchTextSize() {
        textSizeSp = if (textSizeSp < MAX_TEXT_SIZE_SP) textSizeSp + 2f else MIN_TEXT_SIZE_SP
        message.textSize = textSizeSp
        val position = body.firstVisiblePosition
        val top = body.getChildAt(0)?.top
        (body.adapter as? TextAdapter)?.notifyDataSetChanged()
        if (top != null) body.setSelectionFromTop(position, top - body.paddingTop)
    }

    private inner class TextAdapter(
        private val text: String,
        private val chunks: List<IntRange>,
    ) : BaseAdapter() {
        override fun getCount() = chunks.size
        override fun getItem(position: Int) = text.substring(chunks[position])
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? TextView ?: TextView(parent.context).apply {
                setTextColor(getColor(R.color.ink))
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                includeFontPadding = false
            }
            row.textSize = textSizeSp
            row.text = getItem(position)
            return row
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(KEY_TEXT_SIZE, textSizeSp)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        cachedFile?.let(cache::release)
    }

    companion object {
        const val EXTRA_FILE = "text_file"
        const val EXTRA_TITLE = "text_title"
        private const val KEY_TEXT_SIZE = "text_size_sp"
        private const val DEFAULT_TEXT_SIZE_SP = 16f
        private const val MIN_TEXT_SIZE_SP = 12f
        private const val MAX_TEXT_SIZE_SP = 24f
    }
}
