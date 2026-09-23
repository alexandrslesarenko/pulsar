package com.puls.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/** NO_SIGNAL - связь с датчиком жива, но измерений нет: датчик снят или нет контакта. */
enum class ConnState { IDLE, CONNECTING, CONNECTED, NO_SIGNAL, RECONNECTING }

interface HrListener {
    fun onState(state: ConnState, deviceName: String?)
    fun onMeasurement(m: HrMeasurement)
    fun onBattery(percent: Int)
}

/**
 * Держит соединение с одним датчиком. Все операции с GATT выполняются на главном потоке,
 * колбэки стека Bluetooth перекладываются туда же.
 *
 * Переподключение: в течение окна активного поиска (searchMs) - прямые попытки подряд,
 * затем autoConnect=true - контроллер сам ждёт появления датчика и не тратит заряд.
 *
 * При выключенном экране процессор засыпает, и таймеры Handler стоят вместе с ним:
 * без удержания пробуждения отложенная попытка выполнится только при включении экрана.
 * Поэтому на время активного поиска и на время установки соединения держим
 * partial wakelock. Ожидание autoConnect процессора не требует.
 */
@SuppressLint("MissingPermission")
class HrBleClient(
    private val context: Context,
    private val listener: HrListener,
    /** Сколько после потери связи искать датчик активно, мс. */
    private val searchMs: () -> Long,
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "puls:ble").apply { setReferenceCounted(false) }
    /** elapsedRealtime начала поиска; 0 - связь есть. */
    private var searchSince = 0L

    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var deviceName: String? = null
    private var attempt = 0
    private var lastDataAt = 0L
    private var stopped = true
    private var probeSentAt = 0L
    private var noSignal = false
    private var batteryReadAt = 0L
    private var batterySubscribed = false

    fun start(address: String) {
        stop()
        this.address = address
        stopped = false
        attempt = 0
        beginSearch()
        connect(autoConnect = false, state = ConnState.CONNECTING)
    }

    fun stop() {
        stopped = true
        cancelTimers()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        searchSince = 0
        releaseWake()
        listener.onState(ConnState.IDLE, deviceName)
    }

    private fun beginSearch() {
        if (searchSince == 0L) searchSince = SystemClock.elapsedRealtime()
        val left = searchLeftMs()
        if (left > 0) wakeLock.acquire(left + WAKE_MARGIN_MS)
    }

    private fun searchLeftMs() = searchMs() - (SystemClock.elapsedRealtime() - searchSince)

    private fun releaseWake() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun connect(autoConnect: Boolean, state: ConnState) {
        val addr = address ?: return
        if (stopped) return
        gatt?.close()
        val device: BluetoothDevice = adapter.getRemoteDevice(addr)
        deviceName = device.name ?: deviceName
        listener.onState(state, deviceName)
        Log.i(TAG, "connect $addr autoConnect=$autoConnect attempt=$attempt")
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        if (autoConnect) main.postDelayed(renewAutoConnect, AUTO_CONNECT_RENEW_MS)
    }

    private fun cancelTimers() {
        main.removeCallbacks(renewAutoConnect)
        main.removeCallbacks(directReconnect)
        main.removeCallbacks(watchdog)
        main.removeCallbacks(discover)
    }

    private val discover = Runnable { gatt?.discoverServices() }

    /** Samsung иногда "забывает" отложенный autoConnect; периодически переоформляем его. */
    private val renewAutoConnect = Runnable { connect(autoConnect = true, state = ConnState.RECONNECTING) }

    private val directReconnect = Runnable { connect(autoConnect = false, state = ConnState.RECONNECTING) }

    private fun scheduleReconnect() {
        if (stopped) return
        cancelTimers()
        beginSearch()
        attempt++
        if (searchLeftMs() > 0) {
            // Прямая попытка сама ждёт датчик около 30 с, поэтому пауза между ними короткая.
            main.postDelayed(directReconnect, BACKOFF_MS[minOf(attempt, BACKOFF_MS.size) - 1])
        } else {
            Log.i(TAG, "active search over, waiting with autoConnect")
            releaseWake()
            connect(autoConnect = true, state = ConnState.RECONNECTING)
        }
    }

    /**
     * Нет данных дольше DATA_TIMEOUT_MS: спрашиваем RSSI. Ответил - связь жива, датчик просто
     * снят, ждём дальше без переподключения. Не ответил за PROBE_TIMEOUT_MS - связь зависла.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            val g = gatt
            if (g != null && now - batteryReadAt >= BATTERY_PERIOD_MS) readBattery(g)
            if (g != null && now - lastDataAt > DATA_TIMEOUT_MS) {
                if (probeSentAt == 0L) {
                    if (now - lastProbeOkAt >= PROBE_PERIOD_MS) {
                        probeSentAt = now
                        if (!g.readRemoteRssi()) probeSentAt = now - PROBE_TIMEOUT_MS
                    }
                } else if (now - probeSentAt >= PROBE_TIMEOUT_MS) {
                    Log.w(TAG, "link not responding, reconnecting")
                    probeSentAt = 0
                    g.disconnect()
                    g.close()
                    gatt = null
                    scheduleReconnect()
                    return
                }
            }
            main.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }
    private var lastProbeOkAt = 0L

    private fun onProbeOk() {
        probeSentAt = 0
        lastProbeOkAt = SystemClock.elapsedRealtime()
        if (!noSignal) {
            Log.i(TAG, "link alive but no data: strap is off")
            noSignal = true
            listener.onState(ConnState.NO_SIGNAL, deviceName)
        }
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val ch = g.getService(Gatt.HEART_RATE_SERVICE)?.getCharacteristic(Gatt.HEART_RATE_MEASUREMENT)
        if (ch == null) {
            Log.e(TAG, "Heart Rate Service not found")
            g.disconnect()
            return
        }
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(Gatt.CCCD) ?: return
        writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    private fun batteryChar(g: BluetoothGatt) =
        g.getService(Gatt.BATTERY_SERVICE)?.getCharacteristic(Gatt.BATTERY_LEVEL)

    /**
     * Заряд читаем при подключении, раз в BATTERY_PERIOD_MS и когда датчик снова надели:
     * на зарядке связь часто не рвётся, и без повторного чтения остаётся старое значение.
     */
    private fun readBattery(g: BluetoothGatt) {
        val ch = batteryChar(g) ?: return
        if (g.readCharacteristic(ch)) batteryReadAt = SystemClock.elapsedRealtime()
    }

    /** Подписка на уведомления о заряде, если датчик их умеет. Только после ответа на чтение: GATT не принимает две операции сразу. */
    private fun subscribeBattery(g: BluetoothGatt) {
        if (batterySubscribed) return
        val ch = batteryChar(g) ?: return
        batterySubscribed = true
        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) return
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(Gatt.CCCD) ?: return
        writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    private fun onValue(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            Gatt.HEART_RATE_MEASUREMENT -> HrParser.parse(value)?.let {
                lastDataAt = SystemClock.elapsedRealtime()
                probeSentAt = 0
                if (noSignal) {
                    noSignal = false
                    Log.i(TAG, "data resumed")
                    listener.onState(ConnState.CONNECTED, deviceName)
                    gatt?.let { readBattery(it) }
                }
                listener.onMeasurement(it)
            }
            Gatt.BATTERY_LEVEL -> if (value.isNotEmpty()) {
                Log.i(TAG, "battery ${value[0].toInt() and 0xFF}%")
                listener.onBattery(value[0].toInt() and 0xFF)
                gatt?.let { subscribeBattery(it) }
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Колбэк будит процессор, но ненадолго: до включения уведомлений держим его сами.
            if (newState == BluetoothProfile.STATE_CONNECTED) wakeLock.acquire(SETUP_WAKE_MS)
            main.post {
                if (g != gatt) return@post
                Log.i(TAG, "state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    cancelTimers()
                    // Сразу после подключения стек иногда отдаёт пустой список сервисов.
                    main.postDelayed(discover, DISCOVER_DELAY_MS)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close()
                    gatt = null
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) enableNotifications(g) else g.disconnect()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt) return@post
                // Подписка на заряд необязательна: её отказ связь не рвёт.
                if (d.characteristic.uuid == Gatt.BATTERY_LEVEL) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    g.disconnect()
                    return@post
                }
                attempt = 0
                searchSince = 0
                releaseWake()
                // HRS шлёт раз в секунду: короткий интервал соединения тут не нужен.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                noSignal = false
                probeSentAt = 0
                lastProbeOkAt = 0
                batterySubscribed = false
                lastDataAt = SystemClock.elapsedRealtime()
                deviceName = g.device.name ?: deviceName
                listener.onState(ConnState.CONNECTED, deviceName)
                main.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
                readBattery(g)
            }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) main.post { if (g == gatt) onProbeOk() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (g == gatt) onValue(ch.uuid, value) }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val v = ch.value ?: return
                main.post { if (g == gatt) onValue(ch.uuid, v) }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) main.post { if (g == gatt) onValue(ch.uuid, value) }
        }

        @Deprecated("API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS) {
                val v = ch.value ?: return
                main.post { if (g == gatt) onValue(ch.uuid, v) }
            }
        }
    }

    companion object {
        private const val TAG = "HrBle"
        private val BACKOFF_MS = longArrayOf(1_000, 3_000, 5_000)
        private const val WAKE_MARGIN_MS = 60_000L
        private const val SETUP_WAKE_MS = 30_000L
        private const val DATA_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_PERIOD_MS = 5_000L
        private const val AUTO_CONNECT_RENEW_MS = 10 * 60_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val PROBE_PERIOD_MS = 30_000L
        private const val DISCOVER_DELAY_MS = 500L
        private const val BATTERY_PERIOD_MS = 10 * 60_000L
    }
}
