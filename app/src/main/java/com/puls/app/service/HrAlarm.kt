package com.puls.app.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

enum class AlarmZone { NORMAL, HIGH, LOW }

enum class AlarmEvent { HIGH, LOW, BACK, LOST }

/**
 * Сигналы о коридоре пульса [low, high], различимые на ощупь на ходу:
 * - выше: 3 длинных импульса, пауза 5 с, повтор;
 * - ниже: 4 коротких, пауза 5 с, повтор;
 * - вернулся в коридор: 1 короткий;
 * - нет данных дольше LOST_AFTER_MS: длинный-короткий-длинный, один раз.
 * Повтор идёт до возврата пульса в коридор или до mute (mute - до конца текущего выхода).
 * Выход из зоны с запасом HYSTERESIS, чтобы пульс у самой границы не дёргал сигнал.
 */
class HrAlarm(
    context: Context,
    private val prefs: Prefs,
    private val onEvent: (AlarmEvent, Int?) -> Unit,
    private val onChange: () -> Unit,
) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val main = Handler(Looper.getMainLooper())

    var zone = AlarmZone.NORMAL
        private set
    var muted = false
        private set

    private var candidate = AlarmZone.NORMAL
    private var candidateSince = 0L
    /** Был сигнал "датчик потерян"; при возврате данных сообщим, где пульс. */
    private var lostSignaled = false
    private var linkUp = false

    private val lostTimer = Runnable {
        if (!prefs.alarmEnabled) return@Runnable
        lostSignaled = true
        setZone(AlarmZone.NORMAL, silent = true)
        play(LOST, repeat = false)
        onEvent(AlarmEvent.LOST, null)
    }

    fun onBpm(bpm: Int, now: Long) {
        if (!linkUp) {
            linkUp = true
            main.removeCallbacks(lostTimer)
        }
        if (!prefs.alarmEnabled) {
            lostSignaled = false
            setZone(AlarmZone.NORMAL, silent = true)
            return
        }
        val high = prefs.alarmHigh
        val low = prefs.alarmLow
        val target = when {
            bpm > high -> AlarmZone.HIGH
            bpm < low -> AlarmZone.LOW
            zone == AlarmZone.HIGH && bpm > high - HYSTERESIS -> AlarmZone.HIGH
            zone == AlarmZone.LOW && bpm < low + HYSTERESIS -> AlarmZone.LOW
            else -> AlarmZone.NORMAL
        }
        if (lostSignaled) {
            // Данные вернулись после потери: сразу сообщаем текущее положение.
            lostSignaled = false
            candidate = target
            if (target == AlarmZone.NORMAL) {
                play(BACK, repeat = false)
                onEvent(AlarmEvent.BACK, bpm)
            } else {
                setZone(target, silent = false, bpm = bpm)
            }
            return
        }
        if (target == AlarmZone.NORMAL) {
            candidate = AlarmZone.NORMAL
            if (zone != AlarmZone.NORMAL) {
                setZone(AlarmZone.NORMAL, silent = true)
                play(BACK, repeat = false)
                onEvent(AlarmEvent.BACK, bpm)
            }
            return
        }
        if (target == zone) return
        if (candidate != target) {
            candidate = target
            candidateSince = now
        }
        // Одиночный выброс датчика не должен будить; ждём, пока выход подтвердится.
        if (now - candidateSince >= CONFIRM_MS) setZone(target, silent = false, bpm = bpm)
    }

    /** Данные перестали приходить (снят, нет контакта, обрыв связи). */
    fun onLinkLost() {
        candidate = AlarmZone.NORMAL
        if (zone != AlarmZone.NORMAL) setZone(AlarmZone.NORMAL, silent = true)
        if (linkUp) {
            linkUp = false
            main.removeCallbacks(lostTimer)
            main.postDelayed(lostTimer, LOST_AFTER_MS)
        }
    }

    fun mute() {
        if (zone == AlarmZone.NORMAL || muted) return
        muted = true
        vibrator.cancel()
        onChange()
    }

    /** Сбор остановлен пользователем: молча забываем всё. */
    fun reset() {
        main.removeCallbacks(lostTimer)
        linkUp = false
        lostSignaled = false
        candidate = AlarmZone.NORMAL
        setZone(AlarmZone.NORMAL, silent = true)
    }

    private fun setZone(z: AlarmZone, silent: Boolean, bpm: Int? = null) {
        if (z == zone && !muted) return
        zone = z
        muted = false
        vibrator.cancel()
        if (!silent && z != AlarmZone.NORMAL) {
            play(if (z == AlarmZone.HIGH) HIGH else LOW, repeat = true)
            onEvent(if (z == AlarmZone.HIGH) AlarmEvent.HIGH else AlarmEvent.LOW, bpm)
        }
        onChange()
    }

    private fun play(pattern: LongArray, repeat: Boolean) {
        // Чётные позиции - пауза, нечётные - импульс на полную силу.
        val amplitudes = IntArray(pattern.size) { if (it % 2 == 1) MAX_AMPLITUDE else 0 }
        val effect = VibrationEffect.createWaveform(pattern, amplitudes, if (repeat) 0 else -1)
        vibrator.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    companion object {
        private const val CONFIRM_MS = 3_000L
        private const val HYSTERESIS = 3
        private const val LOST_AFTER_MS = 15_000L
        private const val MAX_AMPLITUDE = 255

        private val HIGH = longArrayOf(0, 700, 300, 700, 300, 700, 5_000)
        private val LOW = longArrayOf(0, 100, 100, 100, 100, 100, 100, 100, 5_000)
        private val BACK = longArrayOf(0, 150)
        private val LOST = longArrayOf(0, 600, 200, 150, 200, 600)
    }
}
