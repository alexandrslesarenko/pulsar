package com.puls.app.service

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Voice language: the app language, English as a fallback, or none (no voices). */
enum class VoiceLang { NATIVE, ENGLISH, NONE }

/**
 * Voice messages about heart rate. Speaks only into headphones: through the phone speaker
 * everyone around hears it on the move, while the owner with the phone in a pocket does not.
 * Ducks the music for the phrase (transient may duck), then releases focus.
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
    private var pending: ((Resources) -> String)? = null
    private var lastPeriodicAt = 0L
    private var focusHeld = false
    /** Current phrase number: callbacks of interrupted phrases must not release focus of a new one. */
    private var seq = 0
    @Volatile private var currentId = ""
    private var createdAt = 0L
    /** Language set on the engine; the app language can be changed on the fly. */
    private var voiceTag = ""
    private val mutableLang = MutableStateFlow<VoiceLang?>(null)
    /** Which language the voice speaks; null - the engine is not ready yet. */
    val lang: StateFlow<VoiceLang?> = mutableLang
    /** English phrases in case there is no voice for the app language. */
    private val english: Resources by lazy {
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.US) }).resources
    }
    private var tts: TextToSpeech = createTts()

    /**
     * Guard against a focus leak: if the engine sent neither onDone, nor onError, nor onStop,
     * focus is released anyway, otherwise the audiobook or music stays paused.
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
        applyLanguage(engine)
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
            // The phrase was interrupted by the next one (QUEUE_FLUSH) or by a stop; onDone does not come then.
            override fun onStop(id: String?, interrupted: Boolean) = releaseOnMain(id)
        })
        ready = true
        pending?.let { speak(it) }
        pending = null
    }

    /**
     * Voice in the language of the phrases: the language is taken from the same resources as the phrases.
     * If the phone has no voice for it - speak English (almost every engine has an English
     * voice): an alarm in a foreign language is better than silence, while foreign text in a native voice
     * is nonsense. No English either - stay silent.
     */
    private fun applyLanguage(engine: TextToSpeech) {
        voiceTag = context.getString(R.string.tts_locale)
        val native = Locale.forLanguageTag(voiceTag)
        val lang = when {
            isAvailable(engine.setLanguage(native)) -> VoiceLang.NATIVE
            isAvailable(engine.setLanguage(Locale.US)) -> VoiceLang.ENGLISH
            else -> VoiceLang.NONE
        }
        if (lang != VoiceLang.NATIVE) Log.w(TAG, "TTS voice for $native is not available, using $lang")
        mutableLang.value = lang
    }

    private fun isAvailable(r: Int) = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED

    /** Resources in whose language the voice currently speaks. */
    private fun voiceRes(): Resources = if (mutableLang.value == VoiceLang.ENGLISH) english else context.resources

    /** Default speech synthesis engine (package): voice installation is opened in it. */
    fun enginePackage(): String? = runCatching { tts.defaultEngine }.getOrNull()

    /** Re-check the voices: the user may have come back from installing a voice. */
    fun recheckLanguage() {
        main.post { if (ready) applyLanguage(tts) }
    }

    fun headphonesConnected(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }

    fun onEvent(e: AlarmEvent, bpm: Int?) {
        lastPeriodicAt = System.currentTimeMillis()
        say { r ->
            when (e) {
                // Speed when leaving the range hints what to do: slow down or speed up.
                // When back in range no action is needed, so the phrase is shorter.
                AlarmEvent.HIGH -> withSpeed(r, r.getString(R.string.voice_high, bpm))
                AlarmEvent.LOW -> withSpeed(r, r.getString(R.string.voice_low, bpm))
                AlarmEvent.BACK -> r.getString(R.string.voice_back, bpm)
                AlarmEvent.LOST -> r.getString(R.string.voice_lost)
            }
        }
    }

    /** Auto selection switched the profile. */
    fun onProfile(p: Profile) {
        lastPeriodicAt = System.currentTimeMillis()
        say { r -> r.getString(R.string.voice_profile, r.getString(p.label)) }
    }

    /** Called on every measurement; decides by itself whether it is time to speak the current heart rate. */
    fun onBpm(bpm: Int, zone: AlarmZone, now: Long) {
        val interval = prefs.voiceIntervalMin * 60_000L
        if (interval <= 0 || now - lastPeriodicAt < interval) return
        lastPeriodicAt = now
        say { r -> withSpeed(r, bpmPhrase(r, bpm, zone)) }
    }

    private fun bpmPhrase(r: Resources, bpm: Int, zone: AlarmZone): String = when (zone) {
        AlarmZone.HIGH -> r.getString(R.string.voice_high, bpm)
        AlarmZone.LOW -> r.getString(R.string.voice_low, bpm)
        AlarmZone.NORMAL -> r.getString(R.string.voice_bpm, bpm)
    }

    /**
     * Appends the speed to the phrase as a whole number: on the move "five" is clearer than "five point
     * two". Standing (under 1 km/h) or speed not measured - the phrase goes without speed.
     */
    private fun withSpeed(r: Resources, text: String): String {
        val kmh = LiveHr.state.value.speedKmh?.roundToInt() ?: return text
        if (kmh < 1) return text
        return text + ". " + r.getQuantityString(R.plurals.voice_speed, kmh, kmh)
    }

    /** On a shake: speak now, without waiting for the interval. */
    fun sayNow(bpm: Int?, zone: AlarmZone) {
        lastPeriodicAt = System.currentTimeMillis()
        say { r -> withSpeed(r, if (bpm == null) r.getString(R.string.voice_no_data) else bpmPhrase(r, bpm, zone)) }
    }

    /** Test from settings: speaks even without headphones so the voice can be heard. */
    fun test(bpm: Int?) = speak { r ->
        if (bpm != null) r.getString(R.string.voice_bpm, bpm) else r.getString(R.string.voice_test)
    }

    private fun say(phrase: (Resources) -> String) {
        if (!prefs.voiceEnabled) return
        if (!headphonesConnected()) {
            val types = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            Log.i(TAG, "no headphones, silent; outputs=$types")
            return
        }
        speak(phrase)
    }

    /**
     * The phrase is built here, after the language check: this way the text is always in the language
     * it will be spoken in.
     */
    private fun speak(phrase: (Resources) -> String) = main.post {
        if (!ready) {
            pending = phrase
            // Initialization failed or hung: retry the engine, but not more often than RETRY_MS.
            if (SystemClock.elapsedRealtime() - createdAt > RETRY_MS) {
                Log.w(TAG, "TTS not ready, recreating")
                runCatching { tts.shutdown() }
                tts = createTts()
            }
            return@post
        }
        // The app language was changed or a voice may have been installed - check again.
        if (context.getString(R.string.tts_locale) != voiceTag || mutableLang.value != VoiceLang.NATIVE) applyLanguage(tts)
        if (mutableLang.value == VoiceLang.NONE) return@post
        val text = phrase(voiceRes())
        val granted = audio.requestAudioFocus(focus)
        focusHeld = true
        main.removeCallbacks(focusTimeout)
        main.postDelayed(focusTimeout, FOCUS_TIMEOUT_MS)
        currentId = "hr-${++seq}"
        val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentId)
        if (r != TextToSpeech.SUCCESS) {
            // The TTS engine is unbound (its process was killed by the system): it will not come back by itself.
            // Release focus, recreate the engine, the phrase goes out after initialization.
            Log.w(TAG, "speak failed ($r, focus=$granted), recreating TTS")
            release()
            runCatching { tts.shutdown() }
            pending = phrase
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
