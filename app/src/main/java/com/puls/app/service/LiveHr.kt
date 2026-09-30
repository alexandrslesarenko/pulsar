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
    /** System.currentTimeMillis() of the last measurement. */
    val updatedAt: Long = 0,
    val alarm: AlarmZone = AlarmZone.NORMAL,
    val alarmMuted: Boolean = false,
    /** The alarm is vibrating now, and it makes sense to mute it. */
    val alarmVibrates: Boolean = false,
    /** Approximate speed, km/h; null - not measured. */
    val speedKmh: Double? = null,
    /** Active profile; null - the service is not running. Auto selection changes it too. */
    val profile: Profile? = null,
    /** Auto selection is on in prefs; it can be turned on from the notification too. */
    val autoProfile: Boolean = false,
    /** Bounds the alarm works by now: in auto they are wider than the profile range. */
    val bounds: IntRange? = null,
)

/** Current sensor state within the process. The source is HrService. */
object LiveHr {
    internal val mutable = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = mutable

    /** Latest measurements (ts, bpm) for the live chart; they reach the DB in batches. */
    internal val recentMutable = MutableStateFlow<List<Pair<Long, Int>>>(emptyList())
    val recent: StateFlow<List<Pair<Long, Int>>> = recentMutable

    const val RECENT_WINDOW_MS = 5 * 60_000L
}
