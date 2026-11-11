package com.hnwd.bdplayer

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
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
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 播放链路：取址、后台续播、音频/视频页与播放队列。
 *
 * 从 MainActivity.kt 按域原样搬出的扩展函数：函数体与语义未改，
 * 仅把 private 提升为 internal，并用 this@? 保真原 this@MainActivity 引用。
 */

internal fun MainActivity.advanceAudioIfEnded(player: Player) {
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


internal fun MainActivity.scheduleHistoryRefresh() {
    historyRefreshPending = true
    if (historyRefreshScheduled) return
    historyRefreshScheduled = true
    content.postDelayed({
        historyRefreshScheduled = false
        refreshHistoryIfPending()
    }, 350)
}


internal fun MainActivity.refreshHistoryIfPending() {
    if (historyRefreshPending && !historyRefreshScheduled && !isDestroyed && hasVerifiedAccount() &&
        screen == Screen.HOME && folders.isEmpty() && audioPage == null && contentScroll.scrollY <= dp(24)
    ) {
        renderHomeSections()
    }
}


internal fun MainActivity.syncAudioAnchor(player: Player) {
    val active = currentAudioEntry(player) ?: return
    if (pendingAudioEntry?.let { !sameEntry(it, active) } == true) return
    if (pendingQueue?.let { sameEntry(it.entry, active) } == true ||
        PlaybackQueueState.entries.any { sameEntry(it, active) }
    ) confirmPlayback(active)
}


internal fun MainActivity.confirmPlayback(entry: PanEntry) {
    pendingQueue?.takeIf { sameEntry(it.entry, entry) }?.let {
        PlaybackQueueState.entries = it.entries
        PlaybackQueueState.listKey = it.listKey
        pendingQueue = null
    }
    PlaybackQueueState.anchor = entry
    renderCourseSwitch()
}


internal fun MainActivity.ownsAudio(player: Player): Boolean {
    val extras = player.currentMediaItem?.mediaMetadata?.extras ?: return false
    return if (extras.getString("provider") == SourceProvider.QUARK.name) {
        extras.getString("account") == AccountScope.current && hasActiveSession(SourceProvider.QUARK)
    } else {
        hasVerifiedAccount() && extras.getString("account") == verifiedAccount
    }
}


internal fun MainActivity.updateNowPlaying() {
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


internal fun MainActivity.resolveAudio(
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


internal fun MainActivity.resolveQuarkPlayback(entry: PanEntry, onResult: (Result<QuarkPlayback>) -> Unit) {
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


internal fun MainActivity.resolveQuarkAudio(entry: PanEntry, openPlayer: Boolean) {
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


internal fun MainActivity.resolveDiskAudio(entry: PanEntry, openPlayer: Boolean) {
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



internal fun MainActivity.resolveAudioFile(
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


internal fun MainActivity.requestMediaLink(
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


internal fun MainActivity.openAudioLink(
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



internal fun MainActivity.startPdfReader(entry: PanEntry, file: File) {
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



internal fun MainActivity.startBackgroundAudio(entry: PanEntry, uri: Uri, openPlayer: Boolean, playlist: ByteArray? = null) {
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


internal fun MainActivity.renewBackgroundAudio(player: MediaController) {
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


internal fun MainActivity.cancelPendingAudioRequest() {
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


internal fun MainActivity.failAudioRequest(message: String) {
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


internal fun MainActivity.updateAudioRequestMessage(message: String?) {
    val previous = audioRequestMessage
    audioRequestMessage = message
    when {
        message != null -> status.text = message
        previous != null && status.text == previous -> status.text = ""
    }
    refreshAudioPageMessage?.invoke()
}


internal fun MainActivity.audioDisplayName(raw: CharSequence): String =
    raw.toString().trim().replace(Regex("(?i)\\.(mp3|m4a|aac|wav|flac|ogg)$"), "")


@OptIn(UnstableApi::class)
internal fun MainActivity.showPlayback(player: MediaController) {
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
    fun icon(res: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setBackgroundResource(android.R.color.transparent)
    }
    val previous = icon(R.drawable.ic_skip_prev)
    previous.contentDescription = "上一节"
    val play = ImageView(this).apply {
        setBackgroundResource(R.drawable.bg_play_accent)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setImageResource(R.drawable.ic_play)
        setColorFilter(color(R.color.onAccent))
    }
    val next = icon(R.drawable.ic_skip_next).apply { contentDescription = "下一节" }
    // D 槽「倍速」的标签；update() 里仍通过 speed.text 更新当前档位
    val speed = TextView(this).apply {
        textSize = 10f
        setTextColor(color(R.color.ink3))
        gravity = Gravity.CENTER
        maxLines = 1
    }
    // 传输组固定三件、严格对称：上一 · 播放 · 下一，播放键恒在中线
    val transport = row().apply {
        gravity = Gravity.CENTER
        addView(previous, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(play, LinearLayout.LayoutParams(dp(56), dp(56)).apply {
            marginStart = dp(26)
            marginEnd = dp(26)
        })
        addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
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
    fun showTimerMenu(anchor: View) {
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
        addView(TextView(this@showPlayback).apply {
            text = "音频播放"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(R.color.ink))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        addView(stop, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    var loadingCourses = false
    var checkedPagination: Pair<PanEntry, String?>? = null
    var canLoadCourses = false
    lateinit var updateAudioPage: () -> Unit
    val courseList = this.AudioCourseList(
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
    // ---- 四槽骨架：A 顶栏 / B 内容 / C 进度 / D 动作 ----
    // B 槽：大封面 + 标题 + 来源 + 状态整体居中，传输组浮在内容底部
    val stageColumn = column().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(cover, LinearLayout.LayoutParams(dp(200), dp(200)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        // 传输组跟在文案后面作为一个整体居中；不浮在槽底，避免列表收起后容器拉伸把两者拉开
        addView(transport, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        })
    }
    val stage = FrameLayout(this).apply {
        addView(stageColumn, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }
    // C 槽：时间 — 轨道 — 时间，同一行
    val progressRow = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(16), 0)
        addView(currentTime)
        addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        addView(totalTime)
    }
    fun seekBy(deltaMs: Long) {
        val duration = player.duration
        val target = player.currentPosition + deltaMs
        player.seekTo(if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0L))
    }
    /** D 槽一格：图标 24 + 标签 10sp，整格可点，与视频页视觉一致 */
    fun actionCell(iconView: ImageView, labelView: TextView, desc: String): LinearLayout {
        iconView.apply {
            isClickable = false
            setBackgroundResource(android.R.color.transparent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }
        labelView.apply {
            textSize = 10f
            setTextColor(color(R.color.ink3))
            gravity = Gravity.CENTER
            maxLines = 1
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_icon_ripple)
            isClickable = true
            contentDescription = desc
            addView(iconView)
            addView(labelView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        }
    }
    // D 槽：选集 · 倍速 · 收藏 · 定时 · 更多（5 等分，顺序与视频页共有项一致）
    val speedIcon = audioIconButton(R.drawable.ic_speed, "播放倍速", color(R.color.ink3)) {}
    val speedCell = actionCell(speedIcon, speed, "播放倍速")
    speedCell.setOnClickListener { anchor ->
        PopupMenu(this, anchor, Gravity.END).apply {
            PlaybackSpeed.values.forEachIndexed { index, value ->
                menu.add(0, index, index, PlaybackSpeed.spoken(value))
                    .setChecked(value == player.playbackParameters.speed)
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener {
                val value = PlaybackSpeed.values[it.itemId]
                player.setPlaybackParameters(PlaybackParameters(value))
                PlaybackSpeed.setAudio(this@showPlayback, value)
                true
            }
            show()
        }
    }
    val favoriteCell = actionCell(favorite, TextView(this).apply { text = "收藏" }, "收藏")
    val timerCell = actionCell(timer, TextView(this).apply { text = "定时" }, "睡眠定时")
    timerCell.setOnClickListener { showTimerMenu(it) }
    val courseCell = actionCell(
        audioIconButton(R.drawable.ic_layers, "选集", color(R.color.ink3)) {},
        TextView(this).apply { text = "选集" }, "选集",
    )
    val moreCell = actionCell(
        audioIconButton(R.drawable.ic_settings, "更多", color(R.color.ink3)) {},
        TextView(this).apply { text = "更多" }, "更多",
    )
    moreCell.setOnClickListener { anchor ->
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(0, 0, 0, "后退 30 秒")
            menu.add(0, 1, 1, "前进 30 秒")
            menu.add(0, 2, 2, "停止播放")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> seekBy(-30_000L)
                    1 -> seekBy(30_000L)
                    else -> { stopBackgroundAudio(); closeAudioPage() }
                }
                true
            }
            show()
        }
    }
    val actionBar = row().apply {
        gravity = Gravity.CENTER
        listOf(courseCell, speedCell, favoriteCell, timerCell, moreCell).forEach { cell ->
            addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }
    val playerPanel = column().apply {
        addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(progressRow, LinearLayout.LayoutParams(-1, dp(40)))
        addView(actionBar, LinearLayout.LayoutParams(-1, dp(66)))
    }
    val playbackBody = column().apply {
        // 播放面板占 2 份：封面 + 标题 + 浮底传输组需要约 400dp，列表 1 份即可
        addView(playerPanel, LinearLayout.LayoutParams(-1, 0, 2f))
        addView(courseList.view, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    // 「选集」用弹层看大列表，与视频页横屏的选集行为一致。
    // 主布局里的列表始终常驻，不在显隐之间改变占位，因此不会出现空白块。
    val listHolder = FrameLayout(this)
    courseCell.setOnClickListener {
        val parent = courseList.view.parent as? ViewGroup ?: return@setOnClickListener
        parent.removeView(courseList.view)
        val rows = minOf(6, PlaybackQueueState.entries.size.coerceAtLeast(1))
        val height = minOf(dp(52) + dp(52) * rows + dp(24), (resources.displayMetrics.heightPixels * 0.72f).toInt())
        listHolder.addView(courseList.view, FrameLayout.LayoutParams(-1, height))
        val sheet = android.app.AlertDialog.Builder(this)
            .setView(listHolder)
            .setNegativeButton("关闭", null)
            .create()
        sheet.setOnDismissListener {
            listHolder.removeView(courseList.view)
            if (courseList.view.parent == null) {
                playbackBody.addView(courseList.view, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        sheet.show()
        sheet.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(color(R.color.accent))
    }
    val playbackContent = column().apply {
        isClickable = true
        accessibilityPaneTitle = "音频播放"
        addView(topBar, LinearLayout.LayoutParams(-1, dp(52)))
        addView(View(this@showPlayback).apply {
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
            playerPanel.layoutParams = if (split) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 2f)
            courseList.view.layoutParams = if (split) LinearLayout.LayoutParams(0, -1, 1f)
            else LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val compact = height < dp(440)
        if (compact != compactHeader) {
            compactHeader = compact
            cover.visibility = if (compact) View.GONE else View.VISIBLE
            stageColumn.setPadding(dp(20), dp(if (compact) 6 else 12), dp(20), dp(if (compact) 6 else 12))
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
        speedCell.contentDescription = "播放倍速，当前 ${PlaybackSpeed.spoken(rate)}"
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
    // 显式 30 秒：长按上一/下一；同步收进「更多」菜单，和通知栏/线控保持一致
    previous.setOnLongClickListener { seekBy(-30_000L); true }
    next.setOnLongClickListener { seekBy(30_000L); true }
    favoriteCell.setOnClickListener {
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


internal fun MainActivity.closeAudioPage() {
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


internal fun MainActivity.stopBackgroundAudio() {
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
internal fun MainActivity.startVideo(
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
                history = AudioHistory(getSharedPreferences("video_history", android.content.Context.MODE_PRIVATE), account),
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


internal fun MainActivity.playbackError(error: PlaybackException): String {
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


internal fun MainActivity.playAudio(entry: PanEntry, uri: Uri, resumeSeconds: Int?) {
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
    val playbackHistory = AudioHistory(getSharedPreferences("audio_history", android.content.Context.MODE_PRIVATE), verifiedAccount)
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
        val favorite = Button(this@playAudio).apply {
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
    lateinit var courseList: MainActivity.AudioCourseList
    fun updateCourses() {
        val queue = PlaybackQueueState.entries
        val index = queue.indexOfFirst { sameEntry(it, entry) }
        courseList.updateSort(entry.takeIf { index >= 0 }, listKey)
        if (index >= 0) courseList.update(queue, index, hasMoreCourses(entry, listKey), loadingCourses)
        else courseList.update(listOf(entry), 0, false, false)
        previousCourse.isEnabled = index > 0
        nextCourse.isEnabled = index in 0 until queue.lastIndex
    }
    courseList = this.AudioCourseList(
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
