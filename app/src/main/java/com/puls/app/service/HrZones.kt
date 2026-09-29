package com.puls.app.service

import java.time.LocalDate
import java.time.Period
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * Age and heart rate zones by date of birth.
 * Max heart rate - 220 minus age; walk (zone 2) - 60-70% of it.
 */
object HrZones {
    /**
     * Age in full years. month and day may be unknown (0): then the middle of the month
     * is used, and without a month - July 1; the error is at most half a year.
     */
    fun age(year: Int, month: Int, day: Int, today: LocalDate = LocalDate.now()): Int {
        val m = if (month in 1..12) month else 7
        val len = YearMonth.of(year, m).lengthOfMonth()
        val d = when {
            month !in 1..12 -> 1
            day in 1..len -> day
            else -> 15
        }
        return Period.between(LocalDate.of(year, m, d), today).years
    }

    fun maxHr(age: Int) = 220 - age

    fun walkZone(age: Int): IntRange {
        val max = maxHr(age)
        return (max * 0.6).roundToInt()..(max * 0.7).roundToInt()
    }

    /** Bounds of zones 1-5 in percent of max heart rate. */
    val ZONE_PCT = listOf(50 to 60, 60 to 70, 70 to 80, 80 to 90, 90 to 100)

    /** Heart rate for the share [loPct, hiPct] percent of max. */
    fun pctRange(age: Int, loPct: Int, hiPct: Int): IntRange {
        val max = maxHr(age)
        return (max * loPct / 100.0).roundToInt()..(max * hiPct / 100.0).roundToInt()
    }

    /** Training (zones 3-4) - 70-85% of max heart rate. */
    fun trainingZone(age: Int): IntRange {
        val max = maxHr(age)
        return (max * 0.7).roundToInt()..(max * 0.85).roundToInt()
    }

    /**
     * Whether the time (minutes since midnight) falls into the night [from, to). The night may cross
     * midnight (23:00-07:00); from == to - no night.
     */
    fun isNight(minuteOfDay: Int, from: Int, to: Int): Boolean = when {
        from == to -> false
        from < to -> minuteOfDay in from until to
        else -> minuteOfDay >= from || minuteOfDay < to
    }

    /** How many minutes after the end of the night still count as sleep while there were no steps. */
    const val MORNING_SLEEP_MIN = 180

    /**
     * Whether the person is asleep: the whole night [from, to) and in the morning after it until steps appear (people wake
     * later than the end of the night - in field tests sleep heart rate lasted until 9-10 am). The morning lasts no longer than
     * MORNING_SLEEP_MIN: a phone forgotten on the nightstand must not extend sleep for the whole day.
     * minutesSinceSteps - how many minutes ago there were steps (null - none); without a pedometer
     * (pedometer = false) sleep is the night only.
     */
    fun isSleep(minuteOfDay: Int, from: Int, to: Int, minutesSinceSteps: Int?, pedometer: Boolean): Boolean {
        if (isNight(minuteOfDay, from, to)) return true
        if (!pedometer || from == to) return false
        val sinceNightEnd = Math.floorMod(minuteOfDay - to, 24 * 60)
        if (sinceNightEnd >= MORNING_SLEEP_MIN) return false
        return minutesSinceSteps == null || minutesSinceSteps > sinceNightEnd
    }

    /** Input check: year is required, month and day are optional (0). */
    fun isValid(year: Int, month: Int, day: Int, today: LocalDate = LocalDate.now()): Boolean {
        if (year !in 1900..today.year - 5) return false
        if (month != 0 && month !in 1..12) return false
        if (day != 0) {
            if (month == 0) return false
            if (day !in 1..YearMonth.of(year, month).lengthOfMonth()) return false
        }
        return true
    }
}
