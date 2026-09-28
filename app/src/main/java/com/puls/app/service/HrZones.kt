package com.puls.app.service

import java.time.LocalDate
import java.time.Period
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * Возраст и зоны пульса по дате рождения.
 * Макс. пульс - 220 минус возраст; прогулка (зона 2) - 60-70% от него.
 */
object HrZones {
    /**
     * Возраст в полных годах. month и day можно не знать (0): тогда берём середину
     * месяца, а без месяца - 1 июля; ошибка не больше полугода.
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

    /** Границы зон 1-5 в процентах от максимального пульса. */
    val ZONE_PCT = listOf(50 to 60, 60 to 70, 70 to 80, 80 to 90, 90 to 100)

    /** Пульс для доли [loPct, hiPct] процентов от максимума. */
    fun pctRange(age: Int, loPct: Int, hiPct: Int): IntRange {
        val max = maxHr(age)
        return (max * loPct / 100.0).roundToInt()..(max * hiPct / 100.0).roundToInt()
    }

    /** Тренировка (зоны 3-4) - 70-85% от максимального пульса. */
    fun trainingZone(age: Int): IntRange {
        val max = maxHr(age)
        return (max * 0.7).roundToInt()..(max * 0.85).roundToInt()
    }

    /**
     * Попадает ли время (минуты от полуночи) в ночь [from, to). Ночь может переходить
     * через полночь (23:00-07:00); from == to - ночи нет.
     */
    fun isNight(minuteOfDay: Int, from: Int, to: Int): Boolean = when {
        from == to -> false
        from < to -> minuteOfDay in from until to
        else -> minuteOfDay >= from || minuteOfDay < to
    }

    /** Сколько минут после конца ночи ещё считаем сном, пока не было шагов. */
    const val MORNING_SLEEP_MIN = 180

    /**
     * Спит ли человек: всю ночь [from, to) и утром после неё, пока нет шагов (просыпаются
     * позже будильника ночи - по испытаниям пульс сна держался до 9-10 ч). Утро - не дольше
     * MORNING_SLEEP_MIN: телефон, забытый на тумбочке, не должен продлить сон на весь день.
     * minutesSinceSteps - сколько минут назад были шаги (null - не было); без шагомера
     * (pedometer = false) сон - только ночь.
     */
    fun isSleep(minuteOfDay: Int, from: Int, to: Int, minutesSinceSteps: Int?, pedometer: Boolean): Boolean {
        if (isNight(minuteOfDay, from, to)) return true
        if (!pedometer || from == to) return false
        val sinceNightEnd = Math.floorMod(minuteOfDay - to, 24 * 60)
        if (sinceNightEnd >= MORNING_SLEEP_MIN) return false
        return minutesSinceSteps == null || minutesSinceSteps > sinceNightEnd
    }

    /** Проверка ввода: год обязателен, месяц и день - по желанию (0). */
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
