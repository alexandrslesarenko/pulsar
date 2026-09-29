package com.puls.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import com.puls.app.data.Bucket
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.puls.app.R
import androidx.core.content.FileProvider
import com.puls.app.data.HrDb
import com.puls.app.data.Stats
import com.puls.app.data.ProfileMark
import com.puls.app.data.MotionSample
import com.puls.app.service.Prefs
import com.puls.app.service.Speed
import com.puls.app.service.Profile
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Period(@StringRes val label: Int, val spanMs: Long, val bucketMs: Long) {
    H1(R.string.period_1h, 3_600_000L, 10_000L),
    H6(R.string.period_6h, 6 * 3_600_000L, 60_000L),
    D1(R.string.period_24h, 24 * 3_600_000L, 5 * 60_000L),
    D7(R.string.period_7d, 7 * 24 * 3_600_000L, 30 * 60_000L),
}

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val dao = remember { HrDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var period by rememberSaveable { mutableStateOf(Period.H1) }
    var exporting by remember { mutableStateOf(false) }

    // The window moves every 30 s; Room delivers new DB rows by itself.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    // Custom window after pinch zoom; null - the last period, the window moves with time.
    var zoomed by remember { mutableStateOf<LongRange?>(null) }
    val base = (now / period.bucketMs * period.bucketMs + period.bucketMs).let { (it - period.spanMs)..it }
    val view = zoomed ?: base
    val from = view.first
    val to = view.last
    val bucketMs = if (zoomed == null) period.bucketMs else bucketFor(to - from)
    // The query has one interval of margin on each side so the line reaches the chart edges;
    // bounds are aligned to the interval so the query does not change on every frame during a gesture.
    val qFrom = Math.floorDiv(from, bucketMs) * bucketMs - bucketMs
    val qTo = Math.floorDiv(to, bucketMs) * bucketMs + 2 * bucketMs
    var buckets by remember { mutableStateOf(emptyList<Bucket>()) }
    var stats by remember { mutableStateOf(Stats(null, null, null, 0)) }
    var marks by remember { mutableStateOf(emptyList<ProfileMark>()) }
    var motion by remember { mutableStateOf(emptyList<MotionSample>()) }
    LaunchedEffect(qFrom, qTo, bucketMs) {
        if (zoomed != null) delay(GESTURE_DEBOUNCE_MS)
        dao.buckets(qFrom, qTo, bucketMs).collect { buckets = it }
    }
    LaunchedEffect(qFrom, qTo) {
        if (zoomed != null) delay(GESTURE_DEBOUNCE_MS)
        launch { dao.marks(qFrom, qTo).collect { marks = it } }
        dao.motion(qFrom, qTo).collect { motion = it }
    }
    // Totals - exactly for the visible part. The keys are the bounds themselves, not aligned to the interval:
    // with a large interval a shift within it does not change the chart query, but does change the totals.
    LaunchedEffect(from, to) {
        if (zoomed != null) delay(GESTURE_DEBOUNCE_MS)
        dao.stats(from, to).collect { stats = it }
    }
    val spans = remember(marks, qTo) { spansOf(marks, qTo) }
    // Motion is recorded per minute, so the speed chart interval is at least a minute.
    val speedBucket = maxOf(bucketMs, 60_000L)
    val height = remember { Prefs(ctx).heightCm }
    val speed = remember(motion, speedBucket) {
        motion.groupBy { it.ts / speedBucket * speedBucket }
            .mapNotNull { (t, rows) -> Speed.of(rows, height)?.let { t to it } }
    }
    val transform: (Float, Float, Float) -> Unit = { zoom, pan, focus ->
        // Several events come per frame: each is applied to the result of the previous one,
        // not to the bounds the frame was drawn with.
        val cur = zoomed ?: base
        val span = (cur.last - cur.first).toDouble()
        val newSpan = (span / zoom).coerceIn(MIN_SPAN_MS.toDouble(), MAX_SPAN_MS.toDouble())
        val focusT = cur.first + span * focus
        // Moving the fingers right goes to earlier time; we do not go past "now".
        val end = (focusT + newSpan * (1 - focus) - pan * newSpan)
            .coerceAtMost((System.currentTimeMillis() + newSpan * 0.05))
        zoomed = (end - newSpan).toLong()..end.toLong()
    }

    var showHint by rememberSaveable { mutableStateOf(false) }
    // Charts take all free height: with any screen and font size the screen is filled
    // instead of ending halfway.
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Aligned to the top: if the period buttons wrap with a very large font, the icons
        // stay in the first line instead of drifting between lines.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            // Icons on the right always stay in place; the period buttons adapt to the remaining width.
            Box(Modifier.weight(1f)) {
                FitRow(spacing = 4.dp) { style ->
                    Period.entries.forEach { p ->
                        FilterChip(
                            selected = p == period && zoomed == null,
                            onClick = {
                                period = p
                                zoomed = null
                            },
                            label = { Text(stringResource(p.label), maxLines = 1, style = style) },
                        )
                    }
                }
            }
            IconButton(onClick = { showHint = !showHint }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (showHint) Icons.Filled.Info else Icons.Outlined.Info,
                    stringResource(R.string.help_toggle),
                    tint = if (showHint) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Export of what is on the chart now (the period or the zoomed part),
            // via the system "Share" menu - hence the "Share" icon next to the period choice.
            val exportLabel = when {
                exporting -> stringResource(R.string.export_preparing)
                zoomed != null -> stringResource(R.string.export_csv_view)
                else -> stringResource(R.string.export_csv, stringResource(period.label))
            }
            IconButton(
                modifier = Modifier.size(40.dp),
                enabled = !exporting && stats.n > 0,
                onClick = {
                    exporting = true
                    scope.launch {
                        runCatching { exportCsv(ctx, from, to, if (zoomed == null) period.name else "view") }
                        exporting = false
                    }
                },
            ) {
                if (exporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Share, exportLabel)
            }
        }
        if (showHint) {
            Text(
                stringResource(R.string.zoom_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val hasSpeed = speed.any { it.second > 0 }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // With speed the height is split 2:1 - heart rate comes first.
            HrChart(
                points = buckets.map { ChartPoint(it.t, it.lo, it.avg, it.hi) },
                from = from, to = to, gapMs = maxOf(bucketMs * 3, 60_000L),
                height = null,
                modifier = Modifier.weight(if (hasSpeed) 2f else 1f),
                spans = spans,
                onTransform = transform,
            )
            if (hasSpeed) {
                Text(
                    stringResource(R.string.speed_chart),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SpeedChart(
                    speed, from, to, gapMs = maxOf(speedBucket * 3, 3 * 60_000L),
                    height = null, modifier = Modifier.weight(1f), onTransform = transform,
                )
            }
        }
        if (zoomed != null) {
            TextButton(onClick = { zoomed = null }) { Text(stringResource(R.string.zoom_reset)) }
        }
        // The order is the same as everywhere in the app (Rest, Walk, Training), not by time of appearance.
        ProfileLegend(marks.mapNotNull { Profile.byKey(it.profile) }.distinct().sorted())
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell(stringResource(R.string.stat_min), stats.lo?.toString())
                StatCell(stringResource(R.string.stat_avg), stats.avg?.roundToInt()?.toString())
                StatCell(stringResource(R.string.stat_max), stats.hi?.toString())
                // The sensor sends a measurement once a second, so the row count roughly equals the seconds recorded.
                StatCell(stringResource(R.string.stat_recorded), if (stats.n > 0) "~" + formatDuration(ctx, stats.n.toLong()) else null)
            }
        }
    }
}

private const val GESTURE_DEBOUNCE_MS = 80L
private const val MIN_SPAN_MS = 5 * 60_000L
private const val MAX_SPAN_MS = 31 * 24 * 3_600_000L
private val BUCKET_STEPS_MS = longArrayOf(
    5_000, 10_000, 30_000, 60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
    3_600_000, 2 * 3_600_000,
)

/** Averaging interval for the visible part: about 400 points per chart. */
internal fun bucketFor(spanMs: Long): Long = BUCKET_STEPS_MS.firstOrNull { it >= spanMs / 400 } ?: BUCKET_STEPS_MS.last()

/**
 * Chart segments from the profile log: each record is in effect until the next one.
 * Leaving the profile range is colored even with the alarm off: the range is the norm
 * for the profile, and the alarm is only a way to remind of it.
 */
private fun spansOf(marks: List<ProfileMark>, to: Long): List<ChartSpan> = marks.mapIndexedNotNull { i, m ->
    val p = Profile.byKey(m.profile) ?: return@mapIndexedNotNull null
    val end = marks.getOrNull(i + 1)?.ts ?: to
    ChartSpan(m.ts, end, m.lo..m.hi, profileColor(p))
}

@Composable
private fun ProfileLegend(profiles: List<Profile>) {
    if (profiles.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        profiles.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(profileColor(p).copy(alpha = 0.5f), RoundedCornerShape(3.dp)))
                Text(stringResource(p.label), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String?) {
    Column {
        Text(value ?: "--", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private suspend fun exportCsv(ctx: Context, from: Long, to: Long, tag: String) {
    val dao = HrDb.get(ctx).dao()
    val fileFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    val rowFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "hr-${fileFmt.format(Date(from))}-$tag.csv")
        f.bufferedWriter().use { w ->
            w.write("time,epoch_ms,bpm\n")
            var after = from - 1
            while (true) {
                val rows = dao.range(after, to, 5_000)
                if (rows.isEmpty()) break
                for (r in rows) w.write("${rowFmt.format(Date(r.ts))},${r.ts},${r.bpm}\n")
                after = rows.last().ts
            }
        }
        f
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/csv")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_chooser)))
}
