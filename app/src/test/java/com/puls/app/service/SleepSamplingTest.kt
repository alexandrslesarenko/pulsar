package com.puls.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepSamplingTest {
    private val start = 1_000_000L

    private fun pause(
        interval: Int = 5, sleep: Boolean = true, alarmOn: Boolean = false, screenOn: Boolean = false,
        elapsed: Long = SleepSampling.WINDOW_MS,
    ) = SleepSampling.shouldPause(interval, sleep, alarmOn, screenOn, start, start + elapsed)

    @Test
    fun pausesAfterWindowInSleep() {
        assertTrue(pause())
        assertFalse(pause(elapsed = SleepSampling.WINDOW_MS - 1))
    }

    @Test
    fun keepsStreamWhenOffOrAwakeOrAlarmOrScreen() {
        assertFalse(pause(interval = 0))
        assertFalse(pause(sleep = false))
        assertFalse(pause(alarmOn = true))
        assertFalse(pause(screenOn = true))
    }

    @Test
    fun intervalCountsFromWindowStart() {
        assertEquals(start + 5 * 60_000L, SleepSampling.resumeAt(5, start, start + SleepSampling.WINDOW_MS))
    }

    @Test
    fun pauseIsNeverTooShort() {
        // The window ran long (alarm, screen): the next one still comes after a real pause.
        val now = start + 10 * 60_000L
        assertEquals(now + SleepSampling.MIN_PAUSE_MS, SleepSampling.resumeAt(5, start, now))
    }
}
