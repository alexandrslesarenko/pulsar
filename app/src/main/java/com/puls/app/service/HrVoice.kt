package com.puls.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.puls.app.R
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Голосовые сообщения о пульсе. Говорит только в наушники: через динамик телефона
 * на ходу это слышат все вокруг, а владелец в кармане - нет.
 * На время фразы приглушает музыку (transient may duck), потом отдаёт фокус.
 */
class HrVoice(private val context: Context, private val prefs: Prefs) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: String? = null
    private var lastPeriodicAt = 0L
    private var focusHeld = false
    /** Номер текущей фразы: колбэки прерванных фраз не должны отдавать фокус новой. */
    private var seq = 0
    @Volatile private var currentId = ""
    private var createdAt = 0L
    private var tts: TextToSpeech = createTts()

    /**
     * Страховка от утечки фокуса: если движок не прислал ни onDone, ни onError, ни onStop,
     * фокус всё равно отдаём, иначе аудиокнига или музыка остаются на паузе.
     */
    private val focusTimeout = Runnable {
        Log.w(TAG, "no callback from TTS, abandoning audio focus")
        release()
    }

    private fun createTts(): TextToSpeech {
        ready = false
        createdAt = SystemClock.elapsedRealtime()
        var created: TextToSpeech? = null
        created = TextToSpeech(context.applicationContext) { status ->
            main.post { onInit(created!!, status) }
        }
        return created
    }

    private fun onInit(engine: TextToSpeech, status: Int) {
        if (engine !== tts) return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            return
        }
        // Голос на языке фраз: язык берётся из тех же ресурсов, что и сами фразы.
        val locale = Locale.forLanguageTag(context.getString(R.string.tts_locale))
        val r = engine.setLanguage(locale)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS voice for $locale is not available")
        }
        engine.setAudioAttributes(attrs)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = releaseOnMain(id)
            @Deprecated("API < 21")
            override fun onError(id: String?) = releaseOnMain(id)
            override fun onError(id: String?, errorCode: Int) {
                Log.w(TAG, "TTS error $errorCode")
                releaseOnMain(id)
            }
            // Фраза прервана следующей (QUEUE_FLUSH) или остановкой; onDone тогда не приходит.
            override fun onStop(id: String?, interrupted: Boolean) = releaseOnMain(id)
        })
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    fun headphonesConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }

    fun onEvent(e: AlarmEvent, bpm: Int?) {
        val text = when (e) {
            // Скорость при выходе за коридор подсказывает, что делать: сбавить шаг или прибавить.
            // При возврате в коридор действовать не нужно, там фраза короче.
            AlarmEvent.HIGH -> withSpeed(context.getString(R.string.voice_high, bpm))
            AlarmEvent.LOW -> withSpeed(context.getString(R.string.voice_low, bpm))
            AlarmEvent.BACK -> context.getString(R.string.voice_back, bpm)
            AlarmEvent.LOST -> context.getString(R.string.voice_lost)
        }
        lastPeriodicAt = System.currentTimeMillis()
        say(text)
    }

    /** Вызывается на каждом измерении; сама решает, пора ли проговорить текущий пульс. */
    fun onBpm(bpm: Int, zone: AlarmZone, now: Long) {
        val interval = prefs.voiceIntervalMin * 60_000L
        if (interval <= 0 || now - lastPeriodicAt < interval) return
        lastPeriodicAt = now
        say(
            withSpeed(
                when (zone) {
                    AlarmZone.HIGH -> context.getString(R.string.voice_high, bpm)
                    AlarmZone.LOW -> context.getString(R.string.voice_low, bpm)
                    AlarmZone.NORMAL -> context.getString(R.string.voice_bpm, bpm)
                }
            )
        )
    }

    /**
     * Добавляет к фразе скорость целым числом: на ходу "пять" понятнее, чем "пять и две
     * десятых". Стоим (меньше 1 км/ч) или скорость не меряется - фраза без скорости.
     */
    private fun withSpeed(text: String): String {
        val kmh = LiveHr.state.value.speedKmh?.roundToInt() ?: return text
        if (kmh < 1) return text
        return text + ". " + context.resources.getQuantityString(R.plurals.voice_speed, kmh, kmh)
    }

    /** По встряхиванию: сказать сейчас, не дожидаясь интервала. */
    fun sayNow(bpm: Int?, zone: AlarmZone) {
        lastPeriodicAt = System.currentTimeMillis()
        say(
            withSpeed(
                when {
                    bpm == null -> context.getString(R.string.voice_no_data)
                    zone == AlarmZone.HIGH -> context.getString(R.string.voice_high, bpm)
                    zone == AlarmZone.LOW -> context.getString(R.string.voice_low, bpm)
                    else -> context.getString(R.string.voice_bpm, bpm)
                }
            )
        )
    }

    /** Проверка из настроек: говорит и без наушников, чтобы можно было услышать голос. */
    fun test(bpm: Int?) = speak(
        if (bpm != null) context.getString(R.string.voice_bpm, bpm) else context.getString(R.string.voice_test)
    )

    private fun say(text: String) {
        if (!prefs.voiceEnabled) return
        if (!headphonesConnected()) {
            val types = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            Log.i(TAG, "no headphones, silent; outputs=$types")
            return
        }
        speak(text)
    }

    private fun speak(text: String) = main.post {
        if (!ready) {
            pending = text
            // Инициализация не удалась или зависла: пробуем движок заново, но не чаще RETRY_MS.
            if (SystemClock.elapsedRealtime() - createdAt > RETRY_MS) {
                Log.w(TAG, "TTS not ready, recreating")
                runCatching { tts.shutdown() }
                tts = createTts()
            }
            return@post
        }
        val granted = audio.requestAudioFocus(focus)
        focusHeld = true
        main.removeCallbacks(focusTimeout)
        main.postDelayed(focusTimeout, FOCUS_TIMEOUT_MS)
        currentId = "hr-${++seq}"
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentId)
        if (r != TextToSpeech.SUCCESS) {
            // Движок TTS отвязан (его процесс выгружен системой): сам он не вернётся.
            // Отдаём фокус, пересоздаём движок, фраза уйдёт после инициализации.
            Log.w(TAG, "speak failed ($r, focus=$granted), recreating TTS")
            release()
            runCatching { tts.shutdown() }
            pending = text
            tts = createTts()
        }
    }

    private fun releaseOnMain(id: String?) {
        main.post { if (id == currentId) release() }
    }

    private fun release() {
        main.removeCallbacks(focusTimeout)
        if (!focusHeld) return
        focusHeld = false
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        main.post {
            pending = null
            runCatching {
                tts.stop()
                tts.shutdown()
            }
            release()
        }
    }

    companion object {
        private const val TAG = "HrVoice"
        private const val FOCUS_TIMEOUT_MS = 15_000L
        private const val RETRY_MS = 30_000L
        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
