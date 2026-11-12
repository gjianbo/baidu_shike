package com.hnwd.bdplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    @Test
    fun endOfTrackAndMinuteTimersCanBeSetInEitherOrder() {
        var now = 1000L
        val timer = SleepTimer { now }
        timer.set(15)
        now += 5000L
        timer.set(SleepTimer.AFTER_CURRENT)
        assertEquals(895_000L, timer.remainingMs())

        timer.set(30)
        assertTrue(timer.consumeStopAfterCurrent())
        assertFalse(timer.consumeStopAfterCurrent())
        assertEquals(1_800_000L, timer.remainingMs())
    }

    @Test
    fun clearingTheMinuteDeadlineKeepsTheEndOfTrackTimer() {
        val timer = SleepTimer { 1000L }
        timer.set(15)
        timer.set(SleepTimer.AFTER_CURRENT)
        timer.clearDeadline()
        assertFalse(timer.isArmed())
        assertTrue(timer.stopAfterCurrent)

        timer.set(0)
        assertFalse(timer.stopAfterCurrent)
    }
}
