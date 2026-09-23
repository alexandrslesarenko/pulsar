package com.puls.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShakeLogicTest {
    private val g = 9.8f

    /** Прогоняет отсчёты 50 Гц; x(t) - ускорение по оси X поверх гравитации по Z. */
    private fun run(logic: ShakeLogic, fromMs: Long, durationMs: Long, x: (Long) -> Float): Int {
        var fired = 0
        var t = fromMs
        while (t < fromMs + durationMs) {
            if (logic.onSample(t, x(t), 0f, g)) fired++
            t += 20
        }
        return fired
    }

    private fun still(logic: ShakeLogic) = run(logic, 0, 2_000) { 0f }

    @Test
    fun alternatingJerksFire() {
        val logic = ShakeLogic()
        still(logic)
        // Рывки по 20 м/с2, направление меняется каждые 150 мс (около 3 Гц).
        val fired = run(logic, 2_000, 1_000) { t -> if (t % 150 < 20) (if ((t / 150) % 2 == 0L) 20f else -20f) else 0f }
        assertEquals(1, fired)
    }

    @Test
    fun oneDirectionImpactsDoNotFire() {
        val logic = ShakeLogic()
        still(logic)
        // Шаги бега: сильные удары вниз около 3 раз в секунду.
        val fired = run(logic, 2_000, 10_000) { t -> if (t % 340 < 20) -20f else 0f }
        assertEquals(0, fired)
    }

    @Test
    fun impactWithReboundDoesNotFire() {
        val logic = ShakeLogic()
        still(logic)
        // Бег с телефоном в кармане: удар вниз и отскок вверх через 100 мс, шаги 2.8 в секунду.
        val fired = run(logic, 2_000, 10_000) { t ->
            val phase = t % 360
            when {
                phase < 20 -> -20f
                phase in 100 until 120 -> 18f
                else -> 0f
            }
        }
        assertEquals(0, fired)
    }

    @Test
    fun walkingDoesNotFire() {
        val logic = ShakeLogic()
        still(logic)
        val fired = run(logic, 2_000, 10_000) { t -> 4f * kotlin.math.sin(t / 1000.0 * 2 * Math.PI * 2).toFloat() }
        assertEquals(0, fired)
    }

    @Test
    fun cooldownBlocksRepeat() {
        val logic = ShakeLogic()
        still(logic)
        val shake: (Long) -> Float = { t -> if (t % 150 < 20) (if ((t / 150) % 2 == 0L) 20f else -20f) else 0f }
        val fired = run(logic, 2_000, 3_000, shake)
        assertFalse(fired > 1)
    }
}
