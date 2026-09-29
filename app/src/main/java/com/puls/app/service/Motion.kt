package com.puls.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.puls.app.data.MotionSample

/** Approximate speed from steps: cadence x stride length. */
object Speed {
    /** Below this cadence the person is considered standing: single steps around the room are not motion. */
    private const val MIN_CADENCE = 30.0

    /**
     * Stride length, m. About 0.415 of height when walking, up to 0.65 when running: between cadence 120 and 160
     * steps per minute it grows linearly.
     */
    fun strideM(heightCm: Int, cadenceSpm: Double): Double {
        val k = when {
            cadenceSpm <= 120 -> 0.415
            cadenceSpm >= 160 -> 0.65
            else -> 0.415 + (cadenceSpm - 120) / 40 * (0.65 - 0.415)
        }
        return heightCm / 100.0 * k
    }

    /** Speed, km/h, from the number of steps over durMs. */
    fun fromSteps(steps: Int, durMs: Long, heightCm: Int): Double {
        if (durMs <= 0) return 0.0
        val cadence = steps * 60_000.0 / durMs
        if (cadence < MIN_CADENCE) return 0.0
        return cadence * strideM(heightCm, cadence) * 60 / 1000
    }

    /** Segment speed, km/h: GPS if it was on, otherwise from steps; null - no data or height not set. */
    fun of(samples: List<MotionSample>, heightCm: Int): Double? {
        val gps = samples.mapNotNull { it.gpsMps }
        if (gps.isNotEmpty()) return gps.average() * 3.6
        val dur = samples.sumOf { it.durMs }
        if (dur <= 0 || heightCm <= 0) return null
        return fromSteps(samples.sumOf { it.steps }, dur, heightCm)
    }
}

/**
 * Steps and GPS speed over intervals between take() calls. The step counter is a hardware one, it counts
 * by itself in a low-power coprocessor; events come in batches when the CPU is awake anyway.
 */
@SuppressLint("MissingPermission")
class MotionTracker(context: Context, private val onLive: (Double?) -> Unit) : SensorEventListener, LocationListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val lm = context.getSystemService(LocationManager::class.java)
    private val stepSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    var stepsOn = false
        private set
    /** The counter is registered with a short latency (for automatic profile selection). */
    private var stepsFast = false
    /** Step cadence for automatic profile selection: computed on every reading, not once a minute. */
    val cadence = Cadence()
    var gpsOn = false
        private set

    /** Counter reading (steps since boot) at the start of the interval, and the latest one. */
    private var baseCount = -1L
    private var lastCount = -1L
    private var since = System.currentTimeMillis()
    private var gpsSum = 0.0
    private var gpsN = 0

    /**
     * fast - short delivery latency: automatic profile selection needs the cadence now, not half a minute
     * later. The CPU wakes up every second anyway for the heart rate from the sensor.
     */
    fun startSteps(fast: Boolean) {
        if (stepSensor == null || (stepsOn && stepsFast == fast)) return
        if (stepsOn) sm.unregisterListener(this, stepSensor)
        stepsFast = fast
        stepsOn = sm.registerListener(
            this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, if (fast) FAST_LATENCY_US else MAX_LATENCY_US,
        )
        Log.i(TAG, "steps on=$stepsOn fast=$fast")
    }

    fun stopSteps() {
        if (!stepsOn) return
        sm.unregisterListener(this)
        stepsOn = false
        baseCount = -1
        lastCount = -1
        cadence.clear()
    }

    fun startGps() {
        if (gpsOn) return
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, this, Looper.getMainLooper())
            gpsOn = true
        }.onFailure { Log.w(TAG, "GPS failed", it) }
        Log.i(TAG, "gps on=$gpsOn")
    }

    fun stopGps() {
        if (!gpsOn) return
        lm.removeUpdates(this)
        gpsOn = false
    }

    fun stop() {
        stopSteps()
        stopGps()
    }

    /** Interval since the previous call; null - neither pedometer nor GPS was working. */
    fun take(now: Long): MotionSample? {
        val dur = now - since
        since = now
        val steps = if (baseCount >= 0 && lastCount >= baseCount) (lastCount - baseCount).toInt() else 0
        baseCount = lastCount
        val gps = if (gpsN > 0) (gpsSum / gpsN).toFloat() else null
        gpsSum = 0.0
        gpsN = 0
        if (!stepsOn && gps == null) return null
        return MotionSample(now - dur, dur, steps, gps)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val c = e.values[0].toLong()
        // After a reboot the counter starts from zero.
        if (baseCount < 0 || c < lastCount) baseCount = c
        lastCount = c
        // Event timestamp is on the elapsedRealtime clock; a batch of readings arrives late.
        val ts = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.timestamp) / 1_000_000
        cadence.add(ts, c)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        if (!l.hasSpeed()) return
        gpsSum += l.speed
        gpsN++
        onLive(l.speed * 3.6)
    }

    companion object {
        private const val TAG = "Motion"
        private const val MAX_LATENCY_US = 30_000_000
        private const val FAST_LATENCY_US = 5_000_000
        private const val GPS_INTERVAL_MS = 2_000L
    }
}
