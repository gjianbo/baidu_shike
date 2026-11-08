package com.hnwd.bdplayer

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val CACHE_TTL_MS = 30 * 60 * 1000L
private const val HOME_RECHECK_COOLDOWN_MS = 30 * 1000L
private const val API_PAGE = "https://${PanApi.HOST}/__shike_api"

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var native: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var appSub: TextView
    private lateinit var sourceName: TextView
    private lateinit var sourceButton: View
    private lateinit var refreshButton: View
    private lateinit var sectionRow: View
    private lateinit var sectionTitle: TextView
    private lateinit var sortButton: TextView
    private lateinit var crumbsScroll: View
    private lateinit var crumbsRow: LinearLayout
    private lateinit var chipAll: TextView
    private lateinit var chipUnheard: TextView
    private lateinit var filterBar: View
    private lateinit var filterCount: TextView
    private lateinit var navRoots: List<View>
    private lateinit var historyPanel: LinearLayout
    private lateinit var nowPlaying: View
    private lateinit var courseSwitch: LinearLayout
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var courseHint: TextView
    private lateinit var audioHistory: AudioHistory
    private lateinit var videoHistory: AudioHistory
    private lateinit var favorites: Favorites
    private lateinit var courseCache: CourseCache
    private var cacheNote = ""
    private var audioControls: AudioControls? = null
    private var audioPage: View? = null
    private var releaseAudioPage: (() -> Unit)? = null
    private var pendingAudioPage: Pair<Int, PanEntry>? = null
    private val videoResolver = VideoStreamResolver()
    private val audioResolver = VideoStreamResolver()
    private val pdfFetcher by lazy { PdfFileFetcher(this) }
    private val mediaAddresses by lazy { MediaAddressCache(this) }
    private var pdfLoading: AlertDialog? = null
    private var mediaCaptchaDialog: Pair<RequestLane, AlertDialog>? = null
    private var videoLoading: AlertDialog? = null
    private var videoPage: VideoPlaybackPage? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val uiExecutor = Executor { runOnUiThread(it) }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            ThemeStore.rememberScreen(this, screen.name)
            ThemeStore.apply(this)
            recreate()
        }
    }

    private enum class PlaybackRoute { AUDIO, WEB_AUDIO }
    private enum class MediaFilter(val label: String) {
        ALL("全部"), DOCUMENT("文档"), AUDIO("音频"), VIDEO("视频");

        fun includes(kind: MediaKind): Boolean = kind == MediaKind.DIRECTORY || when (this) {
            ALL -> true
            DOCUMENT -> kind == MediaKind.PDF || kind == MediaKind.DOCUMENT
            AUDIO -> kind == MediaKind.AUDIO
            VIDEO -> kind == MediaKind.VIDEO
        }
    }

    private var mediaFilter = MediaFilter.ALL
    private var redrawFilteredRows: (() -> Unit)? = null
    private var showingHomeDirectory = false
    private lateinit var mediaChips: List<TextView>

    private enum class CourseOrder(val label: String) {
        ASCENDING("名称升序"),
        DESCENDING("名称降序"),
        NETWORK("网盘顺序"),
    }

    private val courseNameParts = Regex("[0-9]+|[^0-9]+")
    private val courseCollator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    private var courseOrder = CourseOrder.ASCENDING

    private enum class Screen { HOME, FAVORITES, BROWSE, SETTINGS }
    private val folders = mutableListOf<PanEntry>()
    private val courseEntries = mutableListOf<PanEntry>()
    private var courseParentKey = ""
    private enum class RequestLane { LIST, AUDIO, RENEWAL }

    private var audioRequestId = 0
    private var audioPlayToken: Long? = null
    private var dispatchedAudioEntry: PanEntry? = null
    private var pendingAudioEntry: PanEntry? = null
    private var stoppedVideo: VideoPlaybackPage.Snapshot? = null
    private var videoToRestore: VideoPlaybackPage.Snapshot? = null
    private var loadingVideo: VideoPlaybackPage.Snapshot? = null
    private data class QueueSelection(val entry: PanEntry, val entries: List<PanEntry>, val listKey: String?)
    private data class RetainedPlayback(val video: VideoPlaybackPage.Snapshot?, val selection: QueueSelection?)
    private var pendingQueue: QueueSelection? = null
    private var audioRequestMessage: String? = null
    private var refreshAudioPageMessage: (() -> Unit)? = null
    private val nowPlayingTicker = object : Runnable {
        override fun run() {
            updateNowPlaying()
            nowPlaying.postDelayed(this, 1_000)
        }
    }
    private var screen = Screen.HOME
    private var unheardOnly = false
    private var unheardCount = 0
    private var recent = true
    private val wide: Boolean
        get() = resources.configuration.screenWidthDp >= 600
    private var activityVisible = false
    private var ready = false
    private var requestId = 0
    private var gid = ""
    private var loginPending = false
    private var pickingSource = false
    private var verifiedAccount = ""
    private var accountProbeId = 0
    private var renewalRequestId = 0
    private var renewingPlaybackId: String? = null
    private var renewedPlaybackId: String? = null
    private var invalidatedRenewedPlaybackId: String? = null
    private var checkingAccount = false
    private var homeDisplayedEntries: List<PanEntry> = emptyList()
    private var pendingHomeUpdate: List<PanEntry>? = null
    private var pendingHomeUpdateNote = ""
    private var homeCheckInFlight = false
    private var homeCheckToken = 0
    private var lastHomeCheckAt = 0L

    private fun hasVerifiedAccount() = verifiedAccount.isNotEmpty() && verifiedAccount == AccountScope.current
    private var observedGroup: Pair<String, String>? = null
    private lateinit var addSourceButton: View

    private data class ShareSource(val id: String, val name: String, val sessionUrl: String = "")

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (lastCustomNonConfigurationInstance as? RetainedPlayback)?.let {
            videoToRestore = it.video
            pendingQueue = it.selection
        }
        val savedOrder = ThemeStore.courseOrder(this)
        courseOrder = CourseOrder.entries.firstOrNull { it.name == savedOrder } ?: CourseOrder.ASCENDING
        window.statusBarColor = color(R.color.bg)
        window.navigationBarColor = color(R.color.bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ThemeStore.isDark(this@MainActivity)
            isAppearanceLightNavigationBars = !ThemeStore.isDark(this@MainActivity)
        }
        PdfCache.prune(this)
        AccountScope.init(this)
        audioHistory = AudioHistory(getSharedPreferences("audio_history", MODE_PRIVATE))
        videoHistory = AudioHistory(getSharedPreferences("video_history", MODE_PRIVATE))
        favorites = Favorites(getSharedPreferences("favorites", MODE_PRIVATE))
        courseCache = CourseCache(getSharedPreferences("course_cache", MODE_PRIVATE))
        gid = getSharedPreferences("account_scope", MODE_PRIVATE)
            .getString(AccountScope.key("group_id"), "").orEmpty()
        val frame = FrameLayout(this).apply { setBackgroundColor(color(R.color.bg)) }
        native = column().apply { setBackgroundColor(color(R.color.bg)) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            val defaultAgent = WebSettings.getDefaultUserAgent(this@MainActivity)
            settings.userAgentString = if ("Android" in defaultAgent && "Mobile" !in defaultAgent) {
                defaultAgent.replace(" Safari/", " Mobile Safari/")
            } else {
                defaultAgent
            }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val host = url.host.orEmpty()
                    return url.scheme != "https" || (host != "baidu.com" && !host.endsWith(".baidu.com"))
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    // Keep the WebView's Baidu cookie jar and API origin without loading the web app.
                    if (request.isForMainFrame && url.toString() == API_PAGE) {
                        return WebResourceResponse(
                            "text/html", "UTF-8", "<!doctype html><title>拾课</title>".byteInputStream(),
                        )
                    }
                    if (url.scheme == "https" && url.host == PanApi.HOST && url.path == "/mbox/group/listshare") {
                        val group = url.getQueryParameter("gid").orEmpty()
                        if (group.matches(Regex("[0-9]{1,24}"))) view.post {
                            val route = Uri.parse(view.url.orEmpty()).fragment.orEmpty()
                            if (pickingSource && route.startsWith("/im/session?")) {
                                observedGroup = route to group
                            }
                        }
                    }
                    return null
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (Uri.parse(url).fragment == "/im/addrBook") observedGroup = null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (pickingSource && Uri.parse(url).host == "wappass.baidu.com") loginPending = true
                    ready = false
                    invalidateAccount()
                    if (native.visibility == View.VISIBLE) refresh()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (view.url?.substringBefore('#') != url.substringBefore('#')) return
                    val address = Uri.parse(url)
                    ready = address.scheme == "https" && address.host == PanApi.HOST
                    if (ready) {
                        if (loginPending) CookieManager.getInstance().flush()
                        verifyAndRefresh()
                    } else if (native.visibility == View.VISIBLE) {
                        refresh()
                    }
                }
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        frame.addView(native, FrameLayout.LayoutParams(-1, -1))
        val webControls = row().apply {
            setBackgroundColor(color(R.color.surface))
            addView(button("返回 App") { showApp() })
            addView(button("通讯录") { openContacts() })
            addView(button("关联当前会话") { associateCurrentSession() }.also { addSourceButton = it })
            addView(button("百度登录") { showLoginDialog() })
            visibility = View.GONE
        }
        frame.addView(webControls, FrameLayout.LayoutParams(-1, dp(52)))
        web.layoutParams = (web.layoutParams as FrameLayout.LayoutParams).apply { topMargin = dp(52) }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            frame.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        native.tag = webControls
        setContentView(frame)

        val shell = layoutInflater.inflate(R.layout.app_main, native, false)
        val shellWidth = if (wide) minOf(resources.displayMetrics.widthPixels, dp(760)) else -1
        native.addView(shell, LinearLayout.LayoutParams(shellWidth, -1).apply {
            if (wide) gravity = Gravity.CENTER_HORIZONTAL
        })
        title = shell.findViewById(R.id.appTitle)
        appSub = shell.findViewById(R.id.appSub)
        sourceName = shell.findViewById(R.id.sourceName)
        sourceButton = shell.findViewById(R.id.btnBrowse)
        sectionRow = shell.findViewById(R.id.sectionRow)
        sectionTitle = shell.findViewById(R.id.sectionTitle)
        sortButton = shell.findViewById(R.id.sortButton)
        crumbsScroll = shell.findViewById(R.id.crumbsScroll)
        crumbsRow = shell.findViewById(R.id.crumbsRow)
        chipAll = shell.findViewById(R.id.chipAll)
        chipUnheard = shell.findViewById(R.id.chipUnheard)
        filterBar = shell.findViewById(R.id.filterBar)
        val mediaFilters = shell.findViewById<LinearLayout>(R.id.mediaFilters)
        mediaChips = MediaFilter.entries.map { filter ->
            TextView(this).apply {
                text = filter.label
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(color(R.color.ink2))
                setOnClickListener {
                    mediaFilter = filter
                    unheardOnly = false
                    updateFilterChips()
                    redrawFilteredRows?.invoke()
                }
                mediaFilters.addView(this, LinearLayout.LayoutParams(-2, dp(36)).apply {
                    if (filter != MediaFilter.ALL) marginStart = dp(8)
                })
            }
        }
        filterCount = shell.findViewById(R.id.filterCount)
        navRoots = listOf(
            shell.findViewById(R.id.navHome),
            shell.findViewById(R.id.navBrowse),
            shell.findViewById(R.id.navFavorites),
            shell.findViewById(R.id.navSettings),
        )
        status = shell.findViewById(R.id.status)
        nowPlaying = shell.findViewById(R.id.miniPlayer)
        historyPanel = shell.findViewById(R.id.historyPanel)
        content = shell.findViewById(R.id.content)
        contentScroll = shell.findViewById(R.id.contentScroll)
        courseSwitch = shell.findViewById(R.id.courseSwitch)
        btnPrev = shell.findViewById(R.id.btnPrev)
        btnNext = shell.findViewById(R.id.btnNext)
        courseHint = shell.findViewById(R.id.courseHint)
        sourceButton.setOnClickListener { chooseSource() }
        refreshButton = shell.findViewById(R.id.btnRefresh)
        refreshButton.setOnClickListener {
            when {
                ready && hasVerifiedAccount() -> refresh(force = true)
                ready && !checkingAccount -> verifyAndRefresh(force = true)
                !ready -> showApp()
            }
        }
        sortButton.setOnClickListener { showCourseSort() }
        chipAll.setOnClickListener { setFilter(false) }
        chipUnheard.setOnClickListener { setFilter(true) }
        nowPlaying.setOnClickListener { controller?.let { showPlayback(it) } }
        nowPlaying.findViewById<View>(R.id.miniClose).setOnClickListener { stopBackgroundAudio() }
        nowPlaying.findViewById<View>(R.id.miniPlay).setOnClickListener {
            controller?.let { player ->
                if (!ownsAudio(player)) return@let
                pendingQueue = null
                cancelPendingAudioRequest()
                syncAudioAnchor(player)
                when {
                    player.playbackState == Player.STATE_ENDED -> {
                        player.seekToDefaultPosition()
                        player.play()
                    }
                    player.playWhenReady -> player.pause()
                    else -> player.play()
                }
            }
        }
        ThemeStore.takePendingScreen(this)?.let { name ->
            Screen.entries.firstOrNull { it.name == name }?.let { restored ->
                screen = restored
                recent = restored == Screen.HOME
            }
        }
        refresh()
        web.loadUrl(API_PAGE)
        val token = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                if (!isDestroyed && !isFinishing) {
                    controller = runCatching { future.get() }.getOrNull()?.also { player ->
                        if (hasVerifiedAccount()) player.sendCustomCommand(AudioPlaybackService.SYNC_ACCOUNT, Bundle.EMPTY)
                        player.addListener(object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events) {
                                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) syncAudioAnchor(player)
                                updateNowPlaying()
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) advanceAudioIfEnded(player)
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                renewBackgroundAudio(player)
                            }
                        })
                    }
                    updateNowPlaying()
                    controller?.let {
                        syncAudioAnchor(it)
                        renewBackgroundAudio(it)
                        advanceAudioIfEnded(it)
                    }
                }
            }, uiExecutor)
        }
    }

    private fun advanceAudioIfEnded(player: Player) {
        if (pendingAudioEntry != null || player.playbackState != Player.STATE_ENDED || !player.playWhenReady) return
        val ended = currentAudioEntry(player) ?: return
        if (PlaybackQueueState.anchor?.let { sameEntry(it, ended) } == true) {
            playSibling(ended, 1, automatic = true)
        }
    }

    private fun syncAudioAnchor(player: Player) {
        val active = currentAudioEntry(player) ?: return
        if (pendingAudioEntry?.let { !sameEntry(it, active) } == true) return
        if (pendingQueue?.let { sameEntry(it.entry, active) } == true ||
            PlaybackQueueState.entries.any { sameEntry(it, active) }
        ) confirmPlayback(active)
    }

    private fun confirmPlayback(entry: PanEntry) {
        pendingQueue?.takeIf { sameEntry(it.entry, entry) }?.let {
            PlaybackQueueState.entries = it.entries
            PlaybackQueueState.listKey = it.listKey
            pendingQueue = null
        }
        PlaybackQueueState.anchor = entry
        renderCourseSwitch()
    }

    private fun ownsAudio(player: Player): Boolean =
        hasVerifiedAccount() &&
            player.currentMediaItem?.mediaMetadata?.extras?.getString("account") == verifiedAccount

    private fun updateNowPlaying() {
        val active = controller
        if (active == null || !ownsAudio(active)) {
            nowPlaying.visibility = View.GONE
            return
        }
        nowPlaying.visibility = View.VISIBLE
        nowPlaying.findViewById<TextView>(R.id.miniTitle).text = active.mediaMetadata.title ?: "当前播放"
        val durationMs = active.duration
        val duration = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        val position = (active.currentPosition / 1000).toInt().coerceAtLeast(0)
        nowPlaying.findViewById<TextView>(R.id.miniMeta).text =
            if (duration > 0) "${formatAudioTime(position)} / ${formatAudioTime(duration)}"
            else formatAudioTime(position)
        val percent = if (duration > 0) (position * 100 / duration).coerceIn(0, 100) else 0
        val progressBar = nowPlaying.findViewById<LinearLayout>(R.id.miniProgress)
        progressBar.visibility = if (duration > 0) View.VISIBLE else View.GONE
        if (duration > 0) {
            nowPlaying.findViewById<View>(R.id.miniFill).layoutParams =
                LinearLayout.LayoutParams(0, -1, percent.coerceAtLeast(1).toFloat())
            nowPlaying.findViewById<View>(R.id.miniBarRest).layoutParams =
                LinearLayout.LayoutParams(0, -1, (100 - percent).coerceAtLeast(1).toFloat())
        }
        nowPlaying.findViewById<ImageView>(R.id.miniPlay).apply {
            val canPause = active.playWhenReady && active.playbackState != Player.STATE_ENDED
            setImageResource(if (canPause) R.drawable.ic_pause else R.drawable.ic_play)
            contentDescription = if (canPause) "暂停" else "播放"
            setColorFilter(color(R.color.accentInk))
        }
        nowPlaying.findViewById<ImageView>(R.id.miniCover).setColorFilter(color(R.color.accentInk))
        nowPlaying.findViewById<ImageView>(R.id.miniClose).setColorFilter(color(R.color.ink3))
        pendingAudioPage?.let { (id, entry) ->
            val item = active.currentMediaItem
            val extras = item?.mediaMetadata?.extras
            if (id != audioRequestId) {
                pendingAudioPage = null
            } else if (item?.mediaId == entry.fileId && extras?.getString("gid") == entry.gid &&
                extras.getString("message") == entry.messageId
            ) {
                pendingAudioPage = null
                showPlayback(active)
            }
        }
    }

    private fun showScreen(target: Screen) {
        screen = target
        recent = target == Screen.HOME
        unheardOnly = false
        folders.clear()
        courseEntries.clear()
        courseParentKey = ""
        contentScroll.scrollTo(0, 0)
        refresh()
    }

    private fun setFilter(unheard: Boolean) {
        unheardOnly = unheard
        updateFilterChips()
        redrawFilteredRows?.invoke()
    }

    private fun updateFilterChips() {
        MediaFilter.entries.forEach { filter ->
            val chip = mediaChips[filter.ordinal]
            chip.isSelected = filter == mediaFilter
            setTabActive(chip, chip.isSelected)
        }
        setTabActive(chipAll, !unheardOnly)
        setTabActive(chipUnheard, unheardOnly)
        chipUnheard.visibility = if (mediaFilter == MediaFilter.DOCUMENT) View.GONE else View.VISIBLE
        chipUnheard.text = "${if (mediaFilter == MediaFilter.AUDIO) "未听" else "未播完"} $unheardCount"
    }

    private fun showAvailableMediaFilters(kinds: Set<MediaKind>) {
        MediaFilter.entries.forEach { filter ->
            val available = filter == MediaFilter.ALL || kinds.any(filter::includes)
            mediaChips[filter.ordinal].visibility = if (available) View.VISIBLE else View.GONE
        }
    }

    private fun updateNav() {
        val screens = listOf(Screen.HOME, Screen.BROWSE, Screen.FAVORITES, Screen.SETTINGS)
        val active = screens.indexOf(screen)
        val titles = listOf("最近", "目录", "收藏", "设置")
        val icons = listOf(R.drawable.ic_history, R.drawable.ic_folder, R.drawable.ic_star, R.drawable.ic_settings)
        navRoots.forEachIndexed { index, root ->
            val on = index == active
            root.findViewById<View>(R.id.navIndicator).setBackgroundResource(
                if (on) R.drawable.bg_nav_pill_on else R.color.transparent,
            )
            root.findViewById<ImageView>(R.id.navIcon).apply {
                setImageResource(icons[index])
                setColorFilter(color(if (on) R.color.accentInk else R.color.ink3))
            }
            val label = root.findViewById<TextView>(R.id.navLabel)
            label.text = titles[index]
            label.setTextColor(color(if (on) R.color.ink else R.color.ink3))
            label.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            root.setOnClickListener { showScreen(screens[index]) }
        }
        renderSourcePicker()
        val browsing = screen == Screen.HOME || screen == Screen.BROWSE
        sourceButton.visibility = if (browsing) View.VISIBLE else View.GONE
        appSub.visibility = if (browsing && (folders.isNotEmpty() || !hasVerifiedAccount())) View.VISIBLE else View.GONE
        refreshButton.visibility = if (browsing) View.VISIBLE else View.GONE
        title.setTextSize(if (screen == Screen.HOME) 30f else 26f)
        crumbsScroll.visibility = if (screen == Screen.BROWSE) View.VISIBLE else View.GONE
        sectionRow.visibility = if (browsing || folders.isNotEmpty()) View.VISIBLE else View.GONE
        sectionTitle.text = when {
            screen == Screen.HOME -> "群内更新"
            screen == Screen.BROWSE -> "文件"
            else -> ""
        }
    }

    private fun renderCrumbs() {
        crumbsRow.removeAllViews()
        fun addCrumb(label: String, current: Boolean, action: (() -> Unit)?) {
            crumbsRow.addView(TextView(this).apply {
                text = label
                textSize = 11.5f
                typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(color(if (current) R.color.ink else R.color.ink2))
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(6), dp(9), dp(6))
                if (current) setBackgroundResource(R.drawable.bg_source_chip)
                isClickable = action != null
                setOnClickListener { action?.invoke() }
            })
        }
        fun addSeparator() {
            crumbsRow.addView(TextView(this).apply {
                text = "›"
                textSize = 13f
                setTextColor(color(R.color.ink3))
                setPadding(dp(2), 0, dp(2), 0)
            })
        }
        addCrumb("根目录", folders.isEmpty()) {
            if (folders.isNotEmpty()) {
                folders.clear()
                refresh()
            }
        }
        folders.forEachIndexed { index, entry ->
            addSeparator()
            addCrumb(entry.name, index == folders.lastIndex) {
                if (index < folders.lastIndex) {
                    while (folders.size > index + 1) folders.removeAt(folders.size - 1)
                    refresh()
                }
            }
        }
        crumbsScroll.visibility = if (screen == Screen.BROWSE) View.VISIBLE else View.GONE
    }

    private fun showThemeDialog() {
        val modes = listOf(ThemeStore.SYSTEM, ThemeStore.LIGHT, ThemeStore.DARK)
        val labels = modes.map { ThemeStore.label(it) }
        val current = modes.indexOf(ThemeStore.mode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("外观")
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, index ->
                dialog.dismiss()
                ThemeStore.set(this, modes[index], screen.name)
                findViewById<TextView>(R.id.setThemeValue)?.text = ThemeStore.label(modes[index])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isUnfinishedMedia(entry: PanEntry): Boolean = when (PanApi.kindOf(entry)) {
        MediaKind.AUDIO, MediaKind.VIDEO -> !isPlayed(entry)
        else -> false
    }

    private fun isPlayed(entry: PanEntry): Boolean {
        val item = when (PanApi.kindOf(entry)) {
            MediaKind.AUDIO -> audioHistory.find(entry)
            MediaKind.VIDEO -> videoHistory.find(entry)
            else -> null
        }
        return item?.completed == true
    }

    private fun shareSources(): List<ShareSource> {
        val prefs = getSharedPreferences("account_scope", MODE_PRIVATE)
        val stored = JSONArray(prefs.getString(AccountScope.key("share_sources"), "[]") ?: "[]")
        val sources = List(stored.length()) { index ->
            val item = stored.getJSONObject(index)
            ShareSource(item.getString("id"), item.getString("name"), item.optString("session"))
        }
        return if (gid.isNotEmpty() && sources.none { it.id == gid }) {
            listOf(ShareSource(gid, "原群文件库")) + sources
        } else sources
    }

    private fun chooseSource() {
        if (!hasVerifiedAccount() || !ready) {
            toast("请先确认百度网盘登录")
            return
        }
        val account = verifiedAccount
        val sources = shareSources()
        if (sources.isEmpty()) {
            discoverGroups()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("切换群组")
            .setSingleChoiceItems(sources.map { it.name }.toTypedArray(), sources.indexOfFirst { it.id == gid }) { dialog, index ->
                dialog.dismiss()
                if (hasVerifiedAccount() && verifiedAccount == account) {
                    val source = sources[index]
                    if (source.sessionUrl.isNotEmpty()) showWeb(source.sessionUrl)
                    else activateGroup(source.id)
                }
            }
            .setPositiveButton("从网盘添加群组") { _, _ -> discoverGroups() }
            .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun discoverGroups() {
        if (!ready || !hasVerifiedAccount()) return
        val account = verifiedAccount
        val candidates = linkedMapOf<String, ShareSource>()
        val pageSize = 20

        fun failed() {
            if (!hasVerifiedAccount() || verifiedAccount != account) return
            AlertDialog.Builder(this)
                .setTitle("无法读取网盘群组")
                .setMessage(status.text)
                .setPositiveButton("重试") { _, _ -> discoverGroups() }
                .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
                .setNegativeButton("取消", null)
                .show()
        }

        fun nextPage(token: String, start: Int) {
            load(PanApi.groups(token, start, pageSize), statusHint = "正在读取网盘群组…", onFailure = ::failed) { json ->
                val records = json.getJSONArray("records")
                val previousCount = candidates.size
                for (index in 0 until records.length()) {
                    val item = records.getJSONObject(index)
                    val id = item.optString("gid")
                    if (id.matches(Regex("[0-9]{1,24}"))) {
                        candidates[id] = ShareSource(id, item.optString("name").ifBlank { "群组 $id" })
                    }
                }
                val nextStart = start + records.length()
                val hasMore = if (json.has("has_more")) json.optInt("has_more") != 0
                    else json.optInt("count", nextStart) > nextStart || records.length() == pageSize
                if (hasMore && candidates.size > previousCount) nextPage(token, nextStart)
                else showGroupPicker(account, candidates.values.toList())
            }
        }

        load(PanApi.groupListSession(), statusHint = "正在读取网盘群组…", onFailure = ::failed) { json ->
            val session = json.getJSONObject("result")
            if (session.optString("uk") != account) {
                verifyAndRefresh()
            } else {
                val token = session.optString("bdstoken")
                check(token.isNotBlank()) { "网盘未返回群组列表所需的会话参数，请重新登录。" }
                nextPage(token, 0)
            }
        }
    }

    private fun showGroupPicker(account: String, candidates: List<ShareSource>) {
        status.text = ""
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("网盘群组")
                .setMessage("这个账号的群组列表为空。")
                .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        val existing = shareSources().associateBy { it.id }
        val checked = BooleanArray(candidates.size) { candidates[it].id in existing }
        AlertDialog.Builder(this)
            .setTitle("勾选共享来源")
            .setMultiChoiceItems(candidates.map { it.name }.toTypedArray(), checked) { _, index, selected ->
                checked[index] = selected
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                if (!hasVerifiedAccount() || verifiedAccount != account) return@setPositiveButton
                val candidateIds = candidates.map { it.id }.toSet()
                val selected = candidates.filterIndexed { index, _ -> checked[index] }
                    .map { existing[it.id]?.takeUnless { source -> source.name == "原群文件库" } ?: it }
                val retained = shareSources().filterNot { it.id in candidateIds }
                val sources = retained + selected
                val nextGroup = gid.takeIf { current -> sources.any { it.id == current } }
                    ?: selected.firstOrNull()?.id
                    ?: retained.firstOrNull { it.sessionUrl.isEmpty() }?.id.orEmpty()
                saveShareSources(sources, nextGroup)
                activateGroup(nextGroup)
            }
            .show()
    }

    private fun saveShareSources(sources: List<ShareSource>, activeGroup: String) {
        val entries = JSONArray()
        sources.forEach {
            entries.put(JSONObject().put("id", it.id).put("name", it.name).put("session", it.sessionUrl))
        }
        getSharedPreferences("account_scope", MODE_PRIVATE).edit()
            .putString(AccountScope.key("share_sources"), entries.toString())
            .putString(AccountScope.key("group_id"), activeGroup)
            .apply()
    }

    private fun openContacts() {
        pickingSource = true
        observedGroup = null
        showWeb(PanApi.CONTACTS)
        toast("打开群里的文件库，再点「关联当前会话」")
    }

    private fun associateCurrentSession() {
        val account = verifiedAccount
        if (!ready || account.isEmpty() || account != AccountScope.current) {
            toast("请先登录百度网盘")
            return
        }
        val address = Uri.parse(web.url.orEmpty())
        val route = address.fragment.orEmpty()
        val isSession = address.scheme == "https" && address.host == PanApi.HOST &&
            address.path == "/disk/main" && route.startsWith("/im/session?")
        val group = observedGroup?.takeIf { isSession && it.first == route }?.second.orEmpty()
        if (group.isEmpty()) {
            toast("还未识别到群文件库。请进入会话的文件列表，或返回 App 从网盘勾选群组。")
            return
        }
        val sources = shareSources()
        val existing = sources.firstOrNull { it.id == group }
        val defaultName = existing?.name ?: "群文件库 ${sources.size + 1}"
        val name = EditText(this).apply {
            setSingleLine()
            setText(defaultName)
            hint = "显示名称"
        }
        AlertDialog.Builder(this)
            .setTitle("关联群文件库")
            .setView(name)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                if (account != verifiedAccount || account != AccountScope.current) return@setPositiveButton
                val source = ShareSource(group, name.text.toString().trim().ifEmpty { defaultName })
                saveShareSources(shareSources().filterNot { it.id == group } + source, group)
                activateGroup(group, returnFromWeb = true)
            }.show()
    }

    private fun activateGroup(group: String, returnFromWeb: Boolean = false) {
        gid = group
        homeCheckToken++
        homeCheckInFlight = false
        lastHomeCheckAt = 0L
        getSharedPreferences("account_scope", MODE_PRIVATE).edit()
            .putString(AccountScope.key("group_id"), gid).apply()
        if (returnFromWeb) {
            screen = Screen.HOME
            recent = true
            folders.clear()
            showApp()
        } else {
            showScreen(screen)
            contentScroll.scrollTo(0, 0)
        }
    }

    private fun refresh(force: Boolean = false) {
        requestId++
        clearHomeUpdatePrompt()
        homeDisplayedEntries = emptyList()
        redrawFilteredRows = null
        showingHomeDirectory = false
        unheardCount = 0
        filterCount.text = ""
        content.removeAllViews()
        showAvailableMediaFilters(emptySet())
        styleContent()
        val earlyParent = folders.lastOrNull()
        val earlyKey = if (screen == Screen.HOME && earlyParent == null && gid.isNotEmpty()) "recent:$gid" else ""
        val earlyCached = if (!hasVerifiedAccount() && (checkingAccount || !ready) && earlyKey.isNotEmpty()) {
            courseCache.readAll(earlyKey)
        } else {
            emptyList()
        }
        if (earlyCached.isNotEmpty()) {
            updateNowPlaying()
            updateNav()
            filterBar.visibility = View.VISIBLE
            sectionRow.visibility = View.VISIBLE
            renderAudioHistory(allowUnverified = true)
            renderCourseSwitch()
            title.text = "最近"
            appSub.text = "最近更新与最近播放"
            cacheNote = "缓存内容 · 上次同步 ${courseCache.syncLabel(earlyKey)}"
            display(earlyCached)
            status.text = cacheNote
            return
        }
        if (!hasVerifiedAccount()) {
            historyPanel.removeAllViews()
            filterBar.visibility = View.GONE
            sectionRow.visibility = View.GONE
            title.text = when (screen) {
                Screen.FAVORITES -> "收藏"
                Screen.SETTINGS -> "设置"
                Screen.BROWSE -> "目录"
                else -> "最近"
            }
            appSub.text = "连接百度网盘后查看群组内容"
            courseEntries.clear()
            renderCourseSwitch()
            updateNowPlaying()
            updateNav()
            sectionRow.visibility = View.GONE
            status.text = if (checkingAccount || !ready) "正在连接网盘并确认账号…" else "无法确认网盘账号，请登录后刷新。"
            if (screen == Screen.SETTINGS) {
                renderSettings()
                return
            }
            content.addView(headAction("登录百度网盘") { showLoginDialog() })
            return
        }
        updateNowPlaying()
        val parent = folders.lastOrNull()
        val cacheKey = when {
            parent != null -> "dir:${parent.gid}:${parent.messageId}:${parent.fileId}"
            screen == Screen.FAVORITES || screen == Screen.SETTINGS -> ""
            screen == Screen.HOME -> "recent:$gid"
            else -> "roots:$gid"
        }
        val key = parent?.let { "${it.gid}:${it.messageId}:${it.fileId}" }.orEmpty()
        if (key != courseParentKey) {
            courseParentKey = key
            courseEntries.clear()
        }
        updateNav()
        updateFilterChips()
        filterBar.visibility =
            if (parent != null || screen == Screen.HOME || screen == Screen.BROWSE) View.VISIBLE else View.GONE
        title.text = when (screen) {
            Screen.FAVORITES -> parent?.name ?: "收藏"
            Screen.SETTINGS -> "设置"
            Screen.BROWSE -> parent?.name ?: "目录"
            else -> parent?.name ?: "最近"
        }
        appSub.text = when {
            parent != null -> "第 ${folders.size} 级目录"
            screen == Screen.FAVORITES -> "仅保存在本机"
            screen == Screen.SETTINGS -> "外观、备份与本机数据"
            screen == Screen.BROWSE -> "群组目录"
            else -> "最近更新与最近播放"
        }
        renderCrumbs()
        renderAudioHistory()
        renderCourseSwitch()
        if ((screen == Screen.HOME || screen == Screen.BROWSE) && parent == null) {
            renderSourcePicker()
        }
        val cached = if (cacheKey.isNotEmpty()) courseCache.readAll(cacheKey) else emptyList()
        val homeRootKey = if (screen == Screen.HOME && parent == null && gid.isNotEmpty()) "roots:$gid" else null
        val recentPage = homeRootKey?.let { courseCache.readPage(cacheKey, 1) }
        val recentIsEmpty = recentPage?.let { it.latestEmpty || it.entries.isEmpty() } == true
        cacheNote = if (cached.isNotEmpty()) {
            "缓存内容 · 上次同步 ${courseCache.syncLabel(cacheKey)}"
        } else ""
        if (homeRootKey != null && recentIsEmpty) {
            renderHomeDirectory(homeRootKey)
        } else if (cached.isNotEmpty()) {
            if (parent != null) {
                courseEntries.clear()
                courseEntries.addAll(cached)
                renderCourse()
            } else {
                display(cached)
            }
        } else if (parent != null) {
            content.addView(headAction("← 返回上一级") {
                folders.removeAt(folders.lastIndex)
                refresh()
            })
        }
        when {
            screen == Screen.FAVORITES && parent == null -> renderFavorites()
            screen == Screen.SETTINGS -> renderSettings()
            parent == null && gid.isEmpty() -> {
                status.text = "请从网盘勾选共享来源"
            }
            !ready -> {
                status.text = if (cached.isNotEmpty()) "$cacheNote · 正在连接网盘"
                else "正在连接百度网盘。如果列表无法加载，请先登录。"
            }
            cacheKey.isNotEmpty() && parent == null && !force && screen == Screen.BROWSE &&
                courseCache.isFresh(cacheKey, CACHE_TTL_MS) &&
                courseCache.readPage(cacheKey, 1)?.nextCursor != null -> {
                status.text = "$cacheNote · 30 分钟内已检查"
                renderRootMore(cacheKey)
            }
            parent != null -> loadChildren(parent, 1, cacheKey = cacheKey)
            homeRootKey != null -> {
                if (!force && homeCheckInFlight) {
                    status.text = if (cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在检查最近更新…"
                } else if (!force && System.currentTimeMillis() - lastHomeCheckAt < HOME_RECHECK_COOLDOWN_MS) {
                    status.text = if (cacheNote.isNotEmpty()) "$cacheNote · 刚刚已检查" else ""
                } else {
                    startHomeCheck(force, cacheKey, homeRootKey, cached, recentIsEmpty)
                }
            }
            else -> loadRoots(cacheKey)
        }
    }

    private fun startHomeCheck(
        force: Boolean,
        cacheKey: String,
        homeRootKey: String,
        cached: List<PanEntry>,
        recentIsEmpty: Boolean,
    ) {
        val checkToken = ++homeCheckToken
        homeCheckInFlight = true
        load(
            PanApi.recent(gid),
            statusHint = "正在检查最近更新…",
            onCancelled = {
                if (checkToken == homeCheckToken) homeCheckInFlight = false
            },
            onFailure = {
                if (checkToken == homeCheckToken) homeCheckInFlight = false
                if (recentIsEmpty) {
                    cacheFailure(homeRootKey)
                } else if (cached.isEmpty()) {
                    content.addView(headAction("打开群目录") { showScreen(Screen.BROWSE) })
                } else cacheFailure(cacheKey)
            },
        ) { json ->
            applyHomeRecent(json, cacheKey, homeRootKey, force, checkToken)
        }
    }

    private fun maybeCheckHomeInBackground() {
        if (!ready || !hasVerifiedAccount() || screen != Screen.HOME || folders.isNotEmpty()) return
        if (homeCheckInFlight || pendingHomeUpdate != null) return
        if (System.currentTimeMillis() - lastHomeCheckAt < HOME_RECHECK_COOLDOWN_MS) return
        val cacheKey = "recent:$gid"
        val cached = courseCache.readAll(cacheKey)
        if (cached.isEmpty()) return
        val recentPage = courseCache.readPage(cacheKey, 1)
        val recentIsEmpty = recentPage?.let { it.latestEmpty || it.entries.isEmpty() } == true
        startHomeCheck(false, cacheKey, "roots:$gid", cached, recentIsEmpty)
    }

    private fun applyHomeRecent(
        json: JSONObject,
        cacheKey: String,
        homeRootKey: String,
        force: Boolean,
        checkToken: Int,
    ) {
        if (checkToken == homeCheckToken) {
            homeCheckInFlight = false
            lastHomeCheckAt = System.currentTimeMillis()
        }
        clearHomeUpdatePrompt()
        val previous = homeDisplayedEntries
        val previousIds = previous.map(::entryKey).toHashSet()
        val fresh = PanApi.parseRecent(json)
        val changed = courseCache.writeNewFirst(cacheKey, 1, fresh)
        if (fresh.isEmpty()) {
            loadHomeDirectory(homeRootKey, force)
            return
        }
        showingHomeDirectory = false
        cacheNote = "已同步 ${courseCache.syncLabel(cacheKey)}"
        val merged = courseCache.readAll(cacheKey)
        val newEntries = if (previous.isNotEmpty()) {
            fresh.filter { entryKey(it) !in previousIds }
        } else {
            emptyList()
        }
        val visibleNew = newEntries.filter(::matchesHomeFilter)
        if (contentScroll.scrollY <= dp(6)) {
            if (changed || previous.isEmpty()) {
                content.removeAllViews()
                renderSourcePicker()
                display(merged)
                contentScroll.scrollTo(0, 0)
            }
            status.text = when {
                previous.isEmpty() -> cacheNote
                visibleNew.isNotEmpty() -> "$cacheNote · 新增 ${visibleNew.size} 条"
                newEntries.isNotEmpty() -> "$cacheNote · 有 ${newEntries.size} 条新内容被当前筛选隐藏"
                !changed -> "$cacheNote · 无更新"
                else -> cacheNote
            }
        } else if (visibleNew.isNotEmpty()) {
            showHomeUpdatePrompt(visibleNew.size, merged, cacheNote)
        } else {
            status.text = if (!changed) "$cacheNote · 无更新" else cacheNote
        }
    }

    private fun showHomeUpdatePrompt(count: Int, entries: List<PanEntry>, note: String) {
        pendingHomeUpdate = entries
        pendingHomeUpdateNote = note
        status.text = "有 $count 条新内容，点此查看"
        status.setTextColor(color(R.color.accentInk))
        status.textSize = 11.5f
        status.setTypeface(null, Typeface.BOLD)
        status.isClickable = true
        status.setOnClickListener { applyHomeUpdateNow() }
    }

    private fun applyHomeUpdateNow() {
        val entries = pendingHomeUpdate ?: return
        val note = pendingHomeUpdateNote
        clearHomeUpdatePrompt()
        content.removeAllViews()
        renderSourcePicker()
        display(entries)
        contentScroll.post { contentScroll.scrollTo(0, 0) }
        status.text = note
    }

    private fun clearHomeUpdatePrompt() {
        pendingHomeUpdate = null
        pendingHomeUpdateNote = ""
        status.setOnClickListener(null)
        status.isClickable = false
        status.setTextColor(color(R.color.ink3))
        status.textSize = 10.5f
        status.setTypeface(null, Typeface.NORMAL)
    }

    private fun matchesHomeFilter(entry: PanEntry): Boolean {
        if (!mediaFilter.includes(PanApi.kindOf(entry))) return false
        return !unheardOnly || entry.directory || isUnfinishedMedia(entry)
    }

    private fun entryKey(entry: PanEntry): String = "${entry.gid}:${entry.messageId}:${entry.fileId}"

    private fun loadHomeDirectory(key: String, force: Boolean) {
        if (!showingHomeDirectory) renderHomeDirectory(key)
        else status.text = if (courseCache.readPage(key, 1)?.entries?.isEmpty() == true) "群目录暂无内容" else ""
        if (!force && courseCache.isFresh(key, CACHE_TTL_MS) && courseCache.readPage(key, 1)?.nextCursor != null) {
            renderRootMore(key)
        } else {
            loadRoots(key)
        }
    }

    private fun renderHomeDirectory(key: String) {
        showingHomeDirectory = true
        redrawFilteredRows = null
        content.removeAllViews()
        renderSourcePicker()
        content.addView(label("近期没有更新，可以从群目录打开已有内容。", 13f))
        content.addView(headAction("打开群目录") { showScreen(Screen.BROWSE) })
        sectionTitle.text = "群目录内容"
        val entries = courseCache.readAll(key)
        addFilteredCourseRows(entries, sortable = true)
        status.text = if (courseCache.readPage(key, 1) != null && entries.isEmpty()) "群目录暂无内容" else ""
    }

    private fun loadRoots(key: String, page: Int = 1, cursor: String = "", previousButton: View? = null) {
        val group = gid
        val homeDirectory = showingHomeDirectory
        load(
            PanApi.roots(group, cursor),
            statusHint = if (homeDirectory) "正在读取群目录…"
            else if (page == 1 && cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else null,
            onCancelled = { previousButton?.isEnabled = true },
            onFailure = {
                previousButton?.isEnabled = true
                if (page == 1) {
                    cacheFailure(key)
                    renderRootMore(key)
                }
            },
        ) { json ->
            val entries = PanApi.parseRoots(json, group)
            val next = nextRootCursor(json, cursor)
            courseCache.writeIfChanged(key, page, entries, next)
            courseCache.clearAfter(key, page)
            cacheNote = "已同步 ${courseCache.syncLabel(key)}"
            if (homeDirectory) {
                renderHomeDirectory(key)
            } else {
                content.removeAllViews()
                renderSourcePicker()
                display(courseCache.readAll(key))
            }
            renderRootMore(key)
            if (next.isEmpty() && json.optInt("has_more") != 0) {
                status.text = "网盘未返回可用的分页游标，请刷新后重试。"
            }
        }
    }

    private fun nextRootCursor(json: JSONObject, cursor: String): String =
        json.optString("last_msg_time").takeIf {
            val time = it.toLongOrNull()
            json.optInt("has_more") != 0 && time != null && time > 0 &&
                time < (cursor.toLongOrNull() ?: Long.MAX_VALUE)
        }.orEmpty()

    private fun renderRootMore(key: String) {
        val (page, cached) = courseCache.lastPage(key) ?: return
        val cursor = cached.nextCursor?.takeIf { it.isNotEmpty() } ?: return
        val more = headAction("加载更多") {}
        more.setBackgroundResource(R.drawable.bg_load_more)
        more.setTextColor(color(R.color.ink3))
        more.setOnClickListener {
            more.isEnabled = false
            loadRoots(key, page + 1, cursor, more)
        }
        content.addView(more)
    }

    private fun invalidateAccount() {
        accountProbeId++
        renewalRequestId++
        homeCheckToken++
        homeCheckInFlight = false
        lastHomeCheckAt = 0L
        renewingPlaybackId = null
        cancelPendingAudioRequest()
        requestId++
        checkingAccount = false
        verifiedAccount = ""
        mediaCaptchaDialog?.second?.dismiss()
        videoResolver.cancel()
        videoLoading?.dismiss()
        videoLoading = null
        if (pdfLoading != null) {
            pdfFetcher.cancel()
            pdfLoading?.dismiss()
            pdfLoading = null
        }
    }

    private fun verifyAndRefresh(force: Boolean = false) {
        invalidateAccount()
        checkingAccount = true
        if (native.visibility == View.VISIBLE) refresh()
        probeAccount { changed, confirmed ->
            checkingAccount = false
            if (loginPending && confirmed) {
                loginPending = false
                if (pickingSource) openContacts() else showApp()
            } else if (native.visibility == View.VISIBLE) {
                refresh(force = force || changed)
            }
        }
    }

    private fun probeAccount(onDone: (Boolean, Boolean) -> Unit) {
        val probe = accountProbeId
        val key = "__groupPlayerUk$probe"
        val script = """
            (() => {
              if (location.origin !== 'https://pan.baidu.com') return false;
              const r = { ready: false };
              window.$key = r;
              fetch(${JSONObject.quote(PanApi.viewer())}, { credentials: 'same-origin', cache: 'no-store', headers: {'X-Requested-With':'XMLHttpRequest'} })
                .then(async resp => { r.body = await resp.text(); })
                .catch(() => { r.body = ''; })
                .finally(() => { r.ready = true; });
              return true;
            })()
        """.trimIndent()
        web.evaluateJavascript(script) { started ->
            if (probe != accountProbeId || isDestroyed) return@evaluateJavascript
            if (started != "true") {
                onDone(false, false)
                return@evaluateJavascript
            }
            var tries = 0
            var finished = false
            fun finish(changed: Boolean, confirmed: Boolean = false) {
                if (finished || isDestroyed) return
                finished = true
                onDone(changed, confirmed)
            }
            fun poll() {
                if (isDestroyed || probe != accountProbeId) return
                if (tries++ > 50) {
                    web.evaluateJavascript("delete window.$key", null)
                    finish(false)
                    return
                }
                web.evaluateJavascript(
                    "(() => { const r = window.$key; if (!r || !r.ready) return null; delete window.$key; return r.body || ''; })()",
                ) { raw ->
                    if (probe != accountProbeId || isDestroyed) return@evaluateJavascript
                    if (raw == null || raw == "null") {
                        web.postDelayed({ poll() }, 200)
                        return@evaluateJavascript
                    }
                    val body = runCatching { JSONTokener(raw).nextValue()?.toString() }.getOrNull().orEmpty()
                    val uk = runCatching {
                        val json = JSONObject(body)
                        if (json.optInt("errno", -1) == 0) json.optJSONObject("result")?.optString("uk").orEmpty()
                        else ""
                    }.getOrDefault("")
                    val confirmed = uk.matches(Regex("[0-9]{1,24}"))
                    verifiedAccount = uk.takeIf { confirmed }.orEmpty()
                    val changed = confirmed && AccountScope.set(this@MainActivity, uk)
                    if (confirmed) controller?.let {
                        it.sendCustomCommand(AudioPlaybackService.SYNC_ACCOUNT, Bundle.EMPTY)
                        renewBackgroundAudio(it)
                    }
                    if (changed) {
                        videoPage?.close()
                        closeAudioPage()
                        folders.clear()
                        courseEntries.clear()
                        courseParentKey = ""
                        pendingQueue = pendingQueue?.takeIf { videoToRestore?.account == verifiedAccount }
                        PlaybackQueueState.anchor = null
                        PlaybackQueueState.entries = emptyList()
                        PlaybackQueueState.listKey = null
                        toast("已切换账号，数据按账号隔离")
                        gid = getSharedPreferences("account_scope", MODE_PRIVATE)
                            .getString(AccountScope.key("group_id"), "").orEmpty()
                    }
                    finish(changed, confirmed)
                    if (confirmed) {
                        restoreVideoIfReady()
                        if (!changed) controller?.let {
                            syncAudioAnchor(it)
                            advanceAudioIfEnded(it)
                        }
                    }
                }
            }
            poll()
        }
    }

    private fun cacheFailure(key: String) {
        if (key.isEmpty()) return
        val label = courseCache.syncLabel(key)
        if (label.isEmpty()) return
        cacheNote = "显示缓存内容 · 上次同步 $label"
        status.text = "${status.text}\n$cacheNote"
    }

    private fun loadChildren(
        parent: PanEntry,
        page: Int,
        previousButton: View? = null,
        cacheKey: String = "",
    ) {
        val key = cacheKey.ifEmpty { "dir:${parent.gid}:${parent.messageId}:${parent.fileId}" }
        load(
            PanApi.children(parent, page),
            post = true,
            statusHint = if (page == 1 && courseCache.syncLabel(key).isNotEmpty()) {
                "$cacheNote · 正在检查更新"
            } else null,
            onCancelled = { previousButton?.isEnabled = true },
            onFailure = {
                previousButton?.isEnabled = true
                if (page == 1) cacheFailure(key)
            },
        ) { json ->
            val entries = PanApi.parseChildren(json, parent)
            val hasMore = json.optInt("has_more") != 0 && entries.isNotEmpty()
            val changed = courseCache.writeIfChanged(key, page, entries, if (hasMore) "more" else "")
            if (changed || !hasMore) courseCache.clearAfter(key, page)
            courseEntries.clear()
            courseEntries.addAll(courseCache.readAll(key))
            cacheNote = "已同步 ${courseCache.syncLabel(key)}"
            renderCourse()
            if (!changed) status.text = "$cacheNote · 无更新"
            if (hasMore) {
                val more = headAction("加载更多") {}
                more.setBackgroundResource(R.drawable.bg_load_more)
                more.setTextColor(color(R.color.ink3))
                more.setOnClickListener {
                    more.isEnabled = false
                    loadChildren(parent, page + 1, more, key)
                }
                content.addView(more)
            }
        }
    }

    private fun renderSourcePicker() {
        val name = shareSources().firstOrNull { it.id == gid }?.name
        sourceName.text = name ?: "选择群组"
        sourceButton.contentDescription = name?.let { "切换群组，当前为 $it" } ?: "选择群组"
    }

    private fun display(entries: List<PanEntry>) {
        if (screen == Screen.HOME && folders.isEmpty()) homeDisplayedEntries = entries
        status.text = if (entries.isEmpty()) "没有条目" else cacheNote
        sectionTitle.text = if (screen == Screen.HOME) "群内更新" else "文件"
        addFilteredCourseRows(entries, sortable = screen == Screen.BROWSE)
    }

    private fun renderCourse() {
        status.text = if (courseEntries.isEmpty()) "没有条目" else cacheNote
        sectionTitle.text = "文件"
        content.removeAllViews()
        addFilteredCourseRows(courseEntries.toList(), sortable = true)
    }

    private fun addFilteredCourseRows(entries: List<PanEntry>, sortable: Boolean) {
        sortButton.visibility = if (sortable) View.VISIBLE else View.GONE
        sortButton.text = courseOrder.label
        val kinds = entries.asSequence().filterNot { it.directory }.map(PanApi::kindOf).toSet()
        showAvailableMediaFilters(kinds)
        if (mediaFilter != MediaFilter.ALL && kinds.none(mediaFilter::includes)) {
            mediaFilter = MediaFilter.ALL
            unheardOnly = false
        }
        val rows = column()
        fun renderRows() {
            val matching = entries.filter { mediaFilter.includes(PanApi.kindOf(it)) }
            val unheard = matching.filter { it.directory || isUnfinishedMedia(it) }
            unheardCount = unheard.count { !it.directory }
            updateFilterChips()
            val shown = if (unheardOnly) unheard else matching
            val ordered = if (sortable) orderCourses(shown) else shown
            filterCount.text = "共 ${ordered.size} 项"
            rows.removeAllViews()
            extendPlaybackQueue(ordered)
            if (ordered.isEmpty() && entries.isNotEmpty()) rows.addView(label("当前已加载的内容中没有匹配项", 12f))
            addCourseRows(rows, ordered)
            renderCourseSwitch()
        }
        content.addView(rows)
        redrawFilteredRows = ::renderRows
        renderRows()
    }

    private fun showCourseSort() {
        val options = CourseOrder.entries
        AlertDialog.Builder(this)
            .setTitle("排序")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), courseOrder.ordinal) { dialog, index ->
                courseOrder = options[index]
                ThemeStore.setCourseOrder(this, courseOrder.name)
                sortButton.text = courseOrder.label
                dialog.dismiss()
                redrawFilteredRows?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun orderCourses(entries: List<PanEntry>, order: CourseOrder = courseOrder): List<PanEntry> = when (order) {
        CourseOrder.ASCENDING -> entries.sortedWith { first, second -> compareCourseNames(first.name, second.name) }
        CourseOrder.DESCENDING -> entries.sortedWith { first, second -> compareCourseNames(second.name, first.name) }
        CourseOrder.NETWORK -> entries
    }

    private fun playbackOrderKey(entry: PanEntry, listKey: String?): String? {
        val directory = directoryCacheKey(entry, listKey) ?: courseCache.directoryContaining(entry)
        return directory?.let { AccountScope.key(it) } ?: playbackPathOrderKey(entry)
    }

    private fun playbackPathOrderKey(entry: PanEntry): String? {
        val path = PanApi.readablePath(entry.path)
        if (!path.startsWith('/')) return null
        return AccountScope.key("path:${entry.gid}:${path.substringBeforeLast('/')}")
    }

    private fun playbackOrder(entry: PanEntry, listKey: String?): CourseOrder {
        val preferences = getSharedPreferences("playback_order", MODE_PRIVATE)
        val saved = playbackOrderKey(entry, listKey)?.let {
            preferences.getString(it, null)
        } ?: playbackPathOrderKey(entry)?.let { preferences.getString(it, null) }
        return when (saved) {
            CourseOrder.DESCENDING.name -> CourseOrder.DESCENDING
            CourseOrder.ASCENDING.name -> CourseOrder.ASCENDING
            else -> if (courseOrder == CourseOrder.DESCENDING) CourseOrder.DESCENDING else CourseOrder.ASCENDING
        }
    }

    private fun playbackSortLabel(entry: PanEntry, listKey: String?): String =
        if (playbackOrder(entry, listKey) == CourseOrder.DESCENDING) "降序 ▾" else "升序 ▾"

    private fun showPlaybackSort(anchor: View, entry: PanEntry, listKey: String?, onSorted: (List<PanEntry>) -> Unit) {
        val key = playbackOrderKey(entry, listKey) ?: return
        val account = verifiedAccount
        val options = listOf(CourseOrder.ASCENDING, CourseOrder.DESCENDING)
        PopupMenu(anchor.context, anchor).apply {
            val selected = playbackOrder(entry, listKey)
            options.forEachIndexed { index, order ->
                menu.add(0, index, index, order.label)
            }
            menu.setGroupCheckable(0, true, true)
            menu.findItem(options.indexOf(selected)).isChecked = true
            setOnMenuItemClickListener { item ->
                if (account == verifiedAccount && hasVerifiedAccount() &&
                    PlaybackQueueState.listKey == listKey &&
                    PlaybackQueueState.anchor?.let { sameEntry(it, entry) } == true
                ) {
                    val order = options[item.itemId]
                    getSharedPreferences("playback_order", MODE_PRIVATE).edit().putString(key, order.name).apply()
                    val courses = orderCourses(PlaybackQueueState.entries, order)
                    PlaybackQueueState.entries = courses
                    pendingQueue = pendingQueue?.let { pending ->
                        if (playbackOrderKey(pending.entry, pending.listKey) == key) {
                            pending.copy(entries = orderCourses(pending.entries, order))
                        } else pending
                    }
                    renderCourseSwitch()
                    onSorted(courses)
                }
                true
            }
            show()
        }
    }

    private fun playbackDirectory(entry: PanEntry, listKey: String?): Pair<String, List<PanEntry>>? {
        val preferredKey = directoryCacheKey(entry, listKey)
        val key = preferredKey?.takeIf { courseCache.readAll(it).any { file -> sameEntry(file, entry) } }
            ?: courseCache.directoryContaining(entry)
            ?: return null
        val media = sameMediaKind(entry, courseCache.readAll(key))
        val queueKey = if (key.startsWith("dir:")) key.removePrefix("dir:") else "BROWSE:${entry.gid}"
        return queueKey to orderCourses(media, playbackOrder(entry, queueKey))
    }

    private fun nextDirectoryPage(entry: PanEntry, listKey: String?): String? {
        val key = directoryCacheKey(entry, listKey) ?: return null
        val (page, cached) = courseCache.lastPage(key) ?: return null
        if (key.startsWith("roots:")) {
            val cursor = cached.nextCursor?.takeIf { it.isNotEmpty() } ?: return null
            return PanApi.roots(entry.gid, cursor)
        }
        if (cached.nextCursor != "more" && (cached.nextCursor != null || cached.entries.size < 100)) return null
        val directoryId = key.substringAfterLast(':')
        return PanApi.children(entry, page + 1, directoryId)
    }

    private fun loadMoreCourses(entry: PanEntry, listKey: String?, done: (List<PanEntry>?) -> Unit) {
        val key = directoryCacheKey(entry, listKey) ?: return done(null)
        val (page, cached) = courseCache.lastPage(key) ?: return done(null)
        val url = nextDirectoryPage(entry, listKey) ?: return done(null)
        val nested = key.startsWith("dir:")
        load(url, post = nested, onCancelled = { done(null) }, onFailure = { done(null) }) { json ->
            val files = if (nested) PanApi.parseChildren(json, entry) else PanApi.parseRoots(json, entry.gid)
            val next = if (nested) {
                if (json.optInt("has_more") != 0 && files.isNotEmpty()) "more" else ""
            } else {
                nextRootCursor(json, cached.nextCursor.orEmpty())
            }
            courseCache.writeIfChanged(key, page + 1, files, next)
            if (PlaybackQueueState.listKey != listKey ||
                PlaybackQueueState.anchor?.let { sameEntry(it, entry) } != true) {
                done(null)
                return@load
            }
            val courses = orderCourses(sameMediaKind(entry, courseCache.readAll(key)), playbackOrder(entry, listKey))
            PlaybackQueueState.entries = courses
            renderCourseSwitch()
            done(courses)
        }
    }

    private fun directoryCacheKey(entry: PanEntry, listKey: String?): String? = when {
        listKey == "BROWSE:${entry.gid}" -> "roots:${entry.gid}"
        listKey?.startsWith("${entry.gid}:") == true -> "dir:$listKey"
        else -> null
    }

    private fun compareCourseNames(first: String, second: String): Int {
        val left = courseNameParts.findAll(first).iterator()
        val right = courseNameParts.findAll(second).iterator()
        while (left.hasNext() && right.hasNext()) {
            val a = left.next().value
            val b = right.next().value
            val aNumber = a[0] in '0'..'9'
            val bNumber = b[0] in '0'..'9'
            val order = when {
                aNumber && bNumber -> {
                    val aDigits = a.trimStart('0')
                    val bDigits = b.trimStart('0')
                    val lengthOrder = aDigits.length.compareTo(bDigits.length)
                    if (lengthOrder == 0) aDigits.compareTo(bDigits) else lengthOrder
                }
                aNumber != bNumber -> if (aNumber) -1 else 1
                else -> courseCollator.compare(a, b)
            }
            if (order != 0) return order
        }
        return left.hasNext().compareTo(right.hasNext())
    }

    private fun styleContent() {
        content.setBackgroundColor(color(R.color.transparent))
    }

    private fun addGroupTitle(value: String) {
        content.addView(TextView(this).apply {
            text = value
            textSize = 11f
            setTextColor(color(R.color.ink3))
            setPadding(0, dp(8), 0, dp(6))
        })
    }

    private fun addCourseRows(container: LinearLayout, entries: List<PanEntry>) {
        if (!wide) {
            entries.forEach { container.addView(courseRow(it, entries)) }
            return
        }
        entries.chunked(2).forEach { pair ->
            val line = row()
            pair.forEachIndexed { index, entry ->
                val params = LinearLayout.LayoutParams(0, -2, 1f)
                if (index == 0 && pair.size == 2) params.rightMargin = dp(8)
                line.addView(courseRow(entry, entries), params)
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, -2, 1f))
            container.addView(line)
        }
    }

    private fun courseRow(entry: PanEntry, entries: List<PanEntry>): View {
        val format = formatOf(entry)
        val kind = PanApi.kindOf(entry)
        val row = layoutInflater.inflate(R.layout.item_course, content, false)
        val fmt = row.findViewById<ImageView>(R.id.fmt)
        when (kind) {
            MediaKind.AUDIO -> {
                fmt.setImageResource(R.drawable.ic_music)
                fmt.setBackgroundResource(R.drawable.bg_fmt_audio)
                fmt.setColorFilter(color(R.color.accentInk))
            }
            MediaKind.VIDEO -> {
                fmt.setImageResource(R.drawable.ic_video)
                fmt.setBackgroundResource(R.drawable.bg_fmt_video)
                fmt.setColorFilter(color(R.color.accentInk))
            }
            MediaKind.PDF, MediaKind.DOCUMENT -> {
                fmt.setImageResource(R.drawable.ic_file)
                fmt.setBackgroundResource(R.drawable.bg_fmt_pdf)
                fmt.setColorFilter(color(R.color.pdfInk))
            }
            else -> {
                fmt.setImageResource(if (entry.directory) R.drawable.ic_folder else R.drawable.ic_file)
                fmt.setBackgroundResource(R.drawable.bg_fmt_folder)
                fmt.setColorFilter(color(R.color.folderInk))
            }
        }
        row.findViewById<TextView>(R.id.itemTitle).text = entry.name
        // 兼容旧收藏/历史里已持久化的编码路径；无斜杠时不再把整串路径回显到 meta
        val folder = PanApi.readablePath(entry.path)
            .substringBeforeLast('/', "")
            .substringAfterLast('/')
        val size = entry.size.toLongOrNull()?.let { if (it >= 1024 * 1024) "${it / 1024 / 1024} MB" else "" }.orEmpty()
        val kindName = when (kind) {
            MediaKind.AUDIO -> "音频"
            MediaKind.VIDEO -> "视频"
            MediaKind.PDF, MediaKind.DOCUMENT -> "文档"
            else -> if (entry.directory) "目录" else format.lowercase()
        }
        row.findViewById<TextView>(R.id.itemMeta).text =
            listOf(kindName, folder, size).filter { it.isNotEmpty() }.joinToString(" · ")
        val side = row.findViewById<ImageView>(R.id.itemSide)
        side.tag = favoriteTag(entry)
        paintFavorite(side, favorites.has(entry))
        side.setOnClickListener { toggleFavorite(entry) }
        val mark = progressMark(entry)
        if (mark != null) {
            row.findViewById<View>(R.id.itemFoot).visibility = View.VISIBLE
            val time = row.findViewById<TextView>(R.id.footTime)
            time.text = mark.first
            time.setTextColor(color(if (mark.second) R.color.ok else R.color.ink3))
            row.findViewById<View>(R.id.footDot).setBackgroundResource(
                if (mark.second) R.drawable.bg_dot_ok else R.drawable.bg_dot_accent,
            )
        }
        row.setOnClickListener { openEntry(entry, entries, currentPlaybackListKey()) }
        if (kind == MediaKind.AUDIO) row.setOnLongClickListener {
            selectPlayback(entry, entries, currentPlaybackListKey())
            resolveAudio(entry, PlaybackRoute.WEB_AUDIO)
            true
        }
        return row
    }

    /** 按扩展名选择音视频播放或 PDF 下载。 */
    private fun openEntry(entry: PanEntry, entries: List<PanEntry>, listKey: String? = null) {
        val kind = PanApi.kindOf(entry)
        if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) selectPlayback(entry, entries, listKey)
        when (kind) {
            MediaKind.DIRECTORY -> {
                folders.add(entry)
                refresh()
            }
            MediaKind.VIDEO -> startVideo(entry)
            MediaKind.AUDIO -> resolveAudio(entry)
            MediaKind.PDF -> openPdf(entry)
            MediaKind.DOCUMENT -> AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setMessage("文档/图片类不能转码播放，只能下载原始文件；当前版本暂只提供 PDF 在线阅读。")
                .setPositiveButton("关闭", null)
                .show()
            MediaKind.OTHER -> AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setMessage("此版本暂不支持该类型，只提供目录信息。")
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun progressMark(entry: PanEntry): Pair<String, Boolean>? {
        val kind = PanApi.kindOf(entry)
        val item = when (kind) {
            MediaKind.AUDIO -> audioHistory.find(entry)
            MediaKind.VIDEO -> videoHistory.find(entry)
            else -> null
        } ?: return null
        val verb = if (kind == MediaKind.AUDIO) "听完" else "看完"
        if (item.completed) return "已$verb" to true
        if (item.seconds <= 0) return null
        return "${if (kind == MediaKind.AUDIO) "听到" else "看到"} ${formatAudioTime(item.seconds)}" to false
    }

    private fun favoriteTag(entry: PanEntry) = "favorite:${entry.gid}:${entry.messageId}:${entry.fileId}"

    private fun paintFavorite(icon: ImageView, starred: Boolean) {
        icon.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
        icon.contentDescription = if (starred) "取消收藏" else "收藏"
    }

    private fun toggleFavorite(entry: PanEntry): Boolean {
        if (!hasVerifiedAccount()) return false
        val starred = favorites.toggle(entry)
        if (screen == Screen.FAVORITES && folders.isEmpty()) {
            content.removeAllViews()
            renderFavorites()
        } else {
            content.findViewWithTag<ImageView>(favoriteTag(entry))?.let { paintFavorite(it, starred) }
        }
        toast(if (starred) "已加入收藏" else "已取消收藏")
        return starred
    }

    private fun showLoginDialog() {
        LoginDialog(this, web.settings.userAgentString) { showApp(recheck = true) }.show()
    }

    private fun currentPlaybackListKey(): String {
        val parent = folders.lastOrNull()
        val listScreen = if (showingHomeDirectory) Screen.BROWSE else screen
        return if (parent == null) "$listScreen:$gid"
        else "${parent.gid}:${parent.messageId}:${parent.fileId}"
    }

    private fun sameMediaKind(entry: PanEntry, entries: List<PanEntry>): List<PanEntry> {
        val kind = PanApi.kindOf(entry)
        return entries.filter { PanApi.kindOf(it) == kind }
    }

    private fun courseSiblings(entry: PanEntry, entries: List<PanEntry>): List<PanEntry> {
        val path = PanApi.readablePath(entry.path)
        if (!path.startsWith('/')) return listOf(entry)
        val directory = path.substringBeforeLast('/')
        return sameMediaKind(entry, entries).filter {
            it.gid == entry.gid && PanApi.readablePath(it.path).let { path ->
                path.startsWith('/') && path.substringBeforeLast('/') == directory
            }
        }.takeIf { list -> list.any { sameEntry(it, entry) } } ?: listOf(entry)
    }

    private fun selectPlayback(entry: PanEntry, entries: List<PanEntry>, listKey: String? = null) {
        val directory = playbackDirectory(entry, listKey)
        val queue = directory?.second ?: orderCourses(courseSiblings(entry, entries), playbackOrder(entry, null))
        pendingQueue = QueueSelection(entry, queue, directory?.first)
    }

    private fun extendPlaybackQueue(entries: List<PanEntry>) {
        val anchor = PlaybackQueueState.anchor ?: return
        if (PlaybackQueueState.listKey != currentPlaybackListKey() || entries.none { sameEntry(it, anchor) }) return
        if (PanApi.kindOf(anchor) == MediaKind.VIDEO && videoPage != null) return
        PlaybackQueueState.entries = playbackDirectory(anchor, PlaybackQueueState.listKey)?.second ?: return
    }

    private fun currentAudioEntry(player: Player): PanEntry? {
        if (!ownsAudio(player)) return null
        val item = player.currentMediaItem ?: return null
        val extras = item.mediaMetadata.extras ?: return null
        return PanEntry(
            extras.getString("gid").orEmpty(), extras.getString("message").orEmpty(),
            extras.getString("owner").orEmpty(), item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
            extras.getString("path").orEmpty(), extras.getString("size").orEmpty(), false,
        )
    }

    private fun siblingOf(entry: PanEntry, delta: Int): PanEntry? {
        val index = PlaybackQueueState.entries.indexOfFirst { sameEntry(it, entry) }
        return if (index >= 0) PlaybackQueueState.entries.getOrNull(index + delta) else null
    }

    private fun renderCourseSwitch() {
        val anchor = PlaybackQueueState.anchor
        val index = if (anchor == null) -1 else PlaybackQueueState.entries.indexOfFirst { sameEntry(it, anchor) }
        if (anchor == null || index < 0 || folders.isEmpty() || courseEntries.none { sameEntry(it, anchor) }) {
            courseSwitch.visibility = View.GONE
            return
        }
        courseSwitch.visibility = View.VISIBLE
        btnPrev.isEnabled = siblingOf(anchor, -1) != null
        btnNext.isEnabled = siblingOf(anchor, 1) != null
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else 0.4f
        btnNext.alpha = if (btnNext.isEnabled) 1f else 0.4f
        courseHint.text = "第 ${index + 1} / ${PlaybackQueueState.entries.size} 节"
        btnPrev.setOnClickListener { playSibling(anchor, -1) }
        btnNext.setOnClickListener { playSibling(anchor, 1) }
    }

    private fun playSibling(
        entry: PanEntry,
        delta: Int,
        automatic: Boolean = false,
        route: PlaybackRoute = PlaybackRoute.AUDIO,
        videoState: VideoPlaybackPage.Snapshot? = null,
    ) {
        if (!ready || !hasVerifiedAccount() || isFinishing || isDestroyed) return
        val target = siblingOf(entry, delta) ?: return
        when (PanApi.kindOf(target)) {
            MediaKind.AUDIO -> resolveAudio(target, route, openPlayer = !automatic)
            MediaKind.VIDEO -> {
                startVideo(
                    target, audioOnly = videoState?.audioOnly == true,
                    fullscreen = videoState?.fullscreen == true,
                )
            }
            else -> Unit
        }
    }

    private fun sameEntry(first: PanEntry, second: PanEntry): Boolean =
        first.gid == second.gid && first.messageId == second.messageId && first.fileId == second.fileId

    private fun formatOf(entry: PanEntry): String =
        if (entry.directory) "目录" else entry.name.substringAfterLast('.', "文件").uppercase()

    private fun resumeFromHistory(entry: PanEntry) {
        val listKey = PlaybackQueueState.listKey
        val directoryQueue = PlaybackQueueState.entries.filter { it.gid == entry.gid }
        val isDirectory = listKey == "BROWSE:${entry.gid}" || listKey?.startsWith("${entry.gid}:") == true
        if (isDirectory && directoryQueue.any { sameEntry(it, entry) }) {
            openEntry(entry, directoryQueue, listKey)
        } else {
            openEntry(entry, listOf(entry))
        }
    }

    private fun renderAudioHistory(allowUnverified: Boolean = false) {
        historyPanel.removeAllViews()
        if ((!allowUnverified && !hasVerifiedAccount()) || screen != Screen.HOME || folders.isNotEmpty()) return
        val all = (audioHistory.recentAcrossGroups() + videoHistory.recentAcrossGroups())
            .sortedByDescending { it.updatedAt }
        if (all.isEmpty()) return
        val recentItems = all.take(10)
        val resumeItems = all.filter { !it.completed && it.seconds > 0 }.take(3)
        val sourceNames = shareSources().associate { it.id to it.name }
        fun itemMeta(item: AudioHistoryItem): String {
            val isVideo = PanApi.kindOf(item.entry) == MediaKind.VIDEO
            val progress = when {
                item.completed -> if (isVideo) "已看完" else "已听完"
                item.seconds > 0 -> "${if (isVideo) "看到" else "听到"} ${formatAudioTime(item.seconds)}"
                else -> "未播放"
            }
            return listOfNotNull(sourceNames[item.entry.gid], progress).joinToString(" · ")
        }
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = if (resumeItems.isEmpty()) "最近播放" else "继续播放"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(color(R.color.ink))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "播放记录"
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setPadding(dp(8), 0, 0, 0)
                setTextColor(color(R.color.accentInk))
                setBackgroundResource(android.R.drawable.list_selector_background)
                isFocusable = true
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("最近播放记录")
                        .setItems(recentItems.map { "${it.entry.name}\n${itemMeta(it)}" }.toTypedArray()) { _, index ->
                            resumeFromHistory(recentItems[index].entry)
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            })
        }
        historyPanel.addView(header)
        if (resumeItems.isEmpty()) return
        val resumeList = column().apply { setBackgroundResource(R.drawable.bg_rec_card) }
        resumeItems.forEach { item ->
            val isVideo = PanApi.kindOf(item.entry) == MediaKind.VIDEO
            val card = layoutInflater.inflate(R.layout.view_rec_card, resumeList, false)
            card.findViewById<ImageView>(R.id.recIcon).apply {
                setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_music)
                setBackgroundResource(if (isVideo) R.drawable.bg_fmt_video else R.drawable.bg_fmt_audio)
                setColorFilter(color(R.color.accentInk))
            }
            card.findViewById<TextView>(R.id.recName).text = item.entry.name
            card.findViewById<TextView>(R.id.recMeta).text = itemMeta(item)
            card.setOnClickListener { resumeFromHistory(item.entry) }
            resumeList.addView(card)
        }
        historyPanel.addView(resumeList)
    }

    @Suppress("DEPRECATION")
    private fun renderFavorites() {
        status.text = ""
        val page = layoutInflater.inflate(R.layout.view_favorites_page, content, false)
        val list = page.findViewById<LinearLayout>(R.id.favList)
        val items = favorites.all()
        page.findViewById<TextView>(R.id.favCount).text = "共 ${items.size} 项"
        page.findViewById<View>(R.id.favEmpty).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
        addCourseRows(list, items)
        page.findViewById<TextView>(R.id.favGoBrowse).setOnClickListener {
            showScreen(Screen.BROWSE)
        }
        content.addView(page)
        renderCourseSwitch()
    }

    @Suppress("DEPRECATION")
    private fun renderSettings() {
        status.text = ""
        val page = layoutInflater.inflate(R.layout.view_settings_page, content, false)
        page.findViewById<TextView>(R.id.setAccountStatus).text = when {
            checkingAccount || !ready -> "正在确认登录状态…"
            hasVerifiedAccount() -> "已登录 · 账号状态正常"
            else -> "未登录 · 点右侧重新登录"
        }
        page.findViewById<View>(R.id.setLoginRow).setOnClickListener { showLoginDialog() }
        page.findViewById<TextView>(R.id.setGroupValue).text =
            shareSources().size.takeIf { it > 0 }?.let { "$it 个来源" } ?: "未关联"
        page.findViewById<View>(R.id.setGroupRow).setOnClickListener { discoverGroups() }
        page.findViewById<TextView>(R.id.setClearValue).text =
            "${audioHistory.recentAcrossGroups().size + videoHistory.recentAcrossGroups().size} 条"
        page.findViewById<View>(R.id.setClearRow).setOnClickListener { confirmClearHistory() }
        page.findViewById<TextView>(R.id.setThemeValue).text = ThemeStore.label(this)
        page.findViewById<View>(R.id.setThemeRow).setOnClickListener { showThemeDialog() }
        page.findViewById<View>(R.id.setBackupRow).setOnClickListener {
            backupLauncher.launch(Intent(this, BackupActivity::class.java))
        }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        page.findViewById<TextView>(R.id.setAbout).text =
            "${getString(R.string.app_name)} $version · $packageName"
        content.addView(page)
        renderCourseSwitch()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清除播放记录")
            .setMessage("将清除当前账号在本机所有群组的播放记录和音视频续播点。网盘文件会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                audioHistory.clear()
                videoHistory.clear()
                val progress = getSharedPreferences("video_progress", MODE_PRIVATE)
                val editor = progress.edit()
                val prefix = AccountScope.prefix()
                progress.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
                editor.apply()
                toast("播放记录已清除")
                refresh()
            }
            .show()
    }

    private fun setTabActive(tab: TextView, active: Boolean) {
        tab.setBackgroundResource(if (active) R.drawable.bg_chip_on else R.drawable.bg_chip)
        tab.setTextColor(color(if (active) R.color.accentInk else R.color.ink2))
    }

    private fun color(id: Int) = resources.getColor(id, theme)

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun audioIconButton(res: Int, description: String, tint: Int, action: () -> Unit) =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setBackgroundResource(R.drawable.bg_icon_ripple)
            isClickable = true
            setOnClickListener { action() }
        }

    private fun headAction(value: String, action: () -> Unit): TextView = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 11.5f
        setTextColor(color(R.color.ink2))
        setBackgroundResource(R.drawable.bg_chip)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        isClickable = true
        setOnClickListener { action() }
    }

    private fun resolveAudio(
        entry: PanEntry,
        route: PlaybackRoute = PlaybackRoute.AUDIO,
        openPlayer: Boolean = true,
    ) {
        if (!ready || !hasVerifiedAccount()) {
            status.text = "请确认网盘登录后重试。"
            return
        }
        if (route == PlaybackRoute.AUDIO) {
            controller?.let { player ->
                if (currentAudioEntry(player)?.let { sameEntry(it, entry) } == true &&
                    player.playerError == null && player.playbackState != Player.STATE_ENDED &&
                    player.playbackState != Player.STATE_IDLE
                ) {
                    cancelPendingAudioRequest()
                    confirmPlayback(entry)
                    player.play()
                    if (openPlayer) showPlayback(player)
                    return
                }
            }
        }
        pendingQueue = pendingQueue?.takeIf { sameEntry(it.entry, entry) }
        dispatchedAudioEntry = null
        val id = ++audioRequestId
        val token = AudioPlayRequestGate.begin()
        audioPlayToken = token
        pendingAudioEntry = entry
        audioResolver.cancel()
        dismissMediaCaptcha(RequestLane.AUDIO)
        updateAudioRequestMessage("正在打开 ${entry.name}…")
        val key = mediaCacheKey(verifiedAccount, entry)
        if (route == PlaybackRoute.AUDIO) {
            val playlist = PanApi.audioPlaylist(entry)
            mediaAddresses.playlist(key, playlist)?.let { stream ->
                startBackgroundAudio(entry, stream.uri, openPlayer, stream.playlist)
                return
            }
        }
        if (route == PlaybackRoute.AUDIO) {
            audioResolver.start(PanApi.audioPlaylist(entry), web.settings.userAgentString) { result ->
                if (id != audioRequestId || isDestroyed) return@start
                if (!AudioPlayRequestGate.isCurrent(token)) {
                    cancelPendingAudioRequest()
                    return@start
                }
                val stream = result.getOrNull()
                if (stream == null) resolveAudioFile(entry, route, openPlayer, id, token)
                else {
                    mediaAddresses.savePlaylist(key, stream)
                    startBackgroundAudio(entry, stream.uri, openPlayer, stream.playlist)
                }
            }
        } else {
            mediaAddresses.link(key)?.let { uri ->
                openAudioLink(entry, uri, route, openPlayer, null)
                return
            }
            resolveAudioFile(entry, route, openPlayer, id, token)
        }
    }

    private fun resolveAudioFile(
        entry: PanEntry,
        route: PlaybackRoute,
        openPlayer: Boolean,
        id: Int,
        token: Long,
    ) {
        web.evaluateJavascript(
            "location.origin === 'https://pan.baidu.com' ? String(window.yunData?.MYUK || window.yunData?.uk || '') : ''",
        ) { result ->
            if (id != audioRequestId || isDestroyed) return@evaluateJavascript
            if (!AudioPlayRequestGate.isCurrent(token)) {
                cancelPendingAudioRequest()
                return@evaluateJavascript
            }
            val viewer = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (viewer != null && viewer.matches(Regex("[0-9]{1,24}"))) {
                requestMediaLink(entry, viewer, route, openPlayer = openPlayer)
            } else {
                load(PanApi.viewer(), lane = RequestLane.AUDIO) { json ->
                    val uk = json.optJSONObject("result")?.optString("uk").orEmpty()
                    check(uk.matches(Regex("[0-9]{1,24}"))) { "无法识别当前网盘账号，请重新登录后重试。" }
                    requestMediaLink(entry, uk, route, openPlayer = openPlayer)
                }
            }
        }
    }

    private fun requestMediaLink(
        entry: PanEntry,
        viewer: String,
        route: PlaybackRoute,
        webResumeSeconds: Int? = null,
        openPlayer: Boolean = true,
    ) {
        if (viewer != verifiedAccount) {
            verifyAndRefresh()
            return
        }
        if (route == PlaybackRoute.AUDIO) {
            mediaAddresses.link(mediaCacheKey(viewer, entry))?.let { uri ->
                openAudioLink(entry, uri, route, openPlayer, webResumeSeconds)
                return
            }
        }
        loadMediaLink(entry, viewer, lane = RequestLane.AUDIO) { uri ->
            openAudioLink(entry, uri, route, openPlayer, webResumeSeconds)
        }
    }

    private fun openAudioLink(
        entry: PanEntry,
        uri: Uri,
        route: PlaybackRoute,
        openPlayer: Boolean,
        webResumeSeconds: Int?,
    ) {
        when (route) {
            PlaybackRoute.AUDIO -> startBackgroundAudio(entry, uri, openPlayer)
            PlaybackRoute.WEB_AUDIO -> {
                pendingAudioEntry = null
                audioPlayToken = null
                confirmPlayback(entry)
                updateAudioRequestMessage(null)
                controller?.pause()
                playAudio(entry, uri, webResumeSeconds)
            }
        }
    }

    private fun openPdf(entry: PanEntry) {
        if (!hasVerifiedAccount()) {
            status.text = "请确认网盘登录后重试。"
            return
        }
        val id = ++requestId
        dismissMediaCaptcha(RequestLane.LIST)
        if (pdfLoading != null) {
            pdfFetcher.cancel()
            pdfLoading?.dismiss()
            pdfLoading = null
        }
        val account = verifiedAccount
        val key = mediaCacheKey(account, entry)
        PdfCache.find(this, key)?.let {
            status.text = ""
            startPdfReader(entry, it)
            return
        }
        mediaAddresses.link(key)?.let {
            downloadPdf(entry, account, key, it, fromCache = true)
            return
        }
        if (!ready) {
            status.text = "请确认网盘登录后重试。"
            return
        }
        status.text = "正在确认网盘账号…"
        web.evaluateJavascript(
            "location.origin === 'https://pan.baidu.com' ? String(window.yunData?.MYUK || window.yunData?.uk || '') : ''",
        ) { result ->
            if (id != requestId || isDestroyed) return@evaluateJavascript
            val viewer = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (viewer != null && viewer.matches(Regex("[0-9]{1,24}"))) {
                requestPdfLink(entry, viewer)
            } else {
                load(PanApi.viewer()) { json ->
                    val uk = json.optJSONObject("result")?.optString("uk").orEmpty()
                    check(uk.matches(Regex("[0-9]{1,24}"))) { "无法识别当前网盘账号，请重新登录后重试。" }
                    requestPdfLink(entry, uk)
                }
            }
        }
    }

    private fun requestPdfLink(entry: PanEntry, viewer: String) {
        if (viewer != verifiedAccount) {
            verifyAndRefresh()
            return
        }
        val key = mediaCacheKey(viewer, entry)
        loadMediaLink(entry, viewer) { link ->
            downloadPdf(entry, viewer, key, link, fromCache = false)
        }
    }

    private fun downloadPdf(entry: PanEntry, account: String, key: String, link: Uri, fromCache: Boolean) {
        if (!activityVisible || account != verifiedAccount) return
        pdfLoading?.dismiss()
        pdfLoading = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage("正在下载文档…")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                pdfFetcher.cancel()
                pdfLoading = null
                status.text = ""
            }
            .show()
        pdfFetcher.start(link) { result ->
            pdfLoading?.dismiss()
            pdfLoading = null
            result.mapCatching { temp ->
                try {
                    val cached = PdfCache.publish(this, key, temp)
                    if (activityVisible && account == verifiedAccount) startPdfReader(entry, cached)
                } finally {
                    PdfCache.discard(temp)
                }
            }.onFailure {
                if (it is InvalidPdfLink) {
                    mediaAddresses.clearLink(key)
                    if (fromCache && ready && activityVisible && account == verifiedAccount) {
                        requestPdfLink(entry, account)
                        return@onFailure
                    }
                }
                status.text = it.message ?: "文档打开失败，请重试。"
            }
        }
    }

    private fun startPdfReader(entry: PanEntry, file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        // 渲染服务是 isolatedProcess，与 App 不同 UID，按包名显式授予读取权限
        grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent(this, PdfReaderActivity::class.java).apply {
            putExtra(PdfReaderActivity.EXTRA_URI, uri.toString())
            putExtra(PdfReaderActivity.EXTRA_TITLE, entry.name)
            putExtra(PdfReaderActivity.EXTRA_FILE, file.absolutePath)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        status.text = ""
    }

    private fun startBackgroundAudio(entry: PanEntry, uri: Uri, openPlayer: Boolean, playlist: ByteArray? = null) {
        val player = controller
        if (player == null || !player.isConnected) {
            failAudioRequest("后台播放器尚未就绪，请稍后重试。")
            return
        }
        val token = audioPlayToken ?: return
        if (!AudioPlayRequestGate.isCurrent(token)) {
            cancelPendingAudioRequest()
            return
        }
        val args = Bundle().apply {
            putLong("requestToken", token)
            putString("account", verifiedAccount)
            putString("uri", uri.toString())
            if (playlist != null) putByteArray("playlist", playlist)
            putString("gid", entry.gid)
            putString("message", entry.messageId)
            putString("owner", entry.owner)
            putString("file", entry.fileId)
            putString("name", entry.name)
            putString("path", entry.path)
            putString("size", entry.size)
        }
        val id = audioRequestId
        dispatchedAudioEntry = entry
        val result = player.sendCustomCommand(AudioPlaybackService.PLAY_FILE, args)
        result.addListener({
            if (!isDestroyed && !isFinishing && id == audioRequestId) {
                dispatchedAudioEntry = null
                if (!AudioPlayRequestGate.isCurrent(token)) {
                    cancelPendingAudioRequest()
                } else if (runCatching { result.get().resultCode }.getOrNull() == SessionResult.RESULT_SUCCESS) {
                    pendingAudioEntry = null
                    audioPlayToken = null
                    confirmPlayback(entry)
                    updateAudioRequestMessage(null)
                    if (openPlayer) pendingAudioPage = id to entry
                    updateNowPlaying()
                } else {
                    failAudioRequest("后台播放器启动失败，请重试。")
                }
            }
        }, uiExecutor)
    }

    private fun renewBackgroundAudio(player: MediaController) {
        if (!ready || !hasVerifiedAccount()) return
        val error = player.playerError ?: return
        val item = player.currentMediaItem ?: return
        val metadata = item.mediaMetadata
        val extras = metadata.extras ?: return
        val wasHls = extras.getBoolean("hls")
        if (!wasHls && !error.isExpiredMediaLink()) return
        val id = extras.getString("playbackId") ?: return
        if (extras.getString("account") != verifiedAccount || id == renewingPlaybackId) return
        val entry = PanEntry(
            extras.getString("gid").orEmpty(), extras.getString("message").orEmpty(),
            extras.getString("owner").orEmpty(), item.mediaId, metadata.title?.toString().orEmpty(),
            "", "", false,
        )
        val account = verifiedAccount
        val key = mediaCacheKey(account, entry)
        if (extras.getBoolean("renewed")) {
            if (invalidatedRenewedPlaybackId != id) {
                mediaAddresses.clearLink(key)
                invalidatedRenewedPlaybackId = id
            }
            return
        }
        if (id == renewedPlaybackId) return
        if (wasHls) mediaAddresses.clearPlaylist(key)
        else mediaAddresses.clearLink(key)
        renewingPlaybackId = id
        fun finishRenewal() {
            renewingPlaybackId = null
            renewedPlaybackId = id
        }
        fun renew(link: Uri) {
            finishRenewal()
            player.sendCustomCommand(AudioPlaybackService.RENEW_FILE, Bundle().apply {
                putString("playbackId", id)
                putString("uri", link.toString())
            })
        }
        val cachedLink = if (wasHls) mediaAddresses.link(key) else null
        if (cachedLink != null) renew(cachedLink)
        else loadMediaLink(entry, account, lane = RequestLane.RENEWAL, onFailure = ::finishRenewal, onResult = ::renew)
    }

    private fun cancelPendingAudioRequest() {
        audioPlayToken?.let(AudioPlayRequestGate::cancel)
        audioPlayToken = null
        dispatchedAudioEntry = null
        audioRequestId++
        audioResolver.cancel()
        dismissMediaCaptcha(RequestLane.AUDIO)
        pendingAudioEntry = null
        pendingAudioPage = null
        updateAudioRequestMessage(null)
    }

    private fun failAudioRequest(message: String) {
        pendingQueue = null
        audioPlayToken = null
        pendingAudioEntry = null
        val active = controller?.let(::currentAudioEntry)
        if (active != null && PlaybackQueueState.entries.any { sameEntry(it, active) }) {
            PlaybackQueueState.anchor = active
            renderCourseSwitch()
        }
        updateAudioRequestMessage(message)
    }

    private fun updateAudioRequestMessage(message: String?) {
        val previous = audioRequestMessage
        audioRequestMessage = message
        when {
            message != null -> status.text = message
            previous != null && status.text == previous -> status.text = ""
        }
        refreshAudioPageMessage?.invoke()
    }

    private inner class AudioCourseList(
        private val onSelect: (PanEntry) -> Unit,
        private val onMore: () -> Unit,
        private val onSorted: () -> Unit,
    ) {
        private var courses: List<PanEntry> = emptyList()
        private var current = -1
        private var sortTarget: Pair<PanEntry, String?>? = null
        private val heading = label("课程", 13.5f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink2))
            gravity = Gravity.CENTER_VERTICAL
        }
        private val more = TextView(this@MainActivity).apply {
            text = "加载更多"
            textSize = 11.5f
            setTextColor(color(R.color.ink2))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onMore() }
        }
        private val sort = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(color(R.color.ink2))
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            visibility = View.GONE
            setOnClickListener { anchor ->
                val (entry, listKey) = sortTarget ?: return@setOnClickListener
                showPlaybackSort(anchor, entry, listKey) {
                    paintSort(entry, listKey)
                    onSorted()
                    if (current >= 0) rows.setSelection((current - 1).coerceAtLeast(0))
                }
            }
        }
        private val rows = ListView(this@MainActivity).apply {
            divider = null
            setPadding(dp(8), 0, dp(8), dp(6))
            clipToPadding = false
        }
        private val adapter = object : BaseAdapter() {
            override fun getCount() = courses.size
            override fun getItem(position: Int) = courses[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return ((convertView as? TextView) ?: label("", 13.5f).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(46)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }).apply {
                    text = "${position + 1}.  ${audioDisplayName(getItem(position).name)}"
                    val active = position == current
                    setTextColor(color(if (active) R.color.accentInk else R.color.ink))
                    setBackgroundColor(color(if (active) R.color.accentSoft else R.color.transparent))
                    contentDescription = if (active) "当前课程，$text" else text
                }
            }
        }
        val view = column().apply {
            setBackgroundColor(color(R.color.surface))
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(10), 0)
                addView(heading, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(more, LinearLayout.LayoutParams(-2, -2))
                addView(sort, LinearLayout.LayoutParams(-2, -2))
            })
            addView(rows, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        fun updateSort(entry: PanEntry?, listKey: String?) {
            val target = entry?.let { it to listKey }
            if (sortTarget == target) return
            sortTarget = target
            sort.visibility = if (entry != null && playbackOrderKey(entry, listKey) != null) View.VISIBLE else View.GONE
            if (entry != null) paintSort(entry, listKey)
        }

        private fun paintSort(entry: PanEntry, listKey: String?) {
            sort.text = playbackSortLabel(entry, listKey)
            sort.contentDescription = "课程排序，当前 ${sort.text.toString().substringBefore(' ')}"
        }

        init {
            rows.adapter = adapter
            rows.setOnItemClickListener { _, _, position, _ ->
                if (position != current) onSelect(courses[position])
            }
        }

        fun update(entries: List<PanEntry>, index: Int, hasMore: Boolean, loading: Boolean) {
            heading.text = if (entries.isEmpty()) "课程" else "课程 · ${entries.size} 节"
            more.visibility = if (hasMore) View.VISIBLE else View.GONE
            more.isEnabled = !loading
            more.text = if (loading) "正在加载…" else "加载更多"
            if (courses == entries && current == index) return
            val changedCourse = index >= 0 && (courses.getOrNull(current)?.let {
                sameEntry(it, entries[index])
            } != true)
            courses = entries
            current = index
            adapter.notifyDataSetChanged()
            if (changedCourse) rows.setSelection((index - 1).coerceAtLeast(0))
        }
    }

    private fun audioDisplayName(raw: CharSequence): String =
        raw.toString().trim().replace(Regex("(?i)\\.(mp3|m4a|aac|wav|flac|ogg)$"), "")

    @OptIn(UnstableApi::class)
    private fun showPlayback(player: MediaController) {
        if (audioPage != null || !ownsAudio(player) || native.visibility != View.VISIBLE) return
        val handler = Handler(Looper.getMainLooper())
        val titleView = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(R.color.ink))
            gravity = Gravity.START
            maxLines = 2
            setLineSpacing(0f, 1.08f)
            ellipsize = android.text.TextUtils.TruncateAt.END
            isAccessibilityHeading = true
        }
        val source = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.accent))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val message = TextView(this).apply {
            textSize = 11.5f
            setTextColor(color(R.color.ink3))
            gravity = Gravity.START
            setPadding(0, dp(3), 0, 0)
        }
        val cover = ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_wave)
            setColorFilter(color(R.color.accent))
            setBackgroundResource(R.drawable.bg_fmt_audio)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val seek = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(color(R.color.accent))
            progressBackgroundTintList = ColorStateList.valueOf(color(R.color.barTrack))
            thumbTintList = ColorStateList.valueOf(color(R.color.accent))
            setPadding(dp(2), dp(4), dp(2), 0)
        }
        val currentTime = TextView(this).apply {
            textSize = 10.5f
            setTextColor(color(R.color.ink3))
        }
        val totalTime = TextView(this).apply {
            textSize = 10.5f
            setTextColor(color(R.color.ink3))
        }
        val times = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(currentTime)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(totalTime)
        }
        fun icon(res: Int): ImageView = ImageView(this).apply {
            setImageResource(res)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setBackgroundResource(android.R.color.transparent)
        }
        val previous = icon(R.drawable.ic_skip_prev)
        previous.contentDescription = "上一节"
        val back10 = icon(R.drawable.ic_replay10).apply { contentDescription = "后退 10 秒" }
        val play = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_play_accent)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setImageResource(R.drawable.ic_play)
            setColorFilter(color(R.color.onAccent))
        }
        val forward10 = icon(R.drawable.ic_forward10).apply { contentDescription = "前进 10 秒" }
        val next = icon(R.drawable.ic_skip_next).apply { contentDescription = "下一节" }
        val speed = headAction("") {}.apply {
            setPadding(dp(4), 0, dp(4), 0)
            maxLines = 1
        }
        speed.setOnClickListener {
            PopupMenu(this, speed, Gravity.END).apply {
                PlaybackSpeed.values.forEachIndexed { index, value ->
                    menu.add(0, index, index, PlaybackSpeed.spoken(value))
                        .setChecked(value == player.playbackParameters.speed)
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener {
                    val value = PlaybackSpeed.values[it.itemId]
                    player.setPlaybackParameters(PlaybackParameters(value))
                    PlaybackSpeed.setAudio(this@MainActivity, value)
                    true
                }
                show()
            }
        }
        val controls = row().apply {
            gravity = Gravity.CENTER
            listOf(previous, back10, play, forward10, next).forEachIndexed { index, view ->
                val params = if (index == 2) LinearLayout.LayoutParams(dp(48), dp(48))
                else LinearLayout.LayoutParams(0, dp(46), 1f)
                addView(view, params)
            }
            addView(speed, LinearLayout.LayoutParams(dp(48), dp(44)))
        }
        val favorite = audioIconButton(R.drawable.ic_star, "收藏", color(R.color.ink3)) {}
        fun paintFavorite(starred: Boolean) {
            favorite.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
            favorite.setColorFilter(color(if (starred) R.color.star else R.color.ink3))
            favorite.contentDescription = if (starred) "取消收藏" else "收藏"
        }
        val stop = audioIconButton(R.drawable.ic_close, "停止播放", color(R.color.ink2)) {
            stopBackgroundAudio()
            closeAudioPage()
        }
        val back = audioIconButton(R.drawable.ic_arrow_back, "返回", color(R.color.ink2)) {
            closeAudioPage()
        }
        val topBar = FrameLayout(this).apply {
            setPadding(dp(6), 0, dp(6), 0)
            addView(back, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(TextView(this@MainActivity).apply {
                text = "音频播放"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(color(R.color.ink))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(row().apply {
                addView(favorite, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(stop, LinearLayout.LayoutParams(dp(44), dp(44)))
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        var loadingCourses = false
        var checkedPagination: Pair<PanEntry, String?>? = null
        var canLoadCourses = false
        lateinit var updateAudioPage: () -> Unit
        val courseList = AudioCourseList(
            onSelect = { resolveAudio(it) },
            onSorted = { updateAudioPage() },
            onMore = {
                currentAudioEntry(player)?.let { entry ->
                    loadingCourses = true
                    loadMoreCourses(entry, PlaybackQueueState.listKey) { updated ->
                        loadingCourses = false
                        checkedPagination = null
                        if (updated == null && audioPage != null) toast("加载失败，请重试")
                    }
                }
            },
        )
        val header = column().apply {
            setPadding(dp(20), dp(14), dp(20), dp(18))
            addView(titleView)
            addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(message)
            addView(cover, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            })
        }
        val page = column().apply {
            setBackgroundColor(color(R.color.bg))
            isClickable = true
            accessibilityPaneTitle = "音频播放"
            addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
            addView(View(this@MainActivity).apply {
                setBackgroundColor(color(R.color.line))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(header)
            addView(column().apply {
                setBackgroundResource(R.drawable.bg_audio_controls)
                setPadding(dp(12), dp(6), dp(12), dp(8))
                addView(seek, LinearLayout.LayoutParams(-1, -2))
                addView(times)
                addView(controls)
            }, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(dp(12), 0, dp(12), dp(12))
            })
            addView(courseList.view, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        var compactHeader = false
        page.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val compact = bottom - top < dp(440)
            if (compact != compactHeader) {
                compactHeader = compact
                cover.visibility = if (compact) View.GONE else View.VISIBLE
                header.setPadding(dp(20), dp(if (compact) 6 else 14), dp(20), dp(if (compact) 6 else 18))
                titleView.maxLines = if (compact) 1 else 2
            }
        }
        var seeking = false
        var displayedEntry: PanEntry? = null
        fun update() {
            if (audioPage !== page) return
            val entry = currentAudioEntry(player)
            val durationMs = player.duration
            val duration = if (durationMs > 0) (durationMs / 1000).toInt() else 0
            val position = (player.currentPosition / 1000).toInt().coerceAtLeast(0)
            val title = audioDisplayName(player.mediaMetadata.title ?: "当前播放")
            if (titleView.text.toString() != title) titleView.text = title
            if (source.text.isEmpty() || entry != displayedEntry) {
                displayedEntry = entry
                source.text = entry?.let {
                    PanApi.readablePath(it.path).substringBeforeLast('/', "").substringAfterLast('/')
                }.orEmpty().ifBlank { "音频课程" }
            }
            if (!seeking) seek.progress = if (duration > 0) (position * 1000 / duration).coerceIn(0, 1000) else 0
            currentTime.text = formatAudioTime(position)
            totalTime.text = formatAudioTime(duration)
            play.setImageResource(if (player.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            play.setColorFilter(color(R.color.onAccent))
            val starred = entry != null && favorites.has(entry)
            paintFavorite(starred)
            val queue = PlaybackQueueState.entries
            val index = entry?.let { active -> queue.indexOfFirst { sameEntry(it, active) } } ?: -1
            val listKey = PlaybackQueueState.listKey
            courseList.updateSort(entry?.takeIf { index >= 0 }, listKey)
            val pagination = entry?.takeIf { index >= 0 }?.let { it to listKey }
            if (pagination != checkedPagination) {
                checkedPagination = pagination
                canLoadCourses = pagination?.let { (file, key) -> nextDirectoryPage(file, key) != null } == true
            }
            if (index >= 0) courseList.update(queue, index, canLoadCourses, loadingCourses)
            else courseList.update(listOfNotNull(entry), if (entry == null) -1 else 0, false, false)
            val rate = player.playbackParameters.speed
            speed.text = PlaybackSpeed.label(rate)
            speed.contentDescription = "播放倍速，当前 ${PlaybackSpeed.spoken(rate)}"
            play.contentDescription = if (player.isPlaying) "暂停" else "播放"
            previous.isEnabled = entry?.let { siblingOf(it, -1) != null } == true
            next.isEnabled = entry?.let { siblingOf(it, 1) != null } == true
            previous.alpha = if (previous.isEnabled) 1f else 0.35f
            next.alpha = if (next.isEnabled) 1f else 0.35f
            val error = player.playerError
            message.text = audioRequestMessage ?: when {
                error != null -> "播放失败（${playbackError(error)}）"
                player.playbackState == Player.STATE_BUFFERING -> "正在缓冲…"
                player.playbackState == Player.STATE_ENDED -> "播放结束"
                player.isPlaying -> "正在播放"
                else -> "已暂停"
            }
        }
        updateAudioPage = ::update
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                val duration = player.duration
                if (duration > 0) player.seekTo(duration * bar.progress / 1000)
                update()
            }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player.duration
                if (duration > 0) currentTime.text = formatAudioTime((duration * progress / 1000 / 1000).toInt())
            }
        })
        play.setOnClickListener {
            when {
                player.playbackState == Player.STATE_ENDED -> {
                    player.seekToDefaultPosition()
                    player.play()
                }
                player.playWhenReady -> player.pause()
                else -> player.play()
            }
        }
        previous.setOnClickListener { currentAudioEntry(player)?.let { playSibling(it, -1) } }
        next.setOnClickListener { currentAudioEntry(player)?.let { playSibling(it, 1) } }
        back10.setOnClickListener { player.seekBack() }
        forward10.setOnClickListener { player.seekForward() }
        favorite.setOnClickListener {
            currentAudioEntry(player)?.let { toggleFavorite(it) }
            update()
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (ownsAudio(player)) update() else closeAudioPage()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { update() }
        }
        player.addListener(listener)
        val ticker = object : Runnable {
            override fun run() {
                if (audioPage === page) {
                    update()
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(ticker)
        releaseAudioPage = {
            player.removeListener(listener)
            handler.removeCallbacks(ticker)
        }
        audioPage = page
        native.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        (native.parent as ViewGroup).addView(page, FrameLayout.LayoutParams(-1, -1))
        update()
    }

    private fun closeAudioPage() {
        val page = audioPage ?: return
        audioPage = null
        refreshAudioPageMessage = null
        releaseAudioPage?.invoke()
        releaseAudioPage = null
        (page.parent as? ViewGroup)?.removeView(page)
        native.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        if (!isDestroyed && !isFinishing) {
            updateNowPlaying()
            renderAudioHistory()
        }
    }

    private fun stopBackgroundAudio() {
        pendingQueue = null
        PlaybackQueueState.anchor = null
        PlaybackQueueState.entries = emptyList()
        PlaybackQueueState.listKey = null
        renderCourseSwitch()
        cancelPendingAudioRequest()
        updateAudioRequestMessage(null)
        controller?.sendCustomCommand(AudioPlaybackService.CLEAR_FILE, Bundle.EMPTY)
        nowPlaying.visibility = View.GONE
        renderAudioHistory()
    }

    private fun startVideo(
        entry: PanEntry,
        positionMs: Long? = null,
        playWhenReady: Boolean = true,
        retryOnExpiredLink: Boolean = true,
        audioOnly: Boolean = false,
        fullscreen: Boolean = false,
        orientationBeforeFullscreen: Int = videoPage?.orientationBeforeFullscreen ?: requestedOrientation,
    ) {
        if (!activityVisible || !ready || !hasVerifiedAccount()) return
        videoToRestore = null
        val account = verifiedAccount
        val key = mediaCacheKey(account, entry)
        val playlist = PanApi.videoPlaylist(entry)
        pendingQueue = pendingQueue?.takeIf { sameEntry(it.entry, entry) }
        loadingVideo = VideoPlaybackPage.Snapshot(
            entry, account, positionMs, playWhenReady, audioOnly, fullscreen, orientationBeforeFullscreen,
        )
        cancelPendingAudioRequest()
        controller?.pause()
        videoLoading?.dismiss()
        val loading = AlertDialog.Builder(this)
            .setMessage("正在获取视频播放清单…")
            .setNegativeButton("取消") { dialog, _ -> dialog.cancel() }
            .create()
        loading.setOnCancelListener {
            if (videoLoading === loading) {
                videoLoading = null
                loadingVideo = null
                pendingQueue = null
                videoResolver.cancel()
            }
        }
        videoLoading = loading
        loading.show()
        fun openStream(result: Result<VideoStream>) {
            videoLoading = null
            loadingVideo = null
            loading.dismiss()
            if (isDestroyed || isFinishing || account != verifiedAccount) return
            result.onSuccess { stream ->
                confirmPlayback(entry)
                val previousPage = videoPage
                val courseListKey = PlaybackQueueState.listKey
                videoPage = VideoPlaybackPage(
                    this, entry, stream,
                    account = account,
                    history = AudioHistory(getSharedPreferences("video_history", MODE_PRIVATE), account),
                    startPositionMs = positionMs,
                    playWhenReady = playWhenReady,
                    retryOnExpiredLink = retryOnExpiredLink,
                    startAudioOnly = audioOnly,
                    startFullscreen = fullscreen,
                    orientationBeforeFullscreen = orientationBeforeFullscreen,
                    onRetry = { snapshot, automatic ->
                        if (videoLoading == null && account == verifiedAccount) {
                            mediaAddresses.clearPlaylist(key)
                            startVideo(
                                entry, snapshot.positionMs, snapshot.playWhenReady, retryOnExpiredLink = !automatic,
                                audioOnly = snapshot.audioOnly, fullscreen = snapshot.fullscreen,
                            )
                        }
                    },
                    courses = PlaybackQueueState.entries,
                    currentCourse = PlaybackQueueState.entries.indexOfFirst { sameEntry(it, entry) },
                    courseSortLabel = playbackSortLabel(entry, courseListKey)
                        .takeIf { playbackOrderKey(entry, courseListKey) != null },
                    onSortCourses = { anchor, done ->
                        showPlaybackSort(anchor, entry, courseListKey) { courses ->
                            done(courses, playbackSortLabel(entry, courseListKey))
                        }
                    },
                    canLoadMoreCourses = { nextDirectoryPage(entry, courseListKey) != null },
                    onLoadMoreCourses = { done -> loadMoreCourses(entry, courseListKey, done) },
                    onSelectCourse = { target, snapshot ->
                        if (videoLoading == null) startVideo(
                            target, audioOnly = snapshot.audioOnly, fullscreen = snapshot.fullscreen,
                        )
                    },
                    onSwitch = { delta, snapshot -> playSibling(entry, delta, videoState = snapshot) },
                    onEnded = { snapshot ->
                        if (videoLoading == null) playSibling(entry, 1, automatic = true, videoState = snapshot)
                    },
                    isFavorite = favorites.has(entry),
                    onFavorite = { account == verifiedAccount && toggleFavorite(entry) },
                    onClearBackgroundAudio = {
                        controller?.sendCustomCommand(AudioPlaybackService.CLEAR_FILE, Bundle.EMPTY)
                        pendingQueue = null
                        updateNowPlaying()
                    },
                    onClose = { closed ->
                        if (videoPage === closed) {
                            videoPage = null
                            if (!isDestroyed) renderAudioHistory()
                        }
                    },
                )
                previousPage?.close(keepOrientation = fullscreen)
            }.onFailure {
                AlertDialog.Builder(this)
                    .setTitle("无法原生播放")
                    .setMessage((it as? IllegalStateException)?.message ?: "读取视频播放清单失败。")
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("重试") { _, _ ->
                        startVideo(
                            entry, positionMs, playWhenReady, audioOnly = audioOnly, fullscreen = fullscreen,
                            orientationBeforeFullscreen = orientationBeforeFullscreen,
                        )
                    }
                    .show()
            }
        }
        val cached = mediaAddresses.playlist(key, playlist)
        if (cached != null) {
            videoResolver.cancel()
            openStream(Result.success(cached))
        } else {
            videoResolver.start(playlist, web.settings.userAgentString) { result ->
                result.getOrNull()?.let { mediaAddresses.savePlaylist(key, it) }
                openStream(result)
            }
        }
    }

    private fun playbackError(error: PlaybackException): String {
        val response = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        // 文档 §9 / §12：地址约 8 小时过期，403 不该只显示一个 HTTP 码
        return when (response?.responseCode) {
            401, 403 -> "播放地址已过期或无权访问，请重新打开（地址约 8 小时有效）"
            404 -> "文件地址已失效，请重新打开"
            else -> response?.let { "HTTP ${it.responseCode}" } ?: error.errorCodeName
        }
    }

    private fun playAudio(entry: PanEntry, uri: Uri, resumeSeconds: Int?) {
        val account = verifiedAccount
        val source = JSONObject.quote(uri.toString())
        val controls = AudioControls(this, entry.name) { playing ->
            val action = if (playing) "a.play().catch(() => {})" else "a.pause()"
            web.evaluateJavascript(
                "(() => { const a = window.__groupPlayerAudio; if (a) $action; })()",
                null,
            )
        }
        audioControls = controls
        val playbackHistory = AudioHistory(getSharedPreferences("audio_history", MODE_PRIVATE), verifiedAccount)
        val previous = playbackHistory.find(entry)
        val startAt = resumeSeconds ?: previous?.takeUnless { it.completed }?.seconds ?: 0
        var lastSavedSeconds = startAt
        var lastSavedAt = SystemClock.elapsedRealtime()
        var lastCompleted = previous?.completed == true
        fun saveProgress(seconds: Int, completed: Boolean, duration: Int = 0) {
            if (seconds <= 0 || (seconds < startAt && !completed)) return
            if (seconds == lastSavedSeconds && completed == lastCompleted) return
            playbackHistory.save(entry, seconds, completed, duration)
            lastSavedSeconds = seconds
            lastSavedAt = SystemClock.elapsedRealtime()
            lastCompleted = completed
        }
        fun switchWebAudio(dialog: AlertDialog, delta: Int, automatic: Boolean = false) {
            if (!activityVisible || siblingOf(entry, delta) == null) return
            dialog.dismiss()
            playSibling(entry, delta, automatic, PlaybackRoute.WEB_AUDIO)
        }
        lateinit var dialog: AlertDialog
        val previousCourse = button("上一节") { switchWebAudio(dialog, -1) }
        val nextCourse = button("下一节") { switchWebAudio(dialog, 1) }
        val actions = row().apply {
            val favorite = Button(this@MainActivity).apply {
                isAllCaps = false
                text = if (favorites.has(entry)) "取消收藏" else "收藏"
                setOnClickListener {
                    if (account == verifiedAccount) text = if (toggleFavorite(entry)) "取消收藏" else "收藏"
                }
            }
            listOf(previousCourse, favorite, nextCourse).forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        val listKey = PlaybackQueueState.listKey
        var loadingCourses = false
        lateinit var courseList: AudioCourseList
        fun updateCourses() {
            val queue = PlaybackQueueState.entries
            val index = queue.indexOfFirst { sameEntry(it, entry) }
            courseList.updateSort(entry.takeIf { index >= 0 }, listKey)
            if (index >= 0) courseList.update(queue, index, nextDirectoryPage(entry, listKey) != null, loadingCourses)
            else courseList.update(listOf(entry), 0, false, false)
            previousCourse.isEnabled = index > 0
            nextCourse.isEnabled = index in 0 until queue.lastIndex
        }
        courseList = AudioCourseList(
            onSorted = ::updateCourses,
            onSelect = { target ->
                if (activityVisible && hasVerifiedAccount() && verifiedAccount == account) {
                    dialog.dismiss()
                    resolveAudio(target, PlaybackRoute.WEB_AUDIO)
                }
            },
            onMore = {
                loadingCourses = true
                updateCourses()
                loadMoreCourses(entry, listKey) { updated ->
                    loadingCourses = false
                    if (dialog.isShowing) {
                        updateCourses()
                        if (updated == null) toast("加载失败，请重试")
                    }
                }
            },
        )
        updateCourses()
        val panel = column().apply {
            addView(actions)
            addView(courseList.view, LinearLayout.LayoutParams(-1, minOf(dp(300), resources.displayMetrics.heightPixels / 3)))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage("正在缓冲…")
            .setView(panel)
            .setPositiveButton("暂停", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnDismissListener {
            if (audioControls === controls) {
                controls.close()
                audioControls = null
            }
            if (!isDestroyed) web.evaluateJavascript(
                """
                (() => {
                  const a = window.__groupPlayerAudio;
                  if (!a) return null;
                  const position = a.readyState >= 2 && !a.seeking
                    ? [Math.floor(a.currentTime), a.ended, Math.floor(a.duration || 0)] : null;
                  a.pause();
                  a.removeAttribute('src');
                  a.load();
                  delete window.__groupPlayerAudio;
                  return position;
                })()
                """.trimIndent(),
            ) { result ->
                val position = result?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                if (position != null) saveProgress(position.optInt(0), position.optBoolean(1), position.optInt(2))
                if (!isDestroyed) renderAudioHistory()
            }
        }
        dialog.show()
        val toggle = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        toggle.isEnabled = false
        toggle.setOnClickListener {
            web.evaluateJavascript(
                "(() => { const a = window.__groupPlayerAudio; if (a) { if (a.paused) a.play().catch(() => {}); else a.pause(); } })()",
                null,
            )
        }
        web.evaluateJavascript(
            """
            (() => {
              if (location.origin !== 'https://pan.baidu.com') return;
              const audio = new Audio($source);
              window.__groupPlayerAudio = audio;
              if ($startAt > 0) {
                audio.addEventListener('loadedmetadata', () => {
                  if ($startAt < audio.duration) audio.currentTime = $startAt;
                  audio.play().catch(() => {});
                }, { once: true });
              } else {
                audio.play().catch(() => {});
              }
            })()
            """.trimIndent(),
            null,
        )
        val deadline = SystemClock.elapsedRealtime() + 20_000
        fun pollAudio() {
            if (!dialog.isShowing || isDestroyed) return
            web.evaluateJavascript(
                "(() => { const a = window.__groupPlayerAudio; return a ? [a.error?.code || 0, a.readyState, a.paused, a.ended, Math.floor(a.currentTime), Math.floor(a.duration || 0)] : null; })()",
            ) { result ->
                if (!dialog.isShowing || isDestroyed) return@evaluateJavascript
                val state = result?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                val error = state?.optInt(0) ?: 0
                val ready = (state?.optInt(1) ?: 0) >= 2
                if ((error == 2 || error == 4) && hasVerifiedAccount() && account == verifiedAccount) {
                    mediaAddresses.clearLink(mediaCacheKey(account, entry))
                }
                if ((error == 2 || error == 4) && resumeSeconds == null && hasVerifiedAccount() && account == verifiedAccount) {
                    val position = maxOf(startAt, lastSavedSeconds, state?.optInt(4) ?: 0)
                    dialog.dismiss()
                    requestMediaLink(entry, account, PlaybackRoute.WEB_AUDIO, webResumeSeconds = position)
                    return@evaluateJavascript
                }
                if (error != 0 || (!ready && SystemClock.elapsedRealtime() >= deadline)) {
                    dialog.setMessage(if (error != 0) "音频读取失败（$error）。关闭后可重试。" else "音频缓冲超时。")
                    toggle.isEnabled = false
                } else {
                    if (ready && state != null) {
                        val paused = state.optBoolean(2)
                        val ended = state.optBoolean(3)
                        val seconds = state.optInt(4)
                        if (ended || paused || SystemClock.elapsedRealtime() - lastSavedAt >= 5_000) {
                            saveProgress(seconds, ended, state.optInt(5))
                        }
                        if (ended && activityVisible && siblingOf(entry, 1) != null) {
                            switchWebAudio(dialog, 1, automatic = true)
                            return@evaluateJavascript
                        }
                        controls.update(!paused && !ended, seconds)
                        toggle.isEnabled = true
                        toggle.text = if (ended) "重播" else if (paused) "继续" else "暂停"
                        val message = if (ended) "播放结束" else if (paused) "已暂停" else "正在播放"
                        dialog.setMessage("$message · ${formatAudioTime(seconds)}")
                        status.text = "已打开音频"
                    }
                    web.postDelayed({ pollAudio() }, 500)
                }
            }
        }
        pollAudio()
    }

    private fun loadMediaLink(
        entry: PanEntry,
        viewer: String,
        lane: RequestLane = RequestLane.LIST,
        onFailure: () -> Unit = {},
        onResult: (Uri) -> Unit,
    ) {
        val form = PanApi.mediaLinkBody(entry, viewer)
        fun stop(message: String) {
            when (lane) {
                RequestLane.LIST, RequestLane.RENEWAL -> status.text = message
                RequestLane.AUDIO -> failAudioRequest(message)
            }
            onFailure()
        }

        fun submit(verification: String = "") {
            load(
                PanApi.mediaLinkRequest(), post = true, form = form + verification, lane = lane,
                onFailure = onFailure,
                onCaptcha = { json, isCurrent ->
                    if (!activityVisible) {
                        stop("需要输入网盘验证码，请返回应用后重试。")
                    } else {
                        val message = if (verification.isEmpty()) "请输入图片中的验证码"
                        else "验证码错误或已失效，请重新输入"
                        fun show(captcha: PanApi.MediaCaptcha, current: () -> Boolean) {
                            showMediaCaptcha(lane, captcha, message, current, ::stop) { answer ->
                                submit("&" + PanApi.mediaCaptchaForm(captcha, answer))
                            }
                        }
                        val captcha = PanApi.parseMediaCaptcha(json)
                        if (captcha != null) show(captcha, isCurrent)
                        else load(
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
                mediaAddresses.saveLink(mediaCacheKey(viewer, entry), link)
                onResult(link)
            }
        }
        submit()
    }

    private fun showMediaCaptcha(
        lane: RequestLane,
        captcha: PanApi.MediaCaptcha,
        message: String,
        isCurrent: () -> Boolean,
        onCancelled: (String) -> Unit,
        onAnswer: (String) -> Unit,
    ) {
        if (!activityVisible || mediaCaptchaDialog != null) {
            onCancelled("需要输入网盘验证码，请稍后重试。")
            return
        }
        val image = ImageView(this).apply {
            contentDescription = "百度网盘验证码图片"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val answer = EditText(this).apply {
            hint = "验证码"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val views = column().apply {
            setPadding(dp(24), 0, dp(24), 0)
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
            addView(answer)
        }
        var submitted = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("百度网盘验证码")
            .setMessage("正在加载验证码图片…")
            .setView(views)
            .setPositiveButton("验证并重试", null)
            .setNegativeButton("取消", null)
            .create()
        mediaCaptchaDialog = lane to dialog
        dialog.setOnDismissListener {
            if (mediaCaptchaDialog?.second === dialog) mediaCaptchaDialog = null
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
            if (value.isEmpty()) {
                answer.error = "请输入验证码"
                return@setOnClickListener
            }
            submitted = true
            mediaCaptchaDialog = null
            dialog.dismiss()
            onAnswer(value)
        }

        val url = captcha.image.toString()
        val cookie = CookieManager.getInstance().getCookie(url)
        val userAgent = web.settings.userAgentString
        Thread {
            val bitmap = runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.useCaches = false
                    connection.setRequestProperty("User-Agent", userAgent)
                    if (!cookie.isNullOrEmpty()) connection.setRequestProperty("Cookie", cookie)
                    check(connection.responseCode == 200)
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            runOnUiThread {
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
        }.start()
    }

    private fun dismissMediaCaptcha(lane: RequestLane) {
        val dialog = mediaCaptchaDialog?.takeIf { it.first == lane }?.second ?: return
        mediaCaptchaDialog = null
        dialog.dismiss()
    }

    private fun apiError(url: String, errno: Int, json: JSONObject): String {
        val path = Uri.parse(url).path.orEmpty()
        val showMsg = json.optString("show_msg").trim().take(60)
        val hint = when (errno) {
            -20 -> if (showMsg.isEmpty()) "百度网盘拒绝了此次请求。请在网盘页面确认该共享仍可访问，或稍后重试"
                else "百度网盘拒绝了此次请求"
            -6 -> "登录态已失效，请重新登录百度网盘后再试"
            -9 -> "请求过于频繁，请稍等片刻再试"
            else -> "网盘请求失败，请查看网盘提示或稍后重试"
        }
        return "$hint（错误码 $errno · 接口 $path）${if (showMsg.isEmpty()) "" else " · 网盘提示：$showMsg"}"
    }

    private fun load(
        url: String,
        post: Boolean = false,
        form: String? = null,
        statusHint: String? = null,
        lane: RequestLane = RequestLane.LIST,
        onCancelled: () -> Unit = {},
        onFailure: () -> Unit = {},
        onCaptcha: ((JSONObject, () -> Boolean) -> Unit)? = null,
        onCurrentResult: ((JSONObject, () -> Boolean) -> Unit)? = null,
        onResult: (JSONObject) -> Unit,
    ) {
        if (!ready || !hasVerifiedAccount()) {
            onCancelled()
            return
        }
        val id = when (lane) {
            RequestLane.LIST -> ++requestId
            RequestLane.AUDIO -> ++audioRequestId
            RequestLane.RENEWAL -> ++renewalRequestId
        }
        dismissMediaCaptcha(lane)
        val playToken = audioPlayToken.takeIf { lane == RequestLane.AUDIO }
        fun isCurrent() = !isDestroyed && id == when (lane) {
            RequestLane.LIST -> requestId
            RequestLane.AUDIO -> audioRequestId
            RequestLane.RENEWAL -> renewalRequestId
        } && (playToken == null || AudioPlayRequestGate.isCurrent(playToken))
        fun showError(message: String?) {
            val text = message ?: "网盘请求失败，请重试。"
            when (lane) {
                RequestLane.LIST -> status.text = text
                RequestLane.AUDIO -> failAudioRequest(text)
                RequestLane.RENEWAL -> Unit
            }
        }
        if (lane == RequestLane.LIST) status.text = statusHint ?: "正在读取…"
        val deadline = SystemClock.elapsedRealtime() + 20_000
        val key = "__groupPlayer${lane.name}$id"
        val target = JSONObject.quote(url)
        val bodyOption = form?.let { ", body: ${JSONObject.quote(it)}" }.orEmpty()
        val method = if (post) "POST" else "GET"
        val headers = if (post) {
            "{'X-Requested-With':'XMLHttpRequest','Content-Type':'application/x-www-form-urlencoded'}"
        } else {
            "{'X-Requested-With':'XMLHttpRequest'}"
        }
        val script = """
            (() => {
              if (location.origin !== 'https://pan.baidu.com') return false;
              const controller = new AbortController();
              const result = { ready: false, controller };
              window.$key = result;
              fetch($target, { method: '$method', credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: $headers$bodyOption })
                .then(async response => {
                  result.status = response.status;
                  result.body = await response.text();
                })
                .catch(() => { result.status = 0; })
                .finally(() => { result.ready = true; });
              return true;
            })()
        """.trimIndent()

        fun cancelRequest() {
            if (!isDestroyed) web.evaluateJavascript(
                "window.$key?.controller.abort(); delete window.$key;", null,
            )
            if (playToken != null && audioPlayToken == playToken && !AudioPlayRequestGate.isCurrent(playToken)) {
                cancelPendingAudioRequest()
            }
            onCancelled()
        }

        fun poll() {
            if (!isCurrent()) {
                cancelRequest()
                return
            }
            web.evaluateJavascript(
                "(() => { const r = window.$key; if (!r || !r.ready) return null; delete window.$key; return r; })()",
            ) { raw ->
                if (!isCurrent()) {
                    cancelRequest()
                    return@evaluateJavascript
                }
                if (raw == null || raw == "null") {
                    if (SystemClock.elapsedRealtime() < deadline) {
                        web.postDelayed({ poll() }, 200)
                    } else {
                        cancelRequest()
                        showError("请求超时，请重试。")
                        onFailure()
                    }
                    return@evaluateJavascript
                }
                runCatching {
                    val response = JSONTokener(raw).nextValue() as JSONObject
                    val http = response.getInt("status")
                    check(http in 200..299) { "请求失败（HTTP $http）。请检查网络或重新登录。" }
                    val body = response.optString("body")
                    check(body.trimStart().startsWith("{")) { "网盘未返回列表数据，请重新登录后刷新。" }
                    val json = JSONObject(body)
                    val errno = json.getInt("errno")
                    if (errno == -6) {
                        invalidateAccount()
                        refresh()
                        val message = apiError(url, errno, json)
                        status.text = message
                        if (lane == RequestLane.AUDIO) updateAudioRequestMessage(message)
                        onCancelled()
                        return@evaluateJavascript
                    }
                    if (errno == -20 && onCaptcha != null) {
                        onCaptcha(json, ::isCurrent)
                    } else {
                        check(errno == 0) { apiError(url, errno, json) }
                        if (onCurrentResult != null) onCurrentResult(json, ::isCurrent) else onResult(json)
                    }
                }.onFailure {
                    showError(if (it is IllegalStateException) it.message else "网盘响应格式有变化，请刷新重试。")
                    onFailure()
                }
            }
        }

        web.evaluateJavascript(script) { started ->
            if (!isCurrent()) {
                cancelRequest()
                return@evaluateJavascript
            }
            if (started == "true") poll()
            else {
                showError("网盘页面尚未就绪，请登录后刷新。")
                onFailure()
            }
        }
    }

    private fun showWeb(url: String) {
        loginPending = url == PanApi.login
        if (url != PanApi.CONTACTS) pickingSource = false
        val sameDocument = ready && web.url?.substringBefore('#') == url.substringBefore('#')
        if (!sameDocument) ready = false
        invalidateAccount()
        addSourceButton.visibility = if (pickingSource) View.VISIBLE else View.GONE
        native.visibility = View.GONE
        (native.tag as View).visibility = View.VISIBLE
        web.loadUrl(url)
        if (sameDocument) verifyAndRefresh()
    }

    private fun showApp(recheck: Boolean = false) {
        pickingSource = false
        loginPending = false
        videoResolver.cancel()
        (native.tag as View).visibility = View.GONE
        native.visibility = View.VISIBLE
        if (ready && web.url == API_PAGE) {
            if (recheck || (!hasVerifiedAccount() && !checkingAccount)) verifyAndRefresh()
            else refresh()
        } else {
            ready = false
            invalidateAccount()
            web.loadUrl(API_PAGE)
            refresh()
        }
    }

    @Deprecated("Android back button compatibility")
    override fun onBackPressed() {
        when {
            audioPage != null -> closeAudioPage()
            native.visibility == View.GONE -> showApp()
            folders.isNotEmpty() -> {
                folders.removeAt(folders.lastIndex)
                refresh()
            }
            screen != Screen.HOME -> showScreen(Screen.HOME)
            else -> super.onBackPressed()
        }
    }

    private fun restoreVideoIfReady() {
        val snapshot = videoToRestore ?: return
        if (!activityVisible || !ready || !hasVerifiedAccount()) return
        videoToRestore = null
        if (snapshot.account != verifiedAccount) {
            pendingQueue = null
            return
        }
        startVideo(
            snapshot.entry, snapshot.positionMs, snapshot.playWhenReady,
            audioOnly = snapshot.audioOnly, fullscreen = snapshot.fullscreen,
            orientationBeforeFullscreen = snapshot.orientationBeforeFullscreen,
        )
    }

    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any? {
        val snapshot = loadingVideo ?: stoppedVideo ?: videoPage?.snapshot() ?: videoToRestore
        val entry = snapshot?.entry ?: dispatchedAudioEntry
        val selection = pendingQueue?.takeIf { entry != null && sameEntry(it.entry, entry) }
        if (snapshot == null && selection == null) return null
        return RetainedPlayback(snapshot, selection)
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        stoppedVideo = null
        if (videoToRestore == null && videoPage == null && videoLoading == null) videoToRestore = loadingVideo
        nowPlaying.post(nowPlayingTicker)
        restoreVideoIfReady()
        maybeCheckHomeInBackground()
    }

    override fun onStop() {
        activityVisible = false
        mediaCaptchaDialog?.second?.dismiss()
        nowPlaying.removeCallbacks(nowPlayingTicker)
        if (pdfLoading != null) {
            pdfFetcher.cancel()
            pdfLoading?.dismiss()
            pdfLoading = null
        }
        stoppedVideo = videoPage?.snapshot()
        videoPage?.pauseForBackground()
        videoResolver.cancel()
        videoLoading?.dismiss()
        videoLoading = null
        super.onStop()
    }

    override fun onDestroy() {
        requestId++
        audioPlayToken?.let(AudioPlayRequestGate::cancel)
        audioRequestId++
        audioResolver.close()
        videoResolver.close()
        videoPage?.close()
        pdfFetcher.close()
        pdfLoading?.dismiss()
        closeAudioPage()
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        audioControls?.close()
        audioControls = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(52, 38, 30))
        setPadding(0, dp(10), 0, dp(10))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }
}

// The playback service and its UI run in the same process. Keep the queue when Android recreates the UI.
private object PlaybackQueueState {
    var anchor: PanEntry? = null
    var entries: List<PanEntry> = emptyList()
    var listKey: String? = null
}
