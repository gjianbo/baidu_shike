package com.hnwd.bdplayer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 图片预览页。优先用签名地址，按需补取当前图及相邻图。
 * 解码统一用平台 ImageDecoder，缩放用 Matrix + 平台手势，不引入第三方图片库。
 */
class ImageViewerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FILE = "image_file"
        const val EXTRA_TITLE = "image_title"
        const val EXTRA_MIME = "image_mime"
        const val EXTRA_INDEX = "image_index"
        const val EXTRA_COUNT = "image_count"
        const val EXTRA_SWITCH_INDEX = "image_switch_index"
        const val EXTRA_OPEN_ORIGINAL = "image_open_original"
        private const val EXTRA_ITEMS = "image_items"
        private const val EXTRA_PROVIDER = "image_provider"
        private const val MAX_DECODE_EDGE = 4096

        internal fun galleryIntent(
            context: Context, items: List<ImagePreviewItem>, index: Int, provider: SourceProvider,
        ): Intent = Intent(context, ImageViewerActivity::class.java)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_PROVIDER, provider.name)
            .putParcelableArrayListExtra(EXTRA_ITEMS, ArrayList(items.map { item ->
                Bundle().apply {
                    putString("id", item.fileId)
                    putString("title", item.title)
                    putString("key", item.key)
                    putString("url", item.url)
                    putString("shareInfoUrl", item.shareInfoUrl)
                    putString("diskDirectory", item.diskDirectory)
                }
            }))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val metadataUserAgent by lazy { WebSettings.getDefaultUserAgent(this) }
    private val drawableCache = LruCache<String, Pair<Drawable, String>>(4)
    private var items: List<ImagePreviewItem> = emptyList()
    private val previewMode get() = items.isNotEmpty()
    private var provider = SourceProvider.BAIDU
    private var generation = 0
    private var currentUrl = ""
    private var originalFetcher: ImageFileFetcher? = null
    private var index = 0
    private var count = 1
    private var localFile: File? = null
    private var mime = "image/*"
    private var animation: AnimatedImageDrawable? = null
    private var decodeThread: Thread? = null
    private lateinit var image: ZoomImageView
    private lateinit var hint: TextView
    private var titleView: TextView? = null
    private var prevButton: TextView? = null
    private var nextButton: TextView? = null
    private var counter: TextView? = null
    private var externalButton: TextView? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = intent.getParcelableArrayListExtra<Bundle>(EXTRA_ITEMS).orEmpty().map { item ->
            ImagePreviewItem(
                item.getString("id").orEmpty(), item.getString("title").orEmpty(),
                item.getString("key").orEmpty(), item.getString("url").orEmpty(),
                item.getString("shareInfoUrl").orEmpty(), item.getString("diskDirectory"),
            )
        }
        if (previewMode) {
            if (intent.getStringExtra(EXTRA_PROVIDER) == SourceProvider.QUARK.name) provider = SourceProvider.QUARK
            count = items.size
            index = (savedInstanceState?.getInt(EXTRA_INDEX) ?: intent.getIntExtra(EXTRA_INDEX, 0))
                .coerceIn(0, count - 1)
        } else {
            val cached = intent.getStringExtra(EXTRA_FILE)?.let(::File)
            if (cached == null || !ImageCache.retain(cached)) {
                Toast.makeText(this, "图片缓存已失效，请从列表重新打开。", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            localFile = cached
            mime = intent.getStringExtra(EXTRA_MIME).takeUnless { it.isNullOrEmpty() } ?: "image/*"
            count = intent.getIntExtra(EXTRA_COUNT, 1).coerceAtLeast(1)
            index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, count - 1)
        }

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // 项目统一约定：Android 15+ 强制 edge-to-edge，工具栏必须避开系统栏
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        image = ZoomImageView(this)
        image.onSwipe = { delta -> switchTo(index + delta) }
        root.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        hint = TextView(this).apply {
            text = "正在加载图片…"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        root.addView(topBar(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP,
        ))
        if (count > 1) {
            root.addView(bottomBar(), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
            ))
        }
        setContentView(root)
        updateChrome()
        if (previewMode) showAt(index) else localFile?.let(::decodeLocal)
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ColorDrawable(Color.argb(190, 0, 0, 0))
            setPadding(dp(6), 0, dp(8), 0)
        }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setBackgroundResource(R.drawable.bg_icon_ripple)
            contentDescription = "返回"
            setOnClickListener { finish() }
        }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        titleView = label
        bar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        externalButton = textButton("用其他应用打开") { openExternally() }.also { bar.addView(it) }
        return bar
    }

    private fun bottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ColorDrawable(Color.argb(190, 0, 0, 0))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        val prev = textButton("上一张") { switchTo(index - 1) }
        val next = textButton("下一张") { switchTo(index + 1) }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        prevButton = prev
        nextButton = next
        counter = label
        bar.addView(prev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(label, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(next, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        setBackgroundResource(R.drawable.bg_icon_ripple)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateChrome() {
        titleView?.text = if (previewMode) items[index].title
        else intent.getStringExtra(EXTRA_TITLE).orEmpty()
        externalButton?.isEnabled = localFile != null || currentUrl.isNotEmpty()
        counter?.text = "${index + 1} / $count"
        prevButton?.let { button ->
            button.isEnabled = index > 0
            button.alpha = if (button.isEnabled) 1f else 0.4f
        }
        nextButton?.let { button ->
            button.isEnabled = index < count - 1
            button.alpha = if (button.isEnabled) 1f else 0.4f
        }
    }

    /** 页内换图：只更新当前页面，不 finish、不回列表。 */
    private fun showAt(target: Int) {
        if (target !in 0 until count) return
        val request = ++generation
        decodeThread?.interrupt()
        originalFetcher?.cancel()
        localFile?.let(ImageCache::release)
        localFile = null
        currentUrl = ""
        index = target
        animation?.stop()
        animation = null
        image.display(null)
        updateChrome()
        hint.text = "正在加载图片…"
        hint.visibility = View.VISIBLE
        val item = items[target]
        if (provider == SourceProvider.QUARK && QuarkPersonalDrive.openSession() == null) {
            showError("请登录夸克网盘后重试")
            return
        }
        drawableCache.get(item.key)?.let { (drawable, url) ->
            currentUrl = url
            showDrawable(drawable)
            prefetchNeighbours()
            return
        }
        ImagePreviewLoader.load(item, provider, userAgent = metadataUserAgent) { result ->
            if (!isCurrent(request)) return@load
            result.onSuccess { preview ->
                decodeInBackground(
                    onSuccess = { drawable ->
                        currentUrl = preview.url
                        drawableCache.put(item.key, drawable to preview.url)
                        showDrawable(drawable)
                        prefetchNeighbours()
                    },
                    onFailure = {
                        ImagePreviewLoader.discard(item.key)
                        openOriginal(item)
                    },
                ) {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(preview.bytes))) { decoder, info, _ ->
                        configureDecoder(decoder, info)
                    }
                }
            }.onFailure { openOriginal(item) }
        }
    }

    private fun prefetchNeighbours() {
        listOf(index - 1, index + 1).forEach { position ->
            items.getOrNull(position)?.let { item ->
                if (item.url.isNotEmpty() || item.shareInfoUrl.isNotEmpty()) {
                    ImagePreviewLoader.load(item, provider, userAgent = metadataUserAgent) {}
                }
            }
        }
    }

    private fun isCurrent(request: Int) = request == generation && !isFinishing && !isDestroyed

    private fun openOriginal(item: ImagePreviewItem) {
        if (provider == SourceProvider.BAIDU) {
            returnToList(index, original = true)
            return
        }
        val ext = ImageTypes.ext(item.title)
        mime = ImageTypes.mime(ext)
        fun showFile(file: File) {
            check(ImageCache.retain(file)) { "图片缓存已失效，请重试" }
            localFile = file
            updateChrome()
            decodeLocal(file)
        }
        ImageCache.find(this, item.key, ext)?.let {
            runCatching { showFile(it) }.onFailure { showError(it.message ?: "图片打开失败") }
            return
        }
        hint.text = "正在加载原图…"
        val request = generation
        val fetcher = originalFetcher ?: ImageFileFetcher(this).also { originalFetcher = it }
        fetcher.startQuark(item.fileId, item.title) { result ->
            if (!isCurrent(request)) {
                result.getOrNull()?.let(ImageCache::discard)
                return@startQuark
            }
            result.mapCatching { temp ->
                try {
                    showFile(ImageCache.publish(this, item.key, ext, temp))
                } finally {
                    ImageCache.discard(temp)
                }
            }.onFailure { showError(it.message ?: "图片打开失败") }
        }
    }

    private fun switchTo(target: Int) {
        if (target < 0 || target >= count || target == index) return
        if (previewMode) {
            showAt(target)
        } else {
            returnToList(target, original = false)
        }
    }

    private fun returnToList(target: Int, original: Boolean) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_SWITCH_INDEX, target)
            .putExtra(EXTRA_OPEN_ORIGINAL, original))
        finish()
    }

    private fun openExternally() {
        val target = localFile
        val view = if (target != null) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            if (currentUrl.isEmpty()) return
            Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "没有可用的图片查看应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeLocal(target: File) = decodeInBackground(
        onSuccess = { drawable ->
            showDrawable(drawable)
            if (previewMode) prefetchNeighbours()
        },
        onFailure = { showError("图片数据不完整或格式不受支持") },
    ) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(target)) { decoder, info, _ ->
            configureDecoder(decoder, info)
        }
    }

    private fun decodeInBackground(onSuccess: (Drawable) -> Unit, onFailure: () -> Unit, build: () -> Drawable) {
        val request = generation
        decodeThread?.interrupt()
        decodeThread = Thread {
            val result = runCatching { build() }
            if (Thread.currentThread().isInterrupted) return@Thread
            handler.post {
                if (isCurrent(request)) result.onSuccess(onSuccess).onFailure { onFailure() }
            }
        }.also { it.start() }
    }

    private fun configureDecoder(decoder: ImageDecoder, info: ImageDecoder.ImageInfo) {
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        val metrics = resources.displayMetrics
        val maxW = (metrics.widthPixels * 2).coerceAtMost(MAX_DECODE_EDGE)
        val maxH = (metrics.heightPixels * 2).coerceAtMost(MAX_DECODE_EDGE)
        val width = info.size.width
        val height = info.size.height
        if (width > maxW || height > maxH) {
            val ratio = maxOf(width.toFloat() / maxW, height.toFloat() / maxH)
            decoder.setTargetSize(
                (width / ratio).toInt().coerceAtLeast(1),
                (height / ratio).toInt().coerceAtLeast(1),
            )
        }
    }

    private fun showDrawable(drawable: Drawable) {
        animation?.stop()
        animation = drawable as? AnimatedImageDrawable
        animation?.start()
        image.display(drawable)
        hint.visibility = View.GONE
        updateChrome()
    }

    private fun showError(message: String) {
        hint.text = if (localFile != null) "$message\n可尝试「用其他应用打开」" else message
        hint.visibility = View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        if (animation?.isRunning == false) animation?.start()
    }

    override fun onStop() {
        animation?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        decodeThread?.interrupt()
        originalFetcher?.close()
        animation?.stop()
        drawableCache.evictAll()
        localFile?.let(ImageCache::release)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_INDEX, index)
    }
}

/** 基于 Matrix 的双击/双指缩放、拖拽与左右滑动翻页，纯平台手势 API。 */
private class ZoomImageView(context: android.content.Context) : AppCompatImageView(context) {
    var onSwipe: ((Int) -> Unit)? = null
    private val transform = Matrix()
    private val matrixValues = FloatArray(9)
    private val drawableRect = RectF()
    private val viewRect = RectF()
    private var baseScale = 1f
    private var currentScale = 1f
    private var maxScale = 4f
    private var drawableWidth = 0
    private var drawableHeight = 0
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (drawableWidth == 0) return false
                val next = (currentScale * detector.scaleFactor).coerceIn(baseScale, maxScale)
                val factor = next / currentScale
                transform.postScale(factor, factor, detector.focusX, detector.focusY)
                clamp()
                applyMatrix()
                return true
            }
        })
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // 未放大时不吃横向拖拽，留给翻页手势
                if (currentScale <= baseScale * 1.02f) return false
                transform.postTranslate(-distanceX, -distanceY)
                clamp()
                applyMatrix()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val swipe = onSwipe ?: return false
                if (e1 == null || currentScale > baseScale * 1.02f) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < 80f || abs(dx) <= abs(dy) * 1.4f || abs(velocityX) < 250f) return false
                swipe.invoke(if (dx < 0) 1 else -1)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (drawableWidth == 0) return false
                val target = if (currentScale > baseScale * 1.1f) baseScale
                else (baseScale * 2.5f).coerceAtMost(maxScale)
                val factor = target / currentScale
                transform.postScale(factor, factor, e.x, e.y)
                clamp()
                applyMatrix()
                return true
            }
        })
    }

    fun display(drawable: Drawable?) {
        setImageDrawable(drawable)
        if (drawable == null) {
            drawableWidth = 0
            drawableHeight = 0
            baseScale = 1f
            transform.reset()
            applyMatrix()
            return
        }
        drawableWidth = drawable.intrinsicWidth.coerceAtLeast(1)
        drawableHeight = drawable.intrinsicHeight.coerceAtLeast(1)
        fit()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (drawableWidth > 0) fit()
    }

    private fun fit() {
        if (drawableWidth <= 0 || drawableHeight <= 0 || width <= 0 || height <= 0) return
        baseScale = minOf(
            (width - paddingLeft - paddingRight).toFloat() / drawableWidth,
            (height - paddingTop - paddingBottom).toFloat() / drawableHeight,
        )
        maxScale = baseScale * 4f
        transform.setScale(baseScale, baseScale)
        transform.postTranslate(
            (width - drawableWidth * baseScale) / 2f,
            (height - drawableHeight * baseScale) / 2f,
        )
        applyMatrix()
    }

    /** 图片小于视图时居中，放大后不允许拖出空白边界。 */
    private fun clamp() {
        viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
        drawableRect.set(0f, 0f, drawableWidth.toFloat(), drawableHeight.toFloat())
        transform.mapRect(drawableRect)
        val dx = when {
            drawableRect.width() <= viewRect.width() -> viewRect.centerX() - drawableRect.centerX()
            drawableRect.left > viewRect.left -> viewRect.left - drawableRect.left
            drawableRect.right < viewRect.right -> viewRect.right - drawableRect.right
            else -> 0f
        }
        val dy = when {
            drawableRect.height() <= viewRect.height() -> viewRect.centerY() - drawableRect.centerY()
            drawableRect.top > viewRect.top -> viewRect.top - drawableRect.top
            drawableRect.bottom < viewRect.bottom -> viewRect.bottom - drawableRect.bottom
            else -> 0f
        }
        transform.postTranslate(dx, dy)
    }

    private fun applyMatrix() {
        imageMatrix = transform
        transform.getValues(matrixValues)
        currentScale = matrixValues[Matrix.MSCALE_X]
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            clamp()
            applyMatrix()
            performClick()
        }
        return true
    }
}
