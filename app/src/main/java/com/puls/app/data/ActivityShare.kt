package com.puls.app.data

import com.puls.app.service.Profile

/**
 * Contract with the Calorie app: which minutes of walks and workouts, and with what heart rate,
 * Pulsar serves through ActivityProvider. Everything here is visible to another app -
 * do not rename the authority, path, columns or mode values.
 */
object ActivityShare {
    const val AUTHORITY = "com.puls.app.activity"
    const val PATH_MINUTES = "minutes"
    const val PARAM_FROM = "from"
    const val PARAM_TO = "to"

    /** Columns: minute start (ms), mode ("walk" / "training"), average heart rate, sample count. */
    const val COL_MINUTE = "minute"
    const val COL_MODE = "mode"
    const val COL_BPM = "bpm"
    const val COL_SAMPLES = "samples"
    val COLUMNS = arrayOf(COL_MINUTE, COL_MODE, COL_BPM, COL_SAMPLES)

    /** Modes with exertion; rest is not served - its expenditure is already in the basal metabolism. */
    val ACTIVE = setOf(Profile.WALK.key, Profile.TRAINING.key)

    data class Interval(val from: Long, val to: Long, val mode: String)

    /**
     * Walk and workout intervals inside [from, to) from the mode log. marks - records in effect
     * in the window (the last one before from and all inside, ascending by ts), as returned by
     * HrDao.marks. Adjacent intervals of the same mode are merged: editing the range writes
     * a new record of the same mode.
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
