package com.puls.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrRecognitionTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun heartRateNames() {
        listOf("COROS HEART RATE 1D3D4B", "Polar H10 A1B2C3", "HRM-Pro:123", "Garmin HRM-Dual", "TICKR 1234", "HR 12345")
            .forEach { assertTrue(it, HrScanner.looksLikeHrName(it)) }
    }

    @Test
    fun otherNames() {
        listOf("Galaxy Buds2 Pro", "[TV] Samsung Q80", "Chrome", "JBL Charge 5", "Mi Band 7", "SHR-1000")
            .forEach { assertFalse(it, HrScanner.looksLikeHrName(it)) }
    }

    @Test
    fun appearanceHeartRateBelt() {
        // flags, затем Appearance 0x0341 (Heart Rate Belt)
        assertTrue(HrScanner.isHrAppearance(bytes(0x02, 0x01, 0x06, 0x03, 0x19, 0x41, 0x03)))
    }

    @Test
    fun appearanceOther() {
        // Appearance 0x0941 (Earbud) и обрезанный пакет
        assertFalse(HrScanner.isHrAppearance(bytes(0x03, 0x19, 0x41, 0x09)))
        assertFalse(HrScanner.isHrAppearance(bytes(0x05, 0x19, 0x41)))
        assertFalse(HrScanner.isHrAppearance(null))
    }
}
