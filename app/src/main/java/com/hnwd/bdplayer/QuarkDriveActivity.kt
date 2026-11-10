package com.hnwd.bdplayer

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

class QuarkDriveActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val folders = mutableListOf<QuarkFile>()
    private val items = mutableListOf<QuarkFile>()
    private val seenIds = hashSetOf<String>()
    private var session: QuarkPersonalDrive? = null
    private var search: String? = null
    private var moveSource: QuarkFile? = null
    private var nextPage = 1
    private var busy = false
    private var hasMore = false
    private var loginDialog: AlertDialog? = null
    private var playbackDialog: AlertDialog? = null
    private lateinit var root: LinearLayout
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var rows: ArrayAdapter<String>
    private lateinit var refresh: Button
    private lateinit var find: Button
    private lateinit var create: Button
    private lateinit var login: Button
    private lateinit var more: Button
    private lateinit var moveHere: Button

    // 导入 Cookie 时逐个域写入，保证 drive-pc.quark.cn 的原生请求也能带上会话标记。
    private val cookieHosts = listOf(
        "https://quark.cn/",
        "https://pan.quark.cn/",
        "https://uop.quark.cn/",
        "https://drive-pc.quark.cn/",
    )

    private val directory: String get() = folders.lastOrNull()?.id ?: QuarkPersonalDrive.ROOT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left + dp(12), bars.top, bars.right + dp(12), bars.bottom)
            insets
        }
        setContentView(root)
        val navigation = bar()
        navigation.addView(button("返回") { onBackPressedDispatcher.onBackPressed() })
        heading = TextView(this).apply { textSize = 20f }
        navigation.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        login = button("登录") { showLogin() }.also(navigation::addView)

        val actions = bar()
        refresh = button("刷新") { loadPage(reset = true) }.also { addAction(actions, it) }
        find = button("搜索") {
            prompt("搜索夸克网盘", search.orEmpty()) {
                search = it
                loadPage(reset = true)
            }
        }.also { addAction(actions, it) }
        create = button("新建文件夹") {
            prompt("新建文件夹", "") { name -> mutate { it.create(directory, name) } }
        }.also { addAction(actions, it) }
        moveHere = button("移到这里") { confirmMove() }.also { addAction(actions, it) }

        status = TextView(this).also { root.addView(it) }
        rows = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list = ListView(this).apply {
            adapter = rows
            setOnItemClickListener { _, _, position, _ ->
                if (!busy) items.getOrNull(position)?.let(::open)
            }
            setOnItemLongClickListener { _, _, position, _ ->
                if (busy || moveSource != null) return@setOnItemLongClickListener false
                val file = items.getOrNull(position) ?: return@setOnItemLongClickListener false
                showActions(file)
                true
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        more = button("加载更多") { loadPage(reset = false) }.also { root.addView(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                when {
                    search != null -> { search = null; loadPage(reset = true) }
                    folders.isNotEmpty() -> { folders.removeAt(folders.lastIndex); loadPage(reset = true) }
                    moveSource != null -> { moveSource = null; loadPage(reset = true) }
                    else -> finish()
                }
            }
        })
        connect()
    }

    private fun connect() {
        session = QuarkPersonalDrive.openSession()
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean) {
        if (reset) {
            nextPage = 1
            hasMore = false
            items.clear()
            seenIds.clear()
            renderRows()
        }
        val page = nextPage
        val keyword = search
        val parent = directory
        val moving = moveSource
        request("正在读取文件…", { drive ->
            val read = if (keyword == null) drive.list(parent, page, 50) else drive.search(keyword, page, 50)
            drive.execute(read)
        }) { result ->
            val added = result.items.filter { seenIds.add(it.id) }
            items += if (moving == null) added else added.filter { it.directory && it.id != moving.id }
            hasMore = result.hasMore && added.isNotEmpty()
            nextPage = page + 1
            renderRows()
            if (reset) list.setSelection(0)
            status.text = when {
                moving != null -> "选择目标目录 · ${moving.name}"
                items.isEmpty() && !hasMore -> if (keyword == null) "此目录为空" else "没有搜索结果"
                else -> "已加载 ${items.size} 项 · 长按管理文件"
            }
            updateControls()
        }
    }

    private fun renderRows() {
        rows.setNotifyOnChange(false)
        rows.clear()
        rows.addAll(items.map { if (it.directory) "目录  ${it.name}" else it.name })
        rows.notifyDataSetChanged()
    }

    private fun updateControls() {
        heading.text = search?.let { "搜索 · $it" } ?: folders.lastOrNull()?.name ?: "夸克网盘"
        val ready = session != null && !busy
        login.text = if (session == null) "登录" else "账号"
        login.isEnabled = !busy
        refresh.isEnabled = ready
        find.isEnabled = ready && moveSource == null
        create.isEnabled = ready && search == null && moveSource == null
        moveHere.visibility = if (moveSource == null) Button.GONE else Button.VISIBLE
        moveHere.isEnabled = ready
        more.visibility = if (hasMore) Button.VISIBLE else Button.GONE
        more.isEnabled = ready
        list.isEnabled = ready
    }

    private fun open(file: QuarkFile) {
        if (file.directory) {
            if (search != null) folders.clear()
            search = null
            folders.add(file)
            loadPage(reset = true)
            return
        }
        when (mediaKind(file.name, false)) {
            MediaKind.VIDEO -> play(file, video = true)
            MediaKind.AUDIO -> play(file, video = false)
            else -> showActions(file)
        }
    }

    private fun showActions(file: QuarkFile) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (!file.directory) actions += "下载原文件" to { download(file) }
        actions += "重命名" to {
            prompt("重命名", file.name) { name ->
                if (name != file.name) mutate { it.rename(file, name) }
            }
        }
        actions += "移动" to {
            moveSource = file
            search = null
            folders.clear()
            loadPage(reset = true)
        }
        actions += "删除" to {
            AlertDialog.Builder(this).setTitle("删除「${file.name}」？")
                .setMessage(if (file.directory) "目录及其内容将一起删除。" else "请确认要删除这个文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> mutate { it.delete(file) } }.show()
        }
        AlertDialog.Builder(this).setTitle(file.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }.show()
    }

    private fun confirmMove() {
        val file = moveSource ?: return
        val target = directory
        val name = folders.lastOrNull()?.name ?: "根目录"
        AlertDialog.Builder(this).setTitle("移动「${file.name}」")
            .setMessage("目标目录：$name")
            .setNegativeButton("取消", null)
            .setPositiveButton("移动") { _, _ -> mutate { it.move(file, target) } }.show()
    }

    private fun mutate(write: (QuarkPersonalDrive) -> Unit) {
        request("正在提交操作…", write) {
            moveSource = null
            toast("操作成功")
            loadPage(reset = true)
        }
    }

    private fun download(file: QuarkFile) {
        request("正在获取下载地址…", { it.download(file) }) { uri ->
            val manager = getSystemService(android.app.DownloadManager::class.java)
            runCatching {
                val safeName = file.name.replace('/', '_').replace('\\', '_')
                val target = "${System.currentTimeMillis()}_$safeName"
                manager.enqueue(android.app.DownloadManager.Request(uri)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, target)
                    .setTitle(file.name)
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED))
            }.onSuccess { status.text = "已加入系统下载" }
                .onFailure { status.text = "无法加入系统下载" }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun play(file: QuarkFile, video: Boolean) {
        request("正在获取播放地址…", { it.playback(file, video) }) { source ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                status.text = "请重新打开文件播放"
                return@request
            }
            status.text = ""
            val player = ExoPlayer.Builder(this).build()
            val view = PlayerView(this).apply {
                this.player = player
                minimumHeight = dp(if (video) 240 else 120)
                showController()
            }
            val dialog = AlertDialog.Builder(this).setTitle(file.name).setView(view)
                .setNegativeButton("关闭", null).create()
            playbackDialog = dialog
            dialog.setOnDismissListener {
                view.player = null
                player.release()
                playbackDialog = null
            }
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    dialog.dismiss()
                    status.text = "播放失败，请重新打开文件以获取播放地址"
                }
            })
            val media = MediaItem.Builder().setUri(source.uri)
            if (source.hls) media.setMimeType(MimeTypes.APPLICATION_M3U8)
            dialog.show()
            player.setMediaItem(media.build())
            player.prepare()
            player.play()
        }
    }

    private fun <T> request(
        loading: String,
        work: (QuarkPersonalDrive) -> T,
        success: (T) -> Unit,
    ) {
        if (busy) return
        val drive = session
        if (drive == null) {
            status.text = "请登录夸克网盘"
            updateControls()
            return
        }
        busy = true
        status.text = loading
        updateControls()
        worker.execute {
            val result = runCatching { work(drive) }
            runOnUiThread {
                if (isDestroyed || isFinishing || session !== drive) return@runOnUiThread
                busy = false
                updateControls()
                result.onSuccess(success).onFailure { error ->
                    status.text = when (error) {
                        is QuarkLoginRequired -> {
                            session = null
                            search = null
                            moveSource = null
                            folders.clear()
                            items.clear()
                            hasMore = false
                            renderRows()
                            updateControls()
                            "登录状态已失效，请重新登录"
                        }
                        is QuarkMutationPending -> {
                            moveSource = null
                            updateControls()
                            "网盘仍在处理，请稍后刷新确认结果"
                        }
                        is IllegalStateException -> error.message ?: "操作失败，请重试"
                        else -> "连接或响应异常，请重试"
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLogin() {
        if (loginDialog != null) return
        val handler = Handler(Looper.getMainLooper())
        val message = TextView(this).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // 官网把移动 UA 重定向到下载推广页，登录使用桌面网页。
            val chrome = Regex("Chrome/[0-9.]+").find(settings.userAgentString)?.value.orEmpty()
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        var attempt = 0
        var loading = false
        var focusLogin = true
        lateinit var retry: Button
        fun failed(reason: String) {
            loading = false
            handler.removeCallbacksAndMessages(null)
            message.text = reason
            message.visibility = View.VISIBLE
            web.visibility = View.GONE
            retry.visibility = View.VISIBLE
        }
        fun startLoading() {
            loading = true
            val current = ++attempt
            message.text = "正在打开夸克登录页…"
            message.visibility = View.VISIBLE
            retry.visibility = View.GONE
            web.visibility = View.VISIBLE
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                if (loading && current == attempt) {
                    val reason = if (focusLogin) "登录区加载超时，可重试或查看完整网页。"
                    else "登录页加载超时，请检查网络后重试。"
                    failed(reason)
                }
            }, 20_000)
        }
        fun openHome() {
            startLoading()
            web.loadUrl(QuarkPersonalDrive.HOME)
        }
        retry = button("重试") { openHome() }.apply { visibility = View.GONE }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (!request.isForMainFrame) return url.scheme != "https"
                val host = url.host.orEmpty().lowercase()
                // 登录链路会跨到 UC 账号、微信开放平台、阿里云验证码等域名，
                // 只放行 quark.cn / uc.cn 会把微信登录和风控回调直接掐断。
                val allowed = url.scheme == "https" && (host == "quark.cn" ||
                    host.endsWith(".quark.cn") || host == "uc.cn" || host.endsWith(".uc.cn") ||
                    host == "weixin.qq.com" || host.endsWith(".weixin.qq.com") ||
                    host == "wechat.com" || host.endsWith(".wechat.com") ||
                    host == "alicdn.com" || host.endsWith(".alicdn.com") ||
                    host == "aliyun.com" || host.endsWith(".aliyun.com") ||
                    host == "alipay.com" || host.endsWith(".alipay.com") ||
                    host == "aliapp.org" || host.endsWith(".aliapp.org"))
                if (!allowed) failed("夸克登录页跳转到了不支持的地址，请重试。")
                return !allowed
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // 官网每 2 秒探测本机 9125~9128 端口找夸克 PC 客户端，模拟器上必然失败，
                // 每轮都会刷 net::ERR_CONNECTION_REFUSED。直接回一个空结果，避免日志噪音。
                val url = request.url
                if (url.scheme == "http" && url.host == "127.0.0.1" &&
                    url.path.orEmpty().endsWith("/desktop_info")
                ) {
                    return WebResourceResponse(
                        "application/json", "utf-8", 200, "OK",
                        emptyMap(), "{}".byteInputStream(),
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (loading) return
                startLoading()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!loading || url != view.url) return
                val current = attempt
                fun checkContent() {
                    if (!loading || current != attempt || url != view.url) return
                    val script = if (focusLogin) FOCUS_QUARK_LOGIN else "Boolean(document.body?.innerText.trim())"
                    view.evaluateJavascript(script) { rendered ->
                        if (!loading || current != attempt || url != view.url) return@evaluateJavascript
                        if (rendered == "true") {
                            loading = false
                            handler.removeCallbacksAndMessages(null)
                            message.visibility = View.GONE
                        } else {
                            handler.postDelayed({ checkContent() }, 400)
                        }
                    }
                }
                checkContent()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failed("登录页加载失败，请检查网络后重试。")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (request.isForMainFrame) failed("登录页暂时无法访问（HTTP ${error.statusCode}），请重试。")
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val height = (resources.configuration.screenHeightDp * 0.65f).toInt().coerceIn(160, 520)
            addView(message)
            addView(retry)
            addView(web, LinearLayout.LayoutParams(-1, dp(height)))
        }
        val dialog = AlertDialog.Builder(this).setTitle("夸克网盘账号")
            .setView(container)
            .setNegativeButton("导入 Cookie", null)
            .setNeutralButton("完整网页", null)
            .setPositiveButton("完成，读取网盘", null).create()
        loginDialog = dialog
        dialog.setOnDismissListener {
            loading = false
            handler.removeCallbacksAndMessages(null)
            container.removeView(web)
            web.destroy()
            loginDialog = null
            if (!isFinishing && !isDestroyed && !worker.isShutdown) {
                CookieManager.getInstance().flush()
                folders.clear()
                search = null
                moveSource = null
                connect()
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { showCookieImport() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // 未真正登录时别关弹窗：以前点「完成」会直接 dismiss，匿名 Cookie 造成
            // 「已登录」的假象，随后接口返回 21001 才提示登录失效。
            if (!QuarkPersonalDrive.hasLoginCookie(
                    CookieManager.getInstance().getCookie(QuarkPersonalDrive.HOME)
                )
            ) {
                toast("还没检测到夸克登录态，请先完成登录或导入 Cookie")
                return@setOnClickListener
            }
            CookieManager.getInstance().flush()
            dialog.dismiss()
        }
        val toggleView = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        toggleView.setOnClickListener {
            focusLogin = !focusLogin
            toggleView.text = if (focusLogin) "完整网页" else "只看登录"
            openHome()
        }
        openHome()
    }

    /** 风控滑块 / 短信验证码在 WebView 里不稳定时，可直接粘贴桌面浏览器的登录 Cookie。 */
    private fun showCookieImport() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 3
            hint = "粘贴 Cookie: 后面的整串（需含 __pus / __puus）"
        }
        val dialog = AlertDialog.Builder(this).setTitle("导入夸克 Cookie")
            .setMessage("从桌面浏览器 DevTools 复制 Cookie 请求头。Cookie 等同账号凭证，请勿外传。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var text = input.text.toString().trim()
            val marker = Regex("(?i)cookie:\\s*").find(text)
            if (marker != null) text = text.substring(marker.range.last + 1).trim()
            text = text.trim('\'', '"').replace("\r", "").replace("\n", ";")
            if (!QuarkPersonalDrive.hasLoginCookie(text)) {
                input.error = "这串 Cookie 里没有 __pus / __puus，请确认复制完整"
                return@setOnClickListener
            }
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            var count = 0
            text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) return@forEach
                cookieHosts.forEach { host -> manager.setCookie(host, "$name=$value") }
                count++
            }
            manager.flush()
            dialog.dismiss()
            toast("已导入 $count 项 Cookie，点「完成，读取网盘」生效")
        }
    }

    private fun prompt(title: String, value: String, submit: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(value)
            setSelection(length())
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input)
            .setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = "请输入内容"
            } else {
                dialog.dismiss()
                submit(text)
            }
        }
    }

    private fun bar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun addAction(bar: LinearLayout, action: Button) {
        bar.addView(action, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStop() {
        playbackDialog?.dismiss()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        loginDialog?.dismiss()
        playbackDialog?.dismiss()
        super.onDestroy()
    }
}


/** 只调整官网登录卡片布局，保留原有二维码、表单和登录回调。 */
private val FOCUS_QUARK_LOGIN = """
    (() => {
        const hasContent = () => Boolean(document.body?.innerText.trim());
        if (location.origin !== 'https://pan.quark.cn' || location.pathname !== '/' ||
            !CSS.supports('selector(:has(*))')) return hasContent();

        const panel = '[class*="LoginComponent--modal--"]';
        const card = document.querySelector(panel);
        if (!card || !card.getClientRects().length) return false;
        if (document.getElementById('bdplayer-quark-login')) return true;

        const viewport = document.querySelector('meta[name="viewport"]');
        const originalViewport = viewport?.getAttribute('content');
        const focusedViewport = viewport || document.createElement('meta');
        focusedViewport.name = 'viewport';
        focusedViewport.content = 'width=400';
        if (!viewport) document.head.append(focusedViewport);

        const style = document.createElement('style');
        style.id = 'bdplayer-quark-login';
        style.textContent = `
            body:has(${'$'}{panel}) :not(${'$'}{panel}):not(${'$'}{panel} *):not(:has(${'$'}{panel})) {
                display: none !important;
            }
            html:has(${'$'}{panel}), body:has(${'$'}{panel}), body :has(${'$'}{panel}) {
                display: block !important;
                position: static !important;
                width: auto !important;
                min-width: 0 !important;
                height: auto !important;
                min-height: 0 !important;
                padding: 0 !important;
                margin: 0 !important;
                transform: none !important;
                overflow: visible !important;
                background: white !important;
            }
            ${'$'}{panel} {
                margin: 0 auto !important;
                border-radius: 0 !important;
                box-shadow: none !important;
            }
        `;
        document.head.append(style);
        window.scrollTo(0, 0);

        const restoreOnLogin = new MutationObserver(() => {
            if (location.pathname === '/' && document.querySelector(panel)) return;
            style.remove();
            if (!viewport) focusedViewport.remove();
            else if (originalViewport == null) viewport.removeAttribute('content');
            else viewport.setAttribute('content', originalViewport);
            restoreOnLogin.disconnect();
        });
        restoreOnLogin.observe(document.body, { childList: true, subtree: true });
        return true;
    })()
""".trimIndent()
