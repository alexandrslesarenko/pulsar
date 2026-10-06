package com.puls.app.service

import android.content.Context

class Prefs(context: Context) {
    companion object {
        /** Normal resting heart rate for adults (American Heart Association). */
        const val REST_LOW = 60
        const val REST_HIGH = 100
        const val SLEEP_LOW = 40

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }

    private val sp = context.getSharedPreferences("puls", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = sp.getString("device_address", null)
        set(v) = sp.edit().putString("device_address", v).apply()

    var deviceName: String?
        get() = sp.getString("device_name", null)
        set(v) = sp.edit().putString("device_name", v).apply()

    /** Collection turned on by the user; the service is restarted after a reboot based on it. */
    var collecting: Boolean
        get() = sp.getBoolean("collecting", false)
        set(v) = sp.edit().putBoolean("collecting", v).apply()

    /** The sensor was handed to another phone: no autostart until an explicit "Start". */
    var released: Boolean
        get() = sp.getBoolean("released", false)
        set(v) = sp.edit().putBoolean("released", v).apply()

    /** How many minutes to search for the sensor actively after the link is lost (keeping the phone awake). */
    var sensorSearchMin: Int
        get() = sp.getInt("sensor_search_min", 5)
        set(v) = sp.edit().putInt("sensor_search_min", v).apply()

    /** Heart rate in the notification and Now Bar; off - a collapsed notification without heart rate. */
    var showInNotification: Boolean
        get() = sp.getBoolean("show_in_notification", true)
        set(v) = sp.edit().putBoolean("show_in_notification", v).apply()

    /** Date of birth; 0 - not set. Month and day are optional. */
    var birthYear: Int
        get() = sp.getInt("birth_year", 0)
        set(v) = sp.edit().putInt("birth_year", v).apply()
    var birthMonth: Int
        get() = sp.getInt("birth_month", 0)
        set(v) = sp.edit().putInt("birth_month", v).apply()
    var birthDay: Int
        get() = sp.getInt("birth_day", 0)
        set(v) = sp.edit().putInt("birth_day", v).apply()

    /** Age by date of birth, or null if it is not set. */
    val age: Int?
        get() = birthYear.takeIf { it > 0 }?.let { HrZones.age(it, birthMonth, birthDay) }

    /** Range alarm - separate for each profile. */
    fun alarmEnabled(p: Profile): Boolean = sp.getBoolean("${p.key}_alarm", true)

    fun setAlarmEnabled(p: Profile, v: Boolean) = sp.edit().putBoolean("${p.key}_alarm", v).apply()

    /** Alarm of the active profile. */
    val alarmEnabled: Boolean get() = alarmEnabled(profile)

    /** Active profile: the range and vibration depend on it. */
    var profile: Profile
        get() = Profile.byKey(sp.getString("profile", null)) ?: Profile.REST
        set(v) = sp.edit().putString("profile", v.key).apply()

    /** The profile is chosen automatically by heart rate and steps; a manual profile choice turns it off. */
    var autoProfile: Boolean
        get() = sp.getBoolean("auto_profile", false)
        set(v) = sp.edit().putBoolean("auto_profile", v).apply()

    /** Profile range. For walk in "auto" mode it is computed from age. */
    fun range(p: Profile): IntRange {
        val a = age
        if (p == Profile.WALK && walkAuto && a != null) return HrZones.walkZone(a)
        val def = p.defaultRange(a)
        return sp.getInt("${p.key}_low", def.first)..sp.getInt("${p.key}_high", def.last)
    }

    fun setRange(p: Profile, low: Int, high: Int) {
        sp.edit().putInt("${p.key}_low", low).putInt("${p.key}_high", high).apply()
    }

    /** Default range: custom bounds are forgotten, walk is computed from age again. */
    fun resetRange(p: Profile) {
        sp.edit().remove("${p.key}_low").remove("${p.key}_high").apply()
        if (p == Profile.WALK) walkAuto = true
    }

    /** The profile range matches what resetRange would give. */
    fun isDefaultRange(p: Profile): Boolean {
        val a = age
        val def = if (p == Profile.WALK && a != null) HrZones.walkZone(a) else p.defaultRange(a)
        return range(p) == def && (p != Profile.WALK || a == null || walkAuto)
    }

    fun vibrate(p: Profile): Boolean = sp.getBoolean("${p.key}_vibrate", p.defaultVibrate)

    fun setVibrate(p: Profile, v: Boolean) = sp.edit().putBoolean("${p.key}_vibrate", v).apply()

    /** Walk: range of 60-70% of max heart rate by age. */
    var walkAuto: Boolean
        get() = sp.getBoolean("walk_auto", true)
        set(v) = sp.edit().putBoolean("walk_auto", v).apply()

    /** Range of the active profile; alarms, the notification and the widget work by it. */
    val alarmLow: Int get() = range(profile).first
    val alarmHigh: Int get() = range(profile).last

    /** At night (during the set hours or in "Do Not Disturb" mode) do not vibrate. */
    var nightQuiet: Boolean
        get() = sp.getBoolean("night_quiet", true)
        set(v) = sp.edit().putBoolean("night_quiet", v).apply()

    /** Night start and end, minutes since midnight. */
    var nightFrom: Int
        get() = sp.getInt("night_from", 23 * 60)
        set(v) = sp.edit().putInt("night_from", v).apply()
    var nightTo: Int
        get() = sp.getInt("night_to", 7 * 60)
        set(v) = sp.edit().putInt("night_to", v).apply()

    /**
     * Lower alarm bound in sleep ("Rest" mode, at night and in the morning until the first steps).
     * In sleep heart rate below daytime rest is normal; a rare heart rate below 40 is a reason to wake up.
     */
    var sleepLow: Int
        get() = sp.getInt("sleep_low", SLEEP_LOW)
        set(v) = sp.edit().putInt("sleep_low", v).apply()

    /** In sleep heart rate is measured once in this many minutes (SleepSampling); 0 - all the time. */
    var sleepSampleMin: Int
        get() = sp.getInt("sleep_sample_min", SleepSampling.DEFAULT_INTERVAL)
        set(v) = sp.edit().putInt("sleep_sample_min", v).apply()

    init {
        migrateCorridor()
        migrateAlarmSwitch()
    }

    /** The alarm used to be one for the whole app: its value is copied to all profiles. */
    private fun migrateAlarmSwitch() {
        if (!sp.contains("alarm_enabled")) return
        val v = sp.getBoolean("alarm_enabled", true)
        val e = sp.edit()
        Profile.entries.forEach { e.putBoolean("${it.key}_alarm", v) }
        e.remove("alarm_enabled").apply()
    }

    /**
     * Before profiles there was a single range alarm_low/alarm_high. If it matched the age-based walk -
     * walk is activated, otherwise it is moved to rest.
     */
    private fun migrateCorridor() {
        if (sp.contains("profile") || !sp.contains("alarm_low")) return
        val lo = sp.getInt("alarm_low", REST_LOW)
        val hi = sp.getInt("alarm_high", REST_HIGH)
        val walk = age?.let { HrZones.walkZone(it) }
        if (walk != null && walk.first == lo && walk.last == hi) {
            profile = Profile.WALK
        } else {
            setRange(Profile.REST, lo, hi)
            profile = Profile.REST
        }
        sp.edit().remove("alarm_low").remove("alarm_high").apply()
    }

    /** Voice in headphones; without headphones it is silent in any case. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice_enabled", true)
        set(v) = sp.edit().putBoolean("voice_enabled", v).apply()

    /** How often to speak the current heart rate, minutes; 0 - only on a zone change. */
    var voiceIntervalMin: Int
        get() = sp.getInt("voice_interval_min", 1)
        set(v) = sp.edit().putInt("voice_interval_min", v).apply()

    /** Shake the phone - the voice tells the current heart rate (headphones only). */
    var shakeEnabled: Boolean
        get() = sp.getBoolean("shake_enabled", true)
        set(v) = sp.edit().putBoolean("shake_enabled", v).apply()

    /** Theme: "system" - follow the system, "light", "dark". */
    var theme: String
        get() = sp.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = sp.edit().putString("theme", v).apply()

    /** Speed from the pedometer. */
    var stepsEnabled: Boolean
        get() = sp.getBoolean("steps_enabled", true)
        set(v) = sp.edit().putBoolean("steps_enabled", v).apply()

    /** Height, cm: stride length depends on it; 0 - not set, no speed from steps. */
    var heightCm: Int
        get() = sp.getInt("height_cm", 0)
        set(v) = sp.edit().putInt("height_cm", v).apply()

    /** In the "Training" profile measure speed by GPS. */
    var gpsInTraining: Boolean
        get() = sp.getBoolean("gps_training", false)
        set(v) = sp.edit().putBoolean("gps_training", v).apply()

    var hcEnabled: Boolean
        get() = sp.getBoolean("hc_enabled", false)
        set(v) = sp.edit().putBoolean("hc_enabled", v).apply()

    /** ts of the last measurement sent to Health Connect. */
    var hcSyncedUntil: Long
        get() = sp.getLong("hc_synced_until", 0)
        set(v) = sp.edit().putLong("hc_synced_until", v).apply()
}
