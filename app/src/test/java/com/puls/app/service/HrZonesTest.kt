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
}
