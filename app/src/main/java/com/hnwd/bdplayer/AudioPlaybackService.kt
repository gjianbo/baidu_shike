package com.hnwd.bdplayer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var player: ExoPlayer
    private val hlsHttp = DefaultHttpDataSource.Factory().setUserAgent(PanApi.DLINK_USER_AGENT)
    private var history: AudioHistory? = null
    private var playbackAccount = ""
    private var entry: PanEntry? = null
    private var hasPlayed = false
    private var lastSaved: Pair<Int, Boolean>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val checkpoint = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AccountScope.init(this)
        // 文档 §5.3：dlink 是 302 跳 CDN，签名自带、不需要 Cookie，但必须带 pan.baidu.com UA。
        // 默认 ExoPlayer UA 会在 302 那一步被拦，所以把带 UA 的 DataSource 注入默认 MediaSourceFactory。
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(PanApi.DLINK_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            setPlaybackParameters(PlaybackParameters(PlaybackSpeed.audio(this@AudioPlaybackService)))
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    handler.removeCallbacks(checkpoint)
                    if (isPlaying) {
                        hasPlayed = true
                        handler.postDelayed(checkpoint, 5_000)
                    } else {
                        saveProgress()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) saveProgress()
                }
            })
        }
        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sameApp = controller.uid == applicationInfo.uid
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (sameApp) commands.add(PLAY_FILE).add(CLEAR_FILE).add(SYNC_ACCOUNT).add(RENEW_FILE)
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build())
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
                }

                @Suppress("DEPRECATION")
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    if (playerCommand == Player.COMMAND_PLAY_PAUSE || playerCommand == Player.COMMAND_STOP) {
                        AudioPlayRequestGate.cancelCurrent()
                    }
                    return SessionResult.RESULT_SUCCESS
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    val result = when {
                        controller.uid != applicationInfo.uid -> SessionResult.RESULT_ERROR_PERMISSION_DENIED
                        customCommand == PLAY_FILE -> playFile(args)
                        customCommand == RENEW_FILE -> renewFile(args)
                        customCommand == SYNC_ACCOUNT -> {
                            if (entry != null && playbackAccount != AccountScope.current) clearFile()
                            SessionResult.RESULT_SUCCESS
                        }
                        customCommand == CLEAR_FILE -> {
                            clearFile()
                            SessionResult.RESULT_SUCCESS
                        }
                        else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
                    }
                    return Futures.immediateFuture(SessionResult(result))
                }
            }).build()
    }

    private fun playFile(args: Bundle): Int {
        if (!AudioPlayRequestGate.isCurrent(args.getLong("requestToken"))) {
            return SessionResult.RESULT_ERROR_INVALID_STATE
        }
        val account = args.getString("account").orEmpty()
        if (account.isEmpty() || account != AccountScope.current) return SessionResult.RESULT_ERROR_BAD_VALUE
        val uri = Uri.parse(args.getString("uri").orEmpty())
        val playlist = args.getByteArray("playlist")
        val next = PanEntry(
            args.getString("gid").orEmpty(), args.getString("message").orEmpty(),
            args.getString("owner").orEmpty(), args.getString("file").orEmpty(),
            args.getString("name").orEmpty(), args.getString("path").orEmpty(),
            args.getString("size").orEmpty(), false,
        )
        if (listOf(next.gid, next.messageId, next.fileId, next.name).any(String::isEmpty)) {
            return SessionResult.RESULT_ERROR_BAD_VALUE
        }
        if (!isAudioSource(next, uri, playlist)) return SessionResult.RESULT_ERROR_BAD_VALUE
        clearFile()
        entry = next
        playbackAccount = account
        val playbackHistory = AudioHistory(getSharedPreferences("audio_history", MODE_PRIVATE), account)
        history = playbackHistory
        val previous = playbackHistory.find(next)?.takeUnless { it.completed }
        lastSaved = previous?.let { it.seconds to false }
        val metadata = MediaMetadata.Builder().setTitle(next.name)
            .setExtras(Bundle().apply {
                putString("account", account)
                putString("gid", next.gid)
                putString("message", next.messageId)
                putString("owner", next.owner)
                putString("path", next.path)
                putString("size", next.size)
                putString("playbackId", UUID.randomUUID().toString())
                putBoolean("hls", playlist != null)
            }).build()
        val item = MediaItem.Builder().setUri(uri).setMediaId(next.fileId)
            .setMediaMetadata(metadata).build()
        setAudioSource(item, uri, playlist, (previous?.seconds ?: 0) * 1000L)
        player.prepare()
        player.play()
        return SessionResult.RESULT_SUCCESS
    }

    private fun renewFile(args: Bundle): Int {
        val item = player.currentMediaItem ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        val extras = item.mediaMetadata.extras ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        val error = player.playerError ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        val wasHls = extras.getBoolean("hls")
        if (playbackAccount != AccountScope.current || extras.getString("playbackId") != args.getString("playbackId") ||
            extras.getBoolean("renewed") || (!wasHls && !error.isExpiredMediaLink())
        ) return SessionResult.RESULT_ERROR_BAD_VALUE
        val uri = Uri.parse(args.getString("uri").orEmpty())
        if (!PanApi.isFileLink(uri)) return SessionResult.RESULT_ERROR_BAD_VALUE

        val position = player.currentPosition
        val metadata = item.mediaMetadata.buildUpon()
            .setExtras(Bundle(extras).apply {
                putBoolean("renewed", !wasHls)
                putBoolean("hls", false)
                if (wasHls) putString("playbackId", UUID.randomUUID().toString())
            })
            .build()
        player.setMediaItem(item.buildUpon().setUri(uri).setMediaMetadata(metadata).build(), position)
        player.prepare()
        return SessionResult.RESULT_SUCCESS
    }

    private fun isAudioSource(entry: PanEntry, uri: Uri, playlist: ByteArray?): Boolean =
        if (playlist == null) PanApi.isFileLink(uri)
        else playlist.isNotEmpty() && uri == PanApi.audioPlaylist(entry)

    private fun setAudioSource(item: MediaItem, uri: Uri, playlist: ByteArray?, position: Long) {
        if (playlist == null) {
            player.setMediaItem(item, position)
        } else {
            val stream = VideoStream(uri, playlist)
            val source = HlsMediaSource.Factory { type ->
                if (type == C.DATA_TYPE_MANIFEST) HlsManifestDataSource(stream, hlsHttp)
                else hlsHttp.createDataSource()
            }.createMediaSource(item)
            player.setMediaSource(source, position)
        }
    }

    private fun clearFile() {
        saveProgress()
        hasPlayed = false
        entry = null
        history = null
        playbackAccount = ""
        session.player.stop()
        session.player.clearMediaItems()
    }

    private fun saveProgress() {
        val current = entry ?: return
        if (!hasPlayed) return
        val player = session.player
        val seconds = (player.currentPosition / 1000).toInt()
        val ended = player.playbackState == Player.STATE_ENDED
        // 自动切歌时 clearFile 可能用 0 秒快照覆盖刚写完的完成记录。
        if (!ended && seconds == 0 && history?.find(current)?.completed == true) return
        val progress = seconds to ended
        if (progress == lastSaved) return
        val durationMs = player.duration
        val duration = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        history?.save(current, progress.first, progress.second, duration)
        lastSaved = progress
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onDestroy() {
        handler.removeCallbacks(checkpoint)
        saveProgress()
        session.player.release()
        session.release()
        super.onDestroy()
    }

    companion object {
        internal val PLAY_FILE = SessionCommand("com.hnwd.bdplayer.PLAY_FILE", Bundle.EMPTY)
        internal val RENEW_FILE = SessionCommand("com.hnwd.bdplayer.RENEW_FILE", Bundle.EMPTY)
        internal val SYNC_ACCOUNT = SessionCommand("com.hnwd.bdplayer.SYNC_ACCOUNT", Bundle.EMPTY)
        internal val CLEAR_FILE = SessionCommand("com.hnwd.bdplayer.CLEAR_FILE", Bundle.EMPTY)
    }
}

internal object AudioPlayRequestGate {
    private val generation = AtomicLong()

    fun begin(): Long = generation.incrementAndGet()
    fun isCurrent(token: Long): Boolean = token > 0 && generation.get() == token
    fun cancel(token: Long) { generation.compareAndSet(token, token + 1) }
    fun cancelCurrent() { generation.incrementAndGet() }
}
