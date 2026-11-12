package com.hnwd.bdplayer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 上传保活壳：只负责前台通知与状态转发，任务本体在 [UploadCenter]（进程级）。
 *
 * 这样 Activity 销毁、进度 Dialog 关闭都不会打断上传；用户从通知栏也能取消。
 * foregroundServiceType=dataSync 是 Android 14 起强制要求的（targetSdk 35）。
 * 未授予 POST_NOTIFICATIONS 时，前台服务通知仍会出现在系统的任务管理器里，
 * 服务照常运行，只是通知抽屉不显示 —— 不影响「后台传完」这个核心目标。
 */
@SuppressLint("NotificationPermission")
internal class UploadForegroundService : Service() {
    private lateinit var notifications: NotificationManager
    private var listener: ((UploadCenter.State) -> Unit)? = null
    private var seenRunning = false

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        // minSdk 29，通知渠道 API 必然可用，无需再做 SDK 判断。
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "文件上传", NotificationManager.IMPORTANCE_LOW).apply {
                description = "上传到网盘的进度与结果"
                setSound(null, null)
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 后必须在 5 秒内进入前台，先做这一步再做别的。
        startForeground(
            NOTIFICATION_ID,
            buildNotification(UploadCenter.state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (listener == null) {
            val observer: (UploadCenter.State) -> Unit = { state -> onState(state) }
            listener = observer
            UploadCenter.addListener(observer)
        }
        when (intent?.action) {
            ACTION_CANCEL -> UploadCenter.cancelCurrent(this)
            ACTION_START -> {
                // 队列由 UploadCenter 持有；本服务只是保活壳，不解析文件 Uri。
                // startUpload 总是先 enqueue（running=true）再 startService，所以
                // running=false 只可能是整批已结束或 START_STICKY 重建后的空进程 —— 直接退出，
                // 避免挂一个没有任务的前台服务。
                if (!UploadCenter.state.running) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun onState(state: UploadCenter.State) {
        if (state.running) {
            seenRunning = true
            notifications.notify(NOTIFICATION_ID, buildNotification(state))
        } else if (state.finished && seenRunning) {
            seenRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            notifications.notify(RESULT_ID, buildResult(state))
            stopSelf()
        }
    }

    private fun buildNotification(state: UploadCenter.State) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (state.name.isBlank()) "正在上传文件" else "正在上传 ${state.name}")
            .setContentText(
                "${state.percent.coerceIn(0, 100)}% · ${state.label}" +
                    if (state.queued > 0) " · 还有 ${state.queued} 个排队" else "",
            )
            .setProgress(100, state.percent.coerceIn(0, 100), false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent())
            .build()

    private fun buildResult(state: UploadCenter.State) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (state.success) "上传完成" else "上传失败")
            .setContentText(state.message)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent() = PendingIntent.getService(
        this,
        1,
        Intent(this, UploadForegroundService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        listener?.let { UploadCenter.removeListener(it) }
        listener = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "shike_upload"
        const val NOTIFICATION_ID = 2101
        const val RESULT_ID = 2102
        const val ACTION_START = "com.hnwd.bdplayer.UPLOAD_START"
        const val ACTION_CANCEL = "com.hnwd.bdplayer.UPLOAD_CANCEL"

        /** 从前台 Activity 发起，符合 Android 12+ 对后台启动前台服务的限制。 */
        fun start(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
