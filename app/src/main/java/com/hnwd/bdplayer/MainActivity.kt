package com.hnwd.bdplayer

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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
    private lateinit var searchButton: View
    private lateinit var refreshButton: View
    private lateinit var sectionRow: LinearLayout
    private lateinit var sectionTitle: TextView
    private lateinit var sortButton: TextView
    private lateinit var btnSelect: TextView
    private lateinit var batchBar: View
    private lateinit var batchCount: TextView
    private lateinit var batchAll: TextView
    private lateinit var batchFav: TextView
    private lateinit var batchDone: TextView
    private lateinit var batchMove: TextView
    private lateinit var batchCopy: TextView
    private lateinit var batchDelete: TextView
    private lateinit var btnDiskNewFolder: View
    private lateinit var crumbsScroll: View
    private lateinit var crumbsRow: LinearLayout
    private lateinit var filterBar: View
    private lateinit var filterCount: TextView
    private lateinit var transferredChip: TextView
    private var transferredOnly = false
    private var diskIndexCache: Set<String>? = null
    private val diskSearchFingerprints = mutableSetOf<String>()

    private var writeRequestId = 0
    private var deleteCheckRequestId = 0
    /** 网盘写操作（新建/重命名/移动/复制/删除/分享/转存）集中在这里，状态与锁都在它内部。 */
    private val diskWrites by lazy { DiskMutationSection(MutationHost()) }

    /** 文档（PDF）预览：取址 -> 下载 -> 打开阅读器。 */
    private var pdfPreviewRef: PdfPreviewSection? = null
    private val pdfPreview: PdfPreviewSection
        get() = pdfPreviewRef ?: PdfPreviewSection(PdfPreviewHostAdapter(), mediaLinks, mediaAddresses)
            .also { pdfPreviewRef = it }

    /** 媒体取址（群组 sharedownload / 我的网盘下载签名 / 验证码），三条预览链共用。 */
    private val mediaLinks by lazy { MediaLinkSection(MediaLinkHostAdapter()) }

    /** 搜索（输入弹窗 / 结果面板 / 定位）；按需创建，销毁时只碰已存在的实例。 */
    private var searchSectionRef: SearchSection? = null
    private val searchSection: SearchSection
        get() = searchSectionRef ?: SearchSection(SearchHostAdapter()).also { searchSectionRef = it }
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
    private val renewalResolver = VideoStreamResolver()
    private var diskRenewAttempts = 0
    private var lastDiskRenewAt = 0L
    private val imageFetcher by lazy { ImageFileFetcher(this) }
    private val mediaAddresses by lazy { MediaAddressCache(this) }
    private var imageLoading: AlertDialog? = null
    private var imageOpenId = 0
    private var imageViewerEntries: List<PanEntry> = emptyList()
    private val imageViewerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val index = result.data?.getIntExtra(ImageViewerActivity.EXTRA_SWITCH_INDEX, -1) ?: -1
            val entry = imageViewerEntries.getOrNull(index) ?: return@registerForActivityResult
            val original = result.data?.getBooleanExtra(ImageViewerActivity.EXTRA_OPEN_ORIGINAL, false) == true
            native.post {
                if (original) openImageOriginal(entry)
                else openImage(entry, imageViewerEntries)
            }
        }
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
    private val quarkLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refresh()
        if (result.resultCode == RESULT_OK) {
            QuarkDriveActivity.selectedFile(result.data)?.let { file ->
                val entry = quarkEntry(file, DISK_SOURCE_ID, EntryOrigin.DISK)
                native.post { openEntry(entry, listOf(entry)) }
            }
        }
        if (quarkLoggedIn()) {
            markQuarkEverLogged()
            if (pendingAddProvider == SourceProvider.QUARK) {
                pendingAddProvider = null
                manageQuarkSources()
            }
        } else if (pendingAddProvider == SourceProvider.QUARK) {
            pendingAddProvider = null
        }
    }

    private var mediaFilter = MediaFilter.ALL
    private var redrawFilteredRows: (() -> Unit)? = null
    private var showingHomeDirectory = false
    private lateinit var mediaChips: List<TextView>

    // V02-2 批量操作
    private var selectionMode = false
    private val selectedKeys = LinkedHashSet<String>()
    private var selectionScreen: Screen? = null
    private var lastRenderedEntries: List<PanEntry> = emptyList()

    private var courseOrder = CourseOrder.ASCENDING

    private val folders = mutableListOf<PanEntry>()
    private val courseEntries = mutableListOf<PanEntry>()
    private var courseParentKey = ""
    private val folderPositions = mutableMapOf<List<String>, FolderPosition>()
    private var searchRequestId = 0

    private var courseRequestId = 0
    private var automaticCourseLoad: PanEntry? = null
    private var historyRefreshScheduled = false
    private var historyRefreshPending = false

    private var audioRequestId = 0
    private var audioPlayToken: Long? = null
    private var dispatchedAudioEntry: PanEntry? = null
    private var pendingAudioEntry: PanEntry? = null
    private var stoppedVideo: VideoPlaybackPage.Snapshot? = null
    private var videoToRestore: VideoPlaybackPage.Snapshot? = null
    private var loadingVideo: VideoPlaybackPage.Snapshot? = null
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
    private var recent = true
    private lateinit var shell: LinearLayout
    private lateinit var navigationRail: ScrollView
    private var railVisible = false
    private val sheetHeights = java.util.WeakHashMap<Dialog, (Int) -> Int>()
    private var activityVisible = false
    private var ready = false
    private var requestId = 0
    private var gid = ""
    /** 网盘页当前网盘，与首页/群组的来源状态相互独立（v0.3 §8 #7）。 */
    private var diskProvider = SourceProvider.BAIDU
    private var bdstoken = ""
    private var homeGroupChip: TextView? = null
    private lateinit var btnFavorites: View
    private lateinit var favBadge: TextView
    private lateinit var btnBack: View
    private val tabFolders = mutableMapOf<Screen, List<PanEntry>>()
    private val tabScroll = mutableMapOf<Screen, Int>()
    private var favoritesOrigin = Screen.HOME
    private var loginPending = false
    private var pickingSource = false
    /** 添加登录流程中，登录成功后要接着打开的来源选择器。 */
    private var pendingAddProvider: SourceProvider? = null
    private var verifiedAccount = ""
    private var verifiedAccountName = ""
    private var verifiedAccountAvatar = ""
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

    private fun playbackAccount(entry: PanEntry): String =
        if (entry.provider == SourceProvider.QUARK) AccountScope.current else verifiedAccount

    /** 当前页面按哪个网盘算会话：网盘页看 diskProvider，首页/群组看活跃来源前缀。 */
    private fun currentPageProvider(): SourceProvider =
        if (screen == Screen.DISK) DriveSourceRouter.diskProvider()
        else DriveSourceRouter.providerOf(DriveSourceRouter.activeSourceId())

    /** 统一会话门禁。百度侧仍是 ready + 已校验账号；夸克侧只看登录 Cookie。 */
    private fun hasActiveSession(p: SourceProvider = currentPageProvider()): Boolean =
        when (p) {
            SourceProvider.BAIDU -> ready && hasVerifiedAccount()
            SourceProvider.QUARK -> QuarkPersonalDrive.openSession() != null
        }
    private var observedGroup: Pair<String, String>? = null
    private lateinit var addSourceButton: View

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
        ImageCache.prune(this)
        AccountScope.init(this)
        DriveSourceRouter.bindActiveSource { gid }
        diskProvider = runCatching {
            SourceProvider.valueOf(
                getSharedPreferences("account_scope", MODE_PRIVATE)
                    .getString(AccountScope.key("disk_provider"), "").orEmpty(),
            )
        }.getOrDefault(SourceProvider.BAIDU)
        DriveSourceRouter.bindDiskProvider({ diskProvider }, { diskProvider = it })
        audioHistory = AudioHistory(getSharedPreferences("audio_history", MODE_PRIVATE))
        videoHistory = AudioHistory(getSharedPreferences("video_history", MODE_PRIVATE))
        favorites = Favorites(getSharedPreferences("favorites", MODE_PRIVATE))
        courseCache = CourseCache(getSharedPreferences("course_cache", MODE_PRIVATE))
        gid = readActiveSource()
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

        shell = layoutInflater.inflate(R.layout.app_main, native, false) as LinearLayout
        navigationRail = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            setBackgroundColor(color(R.color.surface))
        }
        val shellRow = row().apply {
            addView(navigationRail, LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.navigation_rail_width), -1,
            ))
            addView(shell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        native.addView(centeredContent(shellRow), LinearLayout.LayoutParams(-1, -1))
        title = shell.findViewById(R.id.appTitle)
        appSub = shell.findViewById(R.id.appSub)
        sourceName = shell.findViewById(R.id.sourceName)
        sourceButton = shell.findViewById(R.id.btnBrowse)
        sectionRow = shell.findViewById(R.id.sectionRow)
        sectionTitle = shell.findViewById(R.id.sectionTitle)
        sortButton = shell.findViewById(R.id.sortButton)
        crumbsScroll = shell.findViewById(R.id.crumbsScroll)
        crumbsRow = shell.findViewById(R.id.crumbsRow)
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
                    updateFilterChips()
                    redrawFilteredRows?.invoke()
                }
                mediaFilters.addView(this, LinearLayout.LayoutParams(-2, dp(36)).apply {
                    if (filter != MediaFilter.ALL) marginStart = dp(8)
                })
            }
        }
        transferredChip = TextView(this).apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(color(R.color.ink2))
            visibility = View.GONE
            setOnClickListener {
                transferredOnly = !transferredOnly
                updateFilterChips()
                redrawFilteredRows?.invoke()
            }
            mediaFilters.addView(this, LinearLayout.LayoutParams(-2, dp(36)).apply {
                marginStart = dp(8)
            })
        }
        filterCount = shell.findViewById(R.id.filterCount)
        navRoots = listOf(
            shell.findViewById(R.id.navHome),
            shell.findViewById(R.id.navDisk),
            shell.findViewById(R.id.navGroups),
            shell.findViewById(R.id.navSettings),
        )
        btnFavorites = shell.findViewById(R.id.btnFavorites)
        favBadge = shell.findViewById(R.id.favBadge)
        btnBack = shell.findViewById(R.id.btnBack)
        btnFavorites.setOnClickListener { openFavorites() }
        btnBack.setOnClickListener { closeFavorites() }
        homeGroupChip = TextView(this).apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink2))
            setBackgroundResource(R.drawable.bg_source_chip)
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            compoundDrawablePadding = dp(4)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_users, 0, R.drawable.ic_chevron_down, 0,
            )
            visibility = View.GONE
            setOnClickListener { chooseSource() }
        }
        sectionRow.addView(homeGroupChip, 1, LinearLayout.LayoutParams(-2, dp(44)).apply {
            marginEnd = dp(8)
        })
        status = shell.findViewById(R.id.status)
        nowPlaying = shell.findViewById(R.id.miniPlayer)
        historyPanel = shell.findViewById(R.id.historyPanel)
        content = shell.findViewById(R.id.content)
        contentScroll = shell.findViewById<ScrollView>(R.id.contentScroll).apply {
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                if (oldScrollY > dp(24) && scrollY <= dp(24)) refreshHistoryIfPending()
            }
        }
        courseSwitch = shell.findViewById(R.id.courseSwitch)
        btnPrev = shell.findViewById(R.id.btnPrev)
        btnNext = shell.findViewById(R.id.btnNext)
        courseHint = shell.findViewById(R.id.courseHint)
        sourceButton.setOnClickListener { chooseSource() }
        searchButton = shell.findViewById<View>(R.id.btnSearch).apply {
            setOnClickListener { searchSection.show() }
        }
        refreshButton = shell.findViewById(R.id.btnRefresh)
        refreshButton.setOnClickListener {
            when {
                currentPageProvider() == SourceProvider.QUARK || hasActiveSession() -> refresh(force = true)
                ready && !checkingAccount -> verifyAndRefresh(force = true)
                !ready -> showApp()
            }
        }
        sortButton.setOnClickListener { showCourseSort() }
        btnSelect = shell.findViewById(R.id.btnSelect)
        batchBar = shell.findViewById(R.id.batchBar)
        batchCount = shell.findViewById(R.id.batchCount)
        batchAll = shell.findViewById(R.id.batchAll)
        batchFav = shell.findViewById(R.id.batchFav)
        batchDone = shell.findViewById(R.id.batchDone)
        btnSelect.setOnClickListener { enterSelection() }
        batchAll.setOnClickListener { selectAllVisible() }
        batchFav.setOnClickListener { batchFavoriteSelected() }
        batchDone.setOnClickListener { exitSelection() }
        batchMove = shell.findViewById(R.id.batchMove)
        batchCopy = shell.findViewById(R.id.batchCopy)
        batchDelete = shell.findViewById(R.id.batchDelete)
        batchMove.setOnClickListener { diskWrites.batchMoveSelected() }
        batchCopy.setOnClickListener { diskWrites.batchCopySelected() }
        batchDelete.setOnClickListener { diskWrites.batchDeleteSelected() }
        btnDiskNewFolder = shell.findViewById(R.id.btnDiskNewFolder)
        btnDiskNewFolder.setOnClickListener { diskWrites.promptNewFolder() }
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
        restoreNavigationState(savedInstanceState)
        updateWindowLayout()
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
                                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                                    syncAudioAnchor(player)
                                    scheduleHistoryRefresh()
                                }
                                updateNowPlaying()
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                if (!isPlaying) scheduleHistoryRefresh()
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_ENDED) {
                                    advanceAudioIfEnded(player)
                                    scheduleHistoryRefresh()
                                }
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
        val ended = currentAudioEntry(player) ?: return
        val mediaItem = player.currentMediaItem
        advanceAfterEnd(
            ended,
            isCurrent = {
                pendingAudioEntry == null && videoLoading == null && videoPage == null &&
                    player.playbackState == Player.STATE_ENDED && player.playWhenReady &&
                    player.currentMediaItem == mediaItem && ownsAudio(player)
            },
        ) {
            playSibling(ended, 1, automatic = true)
        }
    }

    private fun scheduleHistoryRefresh() {
        historyRefreshPending = true
        if (historyRefreshScheduled) return
        historyRefreshScheduled = true
        content.postDelayed({
            historyRefreshScheduled = false
            refreshHistoryIfPending()
        }, 350)
    }

    private fun refreshHistoryIfPending() {
        if (historyRefreshPending && !historyRefreshScheduled && !isDestroyed && hasVerifiedAccount() &&
            screen == Screen.HOME && folders.isEmpty() && audioPage == null && contentScroll.scrollY <= dp(24)
        ) {
            renderHomeSections()
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

    private fun ownsAudio(player: Player): Boolean {
        val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return false
        return if (extras.getString("provider") == SourceProvider.QUARK.name) {
            extras.getString("account") == AccountScope.current && hasActiveSession(SourceProvider.QUARK)
        } else {
            hasVerifiedAccount() && extras.getString("account") == verifiedAccount
        }
    }

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN, screen.name)
        outState.putString(STATE_FILTER, mediaFilter.name)
        outState.putBoolean(STATE_TRANSFERRED_ONLY, transferredOnly)
        outState.putString(STATE_FAVORITES_ORIGIN, favoritesOrigin.name)
        if (screen == Screen.DISK || screen == Screen.GROUPS || screen == Screen.HOME) {
            outState.putString(STATE_FOLDERS, encodeEntries(folders))
        }
        super.onSaveInstanceState(outState)
    }

    private fun restoreNavigationState(state: Bundle?) {
        if (state == null) return
        val restored = runCatching { Screen.valueOf(state.getString(STATE_SCREEN).orEmpty()) }.getOrNull()
            ?: return
        screen = restored
        recent = restored == Screen.HOME
        mediaFilter = runCatching { MediaFilter.valueOf(state.getString(STATE_FILTER).orEmpty()) }
            .getOrDefault(MediaFilter.ALL)
        transferredOnly = state.getBoolean(STATE_TRANSFERRED_ONLY, false)
        favoritesOrigin = runCatching {
            Screen.valueOf(state.getString(STATE_FAVORITES_ORIGIN).orEmpty())
        }.getOrDefault(Screen.HOME)
        folders.clear()
        if (restored == Screen.DISK || restored == Screen.GROUPS || restored == Screen.HOME) {
            val trail = decodeEntries(state.getString(STATE_FOLDERS))
            if (restored == Screen.DISK || trail.all { it.gid == gid }) folders.addAll(trail)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从夸克登录页返回后，设置页的网盘登录态需要重算。
        if (screen == Screen.SETTINGS && ready) refresh()
    }

    private fun showScreen(target: Screen) {
        clearSelectionIfActive()
        if (screen == target) {
            contentScroll.scrollTo(0, 0)
            refresh()
            return
        }
        if (screen != Screen.FAVORITES) {
            tabFolders[screen] = folders.toList()
            tabScroll[screen] = contentScroll.scrollY
        }
        screen = target
        recent = target == Screen.HOME
        transferredOnly = false
        folders.clear()
        if (target == Screen.DISK || target == Screen.GROUPS || target == Screen.HOME) {
            tabFolders[target]?.let { folders.addAll(it) }
        }
        courseEntries.clear()
        courseParentKey = ""
        contentScroll.scrollTo(0, 0)
        refresh()
        scrollAfterLayout(tabScroll[target] ?: 0)
    }

    private fun openFavorites() {
        if (!hasVerifiedAccount()) {
            toast("请先登录百度网盘")
            return
        }
        if (screen == Screen.FAVORITES) return
        favoritesOrigin = when (screen) {
            Screen.DISK, Screen.GROUPS, Screen.HOME -> screen
            else -> Screen.HOME
        }
        showScreen(Screen.FAVORITES)
    }

    private fun closeFavorites() {
        if (screen == Screen.FAVORITES) showScreen(favoritesOrigin)
    }

    private fun updateFilterChips() {
        MediaFilter.entries.forEach { filter ->
            val chip = mediaChips[filter.ordinal]
            chip.isSelected = filter == mediaFilter
            setTabActive(chip, chip.isSelected)
        }
        transferredChip.isSelected = transferredOnly
        setTabActive(transferredChip, transferredOnly)
    }

    private fun showAvailableMediaFilters(kinds: Set<MediaKind>) {
        MediaFilter.entries.forEach { filter ->
            val available = filter == MediaFilter.ALL || kinds.any(filter::includes)
            mediaChips[filter.ordinal].visibility = if (available) View.VISIBLE else View.GONE
        }
    }

    private fun updateNav() {
        val screens = listOf(Screen.HOME, Screen.DISK, Screen.GROUPS, Screen.SETTINGS)
        val active = if (screen == Screen.FAVORITES) screens.indexOf(favoritesOrigin) else screens.indexOf(screen)
        val titles = listOf("首页", "网盘", groupTabLabel(), "设置")
        val icons = listOf(R.drawable.ic_home, R.drawable.ic_cloud, R.drawable.ic_users, R.drawable.ic_settings)
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
        shell.findViewById<TextView>(R.id.btnQuarkDrive).apply {
            val browsing = screen == Screen.GROUPS
            visibility = if (screen == Screen.DISK || browsing) View.VISIBLE else View.GONE
            val currentProvider = if (screen == Screen.DISK) diskProvider else providerOfSourceId(gid)
            text = when (currentProvider) {
                SourceProvider.QUARK -> "夸克网盘"
                SourceProvider.BAIDU -> "百度网盘"
            }
            contentDescription = "切换网盘，当前为 $text"
            setOnClickListener { chooseProvider() }
        }
        renderSourcePicker()
        updateFavoritesBadge()
        val browsing = screen == Screen.HOME || screen == Screen.DISK || screen == Screen.GROUPS
        sourceButton.visibility = if (screen == Screen.GROUPS) View.VISIBLE else View.GONE
        homeGroupChip?.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        btnFavorites.visibility = if (screen == Screen.DISK || screen == Screen.GROUPS) View.VISIBLE else View.GONE
        btnBack.visibility = if (screen == Screen.FAVORITES) View.VISIBLE else View.GONE
        appSub.visibility = when {
            screen == Screen.HOME && hasVerifiedAccount() && verifiedAccountName.isNotBlank() -> View.VISIBLE
            screen == Screen.DISK -> View.VISIBLE
            browsing && (folders.isNotEmpty() || !hasActiveSession()) -> View.VISIBLE
            else -> View.GONE
        }
        refreshButton.visibility = if (browsing) View.VISIBLE else View.GONE
        searchButton.visibility = if (hasActiveSession() &&
            (screen == Screen.DISK || (screen == Screen.GROUPS && gid.isNotEmpty() &&
                currentPageProvider() == SourceProvider.BAIDU && !isContactSource(gid)))
        ) View.VISIBLE else View.GONE
        if (::btnDiskNewFolder.isInitialized) {
            val diskManage = screen == Screen.DISK && hasActiveSession()
            btnDiskNewFolder.visibility = if (diskManage) View.VISIBLE else View.GONE
        }
        title.setTextSize(
            if (screen == Screen.HOME ||
                ((screen == Screen.DISK || screen == Screen.GROUPS) && folders.isEmpty())
            ) 30f else 26f,
        )
        crumbsScroll.visibility =
            if (browsing && folders.isNotEmpty()) View.VISIBLE else View.GONE
        sectionRow.visibility = if (browsing) View.VISIBLE else View.GONE
        sectionTitle.text = when (screen) {
            Screen.HOME -> "群组"
            Screen.DISK, Screen.GROUPS -> "文件"
            else -> ""
        }
    }

    private fun updateFavoritesBadge() {
        val count = favorites.all().size
        favBadge.text = count.toString()
        favBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
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
        val rootLabel = when (screen) {
            Screen.DISK -> "我的网盘"
            Screen.HOME -> "群组"
            Screen.GROUPS -> shareSources().firstOrNull { it.id == gid }?.name ?: "共享文件库"
            else -> "根目录"
        }
        addCrumb(rootLabel, folders.isEmpty()) {
            if (folders.isNotEmpty()) returnToFolder(0)
        }
        folders.forEachIndexed { index, entry ->
            addSeparator()
            addCrumb(entry.name, index == folders.lastIndex) {
                if (index < folders.lastIndex) {
                    returnToFolder(index + 1)
                }
            }
        }
        crumbsScroll.visibility =
            if (folders.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showChoiceSheet(
        title: String,
        subtitle: String? = null,
        choices: List<SheetChoice>,
        multi: Boolean = false,
        confirmLabel: String? = null,
        onSelect: ((SheetChoice) -> Unit)? = null,
        onConfirm: ((List<SheetChoice>) -> Unit)? = null,
        footerActions: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        if (choices.isEmpty()) return
        val checked = BooleanArray(choices.size) { choices[it].checked }
        val view = layoutInflater.inflate(R.layout.dialog_choice_sheet, null, false)
        view.findViewById<TextView>(R.id.choiceTitle).text = title
        val subtitleView = view.findViewById<TextView>(R.id.choiceSubtitle)
        if (subtitle.isNullOrEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.text = subtitle
            subtitleView.visibility = View.VISIBLE
        }
        val list = view.findViewById<ListView>(R.id.choiceList)
        val adapter = object : BaseAdapter() {
            override fun getCount() = choices.size
            override fun getItem(position: Int) = choices[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_choice, parent, false)
                val choice = choices[position]
                val label = row.findViewById<TextView>(R.id.choiceLabel)
                label.text = choice.title
                label.setTextColor(color(if (checked[position]) R.color.accentInk else R.color.ink))
                val meta = row.findViewById<TextView>(R.id.choiceMeta)
                if (choice.meta.isEmpty()) {
                    meta.visibility = View.GONE
                } else {
                    meta.text = choice.meta
                    meta.visibility = View.VISIBLE
                }
                row.findViewById<View>(R.id.choiceCheck).visibility =
                    if (checked[position]) View.VISIBLE else View.GONE
                row.findViewById<View>(R.id.choiceDivider).visibility =
                    if (position == choices.lastIndex) View.GONE else View.VISIBLE
                return row
            }
        }
        list.adapter = adapter
        val dialog = Dialog(this)
        dialog.setContentView(view)
        val footer = view.findViewById<LinearLayout>(R.id.choiceFooter)
        val actions = view.findViewById<LinearLayout>(R.id.choiceActions)
        var footerHeight = 0
        fun addAction(label: String, primary: Boolean, action: () -> Unit) {
            val button = TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(18), 0, dp(18), 0)
                if (primary) {
                    setBackgroundResource(R.drawable.bg_search_action)
                    setTextColor(color(R.color.onAccent))
                } else {
                    setBackgroundResource(R.drawable.bg_chip)
                    setTextColor(color(R.color.ink2))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }
            val params = LinearLayout.LayoutParams(-2, dp(44))
            if (actions.childCount > 0) params.marginStart = dp(8)
            actions.addView(button, params)
        }
        if (multi && confirmLabel != null) {
            addAction("取消", false) { }
            addAction(confirmLabel, true) {
                onConfirm?.invoke(choices.filterIndexed { index, _ -> checked[index] })
            }
            footer.visibility = View.VISIBLE
            footerHeight = dp(66)
        } else if (footerActions.isNotEmpty()) {
            footerActions.forEachIndexed { index, pair ->
                addAction(pair.first, index == footerActions.lastIndex) { pair.second() }
            }
            footer.visibility = View.VISIBLE
            footerHeight = dp(66)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val choice = choices[position]
            if (multi) {
                checked[position] = !checked[position]
                adapter.notifyDataSetChanged()
            } else {
                dialog.dismiss()
                onSelect?.invoke(choice)
            }
        }
        sizeSheet(dialog) { available ->
            val maxHeight = sheetMaxHeight(available)
            (dp(132) + minOf(choices.size, 8) * dp(56) + footerHeight)
                .coerceIn(minOf(dp(260), maxHeight), maxHeight)
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(R.style.SearchSheetAnimation)
        }
        dialog.setCanceledOnTouchOutside(true)
        view.findViewById<ImageView>(R.id.choiceClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showThemeDialog() {
        val modes = listOf(ThemeStore.SYSTEM, ThemeStore.LIGHT, ThemeStore.DARK)
        val current = modes.indexOf(ThemeStore.mode(this)).coerceAtLeast(0)
        showChoiceSheet(
            title = "外观",
            subtitle = "选择界面配色",
            choices = modes.mapIndexed { index, mode ->
                SheetChoice(ThemeStore.label(mode), checked = index == current, tag = mode)
            },
            onSelect = { choice ->
                val mode = choice.tag as String
                ThemeStore.set(this, mode, screen.name)
                findViewById<TextView>(R.id.setThemeValue)?.text = ThemeStore.label(mode)
            },
        )
    }

    private fun hasFilterOptions(entries: List<PanEntry>): Boolean =
        entries.map(PanApi::kindOf).distinct().size >= 2

    private fun transferredIndex(): Set<String> =
        diskIndexCache ?: (courseCache.diskIndex() + diskSearchFingerprints).also { diskIndexCache = it }

    private fun matchesTransferred(entry: PanEntry, index: Set<String>): Boolean =
        !entry.directory && entry.md5.isNotEmpty() &&
            index.contains("${PanApi.md5Key(entry.md5)}:${entry.size}")

    private fun groupTabLabel() =
        if (providerOfSourceId(gid) == SourceProvider.QUARK) "监控目录" else "群组"

    // 百度沿用原群号，保持接口参数、缓存和历史记录一致；夸克使用独立的前缀。
    private fun sourceId(provider: SourceProvider, id: String): String =
        if (provider == SourceProvider.QUARK) qualifySourceId(provider, rawSourceId(id)) else rawSourceId(id)

    private fun storedShareSources(): List<ShareSource> {
        val prefs = getSharedPreferences("account_scope", MODE_PRIVATE)
        val stored = JSONArray(prefs.getString(AccountScope.key("share_sources"), "[]") ?: "[]")
        return List(stored.length()) { index ->
            val item = stored.getJSONObject(index)
            val rawId = item.getString("id")
            val provider = if (rawId.startsWith(PREFIX_BAIDU) || rawId.startsWith(PREFIX_QUARK)) {
                providerOfSourceId(rawId)
            } else {
                SourceProvider.entries.firstOrNull { it.name == item.optString("provider") } ?: SourceProvider.BAIDU
            }
            ShareSource(sourceId(provider, rawId), item.getString("name"), item.optString("session"), provider)
        }
    }

    private fun readActiveSource(): String {
        val prefs = getSharedPreferences("account_scope", MODE_PRIVATE)
        val saved = prefs.getString(AccountScope.key("group_id"), "").orEmpty()
        val stored = storedShareSources()
        val sources = stored.filterNot {
            it.provider == SourceProvider.BAIDU && it.name == "原群文件库" && it.sessionUrl.isEmpty()
        }.distinctBy { it.id }
        val normalized = sourceId(providerOfSourceId(saved), saved)
        val active = sources.firstOrNull { it.id == normalized }?.id
            ?: sources.firstOrNull { it.provider == SourceProvider.QUARK && rawSourceId(it.id) == saved }?.id
            ?: sources.firstOrNull()?.id.orEmpty()
        if (sources.size != stored.size || active != saved) {
            saveShareSources(sources, active)
        }
        return active
    }

    private fun shareSources(): List<ShareSource> = storedShareSources().distinctBy { it.id }

    private fun chooseSource() {
        val sources = shareSources()
        if (sources.isEmpty()) {
            manageSources()
            return
        }
        // 扁平列表 + 按网盘分组：百度在前（群组/联系人），夸克在后（监控目录）。
        val ordered = sources.sortedBy { it.provider.ordinal }
        showChoiceSheet(
            title = "切换文件库",
            subtitle = "选择要浏览的来源（按网盘分组）",
            choices = ordered.map {
                val meta = when (it.provider) {
                    SourceProvider.QUARK -> "夸克 · 监控目录"
                    SourceProvider.BAIDU -> if (isContactSource(it.id)) "百度 · 联系人" else "百度 · 群组"
                }
                SheetChoice(it.name, meta = meta, checked = it.id == gid, tag = it)
            },
            onSelect = { choice ->
                (choice.tag as? ShareSource)?.let { selectSource(it) }
            },
            footerActions = listOf("添加 / 管理来源" to { manageSources() }),
        )
    }

    private fun selectSource(source: ShareSource) {
        getSharedPreferences("account_scope", MODE_PRIVATE).edit()
            .putString(AccountScope.key("source_last_${source.provider.name.lowercase()}"), source.id)
            .apply()
        activateGroup(source.id)
    }

    private fun manageSources() {
        showChoiceSheet(
            title = "添加 / 管理来源",
            subtitle = "选择来源所属网盘",
            choices = listOf(
                SheetChoice("百度网盘", meta = "群组 / 联系人", tag = SourceProvider.BAIDU),
                SheetChoice("夸克网盘", meta = "从根目录勾选监控目录", tag = SourceProvider.QUARK),
            ),
            onSelect = { choice ->
                when (choice.tag as? SourceProvider) {
                    SourceProvider.QUARK -> manageQuarkSources()
                    else -> manageBaiduSources()
                }
            },
        )
    }

    private fun manageBaiduSources() {
        if (!ready || !hasVerifiedAccount()) {
            toast("请先确认百度网盘登录")
            return
        }
        showChoiceSheet(
            title = "管理百度来源",
            subtitle = "勾选要在 App 中浏览的文件库",
            choices = listOf(SheetChoice("群组"), SheetChoice("联系人")),
            onSelect = { discoverSources(contacts = it.title == "联系人") },
        )
    }

    /** 从夸克网盘根目录勾选最多 5 个目录，存为 provider = QUARK 的来源。 */
    private fun manageQuarkSources() {
        val session = QuarkPersonalDrive.openSession()
        if (session == null) {
            toast("请先登录夸克网盘")
            return
        }
        toast("正在读取夸克网盘目录…")
        Thread {
            val result = runCatching {
                session.execute(session.list(QuarkPersonalDrive.ROOT, 1, 200))
            }
            runOnUiThread {
                result.onFailure { error ->
                    toast("读取夸克目录失败：${error.message ?: "未知错误"}")
                }.onSuccess { page ->
                    val dirs = page.items.filter { it.directory }
                    if (dirs.isEmpty()) {
                        toast("夸克网盘根目录没有子文件夹")
                        return@onSuccess
                    }
                    val checkedIds = shareSources()
                        .filter { it.provider == SourceProvider.QUARK }
                        .map { rawSourceId(it.id) }
                        .toSet()
                    showChoiceSheet(
                        title = "夸克监控目录",
                        subtitle = "勾选要监控的目录（最多 5 个，仅根目录一层）",
                        choices = dirs.map {
                            SheetChoice(
                                it.name,
                                meta = "夸克 · 目录",
                                checked = it.id in checkedIds,
                                tag = it,
                            )
                        },
                        multi = true,
                        confirmLabel = "保存",
                        onConfirm = { selected ->
                            val picked = selected.mapNotNull { it.tag as? QuarkFile }.take(5)
                            val others = shareSources().filter { it.provider != SourceProvider.QUARK }
                            val quark = picked.map {
                                ShareSource(qualifySourceId(SourceProvider.QUARK, it.id), it.name,
                                    provider = SourceProvider.QUARK)
                            }
                            val merged = others + quark
                            val next = gid.takeIf { current -> merged.any { it.id == current } }
                                ?: quark.firstOrNull()?.id
                                ?: others.firstOrNull()?.id.orEmpty()
                            saveShareSources(merged, next)
                            activateGroup(next)
                            toast("已保存 ${quark.size} 个夸克监控目录")
                        },
                    )
                }
            }
        }.start()
    }

    private fun discoverSources(contacts: Boolean) {
        if (!ready || !hasVerifiedAccount()) return
        val label = if (contacts) "联系人" else "群组"
        val account = verifiedAccount
        val candidates = linkedMapOf<String, ShareSource>()
        val seenPages = mutableSetOf<List<String>>()
        val idField = if (contacts) "uk" else "gid"
        val pageSize = 20

        fun failed() {
            if (!hasVerifiedAccount() || verifiedAccount != account) return
            AlertDialog.Builder(this)
                .setTitle("无法读取网盘${label}")
                .setMessage(status.text)
                .setPositiveButton("重试") { _, _ -> discoverSources(contacts) }
                .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
                .setNegativeButton("取消", null)
                .show()
        }

        fun nextPage(token: String, start: Int) {
            val url = if (contacts) PanApi.contacts(token, start, pageSize) else PanApi.groups(token, start, pageSize)
            load(url, statusHint = "正在读取网盘${label}…", onFailure = ::failed) { json ->
                val records = json.getJSONArray("records")
                val items = List(records.length(), records::getJSONObject)
                val freshPage = seenPages.add(items.map { it.optString(idField) })
                for (item in items) {
                    val id = item.optString(idField)
                    if (!id.matches(Regex("[0-9]{1,24}"))) continue
                    if (contacts && item.optInt("black_flag") != 0) continue
                    val sourceId = if (contacts) CONTACT_SOURCE_PREFIX + id else id
                    val name = if (contacts) sequenceOf("remark", "display_name", "nick_name", "uname")
                        .filterNot(item::isNull).map { item.optString(it, "") }.firstOrNull { it.isNotBlank() } else item.optString("name")
                    candidates[sourceId] = ShareSource(sourceId, name?.takeIf { it.isNotBlank() } ?: "$label $id")
                }
                val nextStart = start + records.length()
                val hasMore = if (!json.isNull("has_more")) PanApi.hasMore(json)
                    else json.optInt("count", nextStart) > nextStart || records.length() == pageSize
                if (hasMore && items.isNotEmpty() && freshPage) {
                    nextPage(token, nextStart)
                } else {
                    showSourcePicker(account, contacts, candidates.values.toList())
                    if (hasMore) toast("网盘仅返回了部分${label}，请稍后重试")
                }
            }
        }

        load(PanApi.groupListSession(), statusHint = "正在读取网盘${label}…", onFailure = ::failed) { json ->
            val session = json.getJSONObject("result")
            if (session.optString("uk") != account) {
                verifyAndRefresh()
            } else {
                val token = session.optString("bdstoken")
                check(!session.isNull("bdstoken") && token.isNotBlank()) {
                    "网盘未返回通讯录所需的会话参数，请重新登录。"
                }
                nextPage(token, 0)
            }
        }
    }

    private fun showSourcePicker(account: String, contacts: Boolean, discovered: List<ShareSource>) {
        status.text = ""
        val label = if (contacts) "联系人" else "群组"
        val existing = shareSources().associateBy { it.id }
        val candidates = (discovered + existing.values.filter {
            it.provider == SourceProvider.BAIDU && isContactSource(it.id) == contacts
        })
            .distinctBy { it.id }
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("网盘${label}")
                .setMessage("这个账号的${label}列表为空。")
                .setNeutralButton("打开网页通讯录") { _, _ -> openContacts() }
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        showChoiceSheet(
            title = "管理${label}文件库",
            subtitle = "可多选，取消勾选可从 App 移除",
            choices = candidates.map { source ->
                SheetChoice(source.name, checked = source.id in existing, tag = source)
            },
            multi = true,
            confirmLabel = "保存",
            onConfirm = { selected ->
                if (hasVerifiedAccount() && verifiedAccount == account) {
                    val candidateIds = candidates.map { it.id }.toSet()
                    val selectedSources = selected.mapNotNull { it.tag as? ShareSource }
                        .map { it.copy(sessionUrl = existing[it.id]?.sessionUrl.orEmpty()) }
                    val retained = shareSources().filterNot { it.id in candidateIds }
                    val sources = retained + selectedSources
                    val nextGroup = gid.takeIf { current -> sources.any { it.id == current } }
                        ?: selectedSources.firstOrNull()?.id
                        ?: retained.firstOrNull()?.id.orEmpty()
                    saveShareSources(sources, nextGroup)
                    activateGroup(nextGroup)
                }
            },
        )
    }

    private fun saveShareSources(sources: List<ShareSource>, activeGroup: String) {
        val entries = JSONArray()
        sources.forEach {
            entries.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("session", it.sessionUrl)
                    .put("provider", it.provider.name),
            )
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
        toast("群文件库可在这里关联，联系人文件库可在 App 设置中勾选")
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
        clearSelectionIfActive()
        val changed = group != gid
        setActiveGroup(group)
        if (!returnFromWeb && screen == Screen.GROUPS && isContactSource(group)) {
            tabFolders.remove(Screen.HOME)
            tabScroll.remove(Screen.HOME)
            showScreen(Screen.HOME)
            tabFolders.remove(Screen.GROUPS)
            tabScroll.remove(Screen.GROUPS)
            return
        }
        if (returnFromWeb) {
            tabFolders.remove(Screen.GROUPS)
            tabScroll.remove(Screen.GROUPS)
            screen = Screen.GROUPS
            recent = false
            folders.clear()
            folderPositions.clear()
            courseEntries.clear()
            courseParentKey = ""
            showApp()
        } else {
            if (changed && (screen == Screen.HOME || screen == Screen.GROUPS)) {
                folders.clear()
                folderPositions.clear()
                courseEntries.clear()
                courseParentKey = ""
                mediaFilter = MediaFilter.ALL
                transferredOnly = false
            }
            refresh(force = changed)
            contentScroll.scrollTo(0, 0)
        }
    }

    private fun setActiveGroup(group: String) {
        if (group != gid) {
            tabFolders.remove(Screen.HOME)
            tabFolders.remove(Screen.GROUPS)
            tabScroll.remove(Screen.HOME)
            tabScroll.remove(Screen.GROUPS)
        }
        gid = group
        homeCheckToken++
        homeCheckInFlight = false
        lastHomeCheckAt = 0L
        getSharedPreferences("account_scope", MODE_PRIVATE).edit()
            .putString(AccountScope.key("group_id"), gid).apply()
    }

    private fun openFolderTrail(group: String, trail: List<PanEntry>) {
        if (!hasVerifiedAccount() || group != gid) return
        clearSelectionIfActive()
        if (screen == Screen.GROUPS && trail.take(folders.size).map(::entryKey) == folders.map(::entryKey) && trail.size > folders.size) {
            rememberFolderPosition()
        }
        if (screen != Screen.GROUPS) folderPositions.clear()
        screen = Screen.GROUPS
        recent = false
        folders.clear()
        folders.addAll(trail)
        mediaFilter = MediaFilter.ALL
        transferredOnly = false
        refresh()
    }

    private fun rememberFolderPosition() {
        folderPositions[folders.map(::entryKey)] =
            FolderPosition(contentScroll.scrollY, mediaFilter, transferredOnly)
    }

    private fun returnToFolder(depth: Int) {
        folders.subList(depth, folders.size).clear()
        val path = folders.map(::entryKey)
        val position = folderPositions.remove(path)
        folderPositions.keys.removeAll { it.size > depth }
        mediaFilter = position?.filter ?: MediaFilter.ALL
        transferredOnly = position?.transferredOnly ?: false
        refresh(cachedFirst = true)
        scrollAfterLayout(position?.scrollY ?: 0)
    }

    private fun scrollAfterLayout(y: Int) {
        val renderedRequest = requestId
        contentScroll.doOnPreDraw {
            if (requestId == renderedRequest) contentScroll.scrollTo(0, y)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::shell.isInitialized) return
        updateWindowLayout()
        sheetHeights.entries.removeAll { !it.key.isShowing }
        sheetHeights.forEach { (dialog, height) -> dialog.window?.sizeBottomSheet(this, height) }
    }

    private fun updateWindowLayout() {
        val pagePad = resources.getDimensionPixelSize(R.dimen.page_pad)
        shell.findViewById<View>(R.id.brandRow).apply {
            setPaddingRelative(pagePad, paddingTop, paddingEnd, paddingBottom)
        }
        listOf(shell.findViewById<View>(R.id.titleRow), appSub, historyPanel, sectionRow, filterBar, status, content)
            .forEach { it.setPaddingRelative(pagePad, it.paddingTop, pagePad, it.paddingBottom) }
        courseSwitch.layoutParams = (courseSwitch.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = pagePad
            marginEnd = pagePad
        }
        nowPlaying.layoutParams = nowPlaying.layoutParams.apply {
            width = resources.getDimensionPixelSize(R.dimen.mini_player_width)
        }
        shell.requestLayout()
        if (railVisible == hasWideWindow) return

        railVisible = hasWideWindow
        val tabBar = (if (railVisible) shell else navigationRail).findViewById<LinearLayout>(R.id.tabBar)
        (tabBar.parent as ViewGroup).removeView(tabBar)
        val tabItems = tabBar.findViewById<LinearLayout>(R.id.tabItems)
        tabItems.orientation = if (railVisible) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        tabBar.findViewById<View>(R.id.tabDivider).visibility = if (railVisible) View.GONE else View.VISIBLE
        navRoots.forEach { tab ->
            tab.layoutParams = if (railVisible) LinearLayout.LayoutParams(-1, dp(64))
            else LinearLayout.LayoutParams(0, -2, 1f)
        }
        if (railVisible) {
            navigationRail.addView(tabBar, FrameLayout.LayoutParams(-1, -2))
            navigationRail.visibility = View.VISIBLE
        } else {
            navigationRail.visibility = View.GONE
            shell.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        redrawFilteredRows?.invoke()
    }

    private fun sizeSheet(dialog: Dialog, height: (Int) -> Int) {
        sheetHeights[dialog] = height
        dialog.window?.sizeBottomSheet(this, height)
    }

    private fun sheetMaxHeight(available: Int): Int =
        minOf((available * 0.70f).toInt(), dp(640), available - dp(24)).coerceAtLeast(1)

    private fun refresh(force: Boolean = false, cachedFirst: Boolean = false) {
        if ((screen != Screen.HOME || folders.isNotEmpty()) && homeCheckInFlight) {
            homeCheckToken++
            homeCheckInFlight = false
        }
        if (screen == Screen.FAVORITES) {
            refreshFavoritesPage()
            return
        }
        if (screen == Screen.DISK) {
            refreshDisk()
            return
        }
        requestId++
        clearHomeUpdatePrompt()
        homeDisplayedEntries = emptyList()
        redrawFilteredRows = null
        showingHomeDirectory = false
        filterCount.text = ""
        content.removeAllViews()
        sortButton.visibility = View.GONE
        showAvailableMediaFilters(emptySet())
        styleContent()
        val earlyParent = folders.lastOrNull()
        val homeSourceKey = if (isContactSource(gid)) "roots:$gid" else "recent:$gid"
        val earlyKey = if (screen == Screen.HOME && earlyParent == null && gid.isNotEmpty()) homeSourceKey else ""
        val earlyCached = if (!hasActiveSession() && (checkingAccount || !ready) && earlyKey.isNotEmpty()) {
            courseCache.readAll(earlyKey)
        } else {
            emptyList()
        }
        if (earlyCached.isNotEmpty()) {
            updateNowPlaying()
            updateNav()
            filterBar.visibility = View.GONE
            sectionRow.visibility = View.VISIBLE
            renderHomeSections(allowUnverified = true)
            renderCourseSwitch()
            title.text = "首页"
            appSub.text = ""
            cacheNote = "缓存内容 · 上次同步 ${courseCache.syncLabel(earlyKey)}"
            display(earlyCached)
            status.text = cacheNote
            return
        }
        if (!hasActiveSession()) {
            val quark = currentPageProvider() == SourceProvider.QUARK
            val providerName = if (quark) "夸克网盘" else "百度网盘"
            historyPanel.removeAllViews()
            filterBar.visibility = View.GONE
            title.text = when (screen) {
                Screen.SETTINGS -> "设置"
                Screen.DISK -> "我的网盘"
                Screen.GROUPS -> groupTabLabel()
                else -> "首页"
            }
            appSub.text = "连接${providerName}后查看内容"
            courseEntries.clear()
            renderCourseSwitch()
            updateNowPlaying()
            updateNav()
            status.text = when {
                quark -> "请先登录夸克网盘"
                checkingAccount || !ready -> "正在连接网盘并确认账号…"
                else -> "无法确认网盘账号，请登录后刷新。"
            }
            if (screen == Screen.SETTINGS) {
                renderSettings()
                return
            }
            content.addView(headAction("登录$providerName") {
                if (quark) quarkLoginLauncher.launch(Intent(this, QuarkDriveActivity::class.java))
                else showLoginDialog()
            })
            return
        }
        updateNowPlaying()
        val parent = folders.lastOrNull()
        val cacheKey = when {
            parent != null -> "dir:${parent.gid}:${parent.messageId}:${parent.fileId}"
            screen == Screen.FAVORITES || screen == Screen.SETTINGS -> ""
            screen == Screen.HOME -> homeSourceKey
            else -> "roots:$gid"
        }
        val key = parent?.let { "${it.gid}:${it.messageId}:${it.fileId}" }.orEmpty()
        if (key != courseParentKey) {
            courseParentKey = key
            courseEntries.clear()
        }
        updateNav()
        updateFilterChips()
        // 由 addFilteredCourseRows 按实际内容决定是否显示；空列表/空目录不露出孤立筛选条。
        filterBar.visibility = View.GONE
        title.text = when (screen) {
            Screen.FAVORITES -> "收藏"
            Screen.SETTINGS -> "设置"
            Screen.DISK -> parent?.name ?: "我的网盘"
            Screen.GROUPS -> parent?.name ?: groupTabLabel()
            else -> parent?.name ?: "首页"
        }
        appSub.text = when {
            parent != null -> "第 ${folders.size} 级目录"
            screen == Screen.FAVORITES -> "仅保存在本机"
            screen == Screen.SETTINGS -> "外观、备份与本机数据"
            screen == Screen.DISK -> "个人文件 · 已转存内容可直接播放"
            screen == Screen.GROUPS -> when {
                isContactSource(gid) -> "联系人文件库"
                currentPageProvider() == SourceProvider.QUARK -> "夸克网盘 · 监控目录"
                else -> "群组文件库"
            }
            screen == Screen.HOME -> verifiedAccountName.trim().takeIf { it.isNotEmpty() }?.let { "你好，$it" } ?: ""
            else -> ""
        }
        renderCrumbs()
        renderHomeSections()
        renderCourseSwitch()
        if ((screen == Screen.HOME || screen == Screen.GROUPS) && parent == null) {
            renderSourcePicker()
        }
        val cached = if (cacheKey.isNotEmpty()) courseCache.readAll(cacheKey) else emptyList()
        val contactHome = screen == Screen.HOME && parent == null && isContactSource(gid)
        val homeRootKey = if (screen == Screen.HOME && !contactHome && parent == null && gid.isNotEmpty()) "roots:$gid" else null
        showingHomeDirectory = contactHome
        if (contactHome) sectionTitle.text = "群组"
        val recentPage = homeRootKey?.let { courseCache.readPage(cacheKey, 1) }
        val recentIsEmpty = recentPage?.let { it.latestEmpty || it.entries.isEmpty() } == true
        cacheNote = if (cached.isNotEmpty()) {
            "缓存内容 · 上次同步 ${courseCache.syncLabel(cacheKey)}"
        } else ""
        if (homeRootKey != null && recentIsEmpty) {
            renderHomeDirectory(homeRootKey)
        } else if (cached.isNotEmpty() || (cachedFirst && courseCache.readPage(cacheKey, 1) != null)) {
            if (parent != null) {
                courseEntries.clear()
                courseEntries.addAll(cached)
                renderCourse()
            } else {
                display(cached)
            }
        } else if (parent != null) {
            content.addView(headAction("← 返回上一级") {
                returnToFolder(folders.lastIndex)
            })
        }
        when {
            screen == Screen.FAVORITES && parent == null -> renderFavorites()
            screen == Screen.SETTINGS -> renderSettings()
            parent == null && gid.isEmpty() -> {
                status.text = "请从网盘勾选共享来源"
            }
            cachedFirst && courseCache.readPage(cacheKey, 1) != null -> {
                when {
                    parent != null -> renderChildMore(parent, cacheKey)
                    showingHomeDirectory && homeRootKey != null -> {
                        loadHomeDirectory(homeRootKey, force = false)
                    }
                    screen == Screen.GROUPS || contactHome -> renderRootMore(cacheKey)
                }
            }
            !hasActiveSession() -> {
                status.text = if (cached.isNotEmpty()) "$cacheNote · 正在连接网盘"
                else "正在连接网盘。如果列表无法加载，请先登录。"
            }
            cacheKey.isNotEmpty() && parent == null && !force && screen == Screen.GROUPS &&
                courseCache.isFresh(cacheKey, CACHE_TTL_MS) &&
                courseCache.readPage(cacheKey, 1)?.nextCursor != null -> {
                status.text = "$cacheNote · 30 分钟内已检查"
                renderRootMore(cacheKey)
            }
            parent != null -> if (providerOfSourceId(parent.gid) == SourceProvider.QUARK) {
                loadQuarkChildren(parent, 1, cacheKey = cacheKey)
            } else {
                loadChildren(parent, 1, cacheKey = cacheKey)
            }
            homeRootKey != null -> {
                if (!force && homeCheckInFlight) {
                    status.text = if (cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在检查最近更新…"
                } else if (!force && System.currentTimeMillis() - lastHomeCheckAt < HOME_RECHECK_COOLDOWN_MS) {
                    if (recentIsEmpty) loadHomeDirectory(homeRootKey, force = false)
                    else status.text = if (cacheNote.isNotEmpty()) "$cacheNote · 刚刚已检查" else ""
                } else {
                    startHomeCheck(force, cacheKey, homeRootKey, cached, recentIsEmpty)
                }
            }
            else -> if (providerOfSourceId(gid) == SourceProvider.QUARK) {
                loadQuarkRoots(cacheKey)
            } else {
                loadRoots(cacheKey)
            }
        }
    }

    private fun refreshFavoritesPage() {
        requestId++
        clearHomeUpdatePrompt()
        updateNowPlaying()
        updateNav()
        title.text = "收藏"
        title.setTextSize(30f)
        appSub.visibility = View.GONE
        historyPanel.removeAllViews()
        sectionRow.visibility = View.GONE
        filterBar.visibility = View.GONE
        sortButton.visibility = View.GONE
        crumbsScroll.visibility = View.GONE
        redrawFilteredRows = null
        content.removeAllViews()
        status.text = ""
        if (!hasVerifiedAccount()) {
            content.addView(headAction("登录百度网盘") { showLoginDialog() })
            return
        }
        renderFavorites()
    }

    /** 网盘页只切网盘；首页/群组切换后自动选中该网盘的来源目录并列出内容。 */
    private fun chooseProvider() {
        val isDisk = screen == Screen.DISK
        val current = if (isDisk) diskProvider else providerOfSourceId(gid)
        val baiduReady = hasVerifiedAccount()
        val quarkReady = QuarkPersonalDrive.hasLoginCookie(
            CookieManager.getInstance().getCookie(QuarkPersonalDrive.HOME),
        )
        showChoiceSheet(
            title = "切换网盘",
            subtitle = if (isDisk) "只切换网盘，不影响首页与群组的来源" else "切换后自动选中该网盘的来源目录",
            choices = listOf(
                SheetChoice(
                    "百度网盘",
                    meta = if (baiduReady) "已登录" else "未登录",
                    checked = current == SourceProvider.BAIDU,
                    tag = SourceProvider.BAIDU,
                ),
                SheetChoice(
                    "夸克网盘",
                    meta = if (quarkReady) "已登录" else "未登录",
                    checked = current == SourceProvider.QUARK,
                    tag = SourceProvider.QUARK,
                ),
            ),
            onSelect = { choice ->
                val target = choice.tag as? SourceProvider
                if (target != null && target != current) {
                    if (isDisk) applyDiskProvider(target) else applySourceProvider(target)
                }
            },
        )
    }

    /** 首页/群组：切到指定网盘，自动选该网盘已配置的来源（优先上次用的），清空目录栈并刷新。 */
    private fun applySourceProvider(target: SourceProvider) {
        val sources = shareSources().filter { it.provider == target }
        if (sources.isEmpty()) {
            toast(if (target == SourceProvider.QUARK) "还没有夸克监控目录，请先添加" else "还没有百度来源，请先添加")
            if (target == SourceProvider.QUARK) manageQuarkSources() else manageBaiduSources()
            return
        }
        val prefs = getSharedPreferences("account_scope", MODE_PRIVATE)
        val memoryKey = AccountScope.key("source_last_${target.name.lowercase()}")
        val remembered = prefs.getString(memoryKey, "").orEmpty()
        val pick = sources.firstOrNull { it.id == sourceId(target, remembered) } ?: sources.first()
        selectSource(pick)
    }

    private fun applyDiskProvider(target: SourceProvider) {
        diskProvider = target
        getSharedPreferences("account_scope", MODE_PRIVATE).edit()
            .putString(AccountScope.key("disk_provider"), target.name)
            .apply()
        folders.clear()
        courseEntries.clear()
        courseParentKey = ""
        tabFolders[Screen.DISK] = emptyList()
        refreshDisk()
    }

    private fun refreshDisk() {
        requestId++
        clearHomeUpdatePrompt()
        homeDisplayedEntries = emptyList()
        redrawFilteredRows = null
        showingHomeDirectory = false
        filterCount.text = ""
        content.removeAllViews()
        historyPanel.removeAllViews()
        status.text = ""
        sortButton.visibility = View.GONE
        showAvailableMediaFilters(emptySet())
        styleContent()
        updateNowPlaying()
        updateNav()
        if (!hasActiveSession()) {
            filterBar.visibility = View.GONE
            sectionRow.visibility = View.GONE
            title.text = "我的网盘"
            if (diskProvider == SourceProvider.QUARK) {
                appSub.text = "连接夸克网盘后浏览我的网盘"
                content.addView(headAction("登录夸克网盘") {
                    quarkLoginLauncher.launch(Intent(this, QuarkDriveActivity::class.java))
                })
            } else {
                appSub.text = "连接百度网盘后浏览我的网盘"
                content.addView(headAction("登录百度网盘") { showLoginDialog() })
            }
            return
        }
        val parent = folders.lastOrNull()
        val cacheKey = when {
            diskProvider == SourceProvider.QUARK ->
                if (parent == null) "disk:quark:roots" else "disk:quark:dir:${parent.fileId}"
            parent == null -> "disk:roots"
            else -> "disk:dir:${parent.fileId}"
        }
        val key = parent?.let { "disk:${it.fileId}" }.orEmpty()
        if (key != courseParentKey) {
            courseParentKey = key
            courseEntries.clear()
        }
        updateFilterChips()
        filterBar.visibility = View.GONE
        title.text = parent?.name ?: "我的网盘"
        appSub.text = if (parent == null) "个人文件 · 已转存内容可直接播放" else "第 ${folders.size} 级目录"
        renderCrumbs()
        renderCourseSwitch()
        val cached = courseCache.readAll(cacheKey)
        cacheNote = if (cached.isNotEmpty()) {
            "缓存内容 · 上次同步 ${courseCache.syncLabel(cacheKey)}"
        } else ""
        if (cached.isNotEmpty()) {
            courseEntries.clear()
            courseEntries.addAll(cached)
            renderCourse()
            status.text = cacheNote
        } else if (parent != null) {
            content.addView(headAction("← 返回上一级") {
                returnToFolder(folders.lastIndex)
            })
        }
        if (diskProvider == SourceProvider.QUARK) {
            loadQuarkDiskPage(parent?.fileId ?: QuarkPersonalDrive.ROOT, parent?.path ?: "/", cacheKey)
        } else {
            loadDiskPage(parent?.path ?: "/", 1, cacheKey)
        }
    }

    /**
     * 夸克网盘列表：首版一次取 200 条、不分页、不写缓存。
     * 目录下钻沿用 folders 栈，父目录 fid 直接作为 pdir_fid。
     */
    private fun loadQuarkDiskPage(dirFid: String, parentPath: String, cacheKey: String) {
        status.text = "正在读取夸克网盘…"
        val request = ++requestId
        Thread {
            val result = runCatching {
                val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
                val page = session.execute(session.list(dirFid, 1, 200))
                page.items
                    .sortedWith(compareByDescending<QuarkFile> { it.directory }.thenBy { it.name })
                    .map { quarkEntry(it, DISK_SOURCE_ID, EntryOrigin.DISK, parentPath) }
            }
            runOnUiThread {
                if (request != requestId || isFinishing || isDestroyed) return@runOnUiThread
                result.onFailure { error ->
                    status.text = "无法读取夸克网盘：${error.message ?: "未知错误"}"
                    if (courseEntries.isEmpty()) {
                        content.addView(headAction("重新登录夸克网盘") {
                            quarkLoginLauncher.launch(Intent(this, QuarkDriveActivity::class.java))
                        })
                    }
                }.onSuccess { entries ->
                    courseEntries.clear()
                    courseEntries.addAll(entries)
                    renderCourse()
                    status.text = if (entries.isEmpty()) "此目录暂无内容" else ""
                }
            }
        }.start()
    }

    private fun ensureBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit) {
        if (bdstoken.isNotEmpty()) {
            onReady(bdstoken)
            return
        }
        val account = verifiedAccount
        load(
            PanApi.groupListSession(), lane = RequestLane.LIST, statusHint = "正在连接我的网盘…",
            onCancelled = onFailure, onFailure = onFailure,
        ) { json ->
            val session = json.getJSONObject("result")
            if (session.optString("uk") != account) {
                verifyAndRefresh()
                onFailure()
                return@load
            }
            val token = session.optString("bdstoken")
            if (token.isBlank() || session.isNull("bdstoken")) {
                status.text = "网盘会话参数缺失，请重新登录。"
                onFailure()
            } else {
                bdstoken = token
                onReady(token)
            }
        }
    }

    private fun loadDiskPage(dir: String, page: Int, cacheKey: String, previousButton: View? = null) {
        val expectedParentKey = courseParentKey
        ensureBdstoken(
            onReady = { token ->
                val request = BaiduPersonalDrive(token).list(dir, page, 100)
                load(
                    request.url, lane = RequestLane.COURSE,
                    statusHint = if (page == 1 && courseCache.readAll(cacheKey).isEmpty()) {
                        "正在读取我的网盘…"
                    } else null,
                    onCancelled = { previousButton?.isEnabled = true },
                    onFailure = {
                        previousButton?.isEnabled = true
                        if (page == 1 && courseCache.readAll(cacheKey).isEmpty()) {
                            status.text = "读取我的网盘失败，请刷新重试。"
                        }
                    },
                ) { json ->
                    if (screen != Screen.DISK || courseParentKey != expectedParentKey) return@load
                    val (entries, hasMore) = request.decode(json)
                    val changed = courseCache.writeIfChanged(
                        cacheKey, page, entries, if (hasMore) "more" else "",
                    )
                    if (changed || !hasMore) courseCache.clearAfter(cacheKey, page)
                    diskIndexCache = null
                    val all = courseCache.readAll(cacheKey)
                    courseEntries.clear()
                    courseEntries.addAll(all)
                    cacheNote = "已同步 ${courseCache.syncLabel(cacheKey)}"
                    renderCourse()
                    if (all.isEmpty()) content.addView(label("此目录暂无内容", 12f))
                    filterBar.visibility = if (hasFilterOptions(all)) View.VISIBLE else View.GONE
                    renderDiskMore(dir, cacheKey)
                    status.text = if (!changed && page == 1) "$cacheNote · 无更新" else cacheNote
                }
            },
            onFailure = {
                previousButton?.isEnabled = true
                if (courseCache.readAll(cacheKey).isEmpty()) status.text = "无法读取我的网盘，请登录后重试。"
            },
        )
    }

    private fun renderDiskMore(dir: String, cacheKey: String) {
        val (page, cached) = courseCache.lastPage(cacheKey) ?: return
        if (cached.nextCursor != "more") return
        val more = headAction("加载更多") {}
        more.setBackgroundResource(R.drawable.bg_load_more)
        more.setTextColor(color(R.color.ink3))
        more.setOnClickListener {
            more.isEnabled = false
            loadDiskPage(dir, page + 1, cacheKey, more)
        }
        content.addView(more)
    }

    private fun renderHomeFavorites(allowUnverified: Boolean = false) {
        if (screen != Screen.HOME || folders.isNotEmpty()) return
        if (!allowUnverified && !hasVerifiedAccount()) return
        val items = favorites.all()
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = if (items.isEmpty()) "收藏" else "收藏 ${items.size}"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(color(R.color.ink))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (items.isNotEmpty()) {
                addView(TextView(this@MainActivity).apply {
                    text = "查看全部"
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(44)
                    setPadding(dp(8), 0, 0, 0)
                    setTextColor(color(R.color.accentInk))
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    isFocusable = true
                    setOnClickListener { openFavorites() }
                })
            }
        }
        historyPanel.addView(header)
        if (items.isEmpty()) {
            historyPanel.addView(TextView(this@MainActivity).apply {
                text = "还没有收藏，在目录右侧点星标"
                textSize = 11.5f
                setTextColor(color(R.color.ink3))
                setPadding(0, dp(4), 0, dp(8))
            })
            return
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(strip)
        }
        items.take(3).forEach { entry ->
            strip.addView(favoriteCard(entry))
        }
        historyPanel.addView(scroll)
    }

    private fun favoriteCard(entry: PanEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(9), dp(8), dp(9), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(280), -2).apply { marginEnd = dp(8) }
            isClickable = true
            setOnClickListener { openFavoriteEntry(entry) }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setPadding(dp(7), dp(7), dp(7), dp(7))
            paintMediaIcon(this, PanApi.kindOf(entry), entry.directory)
        }
        val copy = column().apply {
            setPadding(dp(9), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = entry.name
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.ink))
            })
            addView(TextView(this@MainActivity).apply {
                text = if (entry.directory) "目录 · ${entrySourceName(entry)}"
                else "${mediaKindLabel(PanApi.kindOf(entry), false, formatOf(entry))} · ${entrySourceName(entry)}"
                textSize = 10.5f
                isSingleLine = true
                setTextColor(color(R.color.ink3))
                setPadding(0, dp(2), 0, 0)
            })
        }
        card.addView(icon)
        card.addView(copy)
        return card
    }

    private fun entrySourceName(entry: PanEntry): String =
        if (entry.isDisk) "我的网盘" else shareSources().firstOrNull { it.id == entry.gid }?.name ?: "共享文件库"

    private fun openFavoriteEntry(entry: PanEntry) {
        if (entry.directory) {
            openSourceDirectory(entry)
        } else {
            openEntry(entry, listOf(entry))
        }
    }

    private fun openSourceDirectory(entry: PanEntry) {
        clearSelectionIfActive()
        val target = when {
            entry.isDisk -> Screen.DISK
            entry.isContact -> Screen.HOME
            else -> Screen.GROUPS
        }
        if (!entry.isDisk) {
            if (shareSources().none { it.id == entry.gid }) {
                toast("共享来源已移除，请重新关联")
                return
            }
            if (entry.gid != gid) setActiveGroup(entry.gid)
        }
        tabFolders[target] = listOf(entry)
        tabScroll[target] = 0
        screen = target
        recent = false
        transferredOnly = false
        folders.clear()
        folders.add(entry)
        courseEntries.clear()
        courseParentKey = ""
        contentScroll.scrollTo(0, 0)
        refresh()
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
        if (providerOfSourceId(gid) == SourceProvider.QUARK) {
            startQuarkHomeCheck(force, cacheKey, homeRootKey, cached, recentIsEmpty, checkToken)
            return
        }
        load(
            PanApi.recent(gid),
            statusHint = "正在检查最近更新…",
            onCancelled = {
                if (checkToken == homeCheckToken) homeCheckInFlight = false
            },
            onFailure = {
                if (checkToken == homeCheckToken) homeCheckInFlight = false
                if (recentIsEmpty) {
                    loadHomeDirectory(homeRootKey, force = false)
                } else if (cached.isEmpty()) {
                    status.text = "无法加载群内更新，请稍后重试"
                    content.addView(headAction("打开文件库") { showScreen(Screen.GROUPS) })
                } else cacheFailure(cacheKey)
            },
        ) { json ->
            applyHomeRecentEntries(PanApi.parseRecent(json), cacheKey, homeRootKey, force, checkToken)
        }
    }

    /**
     * 夸克没有 recent 接口：直接列监控目录第一层，按 mtime 客户端倒序取 Top N。
     * 结果与百度侧走同一个 [applyHomeRecentEntries]，渲染路径完全复用。
     */
    private fun startQuarkHomeCheck(
        force: Boolean,
        cacheKey: String,
        homeRootKey: String,
        cached: List<PanEntry>,
        recentIsEmpty: Boolean,
        checkToken: Int,
    ) {
        val sourceId = gid
        Thread {
            val result = runCatching {
                val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
                val page = session.execute(session.list(rawSourceId(sourceId), 1, 50))
                page.items
                    .sortedByDescending { it.mtime }
                    .take(50)
                    .map { quarkEntry(it, sourceId) }
            }
            runOnUiThread {
                if (checkToken != homeCheckToken || isDestroyed || isFinishing) return@runOnUiThread
                result.onFailure {
                    homeCheckInFlight = false
                    if (recentIsEmpty) {
                        loadHomeDirectory(homeRootKey, force = false)
                    } else if (cached.isEmpty()) {
                        status.text = "无法加载监控目录更新，请稍后重试"
                        content.addView(headAction("打开文件库") { showScreen(Screen.GROUPS) })
                    } else cacheFailure(cacheKey)
                }.onSuccess { entries ->
                    applyHomeRecentEntries(entries, cacheKey, homeRootKey, force, checkToken)
                }
            }
        }.start()
    }

    private fun maybeCheckHomeInBackground() {
        if (!hasActiveSession() || screen != Screen.HOME || folders.isNotEmpty()) return
        if (isContactSource(gid) || homeCheckInFlight || pendingHomeUpdate != null) return
        if (System.currentTimeMillis() - lastHomeCheckAt < HOME_RECHECK_COOLDOWN_MS) return
        val cacheKey = "recent:$gid"
        val cached = courseCache.readAll(cacheKey)
        if (cached.isEmpty()) return
        val recentPage = courseCache.readPage(cacheKey, 1)
        val recentIsEmpty = recentPage?.let { it.latestEmpty || it.entries.isEmpty() } == true
        startHomeCheck(false, cacheKey, "roots:$gid", cached, recentIsEmpty)
    }

    private fun applyHomeRecentEntries(
        fresh: List<PanEntry>,
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
        val changed = courseCache.writeNewFirst(cacheKey, 1, fresh)
        if (fresh.isEmpty()) {
            homeDisplayedEntries = emptyList()
            loadHomeDirectory(homeRootKey, force)
            return
        }
        val wasShowingDirectory = showingHomeDirectory
        showingHomeDirectory = false
        cacheNote = "已同步 ${courseCache.syncLabel(cacheKey)}"
        val merged = courseCache.readAll(cacheKey)
        val newEntries = if (previous.isNotEmpty()) {
            fresh.filter { entryKey(it) !in previousIds }
        } else {
            emptyList()
        }
        val visibleNew = newEntries.filter(::matchesHomeFilter)
        if (wasShowingDirectory || contentScroll.scrollY <= dp(6)) {
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

    private fun matchesHomeFilter(entry: PanEntry): Boolean =
        mediaFilter.includes(PanApi.kindOf(entry))

    private fun loadHomeDirectory(key: String, force: Boolean) {
        if (!showingHomeDirectory) renderHomeDirectory(key)
        else status.text = if (courseCache.readPage(key, 1)?.entries?.isEmpty() == true) "文件库暂无内容" else ""
        if (!force && courseCache.isFresh(key, CACHE_TTL_MS) && courseCache.readPage(key, 1)?.nextCursor != null) {
            renderRootMore(key)
        } else if (providerOfSourceId(gid) == SourceProvider.QUARK) {
            loadQuarkRoots(key)
        } else {
            loadRoots(key)
        }
    }

    private fun renderHomeDirectory(key: String) {
        showingHomeDirectory = true
        redrawFilteredRows = null
        content.removeAllViews()
        renderSourcePicker()
        sectionTitle.text = "群组"
        val entries = courseCache.readAll(key)
        addFilteredCourseRows(entries, sortable = false)
        status.text = if (courseCache.readPage(key, 1) != null && entries.isEmpty()) "文件库暂无内容" else ""
    }

    private fun loadRoots(key: String, page: Int = 1, cursor: String = "", previousButton: View? = null) {
        val group = gid
        val homeDirectory = showingHomeDirectory
        load(
            PanApi.roots(group, cursor),
            post = isContactSource(group),
            statusHint = if (homeDirectory) "正在读取文件库…"
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
            val next = nextRootCursor(json, group, cursor)
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
            val pagination = if (isContactSource(group)) json.getJSONObject("records") else json
            if (next.isEmpty() && PanApi.hasMore(pagination)) {
                status.text = "网盘未返回可用的分页游标，请刷新后重试。"
            }
        }
    }

    private fun nextRootCursor(json: JSONObject, sourceId: String, cursor: String): String {
        if (isContactSource(sourceId)) {
            val records = json.getJSONObject("records")
            return records.optString("cursor", "").takeIf {
                PanApi.hasMore(records) && !records.isNull("cursor") && it != cursor
            }.orEmpty()
        }
        return json.optString("last_msg_time").takeIf {
            val time = it.toLongOrNull()
            PanApi.hasMore(json) && time != null && time > 0 &&
                time < (cursor.toLongOrNull() ?: Long.MAX_VALUE)
        }.orEmpty()
    }

    /**
     * 夸克来源的目录列表（群组/首页的「监控目录」）。走 QuarkPersonalDrive 原生 HTTP，
     * 结果转 PanEntry 后复用百度侧的缓存/渲染（CourseCache/display/renderCourse）。
     */
    private fun loadQuarkRoots(key: String, page: Int = 1, previousButton: View? = null) {
        val sourceId = gid
        if (providerOfSourceId(sourceId) != SourceProvider.QUARK) {
            loadRoots(key)
            return
        }
        status.text = if (page == 1 && cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在读取夸克目录…"
        val token = ++requestId
        Thread {
            val result = runCatching {
                val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
                session.execute(session.list(rawSourceId(sourceId), page, 100))
            }
            runOnUiThread {
                previousButton?.isEnabled = true
                if (token != requestId) return@runOnUiThread
                result.onFailure { error ->
                    if (page == 1) cacheFailure(key)
                    status.text = when (error) {
                        is QuarkLoginRequired -> "请先登录夸克网盘"
                        is IllegalStateException -> error.message ?: "读取夸克目录失败"
                        else -> "读取夸克目录失败，请稍后重试"
                    }
                    if (page == 1) renderRootMore(key)
                }.onSuccess { pageData ->
                    val entries = pageData.items.map { quarkEntry(it, sourceId) }
                    courseCache.writeIfChanged(key, page, entries, if (pageData.hasMore) "more" else "")
                    if (!pageData.hasMore) courseCache.clearAfter(key, page)
                    cacheNote = "已同步 ${courseCache.syncLabel(key)}"
                    if (showingHomeDirectory) renderHomeDirectory(key)
                    else {
                        content.removeAllViews()
                        renderSourcePicker()
                        display(courseCache.readAll(key))
                        status.text = cacheNote
                    }
                    renderRootMore(key)
                }
            }
        }.start()
    }

    /** 夸克子目录列表（从监控目录下钻）。 */
    private fun loadQuarkChildren(
        parent: PanEntry,
        page: Int,
        previousButton: View? = null,
        cacheKey: String = "",
    ) {
        val key = cacheKey.ifEmpty { "dir:${parent.gid}:${parent.messageId}:${parent.fileId}" }
        val sourceId = parent.gid
        status.text = if (page == 1 && cacheNote.isNotEmpty()) "$cacheNote · 正在检查更新" else "正在读取…"
        val token = ++requestId
        Thread {
            val result = runCatching {
                val session = QuarkPersonalDrive.openSession() ?: throw QuarkLoginRequired()
                session.execute(session.list(parent.fileId, page, 100))
            }
            runOnUiThread {
                previousButton?.isEnabled = true
                if (token != requestId) return@runOnUiThread
                result.onFailure { error ->
                    if (page == 1) cacheFailure(key)
                    status.text = when (error) {
                        is QuarkLoginRequired -> "请先登录夸克网盘"
                        else -> "读取夸克目录失败，请稍后重试"
                    }
                }.onSuccess { pageData ->
                    val entries = pageData.items.map {
                        quarkEntry(it, sourceId, EntryOrigin.GROUP, parent.path)
                    }
                    val hasMore = pageData.hasMore && entries.isNotEmpty()
                    val changed = courseCache.writeIfChanged(key, page, entries, if (hasMore) "more" else "")
                    if (changed || !hasMore) courseCache.clearAfter(key, page)
                    courseEntries.clear()
                    courseEntries.addAll(courseCache.readAll(key))
                    cacheNote = "已同步 ${courseCache.syncLabel(key)}"
                    renderCourse()
                    if (!changed) status.text = "$cacheNote · 无更新"
                    renderChildMore(parent, key)
                }
            }
        }.start()
    }

    private fun renderRootMore(key: String) {
        val (page, cached) = courseCache.lastPage(key) ?: return
        val cursor = cached.nextCursor?.takeIf { it.isNotEmpty() } ?: return
        val more = headAction("加载更多") {}
        more.setBackgroundResource(R.drawable.bg_load_more)
        more.setTextColor(color(R.color.ink3))
        more.setOnClickListener {
            more.isEnabled = false
            if (providerOfSourceId(gid) == SourceProvider.QUARK) loadQuarkRoots(key, page + 1, more)
            else loadRoots(key, page + 1, cursor, more)
        }
        content.addView(more)
    }

    private fun invalidateAccount() {
        pdfPreviewRef?.close()
        cancelImageLoading()
        accountProbeId++
        courseRequestId++
        automaticCourseLoad = null
        historyRefreshPending = false
        searchSectionRef?.dismiss()
        renewalRequestId++
        homeCheckToken++
        homeCheckInFlight = false
        lastHomeCheckAt = 0L
        renewingPlaybackId = null
        cancelPendingAudioRequest()
        requestId++
        checkingAccount = false
        verifiedAccount = ""
        verifiedAccountName = ""
        verifiedAccountAvatar = ""
        bdstoken = ""
        diskIndexCache = null
        diskSearchFingerprints.clear()
        writeRequestId++
        deleteCheckRequestId++
        resetSectionGuards()
        mediaLinks.dismissAnyCaptcha()
        videoResolver.cancel()
        renewalResolver.cancel()
        videoLoading?.dismiss()
        videoLoading = null
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
            // 无论本次探测是否成功都要清标记，否则残留到后续任意一次 verifyAndRefresh
            // 都会在 confirmed 时突然弹出「管理百度来源」，属于误触发。
            if (pendingAddProvider == SourceProvider.BAIDU) {
                val shouldOpenSources = confirmed
                pendingAddProvider = null
                if (shouldOpenSources) {
                    web.post { if (!isFinishing && !isDestroyed) manageBaiduSources() }
                }
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
                    val result = runCatching {
                        val json = JSONObject(body)
                        if (json.optInt("errno", -1) == 0) json.optJSONObject("result") else null
                    }.getOrNull()
                    val uk = result?.optString("uk").orEmpty()
                    val confirmed = uk.matches(Regex("[0-9]{1,24}"))
                    verifiedAccount = uk.takeIf { confirmed }.orEmpty()
                    verifiedAccountName = if (confirmed) {
                        result?.optString("netdisk_name").orEmpty().ifBlank {
                            result?.optString("username").orEmpty()
                        }
                    } else ""
                    verifiedAccountAvatar = if (confirmed) result?.optString("avatar_url").orEmpty() else ""
                    val changed = confirmed && AccountScope.set(this@MainActivity, uk)
                    if (confirmed) controller?.let {
                        it.sendCustomCommand(AudioPlaybackService.SYNC_ACCOUNT, Bundle.EMPTY)
                        renewBackgroundAudio(it)
                    }
                    if (changed) {
                        videoPage?.close()
                        closeAudioPage()
                        folders.clear()
                        folderPositions.clear()
                        tabFolders.clear()
                        tabScroll.clear()
                        favoritesOrigin = Screen.HOME
                        screen = Screen.HOME
                        recent = true
                        bdstoken = ""
                        diskIndexCache = null
                        diskSearchFingerprints.clear()
                        courseEntries.clear()
                        courseParentKey = ""
                        pendingQueue = pendingQueue?.takeIf { videoToRestore?.account == verifiedAccount }
                        PlaybackQueueState.anchor = null
                        PlaybackQueueState.entries = emptyList()
                        PlaybackQueueState.listKey = null
                        toast("已切换账号，数据按账号隔离")
                        setActiveGroup(readActiveSource())
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
            val hasMore = PanApi.hasMore(json) && entries.isNotEmpty()
            val changed = courseCache.writeIfChanged(key, page, entries, if (hasMore) "more" else "")
            if (changed || !hasMore) courseCache.clearAfter(key, page)
            courseEntries.clear()
            courseEntries.addAll(courseCache.readAll(key))
            cacheNote = "已同步 ${courseCache.syncLabel(key)}"
            renderCourse()
            if (!changed) status.text = "$cacheNote · 无更新"
            renderChildMore(parent, key)
        }
    }

    private fun renderChildMore(parent: PanEntry, key: String) {
        val (page, cached) = courseCache.lastPage(key) ?: return
        if (cached.nextCursor.isNullOrEmpty()) return
        val more = headAction("加载更多") {}
        more.setBackgroundResource(R.drawable.bg_load_more)
        more.setTextColor(color(R.color.ink3))
        more.setOnClickListener {
            more.isEnabled = false
            if (providerOfSourceId(parent.gid) == SourceProvider.QUARK) loadQuarkChildren(parent, page + 1, more, key)
            else loadChildren(parent, page + 1, more, key)
        }
        content.addView(more)
    }

    private fun renderSourcePicker() {
        val name = shareSources().firstOrNull { it.id == gid }?.name
        sourceName.text = name ?: "选择文件库"
        sourceButton.contentDescription = name?.let { "切换文件库，当前为 $it" } ?: "选择文件库"
        homeGroupChip?.text = name ?: "选择文件库"
        homeGroupChip?.contentDescription = name?.let { "切换文件库，当前为 $it" } ?: "选择文件库"
    }

    private fun display(entries: List<PanEntry>) {
        if (screen == Screen.HOME && folders.isEmpty()) homeDisplayedEntries = entries
        status.text = if (entries.isEmpty()) "文件库暂无内容" else cacheNote
        sectionTitle.text = when {
            screen == Screen.HOME -> "群组"
            else -> "文件"
        }
        addFilteredCourseRows(entries, sortable = screen == Screen.GROUPS)
    }

    private fun renderCourse() {
        status.text = if (courseEntries.isEmpty()) "没有条目" else cacheNote
        sectionTitle.text = "文件"
        content.removeAllViews()
        addFilteredCourseRows(courseEntries.toList(), sortable = screen != Screen.HOME)
    }

    private fun addFilteredCourseRows(entries: List<PanEntry>, sortable: Boolean) {
        val home = screen == Screen.HOME
        val transferred = if (entries.any { !it.isDisk }) transferredIndex() else emptySet()
        val transferredCount = entries.count { matchesTransferred(it, transferred) }
        if (home || transferredCount == 0) transferredOnly = false
        transferredChip.visibility = if (!home && transferredCount > 0) View.VISIBLE else View.GONE
        transferredChip.text = "已转存 $transferredCount"
        transferredChip.contentDescription = "只看已转存，共 $transferredCount 项"
        val kinds = entries.map(PanApi::kindOf).toSet()
        val showFilters = if (home) {
            MediaFilter.entries.any { it != MediaFilter.ALL && kinds.any(it::includes) }
        } else {
            kinds.size >= 2 || transferredCount > 0
        }
        filterBar.visibility = if (showFilters) View.VISIBLE else View.GONE
        sortButton.text = courseOrder.label
        showAvailableMediaFilters(kinds)
        if (mediaFilter != MediaFilter.ALL && kinds.none(mediaFilter::includes)) {
            mediaFilter = MediaFilter.ALL
        }
        val rows = column()
        fun renderRows() {
            val matching = entries.filter {
                mediaFilter.includes(PanApi.kindOf(it)) &&
                    (!transferredOnly || matchesTransferred(it, transferred))
            }
            updateFilterChips()
            val ordered = if (sortable) orderCourses(matching, courseOrder) else matching
            filterCount.text = "共 ${ordered.size} 项"
            lastRenderedEntries = ordered
            sortButton.visibility = if (sortable && entries.isNotEmpty() && !selectionMode) View.VISIBLE else View.GONE
            btnSelect.visibility = if (!selectionMode && sortable && ordered.isNotEmpty()) View.VISIBLE else View.GONE
            rows.removeAllViews()
            extendPlaybackQueue(ordered)
            if (ordered.isEmpty() && entries.isNotEmpty()) rows.addView(label("当前已加载的内容中没有匹配项", 12f))
            addCourseRows(rows, ordered, transferred)
            renderCourseSwitch()
        }
        content.addView(rows)
        redrawFilteredRows = ::renderRows
        renderRows()
    }

    private fun showCourseSort() {
        val options = CourseOrder.entries
        showChoiceSheet(
            title = "排序",
            choices = options.map { SheetChoice(it.label, checked = it == courseOrder, tag = it) },
            onSelect = { choice ->
                courseOrder = choice.tag as CourseOrder
                ThemeStore.setCourseOrder(this, courseOrder.name)
                sortButton.text = courseOrder.label
                redrawFilteredRows?.invoke()
            },
        )
    }

    private fun playbackOrderKey(entry: PanEntry, listKey: String?): String? {
        if (entry.isDisk) return playbackPathOrderKey(entry)
        val directory = directoryCacheKey(entry, listKey) ?: courseCache.directoryContaining(entry)
        return directory?.let { AccountScope.key(it) } ?: playbackPathOrderKey(entry)
    }

    private fun playbackPathOrderKey(entry: PanEntry): String? {
        val path = PanApi.readablePath(entry.path)
        if (!path.startsWith('/')) return null
        return AccountScope.key("path:${entry.origin}:${entry.sourceId}:${path.substringBeforeLast('/')}")
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
        val queueKey = when {
            key == "disk:roots" -> "D:root"
            key.startsWith("disk:dir:") -> "D:${key.removePrefix("disk:dir:")}"
            key.startsWith("roots:") -> "G:${entry.gid}"
            key.startsWith("dir:") -> "G:${key.removePrefix("dir:")}"
            else -> if (entry.isDisk) "D:root" else "G:${entry.gid}"
        }
        return queueKey to orderCourses(media, playbackOrder(entry, queueKey))
    }

    private fun nextDirectoryPage(entry: PanEntry, listKey: String?): String? {
        val key = directoryCacheKey(entry, listKey) ?: return null
        if (entry.isDisk || key.startsWith("disk:")) return null
        val (page, cached) = courseCache.lastPage(key) ?: return null
        if (key.startsWith("roots:")) {
            val cursor = cached.nextCursor?.takeIf { it.isNotEmpty() } ?: return null
            return PanApi.roots(entry.gid, cursor)
        }
        if (cached.nextCursor != "more" && (cached.nextCursor != null || cached.entries.size < 100)) return null
        val directoryId = key.substringAfterLast(':')
        return PanApi.children(entry, page + 1, directoryId)
    }

    private fun loadMoreCourses(
        entry: PanEntry,
        listKey: String?,
        isCurrentCourse: () -> Boolean = { PlaybackQueueState.anchor?.let { sameEntry(it, entry) } == true },
        done: (List<PanEntry>?) -> Unit,
    ) {
        if (entry.provider == SourceProvider.QUARK) return done(null)
        if (entry.isDisk) {
            loadMoreDiskCourses(entry, listKey, isCurrentCourse, done)
            return
        }
        val key = directoryCacheKey(entry, listKey) ?: return done(null)
        val (page, cached) = courseCache.lastPage(key) ?: return done(null)
        val url = nextDirectoryPage(entry, listKey) ?: return done(null)
        val nested = key.startsWith("dir:")
        var finished = false
        fun complete(entries: List<PanEntry>?) {
            if (finished) return
            finished = true
            done(entries)
        }
        load(
            url, post = nested || entry.isContact, lane = RequestLane.COURSE,
            onCancelled = { complete(null) }, onFailure = { complete(null) },
        ) { json ->
            val files = if (nested) PanApi.parseChildren(json, entry) else PanApi.parseRoots(json, entry.gid)
            val next = if (nested) {
                if (PanApi.hasMore(json) && files.isNotEmpty() && files != cached.entries) "more" else ""
            } else {
                nextRootCursor(json, entry.gid, cached.nextCursor.orEmpty())
            }
            val last = courseCache.lastPage(key)
            if (last?.first == page && last.second == cached) {
                courseCache.writeIfChanged(key, page + 1, files, next)
            } else if (last == null || last.first <= page) {
                complete(null)
                return@load
            }
            if (PlaybackQueueState.listKey != listKey || !isCurrentCourse()) {
                complete(null)
                return@load
            }
            val courses = orderCourses(sameMediaKind(entry, courseCache.readAll(key)), playbackOrder(entry, listKey))
            PlaybackQueueState.entries = courses
            renderCourseSwitch()
            complete(courses)
        }
    }

    private fun loadMoreDiskCourses(
        entry: PanEntry,
        listKey: String?,
        isCurrentCourse: () -> Boolean,
        done: (List<PanEntry>?) -> Unit,
    ) {
        val key = directoryCacheKey(entry, listKey) ?: return done(null)
        if (!key.startsWith("disk:")) return done(null)
        val (page, cached) = courseCache.lastPage(key) ?: return done(null)
        if (cached.nextCursor != "more") return done(null)
        val dir = if (key == "disk:roots") {
            "/"
        } else {
            PanApi.readablePath(entry.path).substringBeforeLast('/').ifEmpty { "/" }
        }
        ensureBdstoken(
            onReady = { token ->
                val request = BaiduPersonalDrive(token).list(dir, page + 1, 100)
                load(
                    request.url, lane = RequestLane.COURSE,
                    onCancelled = { done(null) }, onFailure = { done(null) },
                ) { json ->
                    if (PlaybackQueueState.listKey != listKey || !isCurrentCourse()) {
                        done(null)
                        return@load
                    }
                    val (files, hasMore) = request.decode(json)
                    val last = courseCache.lastPage(key)
                    if (last?.first != page || last.second != cached) {
                        done(null)
                        return@load
                    }
                    courseCache.writeIfChanged(key, page + 1, files, if (hasMore) "more" else "")
                    diskIndexCache = null
                    val courses = orderCourses(
                        sameMediaKind(entry, courseCache.readAll(key)),
                        playbackOrder(entry, listKey),
                    )
                    PlaybackQueueState.entries = courses
                    renderCourseSwitch()
                    done(courses)
                }
            },
            onFailure = { done(null) },
        )
    }

    private fun hasMoreCourses(entry: PanEntry, listKey: String?): Boolean {
        if (entry.provider == SourceProvider.QUARK) return false
        val key = directoryCacheKey(entry, listKey) ?: return false
        val (_, cached) = courseCache.lastPage(key) ?: return false
        return if (entry.isDisk) cached.nextCursor == "more" else nextDirectoryPage(entry, listKey) != null
    }

    private fun directoryCacheKey(entry: PanEntry, listKey: String?): String? = when {
        listKey == "D:root" && entry.isDisk -> "disk:roots"
        listKey?.startsWith("D:") == true && entry.isDisk -> "disk:dir:${listKey.removePrefix("D:")}"
        listKey == "G:${entry.gid}" -> "roots:${entry.gid}"
        listKey?.startsWith("G:${entry.gid}:") == true -> "dir:${listKey.removePrefix("G:")}"
        else -> null
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

    private fun addCourseRows(
        container: LinearLayout,
        entries: List<PanEntry>,
        transferred: Set<String> = emptySet(),
    ) {
        val favoriteKeys = favorites.all().map(::entryKey).toSet()
        val sourceNames = if (screen == Screen.FAVORITES) {
            shareSources().associate { it.id to it.name }
        } else {
            emptyMap()
        }
        if (!hasWideWindow) {
            entries.forEach { container.addView(courseRow(it, entries, transferred, favoriteKeys, sourceNames)) }
            return
        }
        entries.chunked(2).forEach { pair ->
            val line = row()
            pair.forEachIndexed { index, entry ->
                val params = LinearLayout.LayoutParams(0, -2, 1f)
                if (index == 0 && pair.size == 2) params.rightMargin = dp(8)
                line.addView(courseRow(entry, entries, transferred, favoriteKeys, sourceNames), params)
            }
            if (pair.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, -2, 1f))
            container.addView(line)
        }
    }

    private fun courseRow(
        entry: PanEntry,
        entries: List<PanEntry>,
        transferred: Set<String> = emptySet(),
        favoriteKeys: Set<String> = emptySet(),
        sourceNames: Map<String, String> = emptyMap(),
    ): View {
        val format = formatOf(entry)
        val kind = PanApi.kindOf(entry)
        val row = layoutInflater.inflate(R.layout.item_course, content, false)
        row.tag = entryKey(entry)
        val fmt = row.findViewById<ImageView>(R.id.fmt)
        paintMediaIcon(fmt, kind, entry.directory)
        if (screen == Screen.HOME && folders.isEmpty() && !showingHomeDirectory &&
            entry.provider == SourceProvider.BAIDU && !entry.isDisk && !entry.isContact &&
            hasVerifiedAccount() && (kind == MediaKind.IMAGE || kind == MediaKind.VIDEO)
        ) {
            loadRecentThumbnail(entry, fmt)
        }
        row.findViewById<TextView>(R.id.itemTitle).text = entry.name
        // 兼容旧收藏/历史里已持久化的编码路径；无斜杠时不再把整串路径回显到 meta
        val folder = PanApi.readablePath(entry.path)
            .substringBeforeLast('/', "")
            .substringAfterLast('/')
        val size = entry.size.toLongOrNull()?.let { if (it >= 1024 * 1024) "${it / 1024 / 1024} MB" else "" }.orEmpty()
        val kindName = mediaKindLabel(kind, entry.directory, format)
        val source = when {
            screen != Screen.FAVORITES -> ""
            entry.isDisk -> "我的网盘"
            else -> sourceNames[entry.gid] ?: "共享文件库"
        }
        val transferredLabel = if (matchesTransferred(entry, transferred)) "已转存" else ""
        row.findViewById<TextView>(R.id.itemMeta).text =
            listOf(source, transferredLabel, kindName, folder, size).filter { it.isNotEmpty() }.joinToString(" · ")
        val side = row.findViewById<ImageView>(R.id.itemSide)
        side.tag = favoriteTag(entry)
        paintFavorite(side, entryKey(entry) in favoriteKeys)
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
        val check = row.findViewById<ImageView>(R.id.itemCheck)
        if (selectionMode) {
            val key = entryKey(entry)
            check.visibility = View.VISIBLE
            check.isSelected = key in selectedKeys
            check.contentDescription = if (check.isSelected) "已选：${entry.name}" else "未选：${entry.name}"
            row.setOnClickListener { toggleSelection(entry) }
            row.setOnLongClickListener(null)
        } else {
            check.visibility = View.GONE
            row.setOnClickListener { openEntry(entry, entries, currentPlaybackListKey()) }
            row.setOnLongClickListener {
                showEntryActions(entry, entries)
                true
            }
        }
        return row
    }

    // ---- V02-2 批量操作 ----

    private fun visibleSelectionEntries(): List<PanEntry> =
        lastRenderedEntries.filter { entryKey(it) in selectedKeys }

    private fun enterSelection() {
        if (selectionMode || lastRenderedEntries.isEmpty()) return
        selectionMode = true
        selectionScreen = screen
        selectedKeys.clear()
        renderBatchBar()
        redrawFilteredRows?.invoke()
    }

    private fun exitSelection() {
        if (!selectionMode) return
        selectionMode = false
        selectionScreen = null
        selectedKeys.clear()
        renderBatchBar()
        redrawFilteredRows?.invoke()
    }

    /** 离开屏幕/目录时强制退出选择模式（不重绘，交由后续 refresh 完成）。 */
    private fun clearSelectionIfActive() {
        if (!selectionMode) return
        selectionMode = false
        selectionScreen = null
        selectedKeys.clear()
        renderBatchBar()
    }

    private fun toggleSelection(entry: PanEntry) {
        val key = entryKey(entry)
        if (!selectedKeys.add(key)) selectedKeys.remove(key)
        renderBatchBar()
        redrawFilteredRows?.invoke()
    }

    private fun selectAllVisible() {
        val visible = lastRenderedEntries.map(::entryKey)
        if (visible.isEmpty()) return
        if (selectedKeys.containsAll(visible)) selectedKeys.removeAll(visible.toSet())
        else selectedKeys.addAll(visible)
        renderBatchBar()
        redrawFilteredRows?.invoke()
    }

    private fun batchFavoriteSelected() {
        val entries = visibleSelectionEntries()
        if (entries.isEmpty()) {
            toast("请先选择条目")
            return
        }
        val allStarred = entries.all { favorites.has(it) }
        if (allStarred) {
            favorites.removeAll(entries)
            toast("已取消收藏 ${entries.size} 项")
        } else {
            favorites.addAll(entries)
            toast("已收藏 ${entries.size} 项")
        }
        updateFavoritesBadge()
        exitSelection()
    }

    // ===================== P2 网盘文件管理 =====================

    private fun currentDiskDir(): String = folders.lastOrNull()?.path ?: "/"

    private fun currentDiskCacheKey(): String =
        folders.lastOrNull()?.let { "disk:dir:${it.fileId}" } ?: "disk:roots"


    private fun showEntryDetail(entry: PanEntry) {
        val message = buildString {
            append("名称：").append(entry.name).append('\n')
            append("位置：").append(PanApi.readablePath(entry.path)).append('\n')
            append("类型：").append(if (entry.directory) "文件夹" else formatOf(entry)).append('\n')
            if (!entry.directory) {
                append("大小：").append(entry.size.toLongOrNull()?.let { formatSize(it) } ?: "-").append('\n')
            }
            if (entry.md5.isNotEmpty()) append("MD5：").append(entry.md5).append('\n')
            append("来源：").append(entrySourceName(entry))
        }
        AlertDialog.Builder(this).setTitle("详情").setMessage(message).setPositiveButton("关闭", null).show()
    }

    /** 长按条目的操作分发：写操作交给 diskWrites，播放/收藏仍是本页职责。 */
    private fun showEntryActions(entry: PanEntry, entries: List<PanEntry>) {
        val kind = PanApi.kindOf(entry)
        val quark = entry.provider == SourceProvider.QUARK
        val choices = mutableListOf<SheetChoice>()
        choices += if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) {
            SheetChoice("播放", meta = if (kind == MediaKind.AUDIO) "仅播放此音频" else "播放此视频", tag = "play")
        } else {
            SheetChoice("打开", tag = "open")
        }
        if (entry.isDisk && screen == Screen.DISK && !quark) {
            choices += SheetChoice("重命名", tag = "rename")
            choices += SheetChoice("移动", tag = "move")
            choices += SheetChoice("复制", tag = "copy")
            choices += SheetChoice("分享", meta = "生成分享链接", tag = "share")
            choices += SheetChoice("删除", meta = "移入回收站", tag = "delete")
        } else if (!entry.isDisk && !entry.directory && !quark) {
            choices += SheetChoice("转存到我的网盘", meta = "保存为网盘副本", tag = "transfer")
        }
        choices += SheetChoice(if (favorites.has(entry)) "取消收藏" else "收藏", tag = "favorite")
        choices += SheetChoice("详情", tag = "detail")

        showChoiceSheet(
            title = entry.name,
            subtitle = "文件操作",
            choices = choices,
            onSelect = { choice ->
                when (choice.tag) {
                    "play", "open" -> openEntry(entry, entries, currentPlaybackListKey())
                    "rename" -> diskWrites.promptRename(entry)
                    "move" -> diskWrites.promptMoveCopy(entry, copy = false)
                    "copy" -> diskWrites.promptMoveCopy(entry, copy = true)
                    "share" -> diskWrites.promptShare(listOf(entry))
                    "delete" -> diskWrites.confirmDelete(listOf(entry))
                    "transfer" -> diskWrites.promptTransfer(entry)
                    "favorite" -> toggleFavorite(entry)
                    "detail" -> showEntryDetail(entry)
                }
            },
        )
    }

    private fun renderBatchBar() {
        if (!::batchBar.isInitialized) return
        if (::batchMove.isInitialized) {
            val diskSel = selectionMode && selectionScreen == Screen.DISK
            batchMove.visibility = if (diskSel) View.VISIBLE else View.GONE
            batchCopy.visibility = if (diskSel) View.VISIBLE else View.GONE
            batchDelete.visibility = if (diskSel) View.VISIBLE else View.GONE
            batchFav.visibility = if (diskSel) View.GONE else View.VISIBLE
        }
        batchBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        if (selectionMode) {
            batchCount.text = "已选 ${selectedKeys.size} 项"
            sectionTitle.text = "已选 ${selectedKeys.size} 项"
            filterCount.visibility = View.GONE
            sortButton.visibility = View.GONE
            val entries = visibleSelectionEntries()
            val allStarred = entries.isNotEmpty() && entries.all { favorites.has(it) }
            batchFav.text = if (allStarred) "取消收藏" else "收藏"
            batchAll.text = if (lastRenderedEntries.isNotEmpty() &&
                selectedKeys.containsAll(lastRenderedEntries.map(::entryKey))
            ) "取消全选" else "全选"
        } else {
            filterCount.visibility = View.VISIBLE
            sectionTitle.text = when (screen) {
                Screen.HOME -> "群组"
                Screen.DISK, Screen.GROUPS -> "文件"
                else -> ""
            }
        }
    }

    /** V02-3 睡眠定时：把定时分钟数下发给音频后台服务（0 = 关闭）。 */
    private fun sendSleepTimer(minutes: Int) {
        if (minutes > 0) SleepTimer.arm(minutes) else SleepTimer.disarm()
        controller?.sendCustomCommand(
            AudioPlaybackService.SLEEP_TIMER,
            Bundle().apply { putInt("minutes", minutes) },
        )
    }

    /** 按扩展名选择音视频播放或 PDF 下载。 */
    private fun openEntry(entry: PanEntry, entries: List<PanEntry>, listKey: String? = null) {
        if (screen == Screen.FAVORITES && entry.directory) {
            openSourceDirectory(entry)
            return
        }
        val kind = PanApi.kindOf(entry)
        if (kind == MediaKind.AUDIO || kind == MediaKind.VIDEO) selectPlayback(entry, entries, listKey)
        else if (kind != MediaKind.PDF && kind != MediaKind.IMAGE && !entry.directory && entry.provider == SourceProvider.QUARK) {
            startActivity(QuarkDriveActivity.openFile(this, entry))
            return
        }
        when (kind) {
            MediaKind.DIRECTORY -> {
                rememberFolderPosition()
                folders.add(entry)
                transferredOnly = false
                contentScroll.scrollTo(0, 0)
                refresh()
            }
            MediaKind.VIDEO -> startVideo(entry)
            MediaKind.AUDIO -> resolveAudio(entry)
            MediaKind.PDF -> pdfPreview.open(entry)
            MediaKind.IMAGE -> openImage(entry, entries)
            MediaKind.DOCUMENT -> AlertDialog.Builder(this)
                .setTitle(entry.name)
            .setMessage("App 内目前支持阅读 PDF。请在百度网盘中打开或下载此文件。")
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

    private fun toggleFavorite(entry: PanEntry): Boolean {
        if (!hasVerifiedAccount()) return false
        val starred = favorites.toggle(entry)
        updateFavoritesBadge()
        if (screen == Screen.FAVORITES) {
            content.removeAllViews()
            renderFavorites()
        } else if (screen == Screen.HOME && folders.isEmpty()) {
            renderHomeSections()
        } else {
            content.findViewWithTag<ImageView>(favoriteTag(entry))?.let { paintFavorite(it, starred) }
        }
        toast(if (starred) "已加入收藏" else "已取消收藏")
        return starred
    }

    private fun showLoginDialog(provider: SourceProvider? = null) {
        val dialog = LoginDialog(this, web.settings.userAgentString) {
            if (provider == SourceProvider.BAIDU) pendingAddProvider = SourceProvider.BAIDU
            showApp(recheck = true)
        }
        sizeSheet(dialog) { available -> (available * 0.94f).toInt() }
        dialog.show()
    }

    private fun currentPlaybackListKey(): String {
        val parent = folders.lastOrNull()
        return when {
            parent != null && parent.isDisk -> "D:${parent.fileId}"
            parent != null -> "G:${parent.gid}:${parent.messageId}:${parent.fileId}"
            screen == Screen.DISK -> "D:root"
            screen == Screen.GROUPS || showingHomeDirectory -> "G:$gid"
            else -> "${screen}:$gid"
        }
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
        val origin = runCatching { EntryOrigin.valueOf(extras.getString("origin") ?: "GROUP") }
            .getOrDefault(EntryOrigin.GROUP)
        return PanEntry(
            extras.getString("gid").orEmpty(), extras.getString("message").orEmpty(),
            extras.getString("owner").orEmpty(), item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
            extras.getString("path").orEmpty(), extras.getString("size").orEmpty(), false, origin,
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

    private fun advanceAfterEnd(entry: PanEntry, isCurrent: () -> Boolean, playNext: () -> Unit) {
        val listKey = PlaybackQueueState.listKey
        fun canAdvance() = hasActiveSession(entry.provider) && !isFinishing && !isDestroyed &&
            PlaybackQueueState.listKey == listKey && isCurrent() &&
            PlaybackQueueState.entries.any { sameEntry(it, entry) }

        fun tryNext() {
            if (!canAdvance()) return
            if (SleepTimer.consumeStopAfterCurrent()) return
            if (siblingOf(entry, 1) != null) {
                playNext()
                return
            }
            if (automaticCourseLoad?.let { sameEntry(it, entry) } == true ||
                !hasMoreCourses(entry, listKey)
            ) return
            automaticCourseLoad = entry
            loadMoreCourses(entry, listKey, ::canAdvance) { courses ->
                if (automaticCourseLoad === entry) {
                    automaticCourseLoad = null
                    if (courses != null) tryNext()
                }
            }
        }
        tryNext()
    }

    private fun playSibling(
        entry: PanEntry,
        delta: Int,
        automatic: Boolean = false,
        route: PlaybackRoute = PlaybackRoute.AUDIO,
        videoState: VideoPlaybackPage.Snapshot? = null,
    ) {
        if (!hasActiveSession(entry.provider) || isFinishing || isDestroyed) return
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

    private fun loadRecentThumbnail(entry: PanEntry, icon: ImageView) {
        val url = PanApi.thumbResize(entry.previewUrl, "c140_u90")
        if (url.isEmpty() || url == entry.previewUrl) return
        val account = verifiedAccount
        val key = mediaCacheKey(account, entry) + ":thumbnail"
        val item = ImagePreviewItem(entry.fileId, entry.name, key, url)
        ImagePreviewLoader.load(item, SourceProvider.BAIDU, thumbnail = true) { result ->
            val bytes = result.getOrNull()?.bytes ?: return@load
            if (isDestroyed || account != verifiedAccount || !hasVerifiedAccount()) return@load
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@load
            icon.clearColorFilter()
            icon.setPadding(0, 0, 0, 0)
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.clipToOutline = true
            icon.setImageBitmap(bitmap)
        }
    }

    private fun resumeFromHistory(entry: PanEntry) {
        playbackDirectory(entry, null)?.let { (queueKey, queue) ->
            openEntry(entry, queue, queueKey)
            return
        }
        val listKey = PlaybackQueueState.listKey
        val directoryQueue = PlaybackQueueState.entries.filter {
            it.origin == entry.origin && it.gid == entry.gid
        }
        val isDirectory = listKey == "G:${entry.gid}" ||
            listKey?.startsWith("G:${entry.gid}:") == true ||
            listKey?.startsWith("D:") == true
        if (isDirectory && directoryQueue.any { sameEntry(it, entry) }) {
            openEntry(entry, directoryQueue, listKey)
        } else {
            openEntry(entry, listOf(entry))
        }
    }

    private fun currentHistoryItems(): List<AudioHistoryItem> =
        (audioHistory.recentAcrossGroups() + videoHistory.recentAcrossGroups())
            .sortedByDescending { it.updatedAt }

    private fun historyMeta(item: AudioHistoryItem, sourceNames: Map<String, String>): String {
        val isVideo = PanApi.kindOf(item.entry) == MediaKind.VIDEO
        val progress: String? = when {
            item.completed -> if (isVideo) "已看完" else "已听完"
            item.seconds > 0 -> "${if (isVideo) "看到" else "听到"} ${formatAudioTime(item.seconds)}"
            else -> null
        }
        val source = if (item.entry.isDisk) "我的网盘" else sourceNames[item.entry.gid]
        return listOfNotNull(source, progress).joinToString(" · ")
    }

    private fun renderHomeSections(allowUnverified: Boolean = false) {
        renderAudioHistory(allowUnverified)
        renderHomeFavorites(allowUnverified)
    }

    private fun renderAudioHistory(allowUnverified: Boolean = false) {
        historyPanel.removeAllViews()
        if ((!allowUnverified && !hasVerifiedAccount()) || screen != Screen.HOME || folders.isNotEmpty()) return
        historyRefreshPending = false
        val all = currentHistoryItems()
        if (all.isEmpty()) return
        val resumeItems = all.filter { !it.completed && it.seconds > 0 }.take(3)
        val homeItems = resumeItems.ifEmpty { all.take(3) }
        val sourceNames = shareSources().associate { it.id to it.name }
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
                setOnClickListener { showPlayHistory() }
            })
        }
        historyPanel.addView(header)
        if (homeItems.isEmpty()) return
        val resumeList = column().apply { setBackgroundResource(R.drawable.bg_rec_card) }
        homeItems.forEach { item ->
            val isVideo = PanApi.kindOf(item.entry) == MediaKind.VIDEO
            val card = layoutInflater.inflate(R.layout.view_rec_card, resumeList, false)
            card.findViewById<ImageView>(R.id.recIcon).apply {
                setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_music)
                setBackgroundResource(if (isVideo) R.drawable.bg_fmt_video else R.drawable.bg_fmt_audio)
                setColorFilter(color(R.color.accentInk))
            }
            card.findViewById<TextView>(R.id.recName).text = item.entry.name
            card.findViewById<TextView>(R.id.recMeta).text = historyMeta(item, sourceNames)
            card.setOnClickListener { resumeFromHistory(item.entry) }
            resumeList.addView(card)
        }
        historyPanel.addView(resumeList)
    }

    private fun showPlayHistory() {
        if (!hasVerifiedAccount() || screen != Screen.HOME || folders.isNotEmpty()) return
        val items = currentHistoryItems().take(10)
        if (items.isEmpty()) return
        val sourceNames = shareSources().associate { it.id to it.name }
        val view = layoutInflater.inflate(R.layout.dialog_play_history, null, false)
        view.findViewById<TextView>(R.id.historySubtitle).text = "最近 ${items.size} 条 · 点选继续播放"
        val list = view.findViewById<ListView>(R.id.historyList)
        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: layoutInflater.inflate(R.layout.item_play_history, parent, false)
                val item = items[position]
                paintMediaIcon(row.findViewById<ImageView>(R.id.historyIcon), PanApi.kindOf(item.entry), false)
                row.findViewById<TextView>(R.id.historyName).text = item.entry.name
                row.findViewById<TextView>(R.id.historyMeta).text = historyMeta(item, sourceNames)
                row.findViewById<View>(R.id.historyDivider).visibility =
                    if (position == items.lastIndex) View.GONE else View.VISIBLE
                return row
            }
        }
        list.adapter = adapter
        val dialog = Dialog(this)
        dialog.setContentView(view)
        sizeSheet(dialog) { available ->
            val maxHeight = sheetMaxHeight(available)
            (dp(136) + minOf(items.size, 8) * dp(88))
                .coerceIn(minOf(dp(280), maxHeight), maxHeight)
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(R.style.SearchSheetAnimation)
        }
        dialog.setCanceledOnTouchOutside(true)
        view.findViewById<ImageView>(R.id.historyClose).setOnClickListener { dialog.dismiss() }
        list.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            resumeFromHistory(item.entry)
        }
        dialog.show()
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
        val transferred = if (items.any { !it.isDisk }) transferredIndex() else emptySet()
        redrawFilteredRows = {
            list.removeAllViews()
            addCourseRows(list, items, transferred)
        }
        redrawFilteredRows?.invoke()
        page.findViewById<TextView>(R.id.favGoBrowse).setOnClickListener {
            showScreen(Screen.GROUPS)
        }
        content.addView(page)
        renderCourseSwitch()
    }

    @Suppress("DEPRECATION")
    private fun renderSettings() {
        status.text = ""
        val page = layoutInflater.inflate(R.layout.view_settings_page, content, false)
        renderLinkedDrives(page)
        page.findViewById<View>(R.id.setAddLoginRow).setOnClickListener { openAddLoginSheet() }
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

    private enum class DriveState { NEVER, ACTIVE, DORMANT }

    private val baiduLogoutUrls = listOf(
        "https://pan.baidu.com", "https://wappass.baidu.com",
        "https://passport.baidu.com", "https://www.baidu.com", "https://baidu.com",
    )
    private val quarkLogoutUrls = listOf(
        "https://pan.quark.cn", "https://drive-pc.quark.cn",
        "https://uop.quark.cn", "https://quark.cn",
    )

    private fun quarkLoggedIn(): Boolean = QuarkPersonalDrive.hasLoginCookie(
        CookieManager.getInstance().getCookie(QuarkPersonalDrive.HOME),
    )

    private fun quarkEverLogged(): Boolean =
        getSharedPreferences("account_scope", MODE_PRIVATE).getBoolean("quark_ever_logged", false)

    private fun markQuarkEverLogged() {
        getSharedPreferences("account_scope", MODE_PRIVATE).edit()
            .putBoolean("quark_ever_logged", true).apply()
    }

    private fun baiduDriveState(): DriveState = when {
        hasVerifiedAccount() || verifiedAccount.isNotEmpty() -> DriveState.ACTIVE
        // 退出登录刻意保留 viewer_uk（数据归属锚点），此时按 DORMANT 呈现。
        AccountScope.current.isNotEmpty() -> DriveState.DORMANT
        shareSources().any { it.provider == SourceProvider.BAIDU } -> DriveState.DORMANT
        else -> DriveState.NEVER
    }

    private fun quarkDriveState(): DriveState = when {
        quarkLoggedIn() -> DriveState.ACTIVE
        quarkEverLogged() || shareSources().any { it.provider == SourceProvider.QUARK } -> DriveState.DORMANT
        else -> DriveState.NEVER
    }

    private fun renderLinkedDrives(page: View) {
        val container = page.findViewById<LinearLayout>(R.id.setLinkedContainer)
        container.removeAllViews()
        if (quarkLoggedIn()) markQuarkEverLogged()
        val states = listOf(
            SourceProvider.BAIDU to baiduDriveState(),
            SourceProvider.QUARK to quarkDriveState(),
        )
        val linked = states.filter { it.second != DriveState.NEVER }
        if (linked.isEmpty()) {
            container.addView(emptyDriveRow())
        } else {
            linked.forEachIndexed { index, (provider, state) ->
                if (index > 0) container.addView(driveDivider())
                container.addView(driveRow(provider, state))
            }
        }
        val hasNever = states.any { it.second == DriveState.NEVER }
        page.findViewById<View>(R.id.setAddLoginRow).visibility =
            if (hasNever) View.VISIBLE else View.GONE
        page.findViewById<TextView>(R.id.setAddLoginHint).text =
            if (hasNever) "选择还没登录过的网盘类型" else "已没有可添加的网盘"
    }

    private fun driveDivider(): View = View(this).apply {
        setBackgroundColor(color(R.color.line))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply { marginStart = dp(69) }
    }

    private fun emptyDriveRow(): View {
        val row = layoutInflater.inflate(R.layout.item_settings_drive, null, false)
        row.isClickable = false
        row.findViewById<TextView>(R.id.drivePill).visibility = View.GONE
        row.findViewById<TextView>(R.id.driveName).text = "还没有关联网盘"
        row.findViewById<TextView>(R.id.driveStatus).text = "点下方「添加登录」开始"
        row.findViewById<TextView>(R.id.driveCount).visibility = View.GONE
        row.findViewById<TextView>(R.id.driveAction).visibility = View.GONE
        return row
    }

    private fun driveRow(provider: SourceProvider, state: DriveState): View {
        val row = layoutInflater.inflate(R.layout.item_settings_drive, null, false)
        val baidu = provider == SourceProvider.BAIDU
        val icon = row.findViewById<ImageView>(R.id.driveIcon)
        val pill = row.findViewById<TextView>(R.id.drivePill)
        val name = row.findViewById<TextView>(R.id.driveName)
        val status = row.findViewById<TextView>(R.id.driveStatus)
        val count = row.findViewById<TextView>(R.id.driveCount)
        val action = row.findViewById<TextView>(R.id.driveAction)
        name.text = if (baidu) "百度网盘" else "夸克网盘"
        val sources = shareSources().count { it.provider == provider }
        if (state == DriveState.ACTIVE) {
            if (baidu) renderAccountAvatar(icon) else icon.setImageResource(R.drawable.ic_layers)
            pill.text = "在用"
            pill.setBackgroundResource(R.drawable.bg_chip_on)
            pill.setTextColor(color(R.color.accentInk))
            status.text = if (baidu) {
                val tail = if (verifiedAccount.length <= 4) "账号 $verifiedAccount"
                else "账号 …${verifiedAccount.takeLast(4)}"
                val nickname = verifiedAccountName.trim()
                if (nickname.isNotEmpty()) "已登录 · $nickname（$tail）" else "已登录 · $tail"
            } else {
                "已登录 · Cookie 有效"
            }
            count.text = "$sources 个来源"
            action.text = "退出登录"
            action.setTextColor(color(R.color.danger))
            action.setOnClickListener { confirmLogout(provider) }
            row.setOnClickListener { openSourcesFor(provider) }
        } else {
            icon.setImageResource(if (baidu) R.drawable.ic_cloud else R.drawable.ic_layers)
            pill.text = "已退出"
            pill.setBackgroundResource(R.drawable.bg_chip)
            pill.setTextColor(color(R.color.ink3))
            status.text = "暂未使用 · 数据已保留"
            count.text = "保留 $sources 个来源"
            action.text = "登录"
            action.setTextColor(color(R.color.accentInk))
            action.setOnClickListener { startLoginFor(provider) }
            row.setOnClickListener { startLoginFor(provider) }
        }
        return row
    }

    private fun openSourcesFor(provider: SourceProvider) {
        when (provider) {
            SourceProvider.BAIDU -> manageBaiduSources()
            SourceProvider.QUARK -> manageQuarkSources()
        }
    }

    private fun startLoginFor(provider: SourceProvider) {
        when (provider) {
            SourceProvider.BAIDU -> showLoginDialog(SourceProvider.BAIDU)
            SourceProvider.QUARK -> {
                pendingAddProvider = SourceProvider.QUARK
                quarkLoginLauncher.launch(Intent(this, QuarkDriveActivity::class.java))
            }
        }
    }

    private fun openAddLoginSheet() {
        val choices = mutableListOf<SheetChoice>()
        if (baiduDriveState() == DriveState.NEVER) {
            choices += SheetChoice(
                "百度网盘",
                meta = "共享文件库可能在群组或联系人下",
                tag = SourceProvider.BAIDU,
            )
        }
        if (quarkDriveState() == DriveState.NEVER) {
            choices += SheetChoice(
                "夸克网盘",
                meta = "关注根目录下的文件夹",
                tag = SourceProvider.QUARK,
            )
        }
        if (choices.isEmpty()) return
        showChoiceSheet(
            title = "添加登录",
            subtitle = "选择还没登录过的网盘类型",
            choices = choices,
            onSelect = { choice -> (choice.tag as? SourceProvider)?.let(::startLoginFor) },
        )
    }

    private fun confirmLogout(provider: SourceProvider) {
        val label = if (provider == SourceProvider.BAIDU) "百度网盘" else "夸克网盘"
        AlertDialog.Builder(this)
            .setTitle("退出$label")
            .setMessage("退出只终止会话，已关联的来源和播放记录都会保留，可随时重新登录。")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出登录") { _, _ ->
                if (provider == SourceProvider.BAIDU) {
                    clearCookies(baiduLogoutUrls, "baidu.com")
                    invalidateAccount()
                    checkingAccount = false
                } else {
                    clearCookies(quarkLogoutUrls, "quark.cn")
                    markQuarkEverLogged()
                }
                pendingAddProvider = null
                toast("已退出$label，数据已保留")
                refresh()
            }
            .show()
    }

    /** 只清指定域名的 Cookie，避免误杀另一个网盘会话（百度/夸克共用 CookieManager）。 */
    private fun clearCookies(urls: List<String>, domain: String) {
        val manager = CookieManager.getInstance()
        urls.forEach { url ->
            val raw = manager.getCookie(url).orEmpty()
            if (raw.isEmpty()) return@forEach
            raw.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                manager.setCookie(url, "$name=; Max-Age=0; Path=/")
                manager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$domain")
            }
        }
        manager.flush()
    }

    /**
     * 账号头像：
     * - 接口提供 avatar_url 时加载真实头像；
     * - 否则用昵称/账号首字母生成字母头像（百度网盘 gettemplatevariable 不返回头像地址）。
     */
    private fun renderAccountAvatar(target: ImageView) {
        val url = verifiedAccountAvatar.trim()
        if (url.isNotEmpty()) {
            target.setImageResource(R.drawable.ic_cloud)
            target.scaleType = ImageView.ScaleType.FIT_CENTER
            target.setPaddingRelative(dp(14), dp(14), dp(14), dp(14))
            Thread {
                val bitmap = runCatching {
                    val conn = java.net.URL(url).openConnection()
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (bitmap != null) runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    target.setPaddingRelative(0, 0, 0, 0)
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageBitmap(bitmap)
                }
            }.start()
            return
        }
        if (!hasVerifiedAccount()) {
            target.setImageResource(R.drawable.ic_cloud)
            target.scaleType = ImageView.ScaleType.FIT_CENTER
            target.setPaddingRelative(dp(14), dp(14), dp(14), dp(14))
            return
        }
        val label = verifiedAccountName.trim().ifEmpty { verifiedAccount }
        val initial = label.firstOrNull()?.uppercaseChar()?.toString() ?: "拾"
        target.background = null
        target.setPaddingRelative(0, 0, 0, 0)
        target.scaleType = ImageView.ScaleType.FIT_CENTER
        target.setImageBitmap(letterAvatar(initial))
    }

    /** 生成圆形字母头像。 */
    private fun letterAvatar(initial: String): android.graphics.Bitmap {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = color(R.color.accent)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = color(R.color.onAccent)
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        paint.textSize = size * 0.46f
        val metrics = paint.fontMetrics
        canvas.drawText(initial, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint)
        return bitmap
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清除播放记录")
            .setMessage("将清除当前账号在本机的播放记录和音视频续播点。网盘文件会保留。")
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

    private fun resolveAudio(
        entry: PanEntry,
        route: PlaybackRoute = PlaybackRoute.AUDIO,
        openPlayer: Boolean = true,
    ) {
        if (!hasActiveSession(entry.provider)) {
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
        if (entry.provider == SourceProvider.QUARK) {
            resolveQuarkAudio(entry, openPlayer)
            return
        }
        if (entry.isDisk) {
            resolveDiskAudio(entry, openPlayer)
            return
        }
        pendingQueue = pendingQueue?.takeIf { sameEntry(it.entry, entry) }
        dispatchedAudioEntry = null
        val id = ++audioRequestId
        val token = AudioPlayRequestGate.begin()
        audioPlayToken = token
        pendingAudioEntry = entry
        audioResolver.cancel()
        mediaLinks.dismissCaptcha(RequestLane.AUDIO)
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

    private fun resolveQuarkPlayback(entry: PanEntry, onResult: (Result<QuarkPlayback>) -> Unit) {
        val account = AccountScope.current
        val session = QuarkPersonalDrive.openSession(WebSettings.getDefaultUserAgent(this))
        Thread {
            val result = runCatching {
                val drive = session ?: throw QuarkLoginRequired()
                val file = QuarkFile(entry.fileId, entry.name, false, entry.size.toLongOrNull() ?: 0)
                drive.playback(file, video = PanApi.kindOf(entry) == MediaKind.VIDEO)
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing && account == AccountScope.current) onResult(result)
            }
        }.start()
    }

    private fun resolveQuarkAudio(entry: PanEntry, openPlayer: Boolean) {
        cancelPendingAudioRequest()
        val id = audioRequestId
        val token = AudioPlayRequestGate.begin()
        audioPlayToken = token
        pendingAudioEntry = entry
        updateAudioRequestMessage("正在打开 ${entry.name}…")
        resolveQuarkPlayback(entry) { result ->
            if (id != audioRequestId) return@resolveQuarkPlayback
            if (!AudioPlayRequestGate.isCurrent(token)) {
                cancelPendingAudioRequest()
                return@resolveQuarkPlayback
            }
            result.onSuccess { startBackgroundAudio(entry, it.uri, openPlayer) }
                .onFailure { failAudioRequest(it.message ?: "无法获取夸克音频播放地址") }
        }
    }

    private fun resolveDiskAudio(entry: PanEntry, openPlayer: Boolean) {
        val id = ++audioRequestId
        val token = AudioPlayRequestGate.begin()
        audioPlayToken = token
        pendingAudioEntry = entry
        dispatchedAudioEntry = null
        audioResolver.cancel()
        mediaLinks.dismissCaptcha(RequestLane.AUDIO)
        updateAudioRequestMessage("正在打开 ${entry.name}…")
        val account = verifiedAccount
        val key = mediaCacheKey(account, entry)
        fun isCurrentPlayback() = !isDestroyed && verifiedAccount == account &&
            audioPlayToken == token && AudioPlayRequestGate.isCurrent(token)
        fun playStream(stream: VideoStream) {
            mediaAddresses.savePlaylist(key, stream)
            startBackgroundAudio(entry, stream.uri, openPlayer, stream.playlist)
        }
        ensureBdstoken(
            onReady = { bd ->
                if (id != audioRequestId || !isCurrentPlayback()) return@ensureBdstoken
                val uri = PanApi.diskAudioPlaylist(entry, bd)
                mediaAddresses.playlist(key, uri)?.let {
                    playStream(it)
                    return@ensureBdstoken
                }
                var attempt = 0
                fun fetch() {
                    if (id != audioRequestId || isDestroyed) return
                    if (!AudioPlayRequestGate.isCurrent(token)) {
                        cancelPendingAudioRequest()
                        return
                    }
                    attempt++
                    if (attempt >= 2) updateAudioRequestMessage("音频转码准备中，请稍候…")
                    audioResolver.start(uri, web.settings.userAgentString) { result ->
                        if (id != audioRequestId || isDestroyed) return@start
                        if (!AudioPlayRequestGate.isCurrent(token)) {
                            cancelPendingAudioRequest()
                            return@start
                        }
                        result.onSuccess(::playStream).onFailure { error ->
                            when {
                                error !is AudioTranscodingPending -> failAudioRequest(
                                    (error as? IllegalStateException)?.message ?: "获取网盘音频失败，请重试。",
                                )
                                attempt < 4 -> web.postDelayed({ fetch() }, 2_000)
                                else -> {
                                    updateAudioRequestMessage("正在打开原始音频…")
                                    mediaLinks.requestDiskLink(
                                        entry, bd, RequestLane.AUDIO, ::isCurrentPlayback,
                                        onFailure = { failAudioRequest("无法打开原始音频，请稍后重试。") },
                                    ) { link -> startBackgroundAudio(entry, link, openPlayer) }
                                }
                            }
                        }
                    }
                }
                fetch()
            },
            onFailure = {
                if (id == audioRequestId && isCurrentPlayback()) {
                    failAudioRequest("无法读取我的网盘，请登录后重试。")
                }
            },
        )
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
        mediaLinks.resolve(entry, viewer, lane = RequestLane.AUDIO) { uri ->
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

    private fun openImage(entry: PanEntry, entries: List<PanEntry>) {
        val quark = entry.provider == SourceProvider.QUARK
        val sessionReady = if (quark) hasActiveSession(SourceProvider.QUARK) else hasVerifiedAccount()
        if (!sessionReady) {
            status.text = "请确认网盘登录后重试。"
            return
        }
        val siblings = entries.filter { it.provider == entry.provider && PanApi.kindOf(it) == MediaKind.IMAGE }
            .toMutableList()
        val key = entryKey(entry)
        val position = siblings.indexOfFirst { entryKey(it) == key }
        if (position < 0) siblings.add(0, entry)
        imageViewerEntries = siblings
        if (!quark && entry.isDisk && entry.previewUrl.isEmpty()) {
            openImageOriginal(entry)
            return
        }
        cancelImageLoading()
        val account = playbackAccount(entry)
        val items = siblings.map { image ->
            ImagePreviewItem(
                image.fileId, image.name, mediaCacheKey(account, image), image.previewUrl,
                shareInfoUrl = if (!quark && !image.isDisk) PanApi.imageShareInfo(image) else "",
                diskDirectory = if (!quark && image.isDisk) image.path.substringBeforeLast('/').ifEmpty { "/" } else null,
            )
        }
        imageViewerLauncher.launch(ImageViewerActivity.galleryIntent(this, items, position.coerceAtLeast(0), entry.provider))
        status.text = ""
    }

    private fun openImageOriginal(entry: PanEntry) {
        if (!hasVerifiedAccount()) {
            status.text = "请确认网盘登录后重试。"
            return
        }
        cancelImageLoading()
        val id = imageOpenId
        requestId++
        mediaLinks.dismissCaptcha(RequestLane.LIST)
        val account = playbackAccount(entry)
        val key = mediaCacheKey(account, entry)
        val ext = ImageTypes.ext(entry.name)
        ImageCache.find(this, key, ext)?.let {
            startImageViewer(entry, it)
            return
        }
        mediaAddresses.link(key)?.let {
            downloadImage(entry, account, id, key, it, fromCache = true)
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
            if (!isCurrentImage(id, account)) return@evaluateJavascript
            val viewer = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (viewer != null && viewer.matches(Regex("[0-9]{1,24}"))) {
                requestImageLink(entry, viewer, id)
            } else {
                load(PanApi.viewer()) { json ->
                    if (!isCurrentImage(id, account)) return@load
                    val uk = json.optJSONObject("result")?.optString("uk").orEmpty()
                    check(uk.matches(Regex("[0-9]{1,24}"))) { "无法识别当前网盘账号，请重新登录后重试。" }
                    requestImageLink(entry, uk, id)
                }
            }
        }
    }

    /** 图片不走 docview/docpreview（PDF 专用），直接取原文件 dlink：群组 sharedownload，我的网盘 /api/download。 */
    private fun requestImageLink(entry: PanEntry, viewer: String, id: Int) {
        if (viewer != verifiedAccount) {
            verifyAndRefresh()
            return
        }
        val key = mediaCacheKey(viewer, entry)
        fun isCurrent() = isCurrentImage(id, viewer)
        fun fail() {
            if (isCurrent()) status.text = "图片打开失败，请重试。"
        }
        fun receive(link: Uri) {
            if (!isCurrent()) return
            mediaAddresses.saveLink(key, link)
            downloadImage(entry, viewer, id, key, link)
        }
        if (entry.isDisk) {
            ensureBdstoken(
                onReady = { token ->
                    if (isCurrent()) {
                        mediaLinks.requestDiskLink(
                            entry, token, RequestLane.LIST, { isCurrent() }, onFailure = { fail() },
                        ) { receive(it) }
                    }
                },
                onFailure = { fail() },
            )
        } else {
            mediaLinks.resolve(entry, viewer, lane = RequestLane.LIST, onFailure = { fail() }) { receive(it) }
        }
    }

    private fun downloadImage(
        entry: PanEntry,
        account: String,
        id: Int,
        key: String,
        link: Uri,
        fromCache: Boolean = false,
    ) {
        if (!isCurrentImage(id, account)) return
        imageLoading?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage("正在加载原图…")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                cancelImageLoading()
                status.text = ""
            }
            .show()
        imageLoading = dialog
        val ext = ImageTypes.ext(entry.name)
        fun complete(result: Result<File>) {
            if (imageLoading === dialog) {
                dialog.dismiss()
                imageLoading = null
            }
            if (!isCurrentImage(id, account)) {
                result.getOrNull()?.let(ImageCache::discard)
                return
            }
            result.mapCatching { temp ->
                try {
                    startImageViewer(entry, ImageCache.publish(this, key, ext, temp))
                } finally {
                    ImageCache.discard(temp)
                }
            }.onFailure {
                if (it is InvalidImageLink) {
                    mediaAddresses.clearLink(key)
                    if (ready && fromCache) {
                        requestImageLink(entry, account, id)
                        return@onFailure
                    }
                }
                val message = it.message ?: "图片打开失败，请重试。"
                status.text = message
            }
        }
        imageFetcher.start(link, ::complete)
    }

    private fun startImageViewer(entry: PanEntry, file: File) {
        // 字段与传给查看器的列表必须完全一致：切图回传的是列表下标
        if (imageViewerEntries.isEmpty()) imageViewerEntries = listOf(entry)
        val images = imageViewerEntries
        val index = images.indexOfFirst { entryKey(it) == entryKey(entry) }.coerceAtLeast(0)
        imageViewerLauncher.launch(Intent(this, ImageViewerActivity::class.java).apply {
            putExtra(ImageViewerActivity.EXTRA_FILE, file.absolutePath)
            putExtra(ImageViewerActivity.EXTRA_TITLE, entry.name)
            putExtra(ImageViewerActivity.EXTRA_MIME, ImageTypes.mime(ImageTypes.ext(entry.name)))
            putExtra(ImageViewerActivity.EXTRA_INDEX, index)
            putExtra(ImageViewerActivity.EXTRA_COUNT, images.size)
        })
        status.text = ""
    }

    private fun isCurrentImage(id: Int, account: String) =
        id == imageOpenId && !isDestroyed && activityVisible &&
            account == verifiedAccount && hasVerifiedAccount()

    private fun cancelImageLoading() {
        imageOpenId++
        imageFetcher.cancel()
        imageLoading?.dismiss()
        imageLoading = null
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
            putString("account", playbackAccount(entry))
            putString("uri", uri.toString())
            if (playlist != null) putByteArray("playlist", playlist)
            putString("gid", entry.gid)
            putString("message", entry.messageId)
            putString("owner", entry.owner)
            putString("file", entry.fileId)
            putString("name", entry.name)
            putString("path", entry.path)
            putString("size", entry.size)
            putString("origin", entry.origin.name)
        }
        val id = audioRequestId
        dispatchedAudioEntry = entry
        diskRenewAttempts = 0
        lastDiskRenewAt = 0L
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
        if (!ownsAudio(player)) return
        val error = player.playerError ?: return
        val item = player.currentMediaItem ?: return
        val metadata = item.mediaMetadata
        val extras = metadata.extras ?: return
        val wasHls = extras.getBoolean("hls")
        if (!wasHls && !error.isExpiredMediaLink()) return
        val id = extras.getString("playbackId") ?: return
        if (id == renewingPlaybackId) return
        val origin = runCatching { EntryOrigin.valueOf(extras.getString("origin") ?: "GROUP") }
            .getOrDefault(EntryOrigin.GROUP)
        val entry = PanEntry(
            extras.getString("gid").orEmpty(), extras.getString("message").orEmpty(),
            extras.getString("owner").orEmpty(), item.mediaId, metadata.title?.toString().orEmpty(),
            extras.getString("path").orEmpty(), extras.getString("size").orEmpty(), false, origin,
        )
        val account = playbackAccount(entry)
        if (!hasActiveSession(entry.provider)) return
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
        fun finishRenewal(allowAgain: Boolean = false) {
            renewingPlaybackId = null
            if (!allowAgain) renewedPlaybackId = id
        }
        fun renew(link: Uri) {
            finishRenewal()
            player.sendCustomCommand(AudioPlaybackService.RENEW_FILE, Bundle().apply {
                putString("playbackId", id)
                putString("uri", link.toString())
            })
        }
        if (entry.provider == SourceProvider.QUARK) {
            resolveQuarkPlayback(entry) { result ->
                if (player.currentMediaItem?.mediaMetadata?.extras?.getString("playbackId") != id) {
                    finishRenewal()
                } else {
                    result.onSuccess { renew(it.uri) }.onFailure { finishRenewal() }
                }
            }
            return
        }
        if (entry.isDisk) {
            if (!wasHls) {
                fun isCurrent() = !isDestroyed && account == verifiedAccount &&
                    player.currentMediaItem?.mediaMetadata?.extras?.getString("playbackId") == id
                ensureBdstoken(
                    onReady = { token ->
                        if (isCurrent()) {
                            mediaLinks.requestDiskLink(
                                entry, token, RequestLane.RENEWAL, ::isCurrent, onFailure = { finishRenewal() },
                            ) { link -> renew(link) }
                        } else {
                            finishRenewal()
                        }
                    },
                    onFailure = { finishRenewal() },
                )
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastDiskRenewAt < 10_000) {
                finishRenewal(allowAgain = true)
                return
            }
            if (diskRenewAttempts >= 3) {
                finishRenewal()
                updateAudioRequestMessage("播放地址已过期，请重新打开")
                return
            }
            lastDiskRenewAt = now
            diskRenewAttempts++
            ensureBdstoken(
                onReady = { token ->
                    if (account != verifiedAccount || isDestroyed) {
                        finishRenewal()
                        return@ensureBdstoken
                    }
                    val uri = PanApi.diskAudioPlaylist(entry, token)
                    renewalResolver.start(uri, web.settings.userAgentString) { result ->
                        if (account != verifiedAccount || isDestroyed) {
                            finishRenewal()
                            return@start
                        }
                        val stream = result.getOrNull()
                        if (stream == null) {
                            finishRenewal()
                            updateAudioRequestMessage(
                                (result.exceptionOrNull() as? IllegalStateException)?.message
                                    ?: "播放地址已过期，请重新打开",
                            )
                            return@start
                        }
                        mediaAddresses.savePlaylist(key, stream)
                        finishRenewal(allowAgain = true)
                        player.sendCustomCommand(AudioPlaybackService.RENEW_HLS, Bundle().apply {
                            putString("playbackId", id)
                            putString("uri", uri.toString())
                            putByteArray("playlist", stream.playlist)
                        })
                        updateAudioRequestMessage(null)
                    }
                },
                onFailure = {
                    finishRenewal()
                    updateAudioRequestMessage("无法续期播放地址，请重新打开")
                },
            )
            return
        }
        val cachedLink = if (wasHls) mediaAddresses.link(key) else null
        if (cachedLink != null) renew(cachedLink)
        else mediaLinks.resolve(
            entry, account, lane = RequestLane.RENEWAL,
            onFailure = { finishRenewal() }, onResult = ::renew,
        )
    }

    private fun cancelPendingAudioRequest() {
        audioPlayToken?.let(AudioPlayRequestGate::cancel)
        audioPlayToken = null
        dispatchedAudioEntry = null
        audioRequestId++
        audioResolver.cancel()
        mediaLinks.dismissCaptcha(RequestLane.AUDIO)
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
        val back10 = icon(R.drawable.ic_replay30).apply { contentDescription = "后退 30 秒" }
        val play = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_play_accent)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setImageResource(R.drawable.ic_play)
            setColorFilter(color(R.color.onAccent))
        }
        val forward10 = icon(R.drawable.ic_forward30).apply { contentDescription = "前进 30 秒" }
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
        val timer = audioIconButton(R.drawable.ic_timer, "睡眠定时", color(R.color.ink3)) {}
        fun paintTimer() {
            timer.setColorFilter(color(if (SleepTimer.isArmed()) R.color.accent else R.color.ink3))
            val remaining = SleepTimer.remainingMs()
            timer.contentDescription = when {
                SleepTimer.isArmed() -> "睡眠定时，剩余 ${formatAudioTime((remaining / 1000).toInt())}"
                SleepTimer.stopAfterCurrent -> "本集结束后停止"
                else -> "睡眠定时"
            }
        }
        timer.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor, Gravity.END)
            menu.menu.add(0, 0, 0, "关闭定时").isChecked = !SleepTimer.isArmed() && !SleepTimer.stopAfterCurrent
            listOf(15, 30, 45, 60).forEachIndexed { index, minutes ->
                menu.menu.add(0, index + 1, index + 1, "$minutes 分钟")
            }
            menu.menu.add(0, 99, 99, "本集结束后停止")
            menu.menu.setGroupCheckable(0, true, false)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> {
                        SleepTimer.disarm()
                        SleepTimer.consumeStopAfterCurrent()
                        sendSleepTimer(0)
                    }
                    99 -> SleepTimer.armStopAfterCurrent()
                    else -> sendSleepTimer(listOf(15, 30, 45, 60)[item.itemId - 1])
                }
                paintTimer()
                true
            }
            menu.show()
        }
        paintTimer()
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
                addView(timer, LinearLayout.LayoutParams(dp(44), dp(44)))
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
        val playbackControls = column().apply {
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
        }
        val controlsScroll = ScrollView(this).apply {
            addView(playbackControls, FrameLayout.LayoutParams(-1, -2))
        }
        val playbackBody = column().apply {
            addView(controlsScroll, LinearLayout.LayoutParams(-1, -2))
            addView(courseList.view, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val playbackContent = column().apply {
            isClickable = true
            accessibilityPaneTitle = "音频播放"
            addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
            addView(View(this@MainActivity).apply {
                setBackgroundColor(color(R.color.line))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(playbackBody, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val page = centeredContent(playbackContent).apply { isClickable = true }
        var compactHeader = false
        page.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            val split = width >= dp(EXPANDED_WINDOW_DP) ||
                (width >= dp(WIDE_WINDOW_DP) && height < dp(360))
            val orientation = if (split) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (playbackBody.orientation != orientation) {
                playbackBody.orientation = orientation
                controlsScroll.layoutParams = if (split) LinearLayout.LayoutParams(0, -1, 1f)
                else LinearLayout.LayoutParams(-1, -2)
                courseList.view.layoutParams = if (split) LinearLayout.LayoutParams(0, -1, 1f)
                else LinearLayout.LayoutParams(-1, 0, 1f)
            }
            val compact = height < dp(440)
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
                canLoadCourses = pagination?.let { (file, key) -> hasMoreCourses(file, key) } == true
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
            paintTimer()
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
        // 显式 30 秒：和通知栏 / 耳机线控的 30 秒保持一致，不依赖播放器增量
        back10.setOnClickListener {
            player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L))
        }
        forward10.setOnClickListener {
            val target = player.currentPosition + 30_000L
            val duration = player.duration
            player.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
        }
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
            renderHomeSections()
        }
    }

    private fun stopBackgroundAudio() {
        pendingQueue = null
        PlaybackQueueState.anchor = null
        PlaybackQueueState.entries = emptyList()
        PlaybackQueueState.listKey = null
        diskRenewAttempts = 0
        lastDiskRenewAt = 0L
        renderCourseSwitch()
        cancelPendingAudioRequest()
        updateAudioRequestMessage(null)
        controller?.sendCustomCommand(AudioPlaybackService.CLEAR_FILE, Bundle.EMPTY)
        nowPlaying.visibility = View.GONE
        renderHomeSections()
    }

    @OptIn(UnstableApi::class)
    private fun startVideo(
        entry: PanEntry,
        positionMs: Long? = null,
        playWhenReady: Boolean = true,
        retryOnExpiredLink: Boolean = true,
        audioOnly: Boolean = false,
        fullscreen: Boolean = false,
        orientationBeforeFullscreen: Int = videoPage?.orientationBeforeFullscreen ?: requestedOrientation,
    ) {
        if (!activityVisible || !hasActiveSession(entry.provider)) {
            if (activityVisible) toast("请先确认网盘登录后重试")
            return
        }
        val quark = entry.provider == SourceProvider.QUARK
        videoToRestore = null
        val account = playbackAccount(entry)
        val key = mediaCacheKey(account, entry)
        pendingQueue = pendingQueue?.takeIf { sameEntry(it.entry, entry) }
        loadingVideo = VideoPlaybackPage.Snapshot(
            entry, account, positionMs, playWhenReady, audioOnly, fullscreen, orientationBeforeFullscreen,
        )
        cancelPendingAudioRequest()
        controller?.pause()
        videoResolver.cancel()
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
        fun openStream(result: Result<MediaSource>) {
            if (videoLoading !== loading) return
            videoLoading = null
            loadingVideo = null
            loading.dismiss()
            if (isDestroyed || isFinishing || account != playbackAccount(entry)) return
            result.onSuccess { source ->
                confirmPlayback(entry)
                val previousPage = videoPage
                val courseListKey = PlaybackQueueState.listKey
                videoPage = VideoPlaybackPage(
                    this, entry, source,
                    account = account,
                    history = AudioHistory(getSharedPreferences("video_history", MODE_PRIVATE), account),
                    startPositionMs = positionMs,
                    playWhenReady = playWhenReady,
                    retryOnExpiredLink = retryOnExpiredLink,
                    startAudioOnly = audioOnly,
                    startFullscreen = fullscreen,
                    orientationBeforeFullscreen = orientationBeforeFullscreen,
                    onRetry = { snapshot, automatic ->
                        if (videoLoading == null && account == playbackAccount(entry)) {
                            if (!quark) mediaAddresses.clearPlaylist(key)
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
                    canLoadMoreCourses = { hasMoreCourses(entry, courseListKey) },
                    onLoadMoreCourses = { done -> loadMoreCourses(entry, courseListKey, done = done) },
                    onSelectCourse = { target, snapshot ->
                        if (videoLoading == null) startVideo(
                            target, audioOnly = snapshot.audioOnly, fullscreen = snapshot.fullscreen,
                        )
                    },
                    onSwitch = { delta, snapshot -> playSibling(entry, delta, videoState = snapshot) },
                    onEnded = { snapshot ->
                        val endedPage = videoPage
                        advanceAfterEnd(entry, isCurrent = {
                            activityVisible && videoLoading == null && videoPage === endedPage &&
                                endedPage?.canAutoAdvance() == true
                        }) {
                            playSibling(entry, 1, automatic = true, videoState = snapshot)
                        }
                    },
                    isFavorite = favorites.has(entry),
                    onFavorite = { account == playbackAccount(entry) && toggleFavorite(entry) },
                    onClearBackgroundAudio = {
                        controller?.sendCustomCommand(AudioPlaybackService.CLEAR_FILE, Bundle.EMPTY)
                        pendingQueue = null
                        updateNowPlaying()
                    },
                    onClose = { closed ->
                        if (videoPage === closed) {
                            videoPage = null
                            if (!isDestroyed) renderHomeSections()
                        }
                    },
                )
                previousPage?.close(keepOrientation = fullscreen)
            }.onFailure {
                AlertDialog.Builder(this)
                    .setTitle("无法原生播放")
                    .setMessage((it as? QuarkLoginRequired)?.message
                        ?: (it as? IllegalStateException)?.message ?: "读取视频播放地址失败。")
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
        fun mediaItem(uri: Uri) = MediaItem.Builder().setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.name).setArtist("拾课").build())
            .build()
        if (quark) {
            resolveQuarkPlayback(entry) { result ->
                openStream(result.mapCatching { it.mediaSource(this, mediaItem(it.uri)) })
            }
            return
        }
        val playlist = if (entry.isDisk) PanApi.diskVideoPlaylist(entry) else PanApi.videoPlaylist(entry)
        val cached = mediaAddresses.playlist(key, playlist)
        if (cached != null) {
            openStream(runCatching { cached.mediaSource(mediaItem(cached.uri)) })
        } else {
            videoResolver.start(playlist, web.settings.userAgentString) { result ->
                result.getOrNull()?.let { mediaAddresses.savePlaylist(key, it) }
                openStream(result.mapCatching { it.mediaSource(mediaItem(it.uri)) })
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
        var webAudioEnded = false
        val controls = AudioControls(
            this,
            entry.name,
            setPlaying = { playing ->
                webAudioEnded = false
                val action = if (playing) "a.play().catch(() => {})" else "a.pause()"
                web.evaluateJavascript(
                    "(() => { const a = window.__groupPlayerAudio; if (a) $action; })()",
                    null,
                )
            },
            seekBy = { delta ->
                web.evaluateJavascript(
                    "(() => { const a = window.__groupPlayerAudio; if (a) a.currentTime = " +
                        "Math.max(0, Math.min(a.duration || 0, a.currentTime + ($delta))); })()",
                    null,
                )
            },
        )
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
            if (index >= 0) courseList.update(queue, index, hasMoreCourses(entry, listKey), loadingCourses)
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
                if (!isDestroyed) renderHomeSections()
            }
        }
        dialog.show()
        val toggle = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        toggle.isEnabled = false
        toggle.setOnClickListener {
            webAudioEnded = false
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
                        val justEnded = ended && !webAudioEnded
                        webAudioEnded = ended
                        if (justEnded && activityVisible) {
                            advanceAfterEnd(entry, isCurrent = {
                                dialog.isShowing && activityVisible && webAudioEnded && account == verifiedAccount
                            }) {
                                switchWebAudio(dialog, 1, automatic = true)
                            }
                            if (!dialog.isShowing) return@evaluateJavascript
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
        acceptAnyErrno: Boolean = false,
        onResult: (JSONObject) -> Unit,
    ) {
        if (!ready || !hasVerifiedAccount()) {
            onCancelled()
            return
        }
        val id = when (lane) {
            RequestLane.LIST -> ++requestId
            RequestLane.SEARCH -> ++searchRequestId
            RequestLane.COURSE -> ++courseRequestId
            RequestLane.AUDIO -> ++audioRequestId
            RequestLane.RENEWAL -> ++renewalRequestId
            RequestLane.WRITE -> ++writeRequestId
            RequestLane.DELETE_CHECK -> ++deleteCheckRequestId
        }
        mediaLinks.dismissCaptcha(lane)
        val playToken = audioPlayToken.takeIf { lane == RequestLane.AUDIO }
        fun isCurrent() = !isDestroyed && id == when (lane) {
            RequestLane.LIST -> requestId
            RequestLane.SEARCH -> searchRequestId
            RequestLane.COURSE -> courseRequestId
            RequestLane.AUDIO -> audioRequestId
            RequestLane.RENEWAL -> renewalRequestId
            RequestLane.WRITE -> writeRequestId
            RequestLane.DELETE_CHECK -> deleteCheckRequestId
        } && (playToken == null || AudioPlayRequestGate.isCurrent(playToken))
        fun showError(message: String?) {
            val text = message ?: "网盘请求失败，请重试。"
            when (lane) {
                RequestLane.LIST -> status.text = text
                RequestLane.AUDIO -> failAudioRequest(text)
                RequestLane.SEARCH, RequestLane.COURSE, RequestLane.RENEWAL, RequestLane.WRITE, RequestLane.DELETE_CHECK -> Unit
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
                    // 2156 是网盘明确返回的“未搜到结果”，不是请求失败。
                    if (lane == RequestLane.SEARCH && errno == 2156) {
                        if (onCurrentResult != null) onCurrentResult(json, ::isCurrent) else onResult(json)
                        return@evaluateJavascript
                    }
                    if (errno == -20 && onCaptcha != null) {
                        onCaptcha(json, ::isCurrent)
                    } else if (acceptAnyErrno) {
                        if (onCurrentResult != null) onCurrentResult(json, ::isCurrent) else onResult(json)
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
            screen == Screen.FAVORITES -> closeFavorites()
            folders.isNotEmpty() -> returnToFolder(folders.lastIndex)
            screen != Screen.HOME -> showScreen(Screen.HOME)
            else -> super.onBackPressed()
        }
    }

    private fun restoreVideoIfReady() {
        val snapshot = videoToRestore ?: return
        if (!activityVisible || !hasActiveSession(snapshot.entry.provider)) return
        videoToRestore = null
        if (snapshot.account != playbackAccount(snapshot.entry)) {
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
        pdfPreviewRef?.close()
        cancelImageLoading()
        mediaLinks.dismissAnyCaptcha()
        nowPlaying.removeCallbacks(nowPlayingTicker)
        stoppedVideo = videoPage?.snapshot()
        videoPage?.pauseForBackground()
        videoResolver.cancel()
        videoLoading?.dismiss()
        videoLoading = null
        super.onStop()
    }

    override fun onDestroy() {
        searchSectionRef?.dismiss()
        requestId++
        audioPlayToken?.let(AudioPlayRequestGate::cancel)
        audioRequestId++
        audioResolver.close()
        videoResolver.close()
        renewalResolver.close()
        videoPage?.close()
        pdfPreviewRef?.release()
        imageFetcher.close()
        imageLoading?.dismiss()
        closeAudioPage()
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        audioControls?.close()
        audioControls = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    /** 账号失效或切换时清掉写操作锁（锁本身在 DiskMutationSection 内部）。 */
    private fun resetSectionGuards() {
        diskWrites.resetGuards()
    }

    // ===================== PdfPreviewHost =====================
    // 文档预览需要的最小宿主能力；取址/验证码已下沉到 MediaLinkSection。

    private inner class PdfPreviewHostAdapter : PdfPreviewHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity

        override fun setStatus(message: String) {
            status.text = message
        }

        override fun say(message: String) {
            hostActivity.toast(message)
        }

        override fun isVisible() = activityVisible
        override fun isDestroyed() = this@MainActivity.isDestroyed
        override fun isReady() = ready
        override fun account() = verifiedAccount
        override fun accountVerified() = hasVerifiedAccount()
        override fun sessionReady(provider: SourceProvider) = hasActiveSession(provider)
        override fun playbackAccount(entry: PanEntry) = this@MainActivity.playbackAccount(entry)

        override fun bumpListRequest() {
            requestId++
        }

        override fun verifyAccount() = verifyAndRefresh()

        override fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit) =
            ensureBdstoken(onReady = onReady, onFailure = onFailure)

        override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
            web.evaluateJavascript(script, onResult)
        }

        override fun openPdfReader(entry: PanEntry, file: File) = startPdfReader(entry, file)

        override fun fetchJson(
            url: String,
            post: Boolean,
            form: String?,
            statusHint: String?,
            lane: RequestLane,
            onFailure: () -> Unit,
            onResult: (JSONObject) -> Unit,
        ) = load(
            url, post = post, form = form, statusHint = statusHint, lane = lane,
            onFailure = onFailure, onResult = onResult,
        )
    }

    // ===================== MediaLinkHost =====================
    // 媒体取址层需要的最小宿主能力（状态栏 / 可见性 / 一次请求 / 一段 WebView 脚本）。

    private inner class MediaLinkHostAdapter : MediaLinkHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity

        override fun setStatus(message: String) {
            status.text = message
        }

        override fun failAudio(message: String) = failAudioRequest(message)

        override fun isVisible() = activityVisible

        override fun userAgent() = web.settings.userAgentString

        override fun saveAddress(key: String, link: Uri) = mediaAddresses.saveLink(key, link)

        override fun evaluateJavascript(script: String, onResult: (String?) -> Unit) {
            web.evaluateJavascript(script, onResult)
        }

        override fun runOnUiThread(action: () -> Unit) {
            this@MainActivity.runOnUiThread(action)
        }

        override fun fetchJson(
            url: String,
            post: Boolean,
            form: String?,
            statusHint: String?,
            lane: RequestLane,
            onCancelled: () -> Unit,
            onFailure: () -> Unit,
            onCaptcha: ((JSONObject, () -> Boolean) -> Unit)?,
            onCurrentResult: ((JSONObject, () -> Boolean) -> Unit)?,
            onResult: (JSONObject) -> Unit,
        ) = load(
            url, post = post, form = form, statusHint = statusHint, lane = lane,
            onCancelled = onCancelled, onFailure = onFailure,
            onCaptcha = onCaptcha, onCurrentResult = onCurrentResult, onResult = onResult,
        )
    }

    // ===================== SearchHost =====================
    // 搜索模块需要的最小宿主能力。与 MutationHost 同样用私有 inner class，
    // 避免把 internal 类型（PanEntry / RequestLane / ShareSource）曝光到公开 API。

    private inner class SearchHostAdapter : SearchHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity

        override fun canSearch() = ready && hasVerifiedAccount()
        override fun account() = verifiedAccount
        override fun accountVerified() = hasVerifiedAccount()
        override fun shareSources() = this@MainActivity.shareSources()
        override fun settings() = getSharedPreferences("app_settings", MODE_PRIVATE)

        override fun fetchJson(
            url: String,
            post: Boolean,
            form: String?,
            lane: RequestLane,
            acceptAnyErrno: Boolean,
            onCancelled: () -> Unit,
            onFailure: () -> Unit,
            onResult: (JSONObject) -> Unit,
        ) = load(
            url, post = post, form = form, lane = lane, acceptAnyErrno = acceptAnyErrno,
            onCancelled = onCancelled, onFailure = onFailure, onResult = onResult,
        )

        override fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit) =
            ensureBdstoken(onReady = onReady, onFailure = onFailure)

        override fun say(message: String) {
            hostActivity.toast(message)
        }

        override fun sizeSheet(dialog: Dialog, height: (Int) -> Int) =
            this@MainActivity.sizeSheet(dialog, height)

        override fun delay(millis: Long, action: () -> Unit) {
            web.postDelayed(action, millis)
        }

        override fun activateGroup(group: String) = setActiveGroup(group)
        override fun activeSourceId() = gid

        override fun openEntry(entry: PanEntry, entries: List<PanEntry>) =
            this@MainActivity.openEntry(entry, entries, null)

        override fun openSourceDirectory(entry: PanEntry) =
            this@MainActivity.openSourceDirectory(entry)

        override fun openFolderTrail(group: String, trail: List<PanEntry>) =
            this@MainActivity.openFolderTrail(group, trail)

        override fun cancelSearchRequests() {
            searchRequestId++
        }
    }

    // ===================== DiskMutationHost =====================
    // 网盘写操作需要的最小宿主能力：一次 JSON 请求 + 状态栏 + 刷新 + 选中态。
    // 用 inner class 而不是让 Activity 直接实现，是为了让这些转发方法保持私有可见性，
    // 不必把 PanEntry / RequestLane 这些 internal 类型曝光到公开 API。

    private inner class MutationHost : DiskMutationHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity
        override val probeId: Int get() = accountProbeId

        override fun onDiskScreen() = screen == Screen.DISK
        override fun selecting() = selectionMode
        override fun activityDestroyed() = isDestroyed
        override fun diskDir() = currentDiskDir()
        override fun diskCacheKey() = currentDiskCacheKey()
        override fun selection() = visibleSelectionEntries()
        override fun sessionReady() = hasActiveSession()
        override fun accountVerified() = hasVerifiedAccount()

        override fun fetchJson(
            url: String,
            post: Boolean,
            form: String?,
            lane: RequestLane,
            acceptAnyErrno: Boolean,
            onCancelled: () -> Unit,
            onFailure: () -> Unit,
            onResult: (JSONObject) -> Unit,
        ) = load(
            url, post = post, form = form, lane = lane, acceptAnyErrno = acceptAnyErrno,
            onCancelled = onCancelled, onFailure = onFailure, onResult = onResult,
        )

        override fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit) =
            ensureBdstoken(onReady = onReady, onFailure = onFailure)

        override fun showStatus(message: String) {
            status.text = message
        }

        override fun say(message: String) {
            hostActivity.toast(message)
        }

        override fun reloadPage() = refresh()

        override fun resetCourseList() {
            courseRequestId++
            courseEntries.clear()
            courseParentKey = ""
        }

        override fun bumpCourseRequest() {
            courseRequestId++
        }

        override fun invalidateDiskCaches() {
            diskIndexCache = null
            diskSearchFingerprints.clear()
            courseCache.clearDisk()
        }

        override fun leaveSelection() = exitSelection()

        override fun openWeb(url: String) = showWeb(url)

        override fun pickFromSheet(
            title: String,
            subtitle: String,
            choices: List<SheetChoice>,
            onSelect: (SheetChoice) -> Unit,
        ) = showChoiceSheet(title = title, subtitle = subtitle, choices = choices, onSelect = onSelect)

        override fun delay(millis: Long, action: () -> Unit) {
            web.postDelayed(action, millis)
        }

        override fun resetWriteGuards() = resetSectionGuards()
    }

    private companion object {
        const val STATE_SCREEN = "screen"
        const val STATE_FILTER = "media_filter"
        const val STATE_TRANSFERRED_ONLY = "transferred_only"
        const val STATE_FAVORITES_ORIGIN = "favorites_origin"
        const val STATE_FOLDERS = "folder_trail"
        /** 「待确认删除」登记的有效期：超过后以服务端列表为准，避免文件被永久隐藏。 */
        const val PENDING_DELETE_TTL_MS = 30_000L
    }
}

// The playback service and its UI run in the same process. Keep the queue when Android recreates the UI.
private object PlaybackQueueState {
    var anchor: PanEntry? = null
    var entries: List<PanEntry> = emptyList()
    var listKey: String? = null
}
