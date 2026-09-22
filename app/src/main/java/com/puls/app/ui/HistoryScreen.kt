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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.puls.app.service.Prefs
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

    // Окно сдвигается раз в 30 с; новые записи БД Room присылает сам.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val to = now / period.bucketMs * period.bucketMs + period.bucketMs
    val from = to - period.spanMs
    val buckets by remember(period, to) { dao.buckets(from, to, period.bucketMs) }.collectAsState(emptyList())
    val stats by remember(period, to) { dao.stats(from, to) }.collectAsState(Stats(null, null, null, 0))

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.entries.forEach { p ->
                FilterChip(selected = p == period, onClick = { period = p }, label = { Text(stringResource(p.label)) })
            }
        }
        HrChart(
            points = buckets.map { ChartPoint(it.t, it.lo, it.avg, it.hi) },
            from = from, to = to, gapMs = maxOf(period.bucketMs * 3, 60_000L),
            height = 260,
            corridor = remember(period) { Prefs(ctx).let { if (it.alarmEnabled) it.alarmLow..it.alarmHigh else null } },
        )
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell(stringResource(R.string.stat_min), stats.lo?.toString())
                StatCell(stringResource(R.string.stat_avg), stats.avg?.roundToInt()?.toString())
                StatCell(stringResource(R.string.stat_max), stats.hi?.toString())
                // Датчик шлёт измерение раз в секунду, поэтому число строк примерно равно секундам записи.
                StatCell(stringResource(R.string.stat_recorded), if (stats.n > 0) "~" + formatDuration(ctx, stats.n.toLong()) else null)
            }
        }
        OutlinedButton(
            enabled = !exporting && stats.n > 0,
            onClick = {
                exporting = true
                scope.launch {
                    runCatching { exportCsv(ctx, from, to, period) }
                    exporting = false
                }
            },
        ) {
            Text(
                if (exporting) stringResource(R.string.export_preparing)
                else stringResource(R.string.export_csv, stringResource(period.label))
            )
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

private suspend fun exportCsv(ctx: Context, from: Long, to: Long, period: Period) {
    val dao = HrDb.get(ctx).dao()
    val fileFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    val rowFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "hr-${fileFmt.format(Date(from))}-${period.name}.csv")
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
