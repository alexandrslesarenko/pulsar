package com.puls.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ChartScaleTest {
    private val almaty = TimeZone.getTimeZone("Asia/Almaty")
    private val hour = 3_600_000L

    @Test
    fun bucketGrowsWithSpan() {
        assertEquals(5_000L, bucketFor(10 * 60_000L))
        assertEquals(10_000L, bucketFor(hour))
        assertEquals(5 * 60_000L, bucketFor(24 * hour))
        assertEquals(2 * hour, bucketFor(31 * DAY_MS))
    }

    @Test
    fun midnightInLocalZone() {
        // 2026-09-23 00:00 в Алматы (UTC+5) - это 2026-09-22 19:00 UTC.
        val midnight = 1_790_103_600_000L
        assertTrue(isMidnight(midnight, almaty))
        assertFalse(isMidnight(midnight + hour, almaty))
    }

    @Test
    fun ticksAreRoundAndFew() {
        val from = 1_790_103_600_000L + 17 * 60_000L
        val (step, ticks) = timeTicks(from, from + 6 * hour, almaty)
        assertEquals(2 * hour, step)
        assertTrue(ticks.size in 2..5)
        ticks.forEach { assertEquals(0L, Math.floorMod(it + almaty.getOffset(it), step)) }
    }

    @Test
    fun weekUsesDayTicks() {
        val from = 1_790_103_600_000L
        val (step, _) = timeTicks(from, from + 7 * DAY_MS, almaty)
        assertTrue(step >= DAY_MS)
    }
}
