package com.hnwd.bdplayer

import android.Manifest
import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.view.WindowManager
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsControllerCompat
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
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.net.URL
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import androidx.core.net.toUri
import androidx.core.view.isGone

internal const val CACHE_TTL_MS = 30 * 60 * 1000L
internal const val HOME_RECHECK_COOLDOWN_MS = 30 * 1000L
internal const val API_PAGE = "https://${PanApi.HOST}/__shike_api"

class MainActivity : AppCompatActivity() {
    internal lateinit var web: WebView
    internal lateinit var native: LinearLayout
    internal lateinit var content: LinearLayout
    internal lateinit var contentScroll: ScrollView
    internal var homeRefreshFeedback: HomeRefreshFeedback? = null
    internal lateinit var status: TextView
    internal lateinit var title: TextView
    internal lateinit var appSub: TextView
    internal lateinit var sourceName: TextView
    internal lateinit var sourceButton: View
    internal lateinit var searchButton: View
    internal lateinit var refreshButton: View
    internal lateinit var btnDownloads: View
    internal lateinit var downloadBadge: TextView
    internal lateinit var sectionRow: LinearLayout
    internal lateinit var sectionTitle: TextView
    internal lateinit var sortButton: TextView
    internal lateinit var btnSelect: TextView
    internal lateinit var batchBar: View
    internal lateinit var batchCount: TextView
    internal lateinit var batchAll: TextView
    internal lateinit var batchFav: TextView
    internal lateinit var batchDone: TextView
    internal lateinit var batchMove: TextView
    internal lateinit var batchCopy: TextView
    internal lateinit var batchDelete: TextView
    internal lateinit var fabAdd: View
    internal lateinit var crumbsScroll: View
    internal lateinit var crumbsRow: LinearLayout
    internal lateinit var filterBar: View
    internal lateinit var filterCount: TextView
    internal lateinit var transferredChip: TextView
    internal var transferredOnly = false
    internal var diskIndexCache: Set<String>? = null
    internal val diskSearchFingerprints = mutableSetOf<String>()

    internal var writeRequestId = 0
    internal var deleteCheckRequestId = 0
    internal var downloadRequestId = 0
    /**
     * 网盘写操作（新建/重命名/移动/复制/删除/分享/转存）集中在这里，状态与锁都在它内部。
     *
     * 用可空引用 + getter，而不是 by lazy：账号失效时要重置写锁，那时如果按需创建，
     * 会为了「清一个还没建过的锁」先造出一个 Section。销毁路径同理只碰已存在的实例。
     */
    internal var diskWritesRef: DiskMutationSection? = null
    internal val diskWrites: DiskMutationSection
        get() = diskWritesRef ?: DiskMutationSection(MutationHost()).also { diskWritesRef = it }

    /** 设置页（账号行 / 主题 / 清记录 / 备份 / 关于）。 */
    internal val settingsPage by lazy { SettingsPage(SettingsHostAdapter()) }

    /** 图片预览：签名看图直连 / 原图下载 / 内置查看器。 */
    internal var imagePreviewRef: ImagePreviewSection? = null
    internal val imagePreview: ImagePreviewSection
        get() = imagePreviewRef ?: ImagePreviewSection(ImagePreviewHostAdapter(), mediaLinks, mediaAddresses)
            .also { imagePreviewRef = it }

    /** 文档（PDF）预览：取址 -> 下载 -> 打开阅读器。 */
    internal var pdfPreviewRef: PdfPreviewSection? = null
    internal val pdfPreview: PdfPreviewSection
        get() = pdfPreviewRef ?: PdfPreviewSection(PdfPreviewHostAdapter(), mediaLinks, mediaAddresses)
            .also { pdfPreviewRef = it }

    /** 媒体取址（群组 sharedownload / 我的网盘下载签名 / 验证码），三条预览链共用。 */
    internal val mediaLinks by lazy { MediaLinkSection(MediaLinkHostAdapter()) }

    /** 长按下载：取址 -> 系统下载队列 -> 下载管理页。 */
    internal val downloads by lazy { DownloadSection(DownloadHostAdapter(), mediaLinks) }

    /** 搜索（输入弹窗 / 结果面板 / 定位）；按需创建，销毁时只碰已存在的实例。 */
    internal var searchSectionRef: SearchSection? = null
    internal val searchSection: SearchSection
        get() = searchSectionRef ?: SearchSection(SearchHostAdapter()).also { searchSectionRef = it }
    internal lateinit var navRoots: List<View>
    internal lateinit var historyPanel: LinearLayout
    internal lateinit var nowPlaying: View
    internal lateinit var courseSwitch: LinearLayout
    internal lateinit var btnPrev: TextView
    internal lateinit var btnNext: TextView
    internal lateinit var courseHint: TextView
    internal lateinit var audioHistory: AudioHistory
    internal lateinit var videoHistory: AudioHistory
    internal lateinit var favorites: Favorites
    internal lateinit var courseCache: CourseCache
    internal var cacheNote = ""
    internal var audioControls: AudioControls? = null
    internal var audioPage: View? = null
    internal var releaseAudioPage: (() -> Unit)? = null
    internal var pendingAudioPage: Pair<Int, PanEntry>? = null
    internal val videoResolver = VideoStreamResolver()
    internal val audioResolver = VideoStreamResolver()
    internal val renewalResolver = VideoStreamResolver()
    internal var diskRenewAttempts = 0
    internal var lastDiskRenewAt = 0L
    internal val mediaAddresses by lazy { MediaAddressCache(this) }
    private var launchedPreviewFile: File? = null
    private val previewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            launchedPreviewFile?.let(PreviewCache.get(this)::release)
            launchedPreviewFile = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val index = result.data?.getIntExtra(ImageViewerActivity.EXTRA_SWITCH_INDEX, -1) ?: -1
            val entry = imagePreview.galleryEntry(index) ?: return@registerForActivityResult
            val original = result.data?.getBooleanExtra(ImageViewerActivity.EXTRA_OPEN_ORIGINAL, false) == true
            val saveOriginal = result.data?.getBooleanExtra(ImageViewerActivity.EXTRA_SAVE_ORIGINAL, false) == true
            native.post {
                if (original) imagePreview.openOriginal(entry, save = saveOriginal)
                else imagePreview.open(entry, imagePreview.entries())
            }
        }
    internal var videoLoading: AlertDialog? = null
    internal var videoOpenError: AlertDialog? = null
    internal var videoPage: VideoPlaybackPage? = null
    internal val videoSleepTimer = SleepTimer()
    internal var controllerFuture: ListenableFuture<MediaController>? = null
    internal var controller: MediaController? = null
    internal val uiExecutor = Executor { runOnUiThread(it) }
    internal val backupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            ThemeStore.rememberScreen(this, screen.name)
            ThemeStore.apply(this)
            recreate()
        }
    }
    internal val quarkLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refresh()
        // 选文件返回带 file_id；登录成功返回是 RESULT_OK 且不带 data，两者不能混。
        val pickedFile = if (result.resultCode == RESULT_OK) {
            QuarkDriveActivity.selectedFile(result.data)
        } else null
        pickedFile?.let { file ->
            val entry = quarkEntry(file, DISK_SOURCE_ID, EntryOrigin.DISK)
            native.post { openEntry(entry, listOf(entry)) }
        }
        if (quarkLoggedIn()) {
            markQuarkEverLogged()
            pendingAddProvider = null
            // 登录成功回主界面后不能只 refresh：夸克内容靠「监控目录」才有，光有 Cookie
            // 首页依旧是空的。这里直接把目录勾选 sheet 推出来，省掉用户自己找入口。
            // 只认「登录成功返回」；选文件返回、按返回取消登录都不弹，避免打断播放。
            if (pickedFile == null && result.resultCode == RESULT_OK) manageQuarkSources()
        } else if (pendingAddProvider == SourceProvider.QUARK) {
            pendingAddProvider = null
        }
    }

    internal val uploadLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) startUpload(uris)
        }

    /**
     * 通知权限只决定进度通知能否出现在通知抽屉；前台服务本身不依赖它。
     * 上传不等这个回调，避免权限弹窗期间 Activity 被切走导致文件丢失。
     */
    internal val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var uploadObserver: ((UploadCenter.State) -> Unit)? = null

    internal var mediaFilter = MediaFilter.ALL
    internal var redrawFilteredRows: (() -> Unit)? = null
    internal var showingHomeDirectory = false
    internal lateinit var mediaChips: List<TextView>

    // V02-2 批量操作
    internal var selectionMode = false
    internal val selectedKeys = LinkedHashSet<String>()
    internal var selectionScreen: Screen? = null
    internal var lastRenderedEntries: List<PanEntry> = emptyList()

    internal var courseOrder = CourseOrder.ASCENDING

    internal val folders = mutableListOf<PanEntry>()
    internal val courseEntries = mutableListOf<PanEntry>()
    internal var courseParentKey = ""
    internal val folderPositions = mutableMapOf<List<String>, FolderPosition>()
    internal var searchRequestId = 0

    internal var courseRequestId = 0
    internal var automaticCourseLoad: PanEntry? = null
    internal var historyRefreshScheduled = false
    internal var historyRefreshPending = false

    internal var audioRequestId = 0
    internal var audioPlayToken: Long? = null
    internal var dispatchedAudioEntry: PanEntry? = null
    internal var pendingAudioEntry: PanEntry? = null
    internal var stoppedVideo: VideoPlaybackPage.Snapshot? = null
    internal var videoToRestore: VideoPlaybackPage.Snapshot? = null
    internal var loadingVideo: VideoPlaybackPage.Snapshot? = null
    internal var pendingQueue: QueueSelection? = null
    /** onCreate 收到媒体通知 action 时置位，等 controller 异步就绪后再展开播放页。 */
    private var pendingOpenPlayer = false
    internal var audioRequestMessage: String? = null
    internal var refreshAudioPageMessage: (() -> Unit)? = null
    internal val nowPlayingTicker = object : Runnable {
        override fun run() {
            updateNowPlaying()
            updateFabPosition()
            updateDownloadBadge()
            nowPlaying.postDelayed(this, 1_000)
        }
    }
    internal var screen = Screen.HOME
    internal var recent = true
    internal lateinit var shell: LinearLayout
    internal lateinit var navigationRail: ScrollView
    internal var railVisible = false
    internal val sheetHeights = java.util.WeakHashMap<Dialog, (Int) -> Int>()
    internal var activityVisible = false
    internal var ready = false
    @Volatile internal var requestId = 0
    internal var gid = ""
    /** 网盘页当前网盘，与首页/群组的来源状态相互独立（v0.3 §8 #7）。 */
    internal var diskProvider = SourceProvider.BAIDU
    internal var bdstoken = ""
    internal var homeGroupChip: TextView? = null
    internal lateinit var btnFavorites: View
    internal lateinit var favBadge: TextView
    internal lateinit var btnBack: View
    internal val tabFolders = mutableMapOf<Screen, List<PanEntry>>()
    internal val tabScroll = mutableMapOf<Screen, Int>()
    internal var favoritesOrigin = Screen.HOME
    internal var loginPending = false
    internal var pickingSource = false
    /** 添加登录流程中，登录成功后要接着打开的来源选择器。 */
    internal var pendingAddProvider: SourceProvider? = null
    internal var verifiedAccount = ""
    internal var verifiedAccountName = ""
    internal var verifiedAccountAvatar = ""
    internal var accountProbeId = 0
    internal var renewalRequestId = 0
    internal var renewingPlaybackId: String? = null
    internal var renewedPlaybackId: String? = null
    internal var invalidatedRenewedPlaybackId: String? = null
    internal var checkingAccount = false
    internal var homeDisplayedEntries: List<PanEntry> = emptyList()
    internal var pendingHomeUpdate: List<PanEntry>? = null
    internal var pendingHomeUpdateNote = ""
    internal var homeCheckInFlight = false
    internal var homeCheckToken = 0
    internal var lastHomeCheckAt = 0L
    internal var observedGroup: Pair<String, String>? = null
    internal lateinit var addSourceButton: View

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_OPEN_PLAYER) {
            pendingOpenPlayer = true
            // 通知 action 只是一次性事件；清掉，避免后续 recreate()（如备份恢复）
            // 读到残留 action 而误弹播放页。
            intent.action = null
        }
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
        PreviewCache.get(this).scheduleTrim()
        AccountScope.init(this)
        DriveSourceRouter.bindActiveSource(this) { gid }
        diskProvider = runCatching {
            SourceProvider.valueOf(
                getSharedPreferences("account_scope", MODE_PRIVATE)
                    .getString(AccountScope.key("disk_provider"), "").orEmpty(),
            )
        }.getOrDefault(SourceProvider.BAIDU)
        DriveSourceRouter.bindDiskProvider(this, { diskProvider }, { diskProvider = it })
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
                            val route = view.url.orEmpty().toUri().fragment.orEmpty()
                            if (pickingSource && route.startsWith("/im/session?")) {
                                observedGroup = route to group
                            }
                        }
                    }
                    return null
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (url.toUri().fragment == "/im/addrBook") observedGroup = null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (pickingSource && url.toUri().host == "wappass.baidu.com") loginPending = true
                    ready = false
                    invalidateAccount()
                    if (native.visibility == View.VISIBLE) refresh()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (view.url?.substringBefore('#') != url.substringBefore('#')) return
                    val address = url.toUri()
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
        btnDownloads = shell.findViewById(R.id.btnDownloads)
        downloadBadge = shell.findViewById(R.id.downloadBadge)
        btnDownloads.setOnClickListener { openDownloads() }
        updateDownloadBadge()
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
        homeRefreshFeedback = HomeRefreshFeedback(this, shell.findViewById(R.id.homeRefreshNotice))
        courseSwitch = shell.findViewById(R.id.courseSwitch)
        btnPrev = shell.findViewById(R.id.btnPrev)
        btnNext = shell.findViewById(R.id.btnNext)
        courseHint = shell.findViewById(R.id.courseHint)
        sourceButton.setOnClickListener { chooseSource() }
        searchButton = shell.findViewById<View>(R.id.btnSearch).apply {
            setOnClickListener { searchSection.show() }
        }
        refreshButton = shell.findViewById(R.id.btnRefresh)
        refreshButton.setOnClickListener { requestBrowseRefresh() }
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
        fabAdd = shell.findViewById(R.id.fabAdd)
        fabAdd.setOnClickListener { showAddSheet() }
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
                    // 通知拉起时 onStart 可能早于这里（controller 还是 null），
                    // 上面那次尝试会保留 pendingOpenPlayer；controller 就绪后补一次。
                    // 若此刻账号校验还没完成，ownsAudio 守卫仍不成立 → 继续保留标志，
                    // 由 updateNowPlaying() 在迷你条可见时做最终收敛。
                    retryOpenPlayerIfPending()
                    // 连接失败时不留悬挂标志，避免后续 onStart 反复空转或延迟弹页。
                    if (controller == null) pendingOpenPlayer = false
                }
            }, uiExecutor)
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

    override fun onResume() {
        super.onResume()
        // 从夸克登录页返回后，设置页的网盘登录态需要重算。
        if (screen == Screen.SETTINGS && ready) refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_PLAYER) {
            // Activity 存活时 controller / 账号校验可能仍在异步建立，openCurrentPlayback()
            // 的守卫此刻不成立会静默失败。先挂起标志，让 controller 就绪回调与
            // updateNowPlaying() 在条件成立时收敛；若播放页已在前台会被立即清零。
            pendingOpenPlayer = true
            // 同上：消费掉一次性 action，避免成为持久 base intent。
            intent.action = null
            openCurrentPlayback()
        }
    }

    /**
     * 媒体通知点击：把当前播放页带到前台。
     * 不主动重建视频页——后台 Service 可能仍持有旧页面的 player，重建会造成双播放器。
     * 播放页已在（Dialog / 音频页）时无需动作；常规后台音频则展开音频播放页。
     *
     * 冷启动（Activity 被回收）时 controller 与账号校验都是异步的，且 showPlayback 的
     * ownsAudio() 守卫依赖异步的 verifiedAccount（WebView probeAccount），过早调用会
     * 静默失败。因此只在守卫确实成立时才展开并清零 [pendingOpenPlayer]；否则保留标志，
     * 由 [retryOpenPlayerIfPending] 在账号校验通过（迷你条可见）后再次触发。
     */
    private fun openCurrentPlayback() {
        when {
            videoPage != null || audioPage != null -> pendingOpenPlayer = false
            else -> controller?.let { player ->
                if (ownsAudio(player)) {
                    // 从 H5/WebView 页点通知回来时主界面被隐藏（showWeb 把 native 置 GONE），
                    // 先恢复主界面再展开，否则 showPlayback 的可见性守卫会一直不成立。
                    if (native.visibility != View.VISIBLE) showApp()
                    if (native.visibility == View.VISIBLE) {
                        pendingOpenPlayer = false
                        showPlayback(player)
                    }
                }
            }
        }
    }

    /** 通知拉起时的重试钩子：onStart / controller 就绪 / 账号校验通过三处都会调用。 */
    internal fun retryOpenPlayerIfPending() {
        if (pendingOpenPlayer) openCurrentPlayback()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::shell.isInitialized) return
        updateWindowLayout()
        sheetHeights.entries.removeAll { !it.key.isShowing }
        sheetHeights.forEach { (dialog, height) -> dialog.window?.sizeBottomSheet(this, height) }
    }

    internal inner class AudioCourseList(
        internal val onSelect: (PanEntry) -> Unit,
        internal val onMore: () -> Unit,
        internal val onSorted: () -> Unit,
    ) {
        internal var courses: List<PanEntry> = emptyList()
        internal var current = -1
        internal var sortTarget: Pair<PanEntry, String?>? = null
        internal val heading = label("课程", 13.5f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.ink2))
            gravity = Gravity.CENTER_VERTICAL
        }
        internal val more = TextView(this@MainActivity).apply {
            text = "加载更多"
            textSize = 11.5f
            setTextColor(color(R.color.ink2))
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            setOnClickListener { onMore() }
        }
        internal val sort = TextView(this@MainActivity).apply {
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
        internal val rows = ListView(this@MainActivity).apply {
            divider = null
            setPadding(dp(8), 0, dp(8), dp(6))
            clipToPadding = false
        }
        internal val adapter = object : BaseAdapter() {
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

    @Deprecated("Android back button compatibility")
    override fun onBackPressed() {
        when {
            audioPage != null -> closeAudioPage()
            native.isGone -> showApp()
            screen == Screen.FAVORITES -> closeFavorites()
            folders.isNotEmpty() -> returnToFolder(folders.lastIndex)
            screen != Screen.HOME -> showScreen(Screen.HOME)
            else -> super.onBackPressed()
        }
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
        videoLoading?.show()
        videoOpenError?.show()
        // controller 与账号校验可能都还没就绪；openCurrentPlayback 只在守卫成立时
        // 才展开并清零，否则保留标志，等 controller 回调 / updateNowPlaying 继续重试。
        retryOpenPlayerIfPending()
        updateDownloadBadge()
        maybeCheckHomeInBackground()
    }

    override fun onStop() {
        activityVisible = false
        pdfPreviewRef?.close()
        imagePreviewRef?.close()
        mediaLinks.dismissAnyCaptcha()
        nowPlaying.removeCallbacks(nowPlayingTicker)
        stoppedVideo = videoPage?.snapshot()
        videoPage?.pauseForBackground()
        if (videoOpenError?.isShowing == true) dismissVideoOpenError()
        videoLoading?.dismiss()
        if (loadingVideo?.audioOnly != true || videoPage?.canPlayInBackground() != true) {
            videoResolver.cancel()
            videoLoading = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        uploadObserver?.let { UploadCenter.removeListener(it) }
        uploadObserver = null
        dismissVideoOpenError()
        homeRefreshFeedback?.clear()
        searchSectionRef?.dismiss()
        requestId++
        audioPlayToken?.let(AudioPlayRequestGate::cancel)
        audioRequestId++
        audioResolver.close()
        videoResolver.close()
        renewalResolver.close()
        videoPage?.close()
        pdfPreviewRef?.release()
        imagePreviewRef?.release()
        launchedPreviewFile?.let(PreviewCache.get(this)::release)
        launchedPreviewFile = null
        closeAudioPage()
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        audioControls?.close()
        audioControls = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        DriveSourceRouter.unbind(this)
        super.onDestroy()
    }

    internal fun dismissVideoOpenError() {
        val error = videoOpenError
        videoOpenError = null
        error?.dismiss()
    }

    /** 账号失效或切换时清掉写操作锁（锁本身在 DiskMutationSection 内部）。 */
    internal fun resetSectionGuards() {
        // 只碰已存在的实例：账号失效时可能还没发生过任何写操作
        diskWritesRef?.resetGuards()
    }

    // lateinit 的 ::prop.isInitialized 需要访问 backing field，扩展文件无法直接使用；
    // 由类内 helper 暴露，语义与原检查完全一致。
    internal fun batchBarReady() = ::batchBar.isInitialized
    internal fun batchMoveReady() = ::batchMove.isInitialized
    internal fun fabReady() = ::fabAdd.isInitialized

    /** 迷你播放条占据右下角时把 FAB 上移，避免两个浮层叠在一起。 */
    internal fun updateFabPosition() {
        if (!fabReady() || !::nowPlaying.isInitialized) return
        val params = fabAdd.layoutParams as? FrameLayout.LayoutParams ?: return
        val target = dp(if (nowPlaying.visibility == View.VISIBLE) 88 else 18)
        if (params.bottomMargin != target) {
            params.bottomMargin = target
            fabAdd.layoutParams = params
        }
    }

    internal fun apiError(url: String, errno: Int, json: JSONObject): String {
        val path = url.toUri().path.orEmpty()
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


    internal fun load(
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
            RequestLane.DOWNLOAD -> ++downloadRequestId
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
            RequestLane.DOWNLOAD -> downloadRequestId
        } && (playToken == null || AudioPlayRequestGate.isCurrent(playToken))
        fun showError(message: String?) {
            val text = message ?: "网盘请求失败，请重试。"
            when (lane) {
                RequestLane.LIST -> status.text = text
                RequestLane.AUDIO -> failAudioRequest(text)
                RequestLane.SEARCH, RequestLane.COURSE, RequestLane.RENEWAL, RequestLane.WRITE,
                RequestLane.DELETE_CHECK, RequestLane.DOWNLOAD -> Unit
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
                        val wasRefreshingHome = homeRefreshFeedback?.refreshing == true
                        invalidateAccount()
                        refresh()
                        val message = apiError(url, errno, json)
                        status.text = message
                        if (wasRefreshingHome && isHomeRoot()) {
                            homeRefreshFeedback?.showResult("登录已失效，请重新登录百度网盘")
                        }
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


    internal fun showWeb(url: String) {
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


    internal fun showApp(recheck: Boolean = false) {
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


    internal fun restoreVideoIfReady() {
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
            historyRecording = snapshot.historyRecording,
        )
    }

    // ===================== SettingsHost =====================
    // 设置页需要的最小宿主能力：页面容器、账号展示信息与各行的跳转动作。

    internal inner class SettingsHostAdapter : SettingsHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity

        override fun clearStatus() {
            status.text = ""
        }

        override fun inflatePage(): View =
            layoutInflater.inflate(R.layout.view_settings_page, content, false)

        override fun addPage(page: View) = content.addView(page)

        override fun inflateDriveRow(): View =
            layoutInflater.inflate(R.layout.item_settings_drive, null, false)

        override fun renderCourseSwitch() = this@MainActivity.renderCourseSwitch()

        override fun historyCount() =
            audioHistory.recentAcrossGroups().size + videoHistory.recentAcrossGroups().size

        override fun themeLabel() = ThemeStore.label(this@MainActivity)

        override fun showThemeDialog() = this@MainActivity.showThemeDialog()

        override fun launchBackup() {
            backupLauncher.launch(Intent(this@MainActivity, BackupActivity::class.java))
        }

        override fun openDownloads() = this@MainActivity.openDownloads()

        override fun downloadSummary(): String =
            transferActiveCount().takeIf { it > 0 }?.let { "$it 个进行中" } ?: ""

        override fun appVersionLine(): String {
            val version = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
            return "${getString(R.string.app_name)} $version · $packageName"
        }

        override fun quarkLoggedIn() = this@MainActivity.quarkLoggedIn()
        override fun markQuarkEverLogged() = this@MainActivity.markQuarkEverLogged()

        override fun account() = verifiedAccount
        override fun accountName() = verifiedAccountName
        override fun accountVerified() = hasVerifiedAccount()
        override fun shareSources() = this@MainActivity.shareSources()
        override fun renderAccountAvatar(icon: ImageView) = this@MainActivity.renderAccountAvatar(icon)

        override fun manageSources(provider: SourceProvider) {
            if (provider == SourceProvider.BAIDU) manageBaiduSources() else manageQuarkSources()
        }

        override fun startLogin(provider: SourceProvider) {
            if (provider == SourceProvider.BAIDU) {
                showLoginDialog(SourceProvider.BAIDU)
            } else {
                pendingAddProvider = SourceProvider.QUARK
                quarkLoginLauncher.launch(QuarkDriveActivity.loginIntent(this@MainActivity))
            }
        }

        override fun confirmClearHistory() = this@MainActivity.confirmClearHistory()

        override fun invalidateAccount() = this@MainActivity.invalidateAccount()

        override fun setCheckingAccount(checking: Boolean) {
            checkingAccount = checking
        }

        override fun clearPendingAddProvider() {
            pendingAddProvider = null
        }

        override fun pickFromSheet(
            title: String,
            subtitle: String,
            choices: List<SheetChoice>,
            onSelect: (SheetChoice) -> Unit,
        ) = showChoiceSheet(title = title, subtitle = subtitle, choices = choices, onSelect = onSelect)

        override fun settings() = getSharedPreferences("account_scope", MODE_PRIVATE)

        override fun say(message: String) {
            hostActivity.toast(message)
        }

        override fun refresh() = this@MainActivity.refresh()
    }

    /** 查看器启动是异步的，父页面保留文件直至预览返回。 */
    internal fun launchPreview(intent: Intent, file: File?) {
        val cache = PreviewCache.get(this)
        if (file != null) check(cache.retain(file)) { "预览缓存已失效，请重新打开。" }
        launchedPreviewFile?.let(cache::release)
        launchedPreviewFile = file
        try {
            previewLauncher.launch(intent)
        } catch (error: RuntimeException) {
            file?.let(cache::release)
            launchedPreviewFile = null
            throw error
        }
    }

    // ===================== ImagePreviewHost =====================
    // 图片预览需要的最小宿主能力。

    internal inner class ImagePreviewHostAdapter : ImagePreviewHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity

        override fun setStatus(message: String) {
            status.text = message
        }

        override fun say(message: String) = hostActivity.toast(message)

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

        override fun userAgent() = web.settings.userAgentString

        override fun launchViewer(intent: Intent, cachedFile: File?) = launchPreview(intent, cachedFile)

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

    // ===================== PdfPreviewHost =====================
    // 文档预览需要的最小宿主能力；取址/验证码已下沉到 MediaLinkSection。

    internal inner class PdfPreviewHostAdapter : PdfPreviewHost {
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

        override fun userAgent() = web.settings.userAgentString

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

    internal inner class MediaLinkHostAdapter : MediaLinkHost {
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

    // ===================== DownloadHost =====================
    // 下载取址需要的最小宿主能力：账号、WebView UA、bdstoken 与提示条。

    internal inner class DownloadHostAdapter : DownloadHost {
        override val hostActivity: android.app.Activity get() = this@MainActivity

        override fun account() = verifiedAccount
        override fun userAgent() = web.settings.userAgentString
        override fun sessionReady(provider: SourceProvider) = hasActiveSession(provider)
        override fun verifyAccount() = verifyAndRefresh()
        override fun isCurrent() = !isDestroyed && !isFinishing
        override fun say(message: String) = toast(message)
        override fun setStatus(message: String) {
            status.text = message
        }
        override fun runOnUiThread(action: () -> Unit) = this@MainActivity.runOnUiThread(action)
        override fun withBdstoken(onReady: (String) -> Unit, onFailure: () -> Unit) =
            ensureBdstoken(onReady = onReady, onFailure = onFailure)

        override fun onAdded(name: String) {
            updateDownloadBadge()
            Snackbar.make(shell, "已加入下载队列：$name", Snackbar.LENGTH_LONG)
                .setAction("查看进度") { openDownloads() }
                .show()
        }
    }

    internal fun openDownloads() {
        startActivity(Intent(this, DownloadActivity::class.java))
    }

    /** 系统文件选择器可多选；任务交给 UploadCenter 串行队列，本页只给一条可点提示。 */
    internal fun startUpload(sources: List<Uri>) {
        if (sources.isEmpty()) return
        showUploadSnackbar(sources.size)
        UploadCenter.enqueue(this, sources, currentDiskDir())
        // 前台服务只负责保活与通知；权限弹窗顺带申请，拒绝也不影响上传。
        UploadForegroundService.start(this)
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 上传不再弹模态进度框：进度统一在通知栏与「传输」页，主界面只留一条 Snackbar，
     * 这样上传期间顶栏、列表仍然可操作，也支持一次排队多个文件。
     */
    private fun showUploadSnackbar(count: Int) {
        Snackbar.make(shell, "已加入上传队列（$count 个文件）", Snackbar.LENGTH_LONG)
            .setAction("查看进度") { openDownloads() }
            .show()
        if (uploadObserver != null) return
        var seenRunning = false
        val observer: (UploadCenter.State) -> Unit = { state ->
            if (state.running) seenRunning = true
            if (state.finished && seenRunning) {
                seenRunning = false
                android.util.Log.i("ShikeUpload", state.message)
                if (!isFinishing && !isDestroyed) {
                    toast(state.message)
                    if (state.success) refresh()
                }
            }
        }
        uploadObserver = observer
        UploadCenter.addListener(observer)
    }

    /** 顶栏传输图标常显，下载与上传的「进行中」合计显示数量角标。 */
    internal fun updateDownloadBadge() {
        if (!::downloadBadge.isInitialized) return
        val count = transferActiveCount()
        downloadBadge.text = count.toString()
        downloadBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun transferActiveCount(): Int =
        DownloadCenter.activeCount(this) + UploadStore.activeCount(this)

    // ===================== SearchHost =====================
    // 搜索模块需要的最小宿主能力。与 MutationHost 同样用私有 inner class，
    // 避免把 internal 类型（PanEntry / RequestLane / ShareSource）曝光到公开 API。

    internal inner class SearchHostAdapter : SearchHost {
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

    internal inner class MutationHost : DiskMutationHost {
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

    }

    internal companion object {
        /** 媒体通知点击：把当前播放页带到前台（两个播放 Service 的 contentIntent 共用）。 */
        const val ACTION_OPEN_PLAYER = "com.hnwd.bdplayer.action.OPEN_PLAYER"
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
internal object PlaybackQueueState {
    var anchor: PanEntry? = null
    var entries: List<PanEntry> = emptyList()
    var listKey: String? = null
}
