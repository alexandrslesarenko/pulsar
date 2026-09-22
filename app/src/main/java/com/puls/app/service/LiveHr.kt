package com.puls.app.service

import com.puls.app.ble.ConnState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LiveState(
    val conn: ConnState = ConnState.IDLE,
    val deviceName: String? = null,
    val bpm: Int? = null,
    val skinContact: Boolean? = null,
    val battery: Int? = null,
    /** System.currentTimeMillis() последнего измерения. */
    val updatedAt: Long = 0,
    val alarm: AlarmZone = AlarmZone.NORMAL,
    val alarmMuted: Boolean = false,
)

/** Текущее состояние датчика в пределах процесса. Источник - HrService. */
object LiveHr {
    internal val mutable = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutable

    /** Последние измерения (ts, bpm) для живого графика; в БД они попадают пачками. */
    internal val recentMutable = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())
    val recent: StateFlow<List<Pair<Long, Int>>> = recentMutable

    const val RECENT_WINDOW_MS = 5 * 60_000L
}
