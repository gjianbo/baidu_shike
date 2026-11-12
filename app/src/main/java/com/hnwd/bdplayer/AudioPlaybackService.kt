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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import androidx.core.net.toUri

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
    /** v0.2 睡眠定时：每秒轮询 SleepTimer，到点暂停并停止。 */
    private val sleepTick = object : Runnable {
        override fun run() {
            if (!SleepTimer.isArmed()) return
            if (SleepTimer.remainingMs() <= 0L) {
                SleepTimer.disarm()
                player.pause()
            } else {
                handler.postDelayed(this, 1_000L)
            }
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
            // 通知栏 / 耳机线控的 30s 快进快退：media3 通知的 ⏪⏩ 直接用这两个增量。
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
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
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(activity)
            // 系统「媒体卡片」（下拉通知里 SystemUI 自己渲染的那块）只认 MediaButtonPreferences，
            // 不读通知里的自定义 action —— 这里同步声明，卡片上才有 30s 两个按钮。
            .setMediaButtonPreferences(seekMediaButtons())
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val sameApp = controller.uid == applicationInfo.uid
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if (sameApp) commands.add(PLAY_FILE).add(CLEAR_FILE).add(SYNC_ACCOUNT).add(RENEW_FILE).add(RENEW_HLS).add(SLEEP_TIMER)
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        // 单条播放：上一项/下一项点了没有效果，去掉，别让系统媒体卡片画一个死按钮
                        .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .remove(Player.COMMAND_SEEK_TO_NEXT)
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
                        customCommand == RENEW_HLS -> renewHls(args)
                        customCommand == SYNC_ACCOUNT -> {
                            if (entry != null && playbackAccount != AccountScope.current) clearFile()
                            SessionResult.RESULT_SUCCESS
                        }
                        customCommand == SLEEP_TIMER -> {
                            val minutes = args.getInt("minutes", 0)
                            handler.removeCallbacks(sleepTick)
                            SleepTimer.arm(minutes)
                            if (minutes > 0) handler.postDelayed(sleepTick, 1_000L)
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
        // media3 默认通知只有上一项/播放暂停；补 30s 快退/快进两个按钮。
        setMediaNotificationProvider(SeekButtonsProvider(this))
    }

    private fun playFile(args: Bundle): Int {
        if (!AudioPlayRequestGate.isCurrent(args.getLong("requestToken"))) {
            return SessionResult.RESULT_ERROR_INVALID_STATE
        }
        val account = args.getString("account").orEmpty()
        val uri = args.getString("uri").orEmpty().toUri()
        val playlist = args.getByteArray("playlist")
        val origin = runCatching { EntryOrigin.valueOf(args.getString("origin") ?: "GROUP") }
            .getOrDefault(EntryOrigin.GROUP)
        val next = PanEntry(
            args.getString("gid").orEmpty(), args.getString("message").orEmpty(),
            args.getString("owner").orEmpty(), args.getString("file").orEmpty(),
            args.getString("name").orEmpty(), args.getString("path").orEmpty(),
            args.getString("size").orEmpty(), false, origin,
        )
        val quark = next.provider == SourceProvider.QUARK
        if (account != AccountScope.current || (!quark && account.isEmpty()) ||
            next.fileId.isEmpty() || next.name.isEmpty() ||
            (!quark && origin == EntryOrigin.GROUP && (next.gid.isEmpty() || next.messageId.isEmpty()))
        ) {
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
            // 显式给 artist：留空时系统媒体卡片会显示 "null"
            .setArtist("拾课")
            .setExtras(Bundle().apply {
                putString("account", account)
                putString("provider", next.provider.name)
                putString("origin", next.origin.name)
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
        val uri = args.getString("uri").orEmpty().toUri()
        val current = entry ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        if (!isAudioSource(current, uri, null)) return SessionResult.RESULT_ERROR_BAD_VALUE

        val position = player.currentPosition
        val metadata = item.mediaMetadata.buildUpon()
            .setExtras(Bundle(extras).apply {
                putBoolean("renewed", !wasHls)
                putBoolean("hls", false)
                if (wasHls) putString("playbackId", UUID.randomUUID().toString())
            })
            .build()
        setAudioSource(item.buildUpon().setUri(uri).setMediaMetadata(metadata).build(), uri, null, position)
        player.prepare()
        return SessionResult.RESULT_SUCCESS
    }

    private fun renewHls(args: Bundle): Int {
        val item = player.currentMediaItem ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        val extras = item.mediaMetadata.extras ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        if (playbackAccount != AccountScope.current ||
            extras.getString("playbackId") != args.getString("playbackId") ||
            !extras.getBoolean("hls")
        ) return SessionResult.RESULT_ERROR_BAD_VALUE
        val current = entry ?: return SessionResult.RESULT_ERROR_BAD_VALUE
        if (!current.isDisk) return SessionResult.RESULT_ERROR_BAD_VALUE
        val uri = args.getString("uri").orEmpty().toUri()
        val playlist = args.getByteArray("playlist")
        if (playlist == null || playlist.isEmpty() || !PanApi.isDiskStreaming(uri, current)) {
            return SessionResult.RESULT_ERROR_BAD_VALUE
        }
        val position = player.currentPosition
        val metadata = item.mediaMetadata.buildUpon()
            .setExtras(Bundle(extras).apply {
                putString("playbackId", UUID.randomUUID().toString())
                putBoolean("hls", true)
                remove("renewed")
            })
            .build()
        val next = item.buildUpon().setUri(uri).setMediaMetadata(metadata).build()
        setAudioSource(next, uri, playlist, position)
        player.prepare()
        player.play()
        return SessionResult.RESULT_SUCCESS
    }

    private fun isAudioSource(entry: PanEntry, uri: Uri, playlist: ByteArray?): Boolean =
        if (entry.provider == SourceProvider.QUARK) {
            playlist == null && QuarkPersonalDrive.isMediaUri(uri)
        } else if (playlist == null) {
            if (entry.isDisk) PanApi.isCachedFileLink(uri) else PanApi.isFileLink(uri)
        }
        else playlist.isNotEmpty() && (
            if (entry.isDisk) PanApi.isDiskStreaming(uri, entry)
            else uri == PanApi.audioPlaylist(entry)
        )

    private fun setAudioSource(item: MediaItem, uri: Uri, playlist: ByteArray?, position: Long) {
        if (entry?.provider == SourceProvider.QUARK) {
            player.setMediaSource(QuarkPlayback(uri, false).mediaSource(this, item), position)
        } else if (playlist == null) {
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
        handler.removeCallbacks(sleepTick)
        SleepTimer.disarm()
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
        handler.removeCallbacks(sleepTick)
        saveProgress()
        session.player.release()
        session.release()
        super.onDestroy()
    }

    companion object {
        internal val PLAY_FILE = SessionCommand("com.hnwd.bdplayer.PLAY_FILE", Bundle.EMPTY)
        internal val RENEW_FILE = SessionCommand("com.hnwd.bdplayer.RENEW_FILE", Bundle.EMPTY)
        internal val RENEW_HLS = SessionCommand("com.hnwd.bdplayer.RENEW_HLS", Bundle.EMPTY)
        internal val SYNC_ACCOUNT = SessionCommand("com.hnwd.bdplayer.SYNC_ACCOUNT", Bundle.EMPTY)
        internal val CLEAR_FILE = SessionCommand("com.hnwd.bdplayer.CLEAR_FILE", Bundle.EMPTY)
        internal val SLEEP_TIMER = SessionCommand("com.hnwd.bdplayer.SLEEP_TIMER", Bundle.EMPTY)
    }
}

/** 30 秒快退 / 播放暂停 / 30 秒快进。同一份按钮同时用于系统媒体卡片（MediaButtonPreferences）
 *  和通知（SeekButtonsProvider），避免两处显示不一致。 */
@OptIn(UnstableApi::class)
private fun seekMediaButtons(showPauseButton: Boolean = true): ImmutableList<CommandButton> {
    val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setDisplayName("快退 30 秒")
        .setSlots(CommandButton.SLOT_BACK)
        .build()
    val playPause = CommandButton.Builder(
        if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY,
    )
        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
        .setDisplayName(if (showPauseButton) "暂停" else "播放")
        .setSlots(CommandButton.SLOT_CENTRAL)
        .build()
    val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setDisplayName("快进 30 秒")
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()
    return ImmutableList.of(back, playPause, forward)
}

@OptIn(UnstableApi::class)
private class SeekButtonsProvider(context: android.content.Context) :
    DefaultMediaNotificationProvider(context) {
    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        currentButtons: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> = seekMediaButtons(showPauseButton)
}

internal object AudioPlayRequestGate {
    private val generation = AtomicLong()

    fun begin(): Long = generation.incrementAndGet()
    fun isCurrent(token: Long): Boolean = token > 0 && generation.get() == token
    fun cancel(token: Long) { generation.compareAndSet(token, token + 1) }
    fun cancelCurrent() { generation.incrementAndGet() }
}
