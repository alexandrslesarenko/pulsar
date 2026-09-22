package com.puls.app.ble

data class HrMeasurement(
    val bpm: Int,
    /** null - датчик не сообщает о контакте с кожей. */
    val skinContact: Boolean?,
    val energyKj: Int?,
    val rrMs: List<Int>,
)

/** Разбор характеристики Heart Rate Measurement (0x2A37) по спецификации Heart Rate Service. */
object HrParser {
    private const val FLAG_UINT16 = 0x01
    private const val FLAG_CONTACT_DETECTED = 0x02
    private const val FLAG_CONTACT_SUPPORTED = 0x04
    private const val FLAG_ENERGY = 0x08
    private const val FLAG_RR = 0x10

    fun parse(data: ByteArray): HrMeasurement? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        var i = 1

        val bpm: Int
        if (flags and FLAG_UINT16 != 0) {
            if (data.size < i + 2) return null
            bpm = u16(data, i); i += 2
        } else {
            if (data.size < i + 1) return null
            bpm = data[i].toInt() and 0xFF; i += 1
        }

        val contact = if (flags and FLAG_CONTACT_SUPPORTED != 0) flags and FLAG_CONTACT_DETECTED != 0 else null

        var energy: Int? = null
        if (flags and FLAG_ENERGY != 0) {
            if (data.size < i + 2) return null
            energy = u16(data, i); i += 2
        }

        val rr = ArrayList<Int>()
        if (flags and FLAG_RR != 0) {
            while (i + 1 < data.size) {
                // RR передаётся в единицах 1/1024 с
                rr += (u16(data, i) * 1000 + 512) / 1024
                i += 2
            }
        }
        return HrMeasurement(bpm, contact, energy, rr)
    }

    private fun u16(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
}
