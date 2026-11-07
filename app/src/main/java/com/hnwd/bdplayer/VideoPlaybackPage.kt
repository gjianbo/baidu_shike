package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import androidx.media3.ui.PlayerView

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
    private lateinit var updateFullscreen: (Boolean) -> Unit

    private fun handleBack() {
        if (immersive) updateFullscreen(false) else close()
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
            setPadding(dp(12), dp(8), dp(12), dp(4))
            maxLines = 2
        }
        val audioOnly = Button(activity).apply {
            text = if (listening) "恢复画面" else "只听声音"
            isAllCaps = false
            isEnabled = false
        }
        fun setListening(enabled: Boolean) {
            listening = enabled
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, listening).build()
            audioOnly.text = if (listening) "恢复画面" else "只听声音"
            message.text = if (listening) "正在听声音" else "正在恢复画面…"
            message.visibility = View.VISIBLE
        }
        audioOnly.setOnClickListener { setListening(!listening) }
        if (listening) setListening(true)
        val favorite = Button(activity).apply {
            isAllCaps = false
            text = if (isFavorite) "取消收藏" else "收藏"
            setOnClickListener { text = if (onFavorite()) "取消收藏" else "收藏" }
        }
        val courseActions = LinearLayout(activity).apply {
            val previous = button("上一节") { onSwitch(-1, snapshot()) }.apply { isEnabled = hasPrevious }
            val next = button("下一节") { onSwitch(1, snapshot()) }.apply { isEnabled = hasNext }
            listOf(previous, favorite, next).forEach {
                addView(it, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val toolbar = LinearLayout(activity).apply {
            addView(button("返回") { close() })
            addView(button("横屏全屏") { updateFullscreen(true) })
            addView(audioOnly)
        }
        val playbackStatus = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(message)
            addView(retry)
        }
        val content = FrameLayout(activity).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(playbackStatus, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        updateFullscreen = { enabled ->
            immersive = enabled
            val visibility = if (enabled) View.GONE else View.VISIBLE
            title.visibility = visibility
            toolbar.visibility = visibility
            courseActions.visibility = visibility
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
            addView(title)
            addView(toolbar)
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(courseActions)
        })
        val checkpoint = object : Runnable {
            override fun run() {
                saveProgress()
                video.postDelayed(this, 5_000)
            }
        }
        dialog.setOnDismissListener {
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
                if (isPlaying) video.postDelayed(checkpoint, 5_000)
                else if (player.playbackState != Player.STATE_ENDED) saveProgress()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioOnly.isEnabled = tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                if (!tracks.isEmpty && listening && !audioOnly.isEnabled) setListening(false)
            }

            override fun onRenderedFirstFrame() {
                rendered = true
                message.visibility = View.GONE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) readyToSave = true
                if (playbackState == Player.STATE_ENDED) {
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
        }.createMediaSource(MediaItem.fromUri(stream.uri))
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

    private fun button(label: String, action: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
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
