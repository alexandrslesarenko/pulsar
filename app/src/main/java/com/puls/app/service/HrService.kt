package com.puls.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.puls.app.R
import com.puls.app.ble.ConnState
import com.puls.app.ble.HrBleClient
import com.puls.app.ble.HrListener
import com.puls.app.ble.HrMeasurement
import com.puls.app.data.HealthSync
import com.puls.app.data.HrDb
import com.puls.app.data.HrSample
import com.puls.app.ui.MainActivity
import com.puls.app.ui.deviceLine
import com.puls.app.ui.statusText
import com.puls.app.widget.HrWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HrService : Service(), HrListener {
    private lateinit var client: HrBleClient
    private lateinit var nm: NotificationManager
    private lateinit var prefs: Prefs
    private lateinit var alarm: HrAlarm
    private lateinit var voice: HrVoice
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val buffer = ArrayList<HrSample>()
    private val bufferLock = Mutex()
    private var lastNotifyAt = 0L
    private var lastWidgetAt = 0L
    private var lastTs = 0L
    private var destroyed = false

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (st == BluetoothAdapter.STATE_ON) prefs.deviceAddress?.let { client.start(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("hr_live")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        // Тихий режим: Android требует уведомление для фонового сервиса, но оно может быть
        // свёрнутым, без значка в строке состояния и без экрана блокировки.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_MIN).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        client = HrBleClient(this, this)
        voice = HrVoice(this, prefs)
        alarm = HrAlarm(
            this, prefs,
            onEvent = { e, bpm -> voice.onEvent(e, bpm) },
            onChange = {
                LiveHr.mutable.value = LiveHr.state.value.copy(alarm = alarm.zone, alarmMuted = alarm.muted)
                notifyNow()
            },
        )
        ContextCompat.registerReceiver(
            this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { periodic() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            notifyNow()
            return START_STICKY
        }
        if (intent?.action == ACTION_MUTE) {
            alarm.mute()
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            prefs.collecting = false
            client.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(LiveHr.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val address = prefs.deviceAddress
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.collecting = true
        prefs.released = false
        if (LiveHr.state.value.conn == ConnState.IDLE) client.start(address)
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        unregisterReceiver(btReceiver)
        client.stop()
        alarm.reset()
        voice.shutdown()
        LiveHr.mutable.value = LiveState()
        LiveHr.recentMutable.value = emptyList()
        // Хвост буфера дописываем синхронно: после onDestroy процесс может умереть.
        runBlocking(Dispatchers.IO) { flush() }
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch { runCatching { HrWidget.push(app, LiveState()) } }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun periodic() {
        var tick = 0L
        while (scope.isActive) {
            delay(FLUSH_PERIOD_MS)
            flush()
            tick++
            if (tick % (SYNC_PERIOD_MS / FLUSH_PERIOD_MS) == 0L) {
                runCatching { HealthSync.sync(this) }.onFailure { Log.w(TAG, "Health Connect sync failed", it) }
            }
        }
    }

    private suspend fun flush() = withContext(NonCancellable + Dispatchers.IO) {
        val batch = bufferLock.withLock { ArrayList(buffer).also { buffer.clear() } }
        if (batch.isNotEmpty()) HrDb.get(this@HrService).dao().insert(batch)
    }

    override fun onState(state: ConnState, deviceName: String?) {
        LiveHr.mutable.value = LiveHr.state.value.let {
            it.copy(
                conn = state,
                deviceName = deviceName ?: it.deviceName,
                bpm = if (state == ConnState.CONNECTED) it.bpm else null,
            )
        }
        when (state) {
            ConnState.CONNECTED -> {}
            ConnState.IDLE -> alarm.reset()
            else -> alarm.onLinkLost()
        }
        notifyNow()
        pushWidget(force = true)
    }

    override fun onMeasurement(m: HrMeasurement) {
        val now = System.currentTimeMillis()
        LiveHr.mutable.value = LiveHr.state.value.copy(bpm = m.bpm, skinContact = m.skinContact, updatedAt = now)
        // Без контакта с кожей датчик шлёт мусор или 0; в историю это не пишем.
        if (m.skinContact != false && m.bpm > 0 && now > lastTs) {
            lastTs = now
            scope.launch { bufferLock.withLock { buffer += HrSample(now, m.bpm) } }
            LiveHr.recentMutable.value = LiveHr.recent.value
                .dropWhile { it.first < now - LiveHr.RECENT_WINDOW_MS } + (now to m.bpm)
            alarm.onBpm(m.bpm, now)
            voice.onBpm(m.bpm, alarm.zone, now)
        } else {
            alarm.onLinkLost()
        }
        if (prefs.showInNotification && now - lastNotifyAt >= NOTIFY_MIN_INTERVAL_MS) notifyNow()
        pushWidget(force = false)
    }

    override fun onBattery(percent: Int) {
        LiveHr.mutable.value = LiveHr.state.value.copy(battery = percent)
    }

    private fun pushWidget(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWidgetAt < WIDGET_MIN_INTERVAL_MS) return
        lastWidgetAt = now
        val s = LiveHr.state.value
        scope.launch { runCatching { HrWidget.push(this@HrService, s) } }
    }

    private fun notifyNow() {
        if (destroyed) return
        lastNotifyAt = System.currentTimeMillis()
        nm.notify(NOTIFICATION_ID, buildNotification(LiveHr.state.value))
    }

    private fun buildNotification(s: LiveState): Notification {
        val live = s.conn == ConnState.CONNECTED && s.bpm != null && s.skinContact != false
        val show = prefs.showInNotification
        val title = when {
            !live -> statusText(this, s)
            s.alarm == AlarmZone.NORMAL && !show -> getString(R.string.notif_collecting)
            s.alarm == AlarmZone.HIGH -> getString(R.string.notif_bpm_high, s.bpm, prefs.alarmHigh)
            s.alarm == AlarmZone.LOW -> getString(R.string.notif_bpm_low, s.bpm, prefs.alarmLow)
            else -> getString(R.string.notif_bpm, s.bpm)
        }
        val text = deviceLine(this, s.deviceName, s.battery)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, if (show) CHANNEL else CHANNEL_QUIET)
        if (s.alarm != AlarmZone.NORMAL && !s.alarmMuted) {
            val mute = PendingIntent.getService(this, 2, muteIntent(this), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.action_mute), mute)
        }
        return b
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(if (show) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)
            .setPriority(if (show) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16 Live Update: чип в строке состояния и карточка в Now Bar на экране блокировки.
            .setRequestPromotedOngoing(show)
            .setShortCriticalText(if (show && live) "${s.bpm}" else null)
            .build()
    }

    companion object {
        private const val TAG = "HrService"
        private const val CHANNEL = "hr_live_v2"
        private const val CHANNEL_QUIET = "hr_quiet"
        private const val ACTION_REFRESH = "com.puls.app.REFRESH"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_MIN_INTERVAL_MS = 1_000L
        private const val WIDGET_MIN_INTERVAL_MS = 5_000L
        private const val FLUSH_PERIOD_MS = 10_000L
        private const val SYNC_PERIOD_MS = 5 * 60_000L
        private const val ACTION_STOP = "com.puls.app.STOP"
        private const val ACTION_MUTE = "com.puls.app.MUTE"

        fun startIntent(context: Context) = Intent(context, HrService::class.java)
        fun stopIntent(context: Context) = Intent(context, HrService::class.java).setAction(ACTION_STOP)
        fun muteIntent(context: Context) = Intent(context, HrService::class.java).setAction(ACTION_MUTE)

        fun mute(context: Context) {
            context.startService(muteIntent(context))
        }

        fun start(context: Context) = ContextCompat.startForegroundService(context, startIntent(context))

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        /** Перерисовать уведомление после смены настроек; если сервис не запущен - ничего. */
        fun refresh(context: Context) {
            if (LiveHr.state.value.conn != ConnState.IDLE) {
                context.startService(Intent(context, HrService::class.java).setAction(ACTION_REFRESH))
            }
        }

        /** Полная остановка: связь рвётся, автостарт выключен до явного "Старт". */
        fun release(context: Context) {
            Prefs(context).released = true
            stop(context)
        }
    }
}
