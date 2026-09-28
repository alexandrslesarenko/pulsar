package com.puls.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoProfileTest {
    private val ranges = mapOf(
        Profile.REST to 60..100,
        Profile.WALK to 102..119,
        Profile.TRAINING to 119..145,
    )

    /** Сценарий посекундных замеров; profile - активный профиль после каждой смены. */
    private inner class Run(var profile: Profile) {
        val auto = AutoProfile()
        var t = 0L
        val switches = mutableListOf<Pair<Long, Profile>>()

        fun go(seconds: Int, bpm: Int, spm: Double) = repeat(seconds) {
            t += 1_000
            auto.onSample(t, bpm, spm, profile) { ranges.getValue(it) }?.let {
                profile = it
                switches += t to it
            }
        }
    }

    @Test
    fun restToWalkByStepsWithinAMinute() {
        val r = Run(Profile.REST)
        r.go(60, 80, 0.0)
        r.go(90, 90, 100.0)
        assertEquals(Profile.WALK, r.profile)
        // Прогулка подтверждается через минуту после первых шагов.
        assertTrue(r.switches.single().first in 115_000L..125_000L)
    }

    @Test
    fun trafficLightKeepsWalk() {
        val r = Run(Profile.WALK)
        r.go(60, 110, 100.0)
        r.go(150, 95, 0.0)
        r.go(60, 110, 100.0)
        assertEquals(Profile.WALK, r.profile)
        assertTrue(r.switches.isEmpty())
    }

    @Test
    fun longStopWithCalmPulseGoesToRest() {
        val r = Run(Profile.WALK)
        r.go(60, 110, 100.0)
        r.go(240, 90, 0.0)
        assertEquals(Profile.REST, r.profile)
    }

    @Test
    fun recoveryAfterWalkHoldsProfile() {
        val r = Run(Profile.WALK)
        r.go(60, 115, 100.0)
        // Стоим 10 минут, пульс между покоем и тренировкой.
        r.go(600, 108, 0.0)
        assertEquals(Profile.WALK, r.profile)
    }

    @Test
    fun shortSpikeDoesNotSwitchToTraining() {
        val r = Run(Profile.WALK)
        r.go(60, 110, 100.0)
        r.go(60, 130, 100.0)
        r.go(120, 112, 100.0)
        assertTrue(r.switches.isEmpty())
    }

    @Test
    fun sustainedHighPulseSwitchesToTraining() {
        val r = Run(Profile.WALK)
        r.go(60, 110, 100.0)
        r.go(180, 130, 100.0)
        assertEquals(Profile.TRAINING, r.profile)
    }

    @Test
    fun runningCadenceIsTrainingEvenWithLowPulse() {
        val r = Run(Profile.REST)
        r.go(200, 100, 150.0)
        assertEquals(Profile.TRAINING, r.profile)
    }

    @Test
    fun highPulseWithoutStepsIsNotTraining() {
        // Дела по дому с телефоном на столе или игра: пульс тренировки, шагов нет.
        val r = Run(Profile.REST)
        r.go(600, 135, 0.0)
        assertEquals(Profile.REST, r.profile)
        assertTrue(r.auto.restHigh)
    }

    @Test
    fun trainingHoldsWhileStandingBetweenSets() {
        val r = Run(Profile.TRAINING)
        r.go(600, 130, 0.0)
        assertEquals(Profile.TRAINING, r.profile)
    }

    @Test
    fun hysteresisKeepsTrainingJustBelowItsRange() {
        val r = Run(Profile.TRAINING)
        // 116 ниже коридора тренировки (119), но выше порога выхода 119 - 5.
        r.go(600, 116, 100.0)
        assertEquals(Profile.TRAINING, r.profile)
        r.go(600, 110, 100.0)
        assertEquals(Profile.WALK, r.profile)
    }

    @Test
    fun medianIgnoresSingleOutliers() {
        val r = Run(Profile.WALK)
        repeat(300) { i -> r.go(1, if (i % 5 == 0) 180 else 110, 100.0) }
        assertTrue(r.switches.isEmpty())
    }

    @Test
    fun noSwitchWithinDwellAfterSwitch() {
        val r = Run(Profile.REST)
        r.go(15, 80, 0.0)
        r.go(70, 90, 100.0)
        assertEquals(Profile.WALK, r.profile)
        val walkAt = r.switches.single().first
        // Сразу бег: подтверждение 2 мин, но и после него ждём конца MIN_DWELL_MS.
        r.go(400, 150, 160.0)
        val trainAt = r.switches.last().first
        assertEquals(Profile.TRAINING, r.profile)
        assertTrue(trainAt - walkAt >= AutoProfile.MIN_DWELL_MS)
    }

    @Test
    fun gapResetsConfirmation() {
        val r = Run(Profile.REST)
        r.go(60, 80, 100.0)
        r.auto.onGap()
        r.go(30, 80, 100.0)
        assertTrue(r.switches.isEmpty())
    }

    @Test
    fun highPulseAtRestRaisesRestHigh() {
        val r = Run(Profile.REST)
        r.go(60, 80, 0.0)
        r.go(100, 108, 0.0)
        assertFalse(r.auto.restHigh)
        r.go(60, 108, 0.0)
        assertTrue(r.auto.restHigh)
        assertEquals(Profile.REST, r.profile)
        // Пошли - сигнал держится до смены профиля.
        r.go(20, 108, 100.0)
        assertTrue(r.auto.restHigh)
        r.go(60, 108, 100.0)
        assertEquals(Profile.WALK, r.profile)
        assertFalse(r.auto.restHigh)
    }

    @Test
    fun restHighClearsWhenPulseCalms() {
        val r = Run(Profile.REST)
        r.go(200, 108, 0.0)
        assertTrue(r.auto.restHigh)
        r.go(30, 90, 0.0)
        assertFalse(r.auto.restHigh)
    }

    @Test
    fun noDecisionUntilEnoughSamples() {
        val a = AutoProfile()
        assertNull(a.onSample(1_000, 150, 160.0, Profile.REST) { ranges.getValue(it) })
        assertNull(a.median)
    }
}

class CadenceTest {
    @Test
    fun stepsOverWindow() {
        val c = Cadence(60_000)
        c.add(0, 1000)
        c.add(30_000, 1050)
        c.add(60_000, 1100)
        assertEquals(100.0, c.spm(60_000), 0.01)
    }

    @Test
    fun noEventsMeansStanding() {
        val c = Cadence(60_000)
        c.add(0, 1000)
        c.add(10_000, 1100)
        assertEquals(0.0, c.spm(80_000), 0.01)
    }

    @Test
    fun counterResetAfterReboot() {
        val c = Cadence(60_000)
        c.add(0, 5000)
        c.add(10_000, 10)
        c.add(20_000, 60)
        assertEquals(50.0, c.spm(20_000), 0.01)
    }

    @Test
    fun emptyIsZero() {
        assertEquals(0.0, Cadence().spm(1_000), 0.0)
    }
}
