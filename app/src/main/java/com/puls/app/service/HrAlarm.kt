package com.puls.app.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import java.time.LocalTime

enum class AlarmZone { NORMAL, HIGH, LOW }

enum class AlarmEvent { HIGH, LOW, BACK, LOST }

/**
 * Heart rate range [low, high] alarms, distinguishable by touch on the move:
 * - above: 3 long pulses, 5 s pause, repeat;
 * - below: 4 short ones, 5 s pause, repeat;
 * - back in range: 1 short;
 * - no data for longer than LOST_AFTER_MS: long-short-long, once;
 * - auto selection switched the profile: 2 medium, once.
 * Repeats until heart rate is back in range or until mute (mute lasts until the end of the current excursion).
 * Leaving the zone uses a HYSTERESIS margin so heart rate right at the bound does not toggle the alarm.
 *
 * Vibration can be turned off per profile and at night; events still go to the voice
 * and the notification, only the vibration motor stays silent.
 */
class HrAlarm(
    context: Context,
    private val prefs: Prefs,
    /** Alarm bounds: the profile range, and in auto the overall bounds (see AutoProfile). */
    private val range: () -> IntRange,
    private val onEvent: (AlarmEvent, Int?) -> Unit,
    private val onChange: () -> Unit,
) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val main = Handler(Looper.getMainLooper())
    private val nm = context.getSystemService(NotificationManager::class.java)

    var zone = AlarmZone.NORMAL
        private set
    var muted = false
        private set
    /** Bounds the last sample was evaluated against. */
    var bounds: IntRange = range()
        private set

    private var candidate = AlarmZone.NORMAL
    private var candidateSince = 0L
    /** The "sensor lost" alarm was given; when data comes back we report where heart rate is. */
    private var lostSignaled = false
    private var linkUp = false
    /** A repeating alarm is in progress. */
    private var vibrating = false

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
        // The night could have started in the middle of a repeating alarm.
        if (vibrating && !vibrationAllowed()) {
            vibrating = false
            vibrator.cancel()
        }
        val r = range()
        if (r != bounds) {
            bounds = r
            onChange()
        }
        val high = r.last
        val low = r.first
        val target = when {
            bpm > high -> AlarmZone.HIGH
            bpm < low -> AlarmZone.LOW
            zone == AlarmZone.HIGH && bpm > high - HYSTERESIS -> AlarmZone.HIGH
            zone == AlarmZone.LOW && bpm < low + HYSTERESIS -> AlarmZone.LOW
            else -> AlarmZone.NORMAL
        }
        if (lostSignaled) {
            // Data came back after a loss: report the current position right away.
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
        // A single sensor outlier should not wake anyone; wait until the excursion is confirmed.
        if (now - candidateSince >= CONFIRM_MS) setZone(target, silent = false, bpm = bpm)
    }

    /** Data stopped coming (taken off, no contact, link lost). */
    fun onLinkLost() {
        candidate = AlarmZone.NORMAL
        if (zone != AlarmZone.NORMAL) setZone(AlarmZone.NORMAL, silent = true)
        if (linkUp) {
            linkUp = false
            main.removeCallbacks(lostTimer)
            main.postDelayed(lostTimer, LOST_AFTER_MS)
        }
    }

    /** Whether vibration is allowed now: the profile is set so and it is not night. */
    fun vibrationAllowed(now: LocalTime = LocalTime.now()): Boolean {
        if (!prefs.vibrate(prefs.profile)) return false
        if (!prefs.nightQuiet) return true
        val dnd = nm.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        return !dnd && !HrZones.isNight(now.hour * 60 + now.minute, prefs.nightFrom, prefs.nightTo)
    }

    fun mute() {
        if (zone == AlarmZone.NORMAL || muted) return
        muted = true
        vibrating = false
        vibrator.cancel()
        onChange()
    }

    /**
     * Auto selection switched the profile: the alarm about the previous range is cleared silently (otherwise
     * there would be a false "above" or "normal") and the switch is announced with the new profile's vibration.
     */
    fun profileSwitched() {
        candidate = AlarmZone.NORMAL
        bounds = range()
        setZone(AlarmZone.NORMAL, silent = true)
        play(SWITCH, repeat = false)
        onChange()
    }

    /** Collection stopped by the user: silently forget everything. */
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
        vibrating = false
        vibrator.cancel()
        if (!silent && z != AlarmZone.NORMAL) {
            play(if (z == AlarmZone.HIGH) HIGH else LOW, repeat = true)
            onEvent(if (z == AlarmZone.HIGH) AlarmEvent.HIGH else AlarmEvent.LOW, bpm)
        }
        onChange()
    }

    private fun play(pattern: LongArray, repeat: Boolean) {
        vibrating = false
        if (!vibrationAllowed()) return
        // Even positions are pauses, odd ones are pulses at full strength.
        val amplitudes = IntArray(pattern.size) { if (it % 2 == 1) MAX_AMPLITUDE else 0 }
        val effect = VibrationEffect.createWaveform(pattern, amplitudes, if (repeat) 0 else -1)
        vibrator.cancel()
        vibrating = repeat
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
        private val SWITCH = longArrayOf(0, 300, 200, 300)
    }
}
