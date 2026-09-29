package com.puls.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityShareTest {
    private fun mark(ts: Long, p: String) = ProfileMark(ts, p, 60, 100, true)

    @Test
    fun onlyWalkAndTrainingInsideWindow() {
        val marks = listOf(mark(0, "rest"), mark(100, "walk"), mark(200, "rest"), mark(300, "training"))
        assertEquals(
            listOf(ActivityShare.Interval(100, 200, "walk"), ActivityShare.Interval(300, 350, "training")),
            ActivityShare.intervals(marks, 50, 350),
        )
    }

    @Test
    fun markBeforeWindowIsClipped() {
        val marks = listOf(mark(0, "walk"), mark(500, "rest"))
        assertEquals(listOf(ActivityShare.Interval(100, 500, "walk")), ActivityShare.intervals(marks, 100, 1000))
    }

    @Test
    fun sameModeMarksAreMerged() {
        // Editing the walk range in the middle of a walk - a new record of the same mode.
        val marks = listOf(mark(100, "walk"), mark(150, "walk"), mark(300, "rest"))
        assertEquals(listOf(ActivityShare.Interval(100, 300, "walk")), ActivityShare.intervals(marks, 0, 1000))
    }

    @Test
    fun emptyJournal() {
        assertEquals(emptyList<ActivityShare.Interval>(), ActivityShare.intervals(emptyList(), 0, 1000))
    }
}
