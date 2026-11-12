package com.hnwd.bdplayer

import android.os.SystemClock

/** 音频服务和视频播放各自维护定时，避免切换播放器时相互清除。 */
internal class SleepTimer(private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime) {
    var deadlineElapsedMs: Long = 0L
        private set
    var stopAfterCurrent: Boolean = false
        private set

    fun set(minutes: Int) {
        when {
            minutes == AFTER_CURRENT -> stopAfterCurrent = true
            minutes > 0 -> deadlineElapsedMs = elapsedRealtime() + minutes * 60_000L
            minutes == 0 -> {
                clearDeadline()
                stopAfterCurrent = false
            }
        }
    }

    fun clearDeadline() { deadlineElapsedMs = 0L }

    fun consumeStopAfterCurrent(): Boolean = stopAfterCurrent.also { stopAfterCurrent = false }

    fun isArmed(): Boolean = deadlineElapsedMs > 0L

    fun remainingMs(): Long = (deadlineElapsedMs - elapsedRealtime()).coerceAtLeast(0L)

    companion object {
        const val AFTER_CURRENT = -1
        val audio = SleepTimer()
    }
}
