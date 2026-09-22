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
