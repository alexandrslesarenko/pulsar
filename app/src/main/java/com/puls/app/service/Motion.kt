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
import android.util.Log
import com.puls.app.data.MotionSample

/** Примерная скорость по шагам: темп x длина шага. */
object Speed {
    /** Ниже этого темпа считаем, что человек стоит: одиночные шаги по комнате - не движение. */
    private const val MIN_CADENCE = 30.0

    /**
     * Длина шага, м. При ходьбе около 0.415 роста, при беге до 0.65: между темпом 120 и 160
     * шагов в минуту растёт линейно.
     */
    fun strideM(heightCm: Int, cadenceSpm: Double): Double {
        val k = when {
            cadenceSpm <= 120 -> 0.415
            cadenceSpm >= 160 -> 0.65
            else -> 0.415 + (cadenceSpm - 120) / 40 * (0.65 - 0.415)
        }
        return heightCm / 100.0 * k
    }

    /** Скорость, км/ч, по числу шагов за durMs. */
    fun fromSteps(steps: Int, durMs: Long, heightCm: Int): Double {
        if (durMs <= 0) return 0.0
        val cadence = steps * 60_000.0 / durMs
        if (cadence < MIN_CADENCE) return 0.0
        return cadence * strideM(heightCm, cadence) * 60 / 1000
    }

    /** Скорость отрезка, км/ч: GPS, если он был, иначе по шагам; null - данных нет. */
    fun of(samples: List<MotionSample>, heightCm: Int): Double? {
        val gps = samples.mapNotNull { it.gpsMps }
        if (gps.isNotEmpty()) return gps.average() * 3.6
        val dur = samples.sumOf { it.durMs }
        if (dur <= 0) return null
        return fromSteps(samples.sumOf { it.steps }, dur, heightCm)
    }
}

/**
 * Шаги и скорость GPS за интервалы между take(). Счётчик шагов - аппаратный, считает
 * сам в экономичном сопроцессоре; события приходят пачками, когда процессор и так проснулся.
 */
@SuppressLint("MissingPermission")
class MotionTracker(context: Context, private val onLive: (Double?) -> Unit) : SensorEventListener, LocationListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val lm = context.getSystemService(LocationManager::class.java)
    private val stepSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    var stepsOn = false
        private set
    var gpsOn = false
        private set

    /** Показание счётчика (шаги с загрузки) на начало интервала и последнее. */
    private var baseCount = -1L
    private var lastCount = -1L
    private var since = System.currentTimeMillis()
    private var gpsSum = 0.0
    private var gpsN = 0

    fun startSteps() {
        if (stepsOn || stepSensor == null) return
        stepsOn = sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_LATENCY_US)
        Log.i(TAG, "steps on=$stepsOn")
    }

    fun stopSteps() {
        if (!stepsOn) return
        sm.unregisterListener(this)
        stepsOn = false
        baseCount = -1
        lastCount = -1
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

    /** Интервал с прошлого вызова; null - ни шагомер, ни GPS не работали. */
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
        // После перезагрузки счётчик начинается с нуля.
        if (baseCount < 0 || c < lastCount) baseCount = c
        lastCount = c
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
        private const val GPS_INTERVAL_MS = 2_000L
    }
}
