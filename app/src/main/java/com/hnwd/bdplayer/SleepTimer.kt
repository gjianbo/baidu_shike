package com.hnwd.bdplayer

import android.os.SystemClock

/**
 * v0.2 睡眠定时：进程内单例，供音频后台服务、视频播放页和界面共同读写。
 * - [deadlineElapsedMs] 为 0 表示未开启定时。
 * - [stopAfterCurrent] 只生效一次，由「本集结束停止」消费。
 */
internal object SleepTimer {
    @Volatile
    var deadlineElapsedMs: Long = 0L
        private set

    @Volatile
    var stopAfterCurrent: Boolean = false
        private set

    fun arm(minutes: Int) {
        deadlineElapsedMs = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
    }

    fun disarm() {
        deadlineElapsedMs = 0L
    }

    fun armStopAfterCurrent() {
        stopAfterCurrent = true
    }

    fun consumeStopAfterCurrent(): Boolean = stopAfterCurrent.also { stopAfterCurrent = false }

    fun isArmed(): Boolean = deadlineElapsedMs > 0L

    fun remainingMs(): Long = (deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
}
