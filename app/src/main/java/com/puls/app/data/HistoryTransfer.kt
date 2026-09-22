package com.puls.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.puls.app.service.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ImportResult(val added: Int, val existing: Int, val skipped: Int, val lastTs: Long)

/**
 * Перенос всей истории между телефонами: CSV "epoch_ms,bpm" в gzip.
 * Импорт принимает и gzip, и обычный CSV, в том числе экспорт за период
 * (там есть лишняя колонка time - её пропускаем по заголовку).
 */
object HistoryTransfer {
    private const val CHUNK = 5_000
    private const val MIN_TS = 946_684_800_000L // 2000-01-01
    private const val DAY_MS = 86_400_000L

    suspend fun export(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val dao = HrDb.get(context).dao()
        var total = 0
        val out = context.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
        GZIPOutputStream(out).bufferedWriter().use { w ->
            w.write("epoch_ms,bpm\n")
            var after = Long.MIN_VALUE
            while (true) {
                val rows = dao.range(after, Long.MAX_VALUE, CHUNK)
                if (rows.isEmpty()) break
                for (r in rows) w.write("${r.ts},${r.bpm}\n")
                total += rows.size
                after = rows.last().ts
            }
        }
        total
    }

    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val db = HrDb.get(context)
        val input = context.contentResolver.openInputStream(uri) ?: error("cannot open $uri")
        openText(input).use { reader ->
            var added = 0
            var existing = 0
            var skipped = 0
            var lastTs = 0L
            val batch = ArrayList<HrSample>(CHUNK)
            suspend fun flush() {
                if (batch.isEmpty()) return
                val ids = db.withTransaction { db.dao().insertNew(batch) }
                val new = ids.count { it != -1L }
                added += new
                existing += batch.size - new
                batch.clear()
            }
            parse(reader, System.currentTimeMillis() + DAY_MS) { sample ->
                if (sample == null) {
                    skipped++
                } else {
                    batch += sample
                    if (sample.ts > lastTs) lastTs = sample.ts
                }
                if (batch.size >= CHUNK) flush()
            }
            flush()
            // Импорт может быть с чужого телефона: в Health Connect его не отправляем.
            if (lastTs > 0) {
                val prefs = Prefs(context)
                prefs.hcSyncedUntil = maxOf(prefs.hcSyncedUntil, lastTs)
            }
            ImportResult(added, existing, skipped, lastTs)
        }
    }

    /** gzip определяем по сигнатуре 1f 8b, а не по имени файла: имя может потеряться при передаче. */
    private fun openText(input: InputStream): BufferedReader {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val gz = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gz) GZIPInputStream(buffered) else buffered
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
    }

    /**
     * Разбирает CSV с заголовком, где есть колонки epoch_ms и bpm (порядок любой).
     * Для каждой строки данных вызывает onRow: образец или null, если строка негодная.
     */
    suspend fun parse(reader: BufferedReader, maxTs: Long, onRow: suspend (HrSample?) -> Unit) {
        val header = reader.readLine()?.trim()?.removePrefix("\uFEFF")?.split(',')?.map { it.trim() }
            ?: return
        val tsCol = header.indexOf("epoch_ms")
        val bpmCol = header.indexOf("bpm")
        require(tsCol >= 0 && bpmCol >= 0) { "CSV header must contain epoch_ms and bpm" }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cols = line.split(',')
            val ts = cols.getOrNull(tsCol)?.trim()?.toLongOrNull()
            val bpm = cols.getOrNull(bpmCol)?.trim()?.toIntOrNull()
            val ok = ts != null && bpm != null && ts in MIN_TS..maxTs && bpm in 1..300
            onRow(if (ok) HrSample(ts!!, bpm!!) else null)
        }
    }
}
