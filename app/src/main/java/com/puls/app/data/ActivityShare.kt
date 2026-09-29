package com.puls.app.data

import com.puls.app.service.Profile

/**
 * Контракт с приложением Calorie: какие минуты прогулок и тренировок и с каким пульсом
 * Pulsar отдаёт через ActivityProvider. Всё, что здесь, видит другое приложение -
 * authority, путь, колонки и значения режима не переименовывать.
 */
object ActivityShare {
    const val AUTHORITY = "com.puls.app.activity"
    const val PATH_MINUTES = "minutes"
    const val PARAM_FROM = "from"
    const val PARAM_TO = "to"

    /** Колонки: начало минуты (мс), режим ("walk" / "training"), средний пульс, число замеров. */
    const val COL_MINUTE = "minute"
    const val COL_MODE = "mode"
    const val COL_BPM = "bpm"
    const val COL_SAMPLES = "samples"
    val COLUMNS = arrayOf(COL_MINUTE, COL_MODE, COL_BPM, COL_SAMPLES)

    /** Режимы с нагрузкой; покой не отдаём - его расход уже в основном обмене. */
    val ACTIVE = setOf(Profile.WALK.key, Profile.TRAINING.key)

    data class Interval(val from: Long, val to: Long, val mode: String)

    /**
     * Интервалы прогулок и тренировок внутри [from, to) по журналу режимов. marks - записи,
     * действовавшие в окне (последняя до from и все внутри, по возрастанию ts), как отдаёт
     * HrDao.marks. Соседние интервалы одного режима склеиваются: правка коридора пишет
     * новую запись того же режима.
     */
    fun intervals(marks: List<ProfileMark>, from: Long, to: Long): List<Interval> {
        val out = mutableListOf<Interval>()
        marks.forEachIndexed { i, m ->
            val start = maxOf(m.ts, from)
            val end = minOf(marks.getOrNull(i + 1)?.ts ?: to, to)
            if (m.profile !in ACTIVE || start >= end) return@forEachIndexed
            val last = out.lastOrNull()
            if (last != null && last.mode == m.profile && last.to == start) out[out.size - 1] = last.copy(to = end)
            else out += Interval(start, end, m.profile)
        }
        return out
    }
}
