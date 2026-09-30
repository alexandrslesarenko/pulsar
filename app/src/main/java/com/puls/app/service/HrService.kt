package com.puls.app.service

import android.Manifest
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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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
import com.puls.app.data.ProfileLog
import com.puls.app.ui.MainActivity
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
    private lateinit var shake: ShakeDetector
    private lateinit var motion: MotionTracker
    private lateinit var audio: AudioManager
    private val auto = AutoProfile()
    /** Auto selection is on and the pedometer works: without steps a walk cannot be told from rest. */
    private var autoOn = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val buffer = ArrayList<HrSample>()
    private val bufferLock = Mutex()
    private var lastNotifyAt = 0L
    /** What the notification shows now; rebuilt only on change. */
    private var shownBpm: Int? = null
    private lateinit var power: PowerManager
    private var lastWidgetAt = 0L
    private var lastTs = 0L
    private var destroyed = false
    private var lastSampleLogAt = 0L
    private var lastContact: Boolean? = null
    /** When there were steps last (cadence from STILL_SPM); in the morning they show the user is awake. */
    private var lastStepsAt = 0L
    /** Last phone battery level written to the log, and the charging flag. */
    private var loggedPhoneBattery: Pair<Int, Boolean>? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (st == BluetoothAdapter.STATE_ON) prefs.deviceAddress?.let { client.start(it) }
        }
    }

    /** Headphones connected or removed: this decides whether to listen for shakes. */
    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = updateShake()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = updateShake()
    }

    /** With the screen off the widget is not updated and the notification is updated rarely; on screen on - right away. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            notifyNow()
            pushWidget(force = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Telemetry.init(this)
        Telemetry.log("svc", "start")
        nm = getSystemService(NotificationManager::class.java)
        power = getSystemService(PowerManager::class.java)
        nm.deleteNotificationChannel("hr_live")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        // Quiet mode: Android requires a notification for a foreground service, but it can be
        // collapsed, without a status bar icon and without the lock screen.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, getString(R.string.channel_quiet), NotificationManager.IMPORTANCE_MIN).apply {
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
        client = HrBleClient(this, this, searchMs = { prefs.sensorSearchMin * 60_000L })
        voice = HrVoice(this, prefs)
        shake = ShakeDetector(this) {
            val s = LiveHr.state.value
            val bpm = s.bpm?.takeIf { s.conn == ConnState.CONNECTED && s.skinContact != false }
            voice.sayNow(bpm, alarm.zone)
        }
        motion = MotionTracker(this) { kmh -> LiveHr.mutable.value = LiveHr.state.value.copy(speedKmh = kmh) }
        audio = getSystemService(AudioManager::class.java)
        audio.registerAudioDeviceCallback(audioCallback, null)
        alarm = HrAlarm(
            this, prefs,
            range = ::alarmRange,
            onEvent = { e, bpm ->
                Telemetry.log("ev", e, bpm, alarm.bounds.first, alarm.bounds.last)
                voice.onEvent(e, bpm)
            },
            onChange = {
                LiveHr.mutable.value = LiveHr.state.value.copy(
                    alarm = alarm.zone, alarmMuted = alarm.muted, alarmVibrates = alarm.vibrationAllowed(),
                    bounds = alarm.bounds,
                )
                notifyNow()
            },
        )
        ContextCompat.registerReceiver(
            this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { periodic() }
        scope.launch(Dispatchers.IO) { runCatching { ProfileLog.record(this@HrService) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_AUTO) {
            prefs.autoProfile = true
        }
        if (intent?.action == ACTION_REFRESH || intent?.action == ACTION_AUTO) {
            updateShake()
            updateMotion()
            updateProfile()
            notifyNow()
            return START_STICKY
        }
        if (intent?.action == ACTION_MUTE) {
            Telemetry.log("mute")
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
        startFg(withLocation = false)
        val address = prefs.deviceAddress
        if (address == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.collecting = true
        prefs.released = false
        // Another sensor was chosen while the service runs - reconnect to it, otherwise we would stay on the old one.
        if (LiveHr.state.value.conn == ConnState.IDLE || client.address != address) {
            if (client.address != null && client.address != address) {
                LiveHr.mutable.value = LiveHr.state.value.copy(deviceName = prefs.deviceName, battery = null)
            }
            client.start(address)
        }
        updateShake()
        updateMotion()
        updateProfile()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        Telemetry.log("svc", "stop")
        unregisterReceiver(btReceiver)
        unregisterReceiver(screenReceiver)
        audio.unregisterAudioDeviceCallback(audioCallback)
        shake.stop()
        motion.stop()
        client.stop()
        alarm.reset()
        voice.shutdown()
        LiveHr.mutable.value = LiveState()
        LiveHr.recentMutable.value = emptyList()
        // The buffer tail is written synchronously: after onDestroy the process may die.
        runBlocking(Dispatchers.IO) { flush() }
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch { runCatching { HrWidget.push(app, LiveState()) } }
        scope.cancel()
        super.onDestroy()
    }

    /** The accelerometer is needed only when the voice has somewhere to speak. */
    private fun updateShake() {
        if (destroyed) return
        if (prefs.shakeEnabled && prefs.voiceEnabled && voice.headphonesConnected()) shake.start() else shake.stop()
    }

    private fun startFg(withLocation: Boolean) {
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (withLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(LiveHr.state.value), type)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Pedometer - always when enabled and permitted. GPS - only in the "Training" profile
     * with the switch on: it drains the battery noticeably.
     */
    private fun updateMotion() {
        if (destroyed) return
        if (prefs.stepsEnabled && granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            motion.startSteps(fast = prefs.autoProfile)
        } else {
            motion.stopSteps()
        }
        val gps = prefs.profile == Profile.TRAINING && prefs.gpsInTraining && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (gps && !motion.gpsOn) {
            // The service gets location through the location type; it can be added only while the app is on screen.
            runCatching { startFg(withLocation = true) }
                .onSuccess { motion.startGps() }
                .onFailure { Log.w(TAG, "location service type refused", it) }
        } else if (!gps && motion.gpsOn) {
            motion.stopGps()
            LiveHr.mutable.value = LiveHr.state.value.copy(speedKmh = null)
            runCatching { startFg(withLocation = false) }
        }
        if (!motion.stepsOn && !motion.gpsOn) LiveHr.mutable.value = LiveHr.state.value.copy(speedKmh = null)
    }

    /**
     * The profile could have changed in the UI, auto selection could have been turned on or off. A manual choice
     * turns auto selection off, so on any such change we restart it from a clean slate.
     */
    private fun updateProfile() {
        if (destroyed) return
        val on = prefs.autoProfile && motion.stepsOn
        if (on != autoOn || LiveHr.state.value.profile != prefs.profile) {
            auto.reset()
            Telemetry.log("mode", prefs.profile, on)
        }
        autoOn = on
        LiveHr.mutable.value = LiveHr.state.value.copy(profile = prefs.profile, autoProfile = prefs.autoProfile)
    }

    /**
     * Alarm bounds. Manual - the profile range, strictly. In auto the profile follows
     * heart rate by itself, so we alarm only outside the overall bounds (below rest, above training)
     * and on high heart rate without motion in rest.
     */
    private fun alarmRange(): IntRange {
        val r = if (!autoActive()) {
            prefs.range(prefs.profile)
        } else {
            val rest = prefs.range(Profile.REST)
            rest.first..(if (auto.restHigh) rest.last else prefs.range(Profile.TRAINING).last)
        }
        // In sleep heart rate is below daytime rest, and the "below" alarm would wake all night (field tests 24-28.09).
        if (prefs.profile != Profile.REST || !sleeping()) return r
        return minOf(prefs.sleepLow, r.first)..r.last
    }

    private fun sleeping(): Boolean {
        val now = System.currentTimeMillis()
        val t = java.time.LocalTime.now()
        val sinceSteps = lastStepsAt.takeIf { it > 0 }?.let { ((now - it) / 60_000).toInt() }
        return HrZones.isSleep(t.hour * 60 + t.minute, prefs.nightFrom, prefs.nightTo, sinceSteps, motion.stepsOn)
    }

    /**
     * A manual choice in the UI turns auto selection off in prefs at once, but REFRESH reaches the service later:
     * in this window auto selection must not override the user's choice.
     */
    private fun autoActive() = autoOn && prefs.autoProfile

    private fun switchProfile(p: Profile) {
        Log.i(TAG, "auto profile ${prefs.profile} -> $p")
        Telemetry.log("sw", prefs.profile, p, auto.median, motion.cadence.spm(System.currentTimeMillis()))
        prefs.profile = p
        LiveHr.mutable.value = LiveHr.state.value.copy(profile = p)
        alarm.profileSwitched()
        voice.onProfile(p)
        // GPS in training: the system will not grant it from the background, but if the app is on screen it turns on.
        updateMotion()
        scope.launch(Dispatchers.IO) { runCatching { ProfileLog.record(this@HrService) } }
    }

    private suspend fun recordMotion() {
        val now = System.currentTimeMillis()
        val (sample, gps) = withContext(Dispatchers.Main) { motion.take(now) to motion.gpsOn }
        if (sample == null) return
        if (!gps) {
            val kmh = Speed.of(listOf(sample), prefs.heightCm)
            LiveHr.mutable.value = LiveHr.state.value.copy(speedKmh = kmh)
        }
        withContext(Dispatchers.IO) { HrDb.get(this@HrService).dao().putMotion(sample) }
    }

    private suspend fun periodic() {
        // Sending to Health Connect goes by the clock, not by a tick counter: the service restarts
        // (update, system), and a counter from zero might not live until the send. The first one - right
        // after start, to catch up with what has accumulated.
        var lastSyncAt = 0L
        while (scope.isActive) {
            delay(FLUSH_PERIOD_MS)
            flush()
            runCatching { recordMotion() }.onFailure { Log.w(TAG, "motion record failed", it) }
            logPhoneBattery()
            val now = SystemClock.elapsedRealtime()
            if (lastSyncAt == 0L || now - lastSyncAt >= SYNC_PERIOD_MS) {
                lastSyncAt = now
                runCatching { HealthSync.sync(this) }.onFailure { Log.w(TAG, "Health Connect sync failed", it) }
            }
        }
    }

    private suspend fun flush() = withContext(NonCancellable + Dispatchers.IO) {
        val batch = bufferLock.withLock { ArrayList(buffer).also { buffer.clear() } }
        if (batch.isNotEmpty()) HrDb.get(this@HrService).dao().insert(batch)
    }

    override fun onState(state: ConnState, deviceName: String?) {
        if (state != LiveHr.state.value.conn) Telemetry.log("conn", state)
        LiveHr.mutable.value = LiveHr.state.value.let {
            it.copy(
                conn = state,
                deviceName = deviceName ?: it.deviceName,
                bpm = if (state == ConnState.CONNECTED) it.bpm else null,
            )
        }
        if (state != ConnState.CONNECTED) auto.onGap()
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
        if (m.skinContact != lastContact) {
            lastContact = m.skinContact
            m.skinContact?.let { Telemetry.log("contact", it) }
            // The "Stop" action depends on contact.
            notifyNow()
        }
        // Without skin contact the sensor sends garbage or 0; this does not go into history.
        if (m.skinContact != false && m.bpm > 0 && now > lastTs) {
            lastTs = now
            scope.launch { bufferLock.withLock { buffer += HrSample(now, m.bpm) } }
            LiveHr.recentMutable.value = LiveHr.recent.value
                .dropWhile { it.first < now - LiveHr.RECENT_WINDOW_MS } + (now to m.bpm)
            if (motion.stepsOn && motion.cadence.spm(now) >= AutoProfile.STILL_SPM) lastStepsAt = now
            if (autoActive()) {
                auto.onSample(now, m.bpm, motion.cadence.spm(now), prefs.profile, prefs::range)?.let(::switchProfile)
            }
            alarm.onBpm(m.bpm, now)
            voice.onBpm(m.bpm, alarm.zone, now)
            if (now - lastSampleLogAt >= SAMPLE_LOG_MS) {
                lastSampleLogAt = now
                logSample(m.bpm, now)
            }
        } else {
            auto.onGap()
            alarm.onLinkLost()
        }
        if (prefs.showInNotification && m.bpm != shownBpm) {
            val minInterval = if (power.isInteractive) NOTIFY_MIN_INTERVAL_MS else NOTIFY_SCREEN_OFF_INTERVAL_MS
            if (now - lastNotifyAt >= minInterval) notifyNow()
        }
        pushWidget(force = false)
    }

    /** Phone battery to the log - only changes: they show how much the app drains. */
    private fun logPhoneBattery() {
        val bm = getSystemService(BatteryManager::class.java)
        val now = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
        if (now == loggedPhoneBattery) return
        loggedPhoneBattery = now
        Telemetry.log("pbat", now.first, now.second)
    }

    /** Log snapshot: heart rate, step cadence and what auto selection thinks of them. */
    private fun logSample(bpm: Int, now: Long) {
        val b = alarm.bounds
        val cand = auto.candidate
        Telemetry.log(
            "s", prefs.profile, autoActive(), bpm, auto.median,
            if (motion.stepsOn) motion.cadence.spm(now) else null, LiveHr.state.value.speedKmh,
            auto.target, cand, cand?.let { (now - auto.candidateSince) / 1000 }, auto.restHigh,
            alarm.zone, b.first, b.last,
        )
    }

    override fun onLinkFailure(what: String, status: Int) {
        Telemetry.log("gatt", what, status)
    }

    override fun onBattery(percent: Int) {
        // Sensor battery to the log - only changes: they show the drain in percent per hour.
        if (percent == LiveHr.state.value.battery) return
        Telemetry.log("bat", percent)
        LiveHr.mutable.value = LiveHr.state.value.copy(battery = percent)
        // Battery is in the notification title; without heart rate (sensor taken off) nothing else would update it.
        notifyNow()
    }

    private fun pushWidget(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastWidgetAt < WIDGET_MIN_INTERVAL_MS || !power.isInteractive)) return
        lastWidgetAt = now
        val s = LiveHr.state.value
        scope.launch { runCatching { HrWidget.push(this@HrService, s) } }
    }

    private fun notifyNow() {
        if (destroyed) return
        lastNotifyAt = System.currentTimeMillis()
        val s = LiveHr.state.value
        shownBpm = s.bpm
        nm.notify(NOTIFICATION_ID, buildNotification(s))
    }

    private fun buildNotification(s: LiveState): Notification {
        val live = s.conn == ConnState.CONNECTED && s.bpm != null && s.skinContact != false
        val show = prefs.showInNotification
        val state = when {
            !live -> statusText(this, s)
            s.alarm == AlarmZone.NORMAL && !show -> getString(R.string.notif_collecting)
            s.alarm == AlarmZone.HIGH -> getString(R.string.notif_bpm_high, s.bpm, alarm.bounds.last)
            s.alarm == AlarmZone.LOW -> getString(R.string.notif_bpm_low, s.bpm, alarm.bounds.first)
            else -> getString(R.string.notif_bpm, s.bpm)
        }
        // One line: battery next to heart rate, the sensor name is not needed - there is only one.
        // While there is no link, the battery value is stale - not shown.
        val linked = s.conn == ConnState.CONNECTED || s.conn == ConnState.NO_SIGNAL
        val title = s.battery?.takeIf { linked }?.let { getString(R.string.notif_with_battery, state, it) } ?: state

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, if (show) CHANNEL else CHANNEL_QUIET)
        if (s.alarm != AlarmZone.NORMAL && !s.alarmMuted && s.alarmVibrates) {
            val mute = PendingIntent.getService(this, 2, muteIntent(this), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.action_mute), mute)
        }
        // Auto selection works only with the pedometer; the button is for turning it back on after a manual choice.
        if (!prefs.autoProfile && motion.stepsOn) {
            val auto = PendingIntent.getService(this, 3, autoIntent(this), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.action_auto), auto)
        }
        // While heart rate comes in, "Stop" is only an accidental tap away from losing the record;
        // it is for a sensor that is off, taken off or out of range.
        if (!live) {
            val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.action_stop), stop)
        }
        return b
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(if (show) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_SECRET)
            .setPriority(if (show) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16 Live Update: a status bar chip and a Now Bar card on the lock screen.
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
        private const val NOTIFY_SCREEN_OFF_INTERVAL_MS = 30_000L
        private const val FLUSH_PERIOD_MS = 60_000L
        private const val SYNC_PERIOD_MS = 5 * 60_000L
        private const val SAMPLE_LOG_MS = 10_000L
        private const val ACTION_STOP = "com.puls.app.STOP"
        private const val ACTION_MUTE = "com.puls.app.MUTE"
        private const val ACTION_AUTO = "com.puls.app.AUTO"

        fun startIntent(context: Context) = Intent(context, HrService::class.java)
        fun stopIntent(context: Context) = Intent(context, HrService::class.java).setAction(ACTION_STOP)
        fun muteIntent(context: Context) = Intent(context, HrService::class.java).setAction(ACTION_MUTE)
        fun autoIntent(context: Context) = Intent(context, HrService::class.java).setAction(ACTION_AUTO)

        fun mute(context: Context) {
            context.startService(muteIntent(context))
        }

        fun start(context: Context) = ContextCompat.startForegroundService(context, startIntent(context))

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        /** Redraw the notification after a settings change; if the service is not running - nothing. */
        fun refresh(context: Context) {
            if (LiveHr.state.value.conn != ConnState.IDLE) {
                context.startService(Intent(context, HrService::class.java).setAction(ACTION_REFRESH))
            }
        }

        /** Full stop: the link is dropped, autostart is off until an explicit "Start". */
        fun release(context: Context) {
            Prefs(context).released = true
            stop(context)
        }
    }
}
