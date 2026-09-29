package com.puls.app.service

import com.puls.app.data.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedTest {
    @Test
    fun walkingPace() {
        // 110 steps per minute, height 175: stride 0.726 m -> about 4.8 km/h.
        assertEquals(4.79, Speed.fromSteps(110, 60_000, 175), 0.05)
    }

    @Test
    fun runningStrideIsLonger() {
        // 170 steps per minute: stride 0.65 of height = 1.1375 m -> about 11.6 km/h.
        assertEquals(11.6, Speed.fromSteps(170, 60_000, 175), 0.05)
    }

    @Test
    fun fewStepsMeanStanding() {
        assertEquals(0.0, Speed.fromSteps(10, 60_000, 175), 0.0)
    }

    @Test
    fun gpsWinsOverSteps() {
        val rows = listOf(MotionSample(0, 60_000, 110, 5f), MotionSample(60_000, 60_000, 110, 3f))
        assertEquals(14.4, Speed.of(rows, 175)!!, 0.01)
    }

    @Test
    fun noDataIsNull() {
        assertNull(Speed.of(emptyList(), 175))
    }

    @Test
    fun noHeightMeansNoStepSpeed() {
        val rows = listOf(MotionSample(0, 60_000, 110, null))
        assertNull(Speed.of(rows, 0))
        // GPS does not depend on height.
        assertEquals(18.0, Speed.of(listOf(MotionSample(0, 60_000, 110, 5f)), 0)!!, 0.01)
    }
}
