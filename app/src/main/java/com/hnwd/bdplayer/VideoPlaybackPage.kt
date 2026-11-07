package com.hnwd.bdplayer

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
internal class VideoPlaybackPage(
    private val activity: Activity,
    private val entry: PanEntry,
    stream: VideoStream,
    private val history: AudioHistory,
    onRetry: () -> Unit,
    onWeb: () -> Unit,
    onClose: () -> Unit,
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar)
    private val player = ExoPlayer.Builder(activity).build()
    private val video = PlayerView(activity).apply { this.player = this@VideoPlaybackPage.player }
    private val progress = activity.getSharedPreferences("video_progress", Activity.MODE_PRIVATE)
    private val progressKey = "${entry.gid}:${entry.messageId}:${entry.fileId}"
    private var readyToSave = false
    private var immersive = false

    init {
        val message = TextView(activity).apply {
            text = "正在缓冲…"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }
        val retry = button("重试") { close(); onRetry() }.apply { visibility = View.GONE }
        val title = TextView(activity).apply {
            text = entry.name
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(4))
            maxLines = 2
        }
        val fullscreen = Button(activity).apply {
            text = "全屏"
            isAllCaps = false
            setOnClickListener {
                immersive = !immersive
                text = if (immersive) "退出全屏" else "全屏"
                title.visibility = if (immersive) View.GONE else View.VISIBLE
                updateSystemBars()
            }
        }
        val toolbar = LinearLayout(activity).apply {
            addView(button("返回") { close() })
            addView(button("百度播放器") { close(); onWeb() })
            addView(fullscreen)
            addView(retry)
        }
        val content = FrameLayout(activity).apply {
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(message, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
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
        })
        dialog.setOnDismissListener {
            saveProgress()
            video.player = null
            player.release()
            onClose()
        }
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            private var rendered = false

            override fun onRenderedFirstFrame() {
                rendered = true
                message.visibility = View.GONE
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) readyToSave = true
                if (playbackState == Player.STATE_ENDED) {
                    progress.edit().remove(progressKey).apply()
                    history.save(entry, (player.currentPosition / 1000).toInt(), completed = true)
                    readyToSave = false
                }
                if (player.playerError != null) return
                message.visibility = if (!rendered || playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                message.text = "播放失败（${error.errorCodeName}）。"
                message.visibility = View.VISIBLE
                retry.visibility = View.VISIBLE
            }
        })
        val segments = DefaultHttpDataSource.Factory().setUserAgent(stream.userAgent)
        val source = HlsMediaSource.Factory { type ->
            if (type == C.DATA_TYPE_MANIFEST) ByteArrayDataSource(stream.playlist) else segments.createDataSource()
        }.createMediaSource(MediaItem.fromUri(stream.uri))
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player.setMediaSource(source, progress.getLong(progressKey, 0L))
        player.prepare()
        player.play()
    }

    fun pause() {
        player.pause()
        saveProgress()
    }

    fun close() = dialog.dismiss()

    private fun saveProgress() {
        if (!readyToSave) return
        val position = player.currentPosition
        progress.edit().putLong(progressKey, position).apply()
        history.save(entry, (position / 1000).toInt(), completed = false)
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
