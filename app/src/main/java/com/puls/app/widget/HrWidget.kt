package com.puls.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.ColorFilter
import com.puls.app.R
import com.puls.app.ble.ConnState
import com.puls.app.service.HrService
import com.puls.app.service.LiveState
import com.puls.app.service.Prefs
import com.puls.app.ui.ZoneColors
import androidx.glance.unit.ColorProvider
import com.puls.app.ui.MainActivity
import com.puls.app.ui.statusText

class HrWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { Content(context) } }
    }

    @Composable
    private fun Content(context: Context) {
        val p = currentState<Preferences>()
        val bpm = p[BPM] ?: -1
        val running = p[RUNNING] ?: false
        val status = p[STATUS] ?: context.getString(R.string.status_idle)
        val zone = p[ZONE] ?: ZONE_NONE

        val size = LocalSize.current
        if (size.width < 100.dp) {
            Tiny(bpm, zone)
            return
        }
        if (size.height < 100.dp) {
            Compact(bpm, status, zone)
            return
        }
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_heart), null,
                    modifier = GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(heartColor(zone)),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    if (bpm > 0) bpm.toString() else "--",
                    style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                )
            }
            Text(LocalContext.current.getString(R.string.bpm_unit), style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
            Text(status, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            Spacer(GlanceModifier.size(6.dp))
            Text(
                context.getString(if (running) R.string.btn_stop else R.string.btn_start),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                modifier = GlanceModifier.padding(horizontal = 12.dp, vertical = 4.dp).clickable(
                    if (running) actionStartService(HrService.stopIntent(context))
                    else actionStartService(HrService.startIntent(context), isForegroundService = true)
                ),
            )
        }
    }

    /** 1x1 variant: a heart above the number; without a link "--" instead of the number. */
    @Composable
    private fun Tiny(bpm: Int, zone: Int) {
        Column(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                ImageProvider(R.drawable.ic_heart), null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(heartColor(zone)),
            )
            Text(
                if (bpm > 0) bpm.toString() else "--",
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                maxLines = 1,
            )
        }
    }

    /** 2x1 variant: heart, number and status in one row; a tap opens the app. */
    @Composable
    private fun Compact(bpm: Int, status: String, zone: Int) {
        Row(
            GlanceModifier.fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(horizontal = 12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                ImageProvider(R.drawable.ic_heart), null,
                modifier = GlanceModifier.size(18.dp),
                colorFilter = ColorFilter.tint(heartColor(zone)),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                if (bpm > 0) bpm.toString() else "--",
                style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
            )
            Spacer(GlanceModifier.width(6.dp))
            Column {
                Text(LocalContext.current.getString(R.string.bpm_unit), style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
                if (bpm <= 0) {
                    Text(status, style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
                }
            }
        }
    }

    @Composable
    private fun heartColor(zone: Int): ColorProvider = when (zone) {
        ZONE_HIGH -> ColorProvider(ZoneColors.High)
        ZONE_LOW -> ColorProvider(ZoneColors.Low)
        ZONE_IN -> ColorProvider(ZoneColors.In)
        else -> GlanceTheme.colors.primary
    }

    companion object {
        private val ZONE = intPreferencesKey("zone")
        private const val ZONE_NONE = 0
        private const val ZONE_IN = 1
        private const val ZONE_HIGH = 2
        private const val ZONE_LOW = 3
        private val BPM = intPreferencesKey("bpm")
        private val RUNNING = booleanPreferencesKey("running")
        private val STATUS = stringPreferencesKey("status")

        suspend fun push(context: Context, s: LiveState) {
            val prefs = Prefs(context)
            val bpm = s.bpm?.takeIf { s.conn == ConnState.CONNECTED }
            val zone = when {
                bpm == null || !prefs.alarmEnabled -> ZONE_NONE
                bpm > prefs.alarmHigh -> ZONE_HIGH
                bpm < prefs.alarmLow -> ZONE_LOW
                else -> ZONE_IN
            }
            val ids = GlanceAppWidgetManager(context).getGlanceIds(HrWidget::class.java)
            for (id in ids) {
                updateAppWidgetState(context, id) { p ->
                    p[BPM] = if (s.conn == ConnState.CONNECTED) s.bpm ?: -1 else -1
                    p[RUNNING] = s.conn != ConnState.IDLE
                    p[STATUS] = statusText(context, s)
                    p[ZONE] = zone
                }
                HrWidget().update(context, id)
            }
        }
    }
}

class HrWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HrWidget()
}
