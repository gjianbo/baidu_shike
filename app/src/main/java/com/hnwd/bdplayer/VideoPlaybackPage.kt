package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
internal class VideoPlaybackPage(
    private val activity: Activity,
    private val entry: PanEntry,
    stream: VideoStream,
    private val account: String,
    private val history: AudioHistory,
    startPositionMs: Long?,
    playWhenReady: Boolean,
    retryOnExpiredLink: Boolean,
    startAudioOnly: Boolean,
    startFullscreen: Boolean,
    val orientationBeforeFullscreen: Int,
    onRetry: (Snapshot, Boolean) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onSwitch: (Int, Snapshot) -> Unit,
    onEnded: (Snapshot) -> Unit,
    isFavorite: Boolean,
    onFavorite: () -> Boolean,
    private val onClearBackgroundAudio: () -> Unit,
    onClose: (VideoPlaybackPage) -> Unit,
) {
    private val dialog = object : Dialog(activity, android.R.style.Theme_Material_NoActionBar) {
        @Deprecated("Used for Android versions before predictive back")
        override fun onBackPressed() = handleBack()
    }
    private val player = ExoPlayer.Builder(activity)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
    private val video = PlayerView(activity).apply { this.player = this@VideoPlaybackPage.player }
    private val progress = activity.getSharedPreferences("video_progress", Activity.MODE_PRIVATE)
    private val progressKey = AccountScope.key("${entry.gid}:${entry.messageId}:${entry.fileId}")
    private var readyToSave = false
    private var keepOrientationOnClose = false
    private var immersive = false
    private var listening = startAudioOnly
    private var backgroundSession = false
    private var audioControllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var updateFullscreen: (Boolean) -> Unit

    private fun handleBack() {
        if (immersive) updateFullscreen(false) else close()
    }

    private fun ensureAudioSession() {
        if (!listening || backgroundSession) return
        if (player.mediaItemCount == 0 || !player.playWhenReady) return
        backgroundSession = true
        onClearBackgroundAudio()
        VideoAudioSessionService.prepare(player)
        val token = SessionToken(activity, ComponentName(activity, VideoAudioSessionService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        audioControllerFuture = future
        future.addListener({
            if (!runCatching { future.get() }.isSuccess) {
                if (audioControllerFuture === future) {
                    audioControllerFuture = null
                    backgroundSession = false
                    VideoAudioSessionService.stop(activity)
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun stopAudioSession() {
        if (!backgroundSession) return
        backgroundSession = false
        audioControllerFuture?.let(MediaController::releaseFuture)
        audioControllerFuture = null
        VideoAudioSessionService.stop(activity)
    }

    fun pauseForBackground() {
        if (listening && backgroundSession) return
        pause()
    }

    init {
        val message = TextView(activity).apply {
            text = "正在缓冲…"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }
        fun retryPlayback(automatic: Boolean) = onRetry(snapshot(), automatic)
        val retry = button("重试") { retryPlayback(false) }.apply { visibility = View.GONE }
        val title = TextView(activity).apply {
            text = entry.name
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        val audioStateIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_headset)
            setColorFilter(Color.WHITE)
            alpha = 0.75f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = if (listening) View.VISIBLE else View.GONE
        }
        val audioOnly = iconButton(R.drawable.ic_headset, if (listening) "恢复画面" else "只听声音", padding = 10) {}.apply {
            isEnabled = false
            alpha = 0.35f
        }
        fun setListening(enabled: Boolean) {
            listening = enabled
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, listening).build()
            audioOnly.contentDescription = if (listening) "恢复画面" else "只听声音"
            audioOnly.setBackgroundResource(if (listening) R.drawable.bg_play_accent else R.drawable.bg_video_icon_ripple)
            audioOnly.setColorFilter(if (listening) ContextCompat.getColor(activity, R.color.onAccent) else Color.WHITE)
            audioStateIcon.visibility = if (listening) View.VISIBLE else View.GONE
            message.text = if (listening) "正在听声音" else "正在恢复画面…"
            message.visibility = View.VISIBLE
            if (enabled) ensureAudioSession() else stopAudioSession()
        }
        audioOnly.setOnClickListener { setListening(!listening) }
        if (listening) setListening(true)
        val favorite = iconButton(
            if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star,
            if (isFavorite) "取消收藏" else "收藏",
            padding = 14,
        ) {}
        fun paintFavorite(starred: Boolean) {
            favorite.setImageResource(if (starred) R.drawable.ic_star_filled else R.drawable.ic_star)
            favorite.setColorFilter(if (starred) Color.rgb(255, 200, 87) else Color.WHITE)
            favorite.contentDescription = if (starred) "取消收藏" else "收藏"
        }
        favorite.setOnClickListener { paintFavorite(onFavorite()) }
        paintFavorite(isFavorite)
        val previous = iconButton(R.drawable.ic_skip_prev, "上一节", padding = 14) {
            if (hasPrevious) onSwitch(-1, snapshot())
        }.apply {
            isEnabled = hasPrevious
            alpha = if (hasPrevious) 1f else 0.35f
        }
        val next = iconButton(R.drawable.ic_skip_next, "下一节", padding = 14) {
            if (hasNext) onSwitch(1, snapshot())
        }.apply {
            isEnabled = hasNext
            alpha = if (hasNext) 1f else 0.35f
        }
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(4), dp(2))
            addView(
                iconButton(R.drawable.ic_arrow_back, "返回") { close() },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                audioOnly,
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) },
            )
            addView(
                iconButton(R.drawable.ic_fullscreen, "横屏全屏") { updateFullscreen(true) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) },
            )
        }
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(8))
            addView(previous, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(favorite, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(18) })
            addView(next, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(18) })
        }
        val playbackStatus = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(audioStateIcon, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(message, LinearLayout.LayoutParams(-2, -2))
            addView(retry, LinearLayout.LayoutParams(-2, -2))
        }
        val content = FrameLayout(activity).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(playbackStatus, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        updateFullscreen = { enabled ->
            immersive = enabled
            val visibility = if (enabled) View.GONE else View.VISIBLE
            topBar.visibility = visibility
            bottomBar.visibility = visibility
            video.setFullscreenButtonState(enabled)
            activity.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else orientationBeforeFullscreen
            updateSystemBars()
            video.showController()
        }
        video.setFullscreenButtonClickListener { updateFullscreen(it) }
        dialog.setContentView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { view, insets ->
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                    insets
                }
            }
            addView(topBar)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottomBar)
        })
        val checkpoint = object : Runnable {
            override fun run() {
                saveProgress()
                video.postDelayed(this, 5_000)
            }
        }
        dialog.setOnDismissListener {
            stopAudioSession()
            if (immersive && !keepOrientationOnClose && !activity.isDestroyed && !activity.isFinishing) {
                activity.requestedOrientation = orientationBeforeFullscreen
            }
            video.removeCallbacks(checkpoint)
            saveProgress()
            video.player = null
            player.release()
            onClose(this)
        }
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            private var rendered = false

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                video.removeCallbacks(checkpoint)
                if (isPlaying) {
                    video.postDelayed(checkpoint, 5_000)
                    ensureAudioSession()
                } else if (player.playbackState != Player.STATE_ENDED) saveProgress()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioOnly.isEnabled = tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                audioOnly.alpha = if (audioOnly.isEnabled) 1f else 0.35f
                if (!tracks.isEmpty && listening && !audioOnly.isEnabled) setListening(false)
            }

            override fun onRenderedFirstFrame() {
                rendered = true
                message.visibility = View.GONE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    readyToSave = true
                    ensureAudioSession()
                }
                if (playbackState == Player.STATE_ENDED) {
                    stopAudioSession()
                    progress.edit().remove(progressKey).apply()
                    history.save(entry, (player.currentPosition / 1000).toInt(), completed = true, duration = durationSeconds())
                    readyToSave = false
                    if (hasNext) video.post { if (dialog.isShowing) onEnded(snapshot()) }
                }
                if (player.playerError != null) return
                message.visibility = if (listening || !rendered || playbackState == Player.STATE_BUFFERING) {
                    View.VISIBLE
                } else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                stopAudioSession()
                message.text = "播放失败（${error.errorCodeName}）。"
                message.visibility = View.VISIBLE
                retry.visibility = View.VISIBLE
                if (retryOnExpiredLink && error.isExpiredMediaLink()) {
                    video.post { if (dialog.isShowing) retryPlayback(true) }
                }
            }
        })
        val http = DefaultHttpDataSource.Factory().setUserAgent(PanApi.DLINK_USER_AGENT)
        val source = HlsMediaSource.Factory { type ->
            if (type == C.DATA_TYPE_MANIFEST) HlsManifestDataSource(stream, http)
            else http.createDataSource()
        }.createMediaSource(
            MediaItem.Builder()
                .setUri(stream.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(entry.name)
                        .setArtist("拾课")
                        .build(),
                )
                .build(),
        )
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::handleBack,
            )
        }
        if (startFullscreen) updateFullscreen(true)
        player.setMediaSource(source, startPositionMs ?: progress.getLong(progressKey, 0L))
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    data class Snapshot(
        val entry: PanEntry,
        val account: String,
        val positionMs: Long?,
        val playWhenReady: Boolean,
        val audioOnly: Boolean,
        val fullscreen: Boolean,
        val orientationBeforeFullscreen: Int,
    )

    fun snapshot() = Snapshot(
        entry, account, player.currentPosition, player.playWhenReady, listening, immersive, orientationBeforeFullscreen,
    )

    fun pause() {
        player.pause()
        saveProgress()
    }

    fun close(keepOrientation: Boolean = false) {
        keepOrientationOnClose = keepOrientation
        dialog.dismiss()
    }

    private fun saveProgress() {
        if (!readyToSave) return
        val position = player.currentPosition
        progress.edit().putLong(progressKey, position).apply()
        history.save(entry, (position / 1000).toInt(), completed = false, duration = durationSeconds())
    }

    private fun durationSeconds(): Int {
        val ms = player.duration
        return if (ms > 0) (ms / 1000).toInt() else 0
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        val window = dialog.window ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (immersive) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = if (immersive) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else 0
        }
    }

    private fun iconButton(
        iconRes: Int,
        description: String,
        padding: Int = 10,
        action: () -> Unit,
    ) = ImageView(activity).apply {
        setImageResource(iconRes)
        setColorFilter(Color.WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        setBackgroundResource(R.drawable.bg_video_icon_ripple)
        isClickable = true
        setOnClickListener { action() }
    }

    private fun button(label: String, action: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_video_chip)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isClickable = true
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}

internal fun PlaybackException.isExpiredMediaLink(): Boolean =
    generateSequence(cause) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .any { it.responseCode == 401 || it.responseCode == 403 }

@OptIn(UnstableApi::class)
private class HlsManifestDataSource(
    private val stream: VideoStream,
    private val http: DataSource.Factory,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var source: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        source?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val delegate = if (dataSpec.uri == stream.uri) ByteArrayDataSource(stream.playlist)
        else http.createDataSource()
        source = delegate
        listeners.forEach(delegate::addTransferListener)
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(source).read(buffer, offset, length)

    override fun getUri(): Uri? = source?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders.orEmpty()

    override fun close() {
        val delegate = source
        source = null
        delegate?.close()
    }
}
