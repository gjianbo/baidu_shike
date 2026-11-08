package com.hnwd.bdplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** 视频页“只听声音”时借用页面里的同一个 ExoPlayer 提供媒体通知和前台保活。 */
internal object VideoAudioBridge {
    @Volatile
    var player: Player? = null
}

class VideoAudioSessionService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = VideoAudioBridge.player
        if (player == null) {
            stopSelf()
            return
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setId(java.util.UUID.randomUUID().toString())
            .setSessionActivity(contentIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        fun prepare(player: Player) {
            VideoAudioBridge.player = player
        }

        fun stop(context: Context) {
            VideoAudioBridge.player = null
            context.stopService(Intent(context, VideoAudioSessionService::class.java))
        }
    }
}
