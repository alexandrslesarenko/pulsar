package com.puls.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
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
        // 2026-09-23 00:00 in Almaty (UTC+5) is 2026-09-22 19:00 UTC.
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

    @Test
    fun midnightsInsideRange() {
        val zone = ZoneId.of("Asia/Almaty")
        val midnight = 1_790_103_600_000L
        assertEquals(listOf(midnight), midnights(midnight - hour, midnight + hour, zone))
        // The from bound itself does not count: a line at the start of the chart is not needed.
        assertEquals(emptyList<Long>(), midnights(midnight, midnight + hour, zone))
        assertEquals(emptyList<Long>(), midnights(midnight + hour, midnight + 5 * hour, zone))
        assertEquals(3, midnights(midnight - hour, midnight + 2 * DAY_MS + hour, zone).size)
    }

    @Test
    fun midnightsFollowDaylightSaving() {
        // On 2026-03-29 Berlin changes clocks: the day of March 28-29 is 23 h long.
        val zone = ZoneId.of("Europe/Berlin")
        val from = java.time.LocalDateTime.of(2026, 3, 28, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val m = midnights(from, from + 2 * DAY_MS, zone)
        assertEquals(2, m.size)
        assertEquals(23 * hour, m[1] - m[0])
        m.forEach { assertEquals(0, java.time.Instant.ofEpochMilli(it).atZone(zone).hour) }
    }
}
