package com.puls.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.puls.app.R
import com.puls.app.ble.ConnState
import com.puls.app.service.LiveState

fun statusText(ctx: Context, s: LiveState): String = ctx.getString(
    when (s.conn) {
        ConnState.IDLE -> R.string.status_idle
        ConnState.CONNECTING -> R.string.status_connecting
        ConnState.RECONNECTING -> R.string.status_reconnecting
        ConnState.NO_SIGNAL -> R.string.status_no_signal
        ConnState.CONNECTED -> when {
            s.skinContact == false -> R.string.status_no_contact
            s.bpm == null -> R.string.status_waiting
            else -> R.string.status_connected
        }
    }
)

/** "имя датчика - батарея N%" для подписей. */
fun deviceLine(ctx: Context, name: String?, battery: Int?): String =
    listOfNotNull(name, battery?.let { ctx.getString(R.string.battery, it) }).joinToString(" - ")

fun formatDuration(ctx: Context, seconds: Long): String {
    val h = (seconds / 3600).toInt()
    val m = (seconds % 3600 / 60).toInt()
    return if (h > 0) ctx.getString(R.string.duration_hm, h, m) else ctx.getString(R.string.duration_m, m)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}
