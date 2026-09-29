package com.puls.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun uint8WithoutExtras() {
        val m = HrParser.parse(bytes(0x00, 72))!!
        assertEquals(72, m.bpm)
        assertNull(m.skinContact)
        assertNull(m.energyKj)
        assertEquals(emptyList<Int>(), m.rrMs)
    }

    @Test
    fun uint16Bpm() {
        assertEquals(300, HrParser.parse(bytes(0x01, 0x2C, 0x01))!!.bpm)
    }

    @Test
    fun contactAndRr() {
        // flags: contact supported and present, RR present; RR = 1024 and 512 (1/1024 s)
        val m = HrParser.parse(bytes(0x16, 60, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(60, m.bpm)
        assertEquals(true, m.skinContact)
        assertEquals(listOf(1000, 500), m.rrMs)
    }

    @Test
    fun noContact() {
        assertEquals(false, HrParser.parse(bytes(0x04, 0))!!.skinContact)
    }

    @Test
    fun energyBeforeRr() {
        val m = HrParser.parse(bytes(0x18, 80, 0x10, 0x00, 0x00, 0x03))!!
        assertEquals(16, m.energyKj)
        assertEquals(listOf(750), m.rrMs)
    }

    @Test
    fun truncatedIsNull() {
        assertNull(HrParser.parse(bytes()))
        assertNull(HrParser.parse(bytes(0x01, 0x10)))
    }
}
