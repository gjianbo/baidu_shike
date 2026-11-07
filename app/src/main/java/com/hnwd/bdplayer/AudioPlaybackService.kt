package com.hnwd.bdplayer

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class AudioPlaybackService : MediaSessionService() {
    private lateinit var session: MediaSession
    private lateinit var history: AudioHistory
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
        history = AudioHistory(getSharedPreferences("audio_history", MODE_PRIVATE))
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
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
                    if (sameApp) commands.add(PLAY_FILE).add(CLEAR_FILE)
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                        .remove(Player.COMMAND_SET_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build())
                        .setAvailablePlayerCommands(playerCommands)
                        .build()
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
        val uri = Uri.parse(args.getString("uri").orEmpty())
        if (!PanApi.isFileLink(uri)) return SessionResult.RESULT_ERROR_BAD_VALUE
        val next = PanEntry(
            args.getString("gid").orEmpty(), args.getString("message").orEmpty(),
            args.getString("owner").orEmpty(), args.getString("file").orEmpty(),
            args.getString("name").orEmpty(), "", "", false,
        )
        if (listOf(next.gid, next.messageId, next.fileId, next.name).any(String::isEmpty)) {
            return SessionResult.RESULT_ERROR_BAD_VALUE
        }
        clearFile()
        entry = next
        val previous = history.find(next)?.takeUnless { it.completed }
        lastSaved = previous?.let { it.seconds to false }
        session.player.apply {
            setMediaItem(
                MediaItem.Builder().setUri(uri).setMediaId(next.fileId)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(next.name).build()).build(),
                (previous?.seconds ?: 0) * 1000L,
            )
            prepare()
            play()
        }
        return SessionResult.RESULT_SUCCESS
    }

    private fun clearFile() {
        saveProgress()
        hasPlayed = false
        entry = null
        session.player.stop()
        session.player.clearMediaItems()
    }

    private fun saveProgress() {
        val current = entry ?: return
        if (!hasPlayed) return
        val player = session.player
        val progress = (player.currentPosition / 1000).toInt() to (player.playbackState == Player.STATE_ENDED)
        if (progress == lastSaved) return
        history.save(current, progress.first, progress.second)
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
        internal val CLEAR_FILE = SessionCommand("com.hnwd.bdplayer.CLEAR_FILE", Bundle.EMPTY)
    }
}
