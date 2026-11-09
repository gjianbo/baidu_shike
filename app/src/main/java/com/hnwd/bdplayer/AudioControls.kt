package com.hnwd.bdplayer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.content.ContextCompat

internal class AudioControls(
    private val context: Context,
    private val title: String,
    private val setPlaying: (Boolean) -> Unit,
    private val seekBy: (Int) -> Unit,
) {
    private val session = MediaSession(context, "GroupPlayer")
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val command = "${context.packageName}.AUDIO_COMMAND"
    private var lastPlaying: Boolean? = null
    private val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != command) return
            val delta = intent.getIntExtra("seek", 0)
            if (delta != 0) seekBy(delta) else setPlaying(intent.getBooleanExtra("play", false))
        }
    }
    private fun seekIntent(requestCode: Int, delta: Int) = PendingIntent.getBroadcast(
        context, requestCode,
        Intent(command).setPackage(context.packageName).putExtra("seek", delta),
        PendingIntent.FLAG_IMMUTABLE,
    )

    init {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(command), "${context.packageName}.CONTROL_AUDIO", null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "音频播放", NotificationManager.IMPORTANCE_LOW),
        )
        session.setSessionActivity(open)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = setPlaying(true)
            override fun onPause() = setPlaying(false)
            override fun onFastForward() = seekBy(SEEK_SECONDS)
            override fun onRewind() = seekBy(-SEEK_SECONDS)
        })
    }

    // This MediaStyle notification has a MediaSession token and is exempt from POST_NOTIFICATIONS.
    @SuppressLint("NotificationPermission")
    fun update(playing: Boolean, seconds: Int) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    seconds * 1000L, if (playing) 1f else 0f,
                ).build(),
        )
        if (lastPlaying == playing) return
        lastPlaying = playing
        session.isActive = true
        val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val label = if (playing) "暂停" else "继续"
        val toggle = PendingIntent.getBroadcast(
            context, if (playing) 1 else 2,
            Intent(command).setPackage(context.packageName).putExtra("play", !playing),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_rew),
                        "快退 $SEEK_SECONDS 秒", seekIntent(3, -SEEK_SECONDS),
                    ).build(),
                )
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, icon), label, toggle).build())
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_ff),
                        "快进 $SEEK_SECONDS 秒", seekIntent(4, SEEK_SECONDS),
                    ).build(),
                )
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
                .build(),
        )
    }

    fun close() {
        context.unregisterReceiver(receiver)
        session.release()
        notifications.cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL = "audio_playback"
        const val NOTIFICATION_ID = 1
        const val SEEK_SECONDS = 30
    }
}
