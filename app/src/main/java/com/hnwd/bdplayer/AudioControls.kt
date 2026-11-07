package com.hnwd.bdplayer

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
import android.os.Build

internal class AudioControls(
    private val context: Context,
    private val title: String,
    private val setPlaying: (Boolean) -> Unit,
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
            if (intent.action == command) setPlaying(intent.getBooleanExtra("play", false))
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(command), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(command), "${context.packageName}.CONTROL_AUDIO", null)
        }
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "音频播放", NotificationManager.IMPORTANCE_LOW),
        )
        session.setSessionActivity(open)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = setPlaying(true)
            override fun onPause() = setPlaying(false)
        })
    }

    fun update(playing: Boolean, seconds: Int) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
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
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, icon), label, toggle).build())
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
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
    }
}
