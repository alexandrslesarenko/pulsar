package com.puls.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Распознаёт встряхивание по ускорениям (м/с2, t - мс). Встряхивание - несколько резких
 * рывков с чередованием направления подряд, без пауз: рука разворачивается примерно
 * каждые 100-150 мс. Шаг в кармане тоже даёт пару удар-отскок, но после неё пауза
 * до следующего шага около 250 мс и больше; такая пауза обнуляет счёт.
 */
class ShakeLogic(
    private val threshold: Float = 13f,
    private val peaksNeeded: Int = 4,
    private val windowMs: Long = 1_500,
    private val cooldownMs: Long = 5_000,
) {
    private val gravity = FloatArray(3)
    private var gravityReady = false
    private val peaks = ArrayDeque<Long>()
    private var lastSign = 0
    private var lastPeakAt = 0L
    private var firedAt = Long.MIN_VALUE / 2

    /** true - распознано встряхивание. */
    fun onSample(t: Long, x: Float, y: Float, z: Float): Boolean {
        val v = floatArrayOf(x, y, z)
        if (!gravityReady) {
            v.copyInto(gravity)
            gravityReady = true
            return false
        }
        // Гравитацию отделяем медленным фильтром, остаток - рывки рукой.
        for (i in 0..2) gravity[i] = gravity[i] * 0.9f + v[i] * 0.1f
        val lin = FloatArray(3) { v[it] - gravity[it] }
        val mag = sqrt(lin[0] * lin[0] + lin[1] * lin[1] + lin[2] * lin[2])
        while (peaks.isNotEmpty() && t - peaks.first() > windowMs) peaks.removeFirst()
        if (mag < threshold || t - lastPeakAt < MIN_PEAK_GAP_MS) return false
        if (t - lastPeakAt > MAX_PEAK_GAP_MS) {
            peaks.clear()
            lastSign = 0
        }
        // Знак по оси с наибольшим рывком: для чередования направления.
        val axis = (0..2).maxBy { abs(lin[it]) }
        val sign = if (lin[axis] > 0) 1 else -1
        if (sign == lastSign && peaks.isNotEmpty()) return false
        lastSign = sign
        lastPeakAt = t
        peaks.addLast(t)
        if (peaks.size >= peaksNeeded && t - firedAt >= cooldownMs) {
            firedAt = t
            peaks.clear()
            lastSign = 0
            return true
        }
        return false
    }

    companion object {
        private const val MIN_PEAK_GAP_MS = 80L
        private const val MAX_PEAK_GAP_MS = 220L
    }
}

/**
 * Акселерометр слушаем, только пока включён (start/stop решает HrService):
 * постоянный опрос датчика тратил бы заряд.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    // Будящий вариант датчика доставляет события и при выключенном экране; пачками раз
    // в секунду, чтобы не будить процессор чаще, чем его и так будит пульс с датчика.
    // Время отсчётов берётся из самих событий, поэтому пачки распознаванию не мешают.
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var logic = ShakeLogic()
    var running = false
        private set

    fun start() {
        if (running || sensor == null) return
        logic = ShakeLogic()
        running = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, MAX_LATENCY_US)
        Log.i(TAG, "start: wakeUp=${sensor.isWakeUpSensor} ok=$running")
    }

    fun stop() {
        if (!running) return
        sm.unregisterListener(this)
        running = false
        Log.i(TAG, "stop")
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (logic.onSample(e.timestamp / 1_000_000, e.values[0], e.values[1], e.values[2])) {
            Log.i(TAG, "shake")
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "Shake"
        private const val MAX_LATENCY_US = 1_000_000
    }
}
