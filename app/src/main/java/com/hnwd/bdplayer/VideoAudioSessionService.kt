package com.hnwd.bdplayer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 视频页「只听声音」时借用页面里的同一个 ExoPlayer 提供媒体通知和前台保活。
 *
 * 生命周期关键点：换课会先构造新 [VideoPlaybackPage]（新 ExoPlayer）再 close 旧页面，
 * 所以 [VideoAudioBridge.prepare] 通过 [MediaSession.setPlayer] **原地换绑**，不重建
 * session、不重启 Service；[VideoAudioBridge.release] 用 player 身份校验，避免旧页面
 * 的 close() 把新页面刚接管的会话清掉（那正是「状态断离 / 内容还是上一课」的根因）。
 */
internal object VideoAudioBridge {
    @Volatile
    var player: Player? = null

    /** Service 存活期间注册；prepare / release 时通知它换绑或停止。 */
    @Volatile
    var onSessionChanged: ((Player?) -> Unit)? = null

    fun prepare(player: Player) {
        // VideoPlaybackPage 构造期会预占一次、STATE_READY 时 ensureAudioSession() 再调一次；
        // 幂等避免对同一个 MediaSession 重复 setPlayer。
        if (this.player === player) return
        this.player = player
        onSessionChanged?.invoke(player)
    }

    /** 仅当传入的 player 仍是当前持有者才清空并停 Service；换课时旧页面会被忽略。 */
    fun release(context: Context, player: Player) {
        if (this.player !== player) return
        this.player = null
        onSessionChanged?.invoke(null)
        context.stopService(Intent(context, VideoAudioSessionService::class.java))
    }
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
        buildSession(player)
        // 换课：新页面 prepare(newPlayer) 时原地换绑，通知的状态与标题
        // （来自 player.currentMediaItem.mediaMetadata）随之更新，无需重启 Service。
        VideoAudioBridge.onSessionChanged = { next ->
            when {
                next == null -> {
                    session?.release()
                    session = null
                    stopSelf()
                }
                session == null -> buildSession(next)
                else -> session?.setPlayer(next)
            }
        }
    }

    /** stopSelf 后 Service 实例可能因系统仍持有 bind 而未立即 onDestroy；
     *  此刻若新页面已预占 player，需要能重建 session，避免 onGetSession 返回 null 的死区。 */
    private fun buildSession(player: Player) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSession.Builder(this, player)
            .setId(java.util.UUID.randomUUID().toString())
            .setSessionActivity(contentIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (session == null) VideoAudioBridge.player?.let(::buildSession)
        return session
    }

    override fun onDestroy() {
        VideoAudioBridge.onSessionChanged = null
        session?.release()
        session = null
        super.onDestroy()
    }
}
