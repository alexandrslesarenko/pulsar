package com.puls.app.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import com.puls.app.service.Prefs
import java.time.Instant
import java.time.ZoneId

/**
 * Переносит измерения из локальной БД в Health Connect.
 * Одна запись HeartRateRecord на минуту; clientRecordId = начало минуты, поэтому
 * повторная отправка той же минуты не создаёт дубль, а перезаписывает её.
 */
object HealthSync {
    private const val TAG = "HealthSync"
    private const val MINUTE = 60_000L
    private const val CHUNK = 3_600

    val PERMISSION = HealthPermission.getWritePermission(HeartRateRecord::class)

    fun isAvailable(context: Context) =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(context: Context): Boolean = isAvailable(context) &&
        PERMISSION in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    /** Отправляет все полные минуты после отметки prefs.hcSyncedUntil; возвращает, сколько минут ушло. */
    suspend fun sync(context: Context): Int {
        val prefs = Prefs(context)
        if (!prefs.hcEnabled || !hasPermission(context)) return 0
        var sent = 0
        val client = HealthConnectClient.getOrCreate(context)
        val dao = HrDb.get(context).dao()
        val before = System.currentTimeMillis() / MINUTE * MINUTE
        val zone = ZoneId.systemDefault()
        val device = Device(type = Device.TYPE_CHEST_STRAP, manufacturer = "COROS", model = "HEART RATE")

        while (true) {
            val raw = dao.range(prefs.hcSyncedUntil, before, CHUNK)
            val full = raw.size == CHUNK
            // Минута на границе пакета может быть неполной; отправим её целиком в следующем пакете.
            val lastMinute = raw.lastOrNull()?.ts?.div(MINUTE)
            if (raw.isEmpty()) break
            val rows = raw.filter { it.bpm in 1..300 && !(full && it.ts / MINUTE == lastMinute) }
            // Отметка двигается по сырым строкам, иначе пакет из одних отброшенных строк застопорит синхронизацию.
            val watermark = if (full) lastMinute!! * MINUTE - 1 else raw.last().ts
            val records = rows.groupBy { it.ts / MINUTE }.map { (minute, list) ->
                val start = Instant.ofEpochMilli(list.first().ts)
                val end = Instant.ofEpochMilli(list.last().ts + 1_000)
                HeartRateRecord(
                    startTime = start,
                    startZoneOffset = zone.rules.getOffset(start),
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    samples = list.map { HeartRateRecord.Sample(Instant.ofEpochMilli(it.ts), it.bpm.toLong()) },
                    metadata = Metadata.autoRecorded(device = device, clientRecordId = "hr-$minute", clientRecordVersion = 1),
                )
            }
            if (records.isNotEmpty()) client.insertRecords(records)
            sent += records.size
            prefs.hcSyncedUntil = watermark
            Log.i(TAG, "synced ${records.size} minutes up to $watermark")
            if (!full) break
        }
        return sent
    }
}
