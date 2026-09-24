package com.puls.app.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Журнал для разбора полевых испытаний (подбор порогов автовыбора и сигналов).
 * Файл на день в no_backup/telemetry (в облачную копию не попадает), хранится KEEP_DAYS
 * дней, старые удаляются. Снять: adb shell run-as com.puls.app cat no_backup/telemetry/<дата>.log
 *
 * Строка: время, тип, поля через запятую. Легенда - первой строкой каждого файла.
 */
object Telemetry {
    private const val TAG = "Telemetry"
    private const val KEEP_DAYS = 7L
    private const val LEGEND = "# s=sample(10s): mode,auto,bpm,med,spm,kmh,target,cand,cand_s,rest_high,zone,lo,hi" +
        " | sw=auto switch: from,to,med,spm | ev=alarm event: event,bpm,lo,hi | mode=manual/UI: mode,auto" +
        " | conn=state | contact=0/1 | bat=sensor battery %, on change | pbat=phone battery %,charging, on change | svc=start/stop | mute"

    private val io = Executors.newSingleThreadExecutor()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var dir: File? = null
    private var day: LocalDate? = null
    private var out: FileWriter? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.noBackupFilesDir, "telemetry")
    }

    fun log(kind: String, vararg fields: Any?) {
        val time = LocalTime.now()
        val date = LocalDate.now()
        val line = buildString {
            append(time.format(timeFmt)).append(',').append(kind)
            fields.forEach { append(',').append(fmt(it)) }
            append('\n')
        }
        io.execute { runCatching { write(date, line) }.onFailure { Log.w(TAG, "write failed", it) } }
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> ""
        is Double -> "%.1f".format(Locale.ROOT, v)
        is Boolean -> if (v) "1" else "0"
        is Profile -> v.name.take(1)
        is AlarmZone -> v.name.take(1)
        else -> v.toString()
    }

    private fun write(date: LocalDate, line: String) {
        val d = dir ?: return
        if (date != day) {
            out?.close()
            d.mkdirs()
            val f = File(d, "$date.log")
            val fresh = !f.exists()
            out = FileWriter(f, true).also { if (fresh) it.write(LEGEND + "\n") }
            day = date
            val oldest = date.minusDays(KEEP_DAYS - 1).toString()
            d.listFiles()?.filter { it.name.removeSuffix(".log") < oldest }?.forEach { it.delete() }
        }
        out?.apply {
            write(line)
            flush()
        }
    }
}
