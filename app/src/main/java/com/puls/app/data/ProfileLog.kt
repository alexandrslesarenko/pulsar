package com.puls.app.data

import android.content.Context
import com.puls.app.service.Prefs

/**
 * Profile log for coloring the history: which profile and range were in effect at each moment.
 * The range slider changes values many times a second, so edits of the same profile
 * within COALESCE_MS of the last record overwrite it instead of piling up new ones.
 * A profile change is always written as a separate record.
 */
object ProfileLog {
    private const val COALESCE_MS = 60_000L

    suspend fun record(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = Prefs(context)
        val p = prefs.profile
        val r = prefs.range(p)
        val mark = ProfileMark(now, p.key, r.first, r.last, prefs.alarmEnabled)
        val dao = HrDb.get(context).dao()
        val last = dao.lastMark()
        if (last != null && last.copy(ts = now) == mark) return
        val coalesce = last != null && last.profile == mark.profile && now - last.ts < COALESCE_MS
        dao.putMark(if (coalesce) mark.copy(ts = last!!.ts) else mark)
    }
}
