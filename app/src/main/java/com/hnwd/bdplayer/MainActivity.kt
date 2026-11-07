package com.hnwd.bdplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var native: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var historyPanel: LinearLayout
    private lateinit var audioHistory: AudioHistory
    private var audioControls: AudioControls? = null
    private var playbackDialog: AlertDialog? = null
    private val videoResolver = VideoStreamResolver()
    private var videoPage: VideoPlaybackPage? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private lateinit var nowPlaying: Button
    private val uiExecutor = Executor { runOnUiThread(it) }

    private enum class PlaybackRoute { AUDIO, WEB_AUDIO }
    private val folders = mutableListOf<PanEntry>()
    private var recent = true
    private var ready = false
    private var requestId = 0
    private var gid = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(42, 31, 25)
        window.navigationBarColor = Color.rgb(42, 31, 25)
        audioHistory = AudioHistory(getSharedPreferences("audio_history", MODE_PRIVATE))
        gid = getPreferences(MODE_PRIVATE).getString("group_id", "").orEmpty()
        val frame = FrameLayout(this)
        native = column().apply {
            setPadding(dp(16), dp(12), dp(16), 0)
            setBackgroundColor(Color.rgb(255, 249, 244))
        }
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

                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    videoResolver.observe(request) { view.settings.userAgentString }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    ready = false
                    requestId++
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val address = Uri.parse(url)
                    ready = address.scheme == "https" && address.host == PanApi.HOST
                    if (ready) {
                        CookieManager.getInstance().flush()
                        if (native.visibility == View.VISIBLE) refresh()
                    }
                }
            }
        }
        videoResolver.attach(web)
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))
        frame.addView(native, FrameLayout.LayoutParams(-1, -1))
        val webControls = row().apply {
            setBackgroundColor(Color.WHITE)
            addView(button("返回 App") { showApp() })
            addView(button("手机网盘") { web.loadUrl(PanApi.MOBILE_HOME) })
            addView(button("百度登录") { web.loadUrl(PanApi.login) })
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

        title = label(getString(R.string.app_name), 26f)
        native.addView(title)
        native.addView(row().apply {
            addView(button("登录") { showWeb(PanApi.login) })
            addView(button("手机网盘") { showWeb(PanApi.MOBILE_HOME) })
            addView(button("设置群 ID") { editGroup() })
        })
        native.addView(row().apply {
            addView(button("最近更新") { openSection(true) })
            addView(button("群目录") { openSection(false) })
            addView(button("刷新") { refresh() })
        })
        status = label("", 14f)
        native.addView(status)
        nowPlaying = button("当前播放") {
            controller?.let { showPlayback(it) }
        }.apply { visibility = View.GONE }
        native.addView(nowPlaying)
        historyPanel = column()
        native.addView(historyPanel)
        content = column()
        native.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        refresh()
        val token = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                if (!isDestroyed && !isFinishing) {
                    controller = runCatching { future.get() }.getOrNull()?.also { player ->
                        player.addListener(object : Player.Listener {
                            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                                updateNowPlaying()
                            }
                        })
                    }
                    updateNowPlaying()
                }
            }, uiExecutor)
        }
    }

    private fun updateNowPlaying() {
        nowPlaying.visibility = if (controller?.currentMediaItem != null) View.VISIBLE else View.GONE
    }

    private fun openSection(showRecent: Boolean) {
        recent = showRecent
        folders.clear()
        refresh()
    }

    private fun editGroup() {
        val field = EditText(this).apply {
            hint = "输入群 ID（gid）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(gid)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("设置群文件库")
            .setMessage("可使用群 ID，或通讯录请求 session_id 中 04_ 后面的数字。")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (!value.matches(Regex("[0-9]{1,24}"))) {
                    field.error = "请输入数字群 ID"
                    return@setOnClickListener
                }
                gid = value
                getPreferences(MODE_PRIVATE).edit().putString("group_id", gid).apply()
                folders.clear()
                dialog.dismiss()
                refresh()
            }
        }
        dialog.show()
    }

    private fun refresh() {
        requestId++
        content.removeAllViews()
        val parent = folders.lastOrNull()
        renderAudioHistory()
        title.text = parent?.name ?: "${getString(R.string.app_name)} · ${if (recent) "最近更新" else "群目录"}"
        if (parent != null) content.addView(button("返回上一级") {
            folders.removeAt(folders.lastIndex)
            refresh()
        })
        when {
            gid.isEmpty() -> status.text = "请先登录百度网盘，并设置要查看的群 ID。"
            !ready -> {
                status.text = "正在连接百度网盘。如果列表无法加载，请先登录。"
                if (web.url != PanApi.CONTACTS) web.loadUrl(PanApi.CONTACTS)
            }
            parent != null -> loadChildren(parent, 1)
            recent -> load(PanApi.recent(gid)) { json ->
                display(PanApi.parseRecent(json))
                if (json.getJSONObject("data").optBoolean("hasmore")) {
                    status.append(" · 已显示首批记录")
                }
            }
            else -> load(PanApi.roots(gid)) { json ->
                display(PanApi.parseRoots(json, gid))
                if (json.optInt("has_more") != 0) status.append(" · 已显示首批目录")
            }
        }
    }

    private fun loadChildren(parent: PanEntry, page: Int, previousButton: Button? = null) {
        load(PanApi.children(parent, page), post = true, onFailure = {
            previousButton?.isEnabled = true
        }) { json ->
            val entries = PanApi.parseChildren(json, parent)
            previousButton?.let { content.removeView(it) }
            display(entries)
            if (json.optInt("has_more") != 0 && entries.isNotEmpty()) {
                val more = button("加载下一页") {}
                more.setOnClickListener {
                    more.isEnabled = false
                    loadChildren(parent, page + 1, more)
                }
                content.addView(more)
            }
        }
    }

    private fun display(entries: List<PanEntry>) {
        status.text = if (entries.isEmpty()) "没有条目" else "已读取 ${entries.size} 条"
        for (entry in entries) {
            val format = if (entry.directory) "目录" else entry.name.substringAfterLast('.', "文件").uppercase()
            content.addView(button("$format · ${entry.name}") {
                when {
                    entry.directory -> {
                        folders.add(entry)
                        refresh()
                    }
                    format == "MP4" -> startVideo(entry)
                    format == "MP3" -> resolveAudio(entry)
                    else -> AlertDialog.Builder(this)
                        .setTitle(entry.name)
                        .setMessage("此版本提供该文件的目录信息。")
                        .setPositiveButton("关闭", null)
                        .show()
                }
            }.apply {
                if (format == "MP3" || format == "MP4") setOnLongClickListener {
                    if (format == "MP3") resolveAudio(entry, PlaybackRoute.WEB_AUDIO)
                    else {
                        controller?.pause()
                        showWeb(PanApi.video(entry))
                    }
                    true
                }
            })
        }
    }

    private fun renderAudioHistory() {
        historyPanel.removeAllViews()
        if (!recent || folders.isNotEmpty() || gid.isEmpty()) return
        val items = audioHistory.recent(gid)
        val latest = items.firstOrNull { !it.completed }
        if (latest != null) {
            historyPanel.addView(button("继续收听 · ${latest.positionText} · ${latest.entry.name}") {
                resolveAudio(latest.entry)
            }.apply {
                maxLines = 2
                setOnLongClickListener {
                    resolveAudio(latest.entry, PlaybackRoute.WEB_AUDIO)
                    true
                }
            })
        }
        if (items.isNotEmpty()) {
            historyPanel.addView(button("最近播放") {
                AlertDialog.Builder(this)
                    .setTitle("最近播放")
                    .setItems(items.take(10).map { "${it.positionText} · ${it.entry.name}" }.toTypedArray()) { _, which ->
                        resolveAudio(items[which].entry)
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            })
        }
    }

    private fun resolveAudio(entry: PanEntry, route: PlaybackRoute = PlaybackRoute.AUDIO) {
        if (!ready) {
            status.text = "网盘页面尚未就绪，请刷新后重试。"
            return
        }
        val id = ++requestId
        status.text = "正在确认网盘账号…"
        web.evaluateJavascript(
            "location.origin === 'https://pan.baidu.com' ? String(window.yunData?.MYUK || window.yunData?.uk || '') : ''",
        ) { result ->
            if (id != requestId || isDestroyed) return@evaluateJavascript
            val viewer = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
            if (viewer != null && viewer.matches(Regex("[0-9]{1,24}"))) {
                requestMediaLink(entry, viewer, route)
            } else {
                load(PanApi.viewer()) { json ->
                    val uk = json.optJSONObject("result")?.optString("uk").orEmpty()
                    check(uk.matches(Regex("[0-9]{1,24}"))) { "无法识别当前网盘账号，请重新登录后重试。" }
                    requestMediaLink(entry, uk, route)
                }
            }
        }
    }

    private fun requestMediaLink(entry: PanEntry, viewer: String, route: PlaybackRoute) {
        load(PanApi.mediaLinkRequest(), post = true, form = PanApi.mediaLinkBody(entry, viewer)) { json ->
            val uri = PanApi.parseMediaLink(json, entry)
            when (route) {
                PlaybackRoute.AUDIO -> startBackgroundAudio(entry, uri)
                PlaybackRoute.WEB_AUDIO -> {
                    controller?.pause()
                    playAudio(entry, uri)
                }
            }
        }
    }

    private fun startBackgroundAudio(entry: PanEntry, uri: Uri) {
        val player = controller
        if (player == null || !player.isConnected) {
            status.text = "后台播放器尚未就绪，请稍后重试。"
            return
        }
        val args = Bundle().apply {
            putString("uri", uri.toString())
            putString("gid", entry.gid)
            putString("message", entry.messageId)
            putString("owner", entry.owner)
            putString("file", entry.fileId)
            putString("name", entry.name)
        }
        val id = requestId
        val result = player.sendCustomCommand(AudioPlaybackService.PLAY_FILE, args)
        result.addListener({
            if (!isDestroyed && !isFinishing && id == requestId) {
                if (runCatching { result.get().resultCode }.getOrNull() == SessionResult.RESULT_SUCCESS) {
                    status.text = "已打开后台播放"
                    showPlayback(player, entry.name)
                } else {
                    status.text = "后台播放器启动失败，请重试。也可长按 MP3 使用网页播放。"
                }
            }
        }, uiExecutor)
    }

    @OptIn(UnstableApi::class)
    private fun showPlayback(player: MediaController, title: String? = null) {
        if (playbackDialog?.isShowing == true) return
        val message = label("", 14f)
        val controls = PlayerControlView(this).apply {
            this.player = player
            showTimeoutMs = 0
        }
        val panel = column().apply {
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(message)
            addView(controls)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title ?: player.mediaMetadata.title ?: "当前播放")
            .setView(panel)
            .setPositiveButton("返回列表", null)
            .setNegativeButton("停止播放") { _, _ ->
                player.sendCustomCommand(AudioPlaybackService.CLEAR_FILE, Bundle.EMPTY)
            }
            .create()
        fun updateMessage() {
            val error = player.playerError
            message.text = when {
                error != null -> "播放失败（${playbackError(error)}）。可长按 MP3 使用网页播放。"
                player.playbackState == Player.STATE_BUFFERING -> "正在缓冲…"
                player.playbackState == Player.STATE_ENDED -> "播放结束"
                player.isPlaying -> "正在播放"
                else -> "已暂停"
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = updateMessage()
        }
        player.addListener(listener)
        updateMessage()
        playbackDialog = dialog
        dialog.setOnDismissListener {
            player.removeListener(listener)
            controls.player = null
            playbackDialog = null
            if (!isDestroyed) renderAudioHistory()
        }
        dialog.show()
        controls.show()
    }

    private fun startVideo(entry: PanEntry) {
        controller?.pause()
        showWeb(PanApi.video(entry)) {
            resolveVideo(entry)
        }
    }

    private fun resolveVideo(entry: PanEntry) {
        videoResolver.start(web, entry) { result ->
            if (isDestroyed || isFinishing) return@start
            result.onSuccess { stream ->
                showApp()
                videoPage = VideoPlaybackPage(
                    this, entry, stream,
                    onRetry = { startVideo(entry) },
                    onWeb = { showWeb(PanApi.video(entry)) },
                    onClose = { videoPage = null },
                )
            }.onFailure {
                AlertDialog.Builder(this)
                    .setTitle("无法原生播放")
                    .setMessage((it as? IllegalStateException)?.message ?: "读取视频播放清单失败。")
                    .setPositiveButton("继续使用百度播放器", null)
                    .setNeutralButton("重试") { _, _ -> startVideo(entry) }
                    .show()
            }
        }
    }

    private fun playbackError(error: PlaybackException): String {
        val response = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        return response?.let { "HTTP ${it.responseCode}" } ?: error.errorCodeName
    }

    private fun playAudio(entry: PanEntry, uri: Uri) {
        val source = JSONObject.quote(uri.toString())
        val controls = AudioControls(this, entry.name) { playing ->
            val action = if (playing) "a.play().catch(() => {})" else "a.pause()"
            web.evaluateJavascript(
                "(() => { const a = window.__groupPlayerAudio; if (a) $action; })()",
                null,
            )
        }
        audioControls = controls
        val previous = audioHistory.find(entry)
        val startAt = previous?.takeUnless { it.completed }?.seconds ?: 0
        var lastSavedSeconds = startAt
        var lastSavedAt = SystemClock.elapsedRealtime()
        var lastCompleted = previous?.completed == true
        fun saveProgress(seconds: Int, completed: Boolean) {
            if (seconds <= 0 || (seconds < startAt && !completed)) return
            if (seconds == lastSavedSeconds && completed == lastCompleted) return
            audioHistory.save(entry, seconds, completed)
            lastSavedSeconds = seconds
            lastSavedAt = SystemClock.elapsedRealtime()
            lastCompleted = completed
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage("正在缓冲…")
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
                    ? [Math.floor(a.currentTime), a.ended] : null;
                  a.pause();
                  a.removeAttribute('src');
                  a.load();
                  delete window.__groupPlayerAudio;
                  return position;
                })()
                """.trimIndent(),
            ) { result ->
                val position = result?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                if (position != null) saveProgress(position.optInt(0), position.optBoolean(1))
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
                "(() => { const a = window.__groupPlayerAudio; return a ? [a.error?.code || 0, a.readyState, a.paused, a.ended, Math.floor(a.currentTime)] : null; })()",
            ) { result ->
                if (!dialog.isShowing || isDestroyed) return@evaluateJavascript
                val state = result?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }
                val error = state?.optInt(0) ?: 0
                val ready = (state?.optInt(1) ?: 0) >= 2
                if (error != 0 || (!ready && SystemClock.elapsedRealtime() >= deadline)) {
                    dialog.setMessage(if (error != 0) "音频读取失败（$error）。关闭后可重试。" else "音频缓冲超时。")
                    toggle.isEnabled = false
                } else {
                    if (ready && state != null) {
                        val paused = state.optBoolean(2)
                        val ended = state.optBoolean(3)
                        val seconds = state.optInt(4)
                        if (ended || paused || SystemClock.elapsedRealtime() - lastSavedAt >= 5_000) {
                            saveProgress(seconds, ended)
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

    private fun load(
        url: String,
        post: Boolean = false,
        form: String? = null,
        onFailure: () -> Unit = {},
        onResult: (JSONObject) -> Unit,
    ) {
        val id = ++requestId
        status.text = "正在读取…"
        val deadline = SystemClock.elapsedRealtime() + 20_000
        val key = "__groupPlayerResult$id"
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
              const result = { ready: false };
              window.$key = result;
              fetch($target, { method: '$method', credentials: 'same-origin', headers: $headers$bodyOption })
                .then(async response => {
                  result.status = response.status;
                  result.body = await response.text();
                })
                .catch(() => { result.status = 0; })
                .finally(() => { result.ready = true; });
              return true;
            })()
        """.trimIndent()

        fun poll() {
            if (id != requestId || isDestroyed) return
            web.evaluateJavascript(
                "(() => { const r = window.$key; if (!r || !r.ready) return null; delete window.$key; return r; })()",
            ) { raw ->
                if (id != requestId || isDestroyed) return@evaluateJavascript
                if (raw == null || raw == "null") {
                    if (SystemClock.elapsedRealtime() < deadline) {
                        web.postDelayed({ poll() }, 200)
                    } else {
                        web.evaluateJavascript("delete window.$key", null)
                        status.text = "请求超时，请重试。"
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
                    check(errno == 0) { "网盘返回错误（$errno）。请确认已登录并有该群的访问权限。" }
                    onResult(json)
                }.onFailure {
                    status.text = if (it is IllegalStateException) it.message else "网盘响应格式有变化，请刷新重试。"
                    onFailure()
                }
            }
        }

        web.evaluateJavascript(script) { started ->
            if (id != requestId || isDestroyed) return@evaluateJavascript
            if (started == "true") poll()
            else {
                status.text = "网盘页面尚未就绪，请登录后刷新。"
                onFailure()
            }
        }
    }

    private fun showWeb(url: String, beforeLoad: () -> Unit = {}) {
        videoResolver.cancel()
        requestId++
        native.visibility = View.GONE
        (native.tag as View).visibility = View.VISIBLE
        beforeLoad()
        web.loadUrl(url)
    }

    private fun showApp() {
        videoResolver.cancel()
        (native.tag as View).visibility = View.GONE
        native.visibility = View.VISIBLE
        if (web.url != PanApi.CONTACTS) {
            ready = false
            web.loadUrl(PanApi.CONTACTS)
            status.text = "正在进入网盘通讯录…"
        } else refresh()
    }

    @Deprecated("Android back button compatibility")
    override fun onBackPressed() {
        when {
            native.visibility == View.GONE -> showApp()
            folders.isNotEmpty() -> {
                folders.removeAt(folders.lastIndex)
                refresh()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onStop() {
        videoPage?.pause()
        videoResolver.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        requestId++
        videoResolver.close()
        videoPage?.close()
        playbackDialog?.dismiss()
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
