package com.puls.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HrZonesTest {
    private val today = LocalDate.of(2026, 9, 22)

    @Test
    fun exactDate() {
        assertEquals(40, HrZones.age(1986, 9, 22, today))
        assertEquals(39, HrZones.age(1986, 9, 23, today))
    }

    @Test
    fun yearAndMonthUseMiddleOfMonth() {
        assertEquals(39, HrZones.age(1986, 10, 0, today))
        assertEquals(40, HrZones.age(1986, 8, 0, today))
    }

    @Test
    fun yearOnlyUsesJulyFirst() {
        assertEquals(40, HrZones.age(1986, 0, 0, today))
        assertEquals(40, HrZones.age(1986, 0, 0, LocalDate.of(2026, 7, 1)))
        assertEquals(39, HrZones.age(1986, 0, 0, LocalDate.of(2026, 6, 30)))
    }

    @Test
    fun walkZoneFor40() {
        assertEquals(180, HrZones.maxHr(40))
        assertEquals(108..126, HrZones.walkZone(40))
    }

    @Test
    fun validation() {
        assertTrue(HrZones.isValid(1986, 0, 0, today))
        assertTrue(HrZones.isValid(1986, 2, 28, today))
        assertTrue(HrZones.isValid(1988, 2, 29, today))
        assertFalse(HrZones.isValid(1986, 2, 29, today))
        assertFalse(HrZones.isValid(1986, 0, 5, today))
        assertFalse(HrZones.isValid(1986, 13, 0, today))
        assertFalse(HrZones.isValid(1850, 0, 0, today))
        assertFalse(HrZones.isValid(2025, 0, 0, today))
    }

    @Test
    fun nightAcrossMidnight() {
        val from = 23 * 60
        val to = 7 * 60
        assertTrue(HrZones.isNight(23 * 60, from, to))
        assertTrue(HrZones.isNight(0, from, to))
        assertTrue(HrZones.isNight(6 * 60 + 59, from, to))
        assertFalse(HrZones.isNight(7 * 60, from, to))
        assertFalse(HrZones.isNight(22 * 60 + 59, from, to))
    }

    @Test
    fun nightWithinDay() {
        assertTrue(HrZones.isNight(14 * 60, 13 * 60, 15 * 60))
        assertFalse(HrZones.isNight(15 * 60, 13 * 60, 15 * 60))
        assertFalse(HrZones.isNight(12 * 60, 13 * 60, 15 * 60))
    }

    @Test
    fun sleepLastsIntoMorningUntilSteps() {
        val from = 23 * 60
        val to = 7 * 60
        assertTrue(HrZones.isSleep(3 * 60, from, to, 5, pedometer = true))
        // 8:00, шагов не было с вечера - ещё спим.
        assertTrue(HrZones.isSleep(8 * 60, from, to, 12 * 60, pedometer = true))
        assertTrue(HrZones.isSleep(8 * 60, from, to, null, pedometer = true))
        // 8:00, шаги в 7:30 - проснулись.
        assertFalse(HrZones.isSleep(8 * 60, from, to, 30, pedometer = true))
        // Утро ограничено: в 10:00 сна уже нет, даже без шагов.
        assertFalse(HrZones.isSleep(10 * 60, from, to, null, pedometer = true))
        // Без шагомера утро не продлеваем.
        assertFalse(HrZones.isSleep(8 * 60, from, to, null, pedometer = false))
        assertFalse(HrZones.isSleep(20 * 60, from, to, null, pedometer = true))
    }

    @Test
    fun emptyNight() {
        assertFalse(HrZones.isNight(0, 7 * 60, 7 * 60))
    }

    @Test
    fun trainingZone() {
        // 40 лет: максимум 180, 70-85% -> 126-153.
        assertEquals(126..153, HrZones.trainingZone(40))
    }

    @Test
    fun zoneTableMatchesProfileRanges() {
        // Зона 2 из таблицы - тот же коридор, что у прогулки в режиме "авто".
        val (lo, hi) = HrZones.ZONE_PCT[1]
        assertEquals(HrZones.walkZone(50), HrZones.pctRange(50, lo, hi))
        assertEquals(85..102, HrZones.pctRange(50, 50, 60))
        assertEquals(153..170, HrZones.pctRange(50, 90, 100))
    }
}
