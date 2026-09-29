package com.puls.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val hasHrService: Boolean,
    /** Looks like a heart rate sensor: service 0x180D, Appearance "Heart Rate Sensor" or name. */
    val isHrSensor: Boolean,
    /** When the sensor was last heard over the air, elapsedRealtime. */
    val seenAt: Long = 0,
)

/** The system refused to scan (for example, too frequent starts); code - ScanCallback.SCAN_FAILED_*. */
class ScanFailed(val code: Int) : Exception("scan failed: $code")

@SuppressLint("MissingPermission")
class HrScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /**
     * Scans without a filter: not all sensors put the service UUID in the advertising packet.
     * Devices with Heart Rate Service in the advertisement are marked and listed first.
     */
    fun scan(): Flow<Map<String, FoundDevice>> = callbackFlow {
        val found = LinkedHashMap<String, FoundDevice>()
        val hrUuid = ParcelUuid(Gatt.HEART_RATE_SERVICE)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name
                val hasHr = r.scanRecord?.serviceUuids?.contains(hrUuid) == true
                val hrAppearance = isHrAppearance(r.scanRecord?.bytes)
                // A nameless sensor with no UUID in the advertisement but with a heart rate Appearance - list it too.
                if (name == null && !hasHr && !hrAppearance) return
                val isHr = hasHr || hrAppearance || (name != null && HR_NAME.containsMatchIn(name))
                found[r.device.address] = FoundDevice(r.device.address, name, r.rssi, hasHr, isHr, SystemClock.elapsedRealtime())
                trySend(found.toMap())
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: $errorCode")
                close(ScanFailed(errorCode))
            }
        }
        val scanner = adapter.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    companion object {
        private const val TAG = "HrScanner"
        private val HR_NAME = Regex("(?i)(heart|\\bhrm?\\b|\\bhr[\\s_-]|polar h|tickr|coros|pulse)")

        /** AD field 0x19 Appearance with category 0x0D (0x0340-0x037F) - Heart Rate Sensor. */
        fun isHrAppearance(adv: ByteArray?): Boolean {
            adv ?: return false
            var i = 0
            while (i + 1 < adv.size) {
                val len = adv[i].toInt() and 0xFF
                if (len == 0 || i + len >= adv.size) break
                val type = adv[i + 1].toInt() and 0xFF
                if (type == 0x19 && len >= 3) {
                    val appearance = (adv[i + 2].toInt() and 0xFF) or ((adv[i + 3].toInt() and 0xFF) shl 8)
                    return appearance shr 6 == 0x0D
                }
                i += len + 1
            }
            return false
        }

        fun looksLikeHrName(name: String) = HR_NAME.containsMatchIn(name)
    }

    /** Whether the sensor with this address is heard over the air (waits no longer than timeoutMs). */
    suspend fun isAdvertising(address: String, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        callbackFlow {
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, r: ScanResult) {
                    trySend(Unit)
                }
            }
            val scanner = adapter.bluetoothLeScanner
            val filter = ScanFilter.Builder().setDeviceAddress(address).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, cb)
            awaitClose { runCatching { scanner.stopScan(cb) } }
        }.first()
        true
    } ?: false
}
