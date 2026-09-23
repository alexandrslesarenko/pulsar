package com.puls.app.data

import android.content.Context
import com.puls.app.service.Prefs

/**
 * Журнал профилей для раскраски истории: какой профиль и коридор действовали в каждый момент.
 * Ползунок коридора меняет значения много раз в секунду, поэтому правки того же профиля
 * в пределах COALESCE_MS от последней записи переписывают её, а не плодят новые.
 * Смена профиля пишется всегда отдельной записью.
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
