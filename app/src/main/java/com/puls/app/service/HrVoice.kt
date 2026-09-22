package com.puls.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.puls.app.R
import java.util.Locale

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
    private var ready = false
    private var pending: String? = null
    private var lastPeriodicAt = 0L

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            // Голос на языке фраз: язык берётся из тех же ресурсов, что и сами фразы.
            val locale = Locale.forLanguageTag(context.getString(R.string.tts_locale))
            val r = tts.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS voice for $locale is not available")
            }
            tts.setAudioAttributes(attrs)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = release()
                @Deprecated("API < 21")
                override fun onError(id: String?) = release()
                override fun onError(id: String?, errorCode: Int) = release()
            })
            ready = true
            pending?.let { speak(it) }
            pending = null
        } else {
            Log.w(TAG, "TTS init failed: $status")
        }
    }

    fun headphonesConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }

    fun onEvent(e: AlarmEvent, bpm: Int?) {
        val text = when (e) {
            AlarmEvent.HIGH -> context.getString(R.string.voice_high, bpm)
            AlarmEvent.LOW -> context.getString(R.string.voice_low, bpm)
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
            when (zone) {
                AlarmZone.HIGH -> context.getString(R.string.voice_high, bpm)
                AlarmZone.LOW -> context.getString(R.string.voice_low, bpm)
                AlarmZone.NORMAL -> context.getString(R.string.voice_bpm, bpm)
            }
        )
    }

    /** Проверка из настроек: говорит и без наушников, чтобы можно было услышать голос. */
    fun test(bpm: Int?) = speak(
        if (bpm != null) context.getString(R.string.voice_bpm, bpm) else context.getString(R.string.voice_test)
    )

    private fun say(text: String) {
        if (!prefs.voiceEnabled || !headphonesConnected()) return
        speak(text)
    }

    private fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hr")
    }

    private fun release() {
        audio.abandonAudioFocusRequest(focus)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        release()
    }

    companion object {
        private const val TAG = "HrVoice"
        private val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
