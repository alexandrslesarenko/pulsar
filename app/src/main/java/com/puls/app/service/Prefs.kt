package com.puls.app.service

import android.content.Context

class Prefs(context: Context) {
    companion object {
        /** Норма пульса в покое для взрослых (American Heart Association). */
        const val REST_LOW = 60
        const val REST_HIGH = 100

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

    /** Сбор включён пользователем; по нему сервис поднимается после перезагрузки. */
    var collecting: Boolean
        get() = sp.getBoolean("collecting", false)
        set(v) = sp.edit().putBoolean("collecting", v).apply()

    /** Датчик отдан другому телефону: никакого автостарта до явного "Старт". */
    var released: Boolean
        get() = sp.getBoolean("released", false)
        set(v) = sp.edit().putBoolean("released", v).apply()

    /** Сколько минут после потери связи искать датчик активно (не давая телефону уснуть). */
    var sensorSearchMin: Int
        get() = sp.getInt("sensor_search_min", 5)
        set(v) = sp.edit().putInt("sensor_search_min", v).apply()

    /** Пульс в уведомлении и Now Bar; выключено - свёрнутое уведомление без пульса. */
    var showInNotification: Boolean
        get() = sp.getBoolean("show_in_notification", true)
        set(v) = sp.edit().putBoolean("show_in_notification", v).apply()

    /** Дата рождения; 0 - не указано. Месяц и день необязательны. */
    var birthYear: Int
        get() = sp.getInt("birth_year", 0)
        set(v) = sp.edit().putInt("birth_year", v).apply()
    var birthMonth: Int
        get() = sp.getInt("birth_month", 0)
        set(v) = sp.edit().putInt("birth_month", v).apply()
    var birthDay: Int
        get() = sp.getInt("birth_day", 0)
        set(v) = sp.edit().putInt("birth_day", v).apply()

    /** Возраст по дате рождения или null, если она не указана. */
    val age: Int?
        get() = birthYear.takeIf { it > 0 }?.let { HrZones.age(it, birthMonth, birthDay) }

    /** Сигнал о выходе из коридора - свой у каждого профиля. */
    fun alarmEnabled(p: Profile): Boolean = sp.getBoolean("${p.key}_alarm", true)

    fun setAlarmEnabled(p: Profile, v: Boolean) = sp.edit().putBoolean("${p.key}_alarm", v).apply()

    /** Сигнал активного профиля. */
    val alarmEnabled: Boolean get() = alarmEnabled(profile)

    /** Активный профиль: от него зависят коридор и вибрация. */
    var profile: Profile
        get() = Profile.byKey(sp.getString("profile", null)) ?: Profile.REST
        set(v) = sp.edit().putString("profile", v.key).apply()

    /** Профиль выбирается сам по пульсу и шагам; ручной выбор профиля его выключает. */
    var autoProfile: Boolean
        get() = sp.getBoolean("auto_profile", false)
        set(v) = sp.edit().putBoolean("auto_profile", v).apply()

    /** Коридор профиля. У прогулки в режиме "авто" он считается от возраста. */
    fun range(p: Profile): IntRange {
        val a = age
        if (p == Profile.WALK && walkAuto && a != null) return HrZones.walkZone(a)
        val def = p.defaultRange(a)
        return sp.getInt("${p.key}_low", def.first)..sp.getInt("${p.key}_high", def.last)
    }

    fun setRange(p: Profile, low: Int, high: Int) {
        sp.edit().putInt("${p.key}_low", low).putInt("${p.key}_high", high).apply()
    }

    fun vibrate(p: Profile): Boolean = sp.getBoolean("${p.key}_vibrate", p.defaultVibrate)

    fun setVibrate(p: Profile, v: Boolean) = sp.edit().putBoolean("${p.key}_vibrate", v).apply()

    /** Прогулка: коридор 60-70% от максимального пульса по возрасту. */
    var walkAuto: Boolean
        get() = sp.getBoolean("walk_auto", true)
        set(v) = sp.edit().putBoolean("walk_auto", v).apply()

    /** Коридор активного профиля; по нему работают сигналы, уведомление и виджет. */
    val alarmLow: Int get() = range(profile).first
    val alarmHigh: Int get() = range(profile).last

    /** Ночью (в заданные часы или при режиме "Не беспокоить") не вибрировать. */
    var nightQuiet: Boolean
        get() = sp.getBoolean("night_quiet", true)
        set(v) = sp.edit().putBoolean("night_quiet", v).apply()

    /** Начало и конец ночи, минуты от полуночи. */
    var nightFrom: Int
        get() = sp.getInt("night_from", 23 * 60)
        set(v) = sp.edit().putInt("night_from", v).apply()
    var nightTo: Int
        get() = sp.getInt("night_to", 7 * 60)
        set(v) = sp.edit().putInt("night_to", v).apply()

    init {
        migrateCorridor()
        migrateAlarmSwitch()
    }

    /** Раньше сигнал включался один на всё приложение: переносим его значение во все профили. */
    private fun migrateAlarmSwitch() {
        if (!sp.contains("alarm_enabled")) return
        val v = sp.getBoolean("alarm_enabled", true)
        val e = sp.edit()
        Profile.entries.forEach { e.putBoolean("${it.key}_alarm", v) }
        e.remove("alarm_enabled").apply()
    }

    /**
     * До профилей был один коридор alarm_low/alarm_high. Совпадал с прогулкой по возрасту -
     * включаем прогулку, иначе переносим его в покой.
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

    /** Голос в наушниках; без наушников молчит в любом случае. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice_enabled", true)
        set(v) = sp.edit().putBoolean("voice_enabled", v).apply()

    /** Как часто проговаривать текущий пульс, минут; 0 - только при смене зоны. */
    var voiceIntervalMin: Int
        get() = sp.getInt("voice_interval_min", 1)
        set(v) = sp.edit().putInt("voice_interval_min", v).apply()

    /** Встряхнуть телефон - голос скажет текущий пульс (только в наушниках). */
    var shakeEnabled: Boolean
        get() = sp.getBoolean("shake_enabled", true)
        set(v) = sp.edit().putBoolean("shake_enabled", v).apply()

    /** Тема оформления: "system" - как в системе, "light", "dark". */
    var theme: String
        get() = sp.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = sp.edit().putString("theme", v).apply()

    /** Скорость по шагомеру. */
    var stepsEnabled: Boolean
        get() = sp.getBoolean("steps_enabled", true)
        set(v) = sp.edit().putBoolean("steps_enabled", v).apply()

    /** Рост, см: от него длина шага. */
    var heightCm: Int
        get() = sp.getInt("height_cm", 175)
        set(v) = sp.edit().putInt("height_cm", v).apply()

    /** В профиле "Тренировка" мерить скорость по GPS. */
    var gpsInTraining: Boolean
        get() = sp.getBoolean("gps_training", false)
        set(v) = sp.edit().putBoolean("gps_training", v).apply()

    var hcEnabled: Boolean
        get() = sp.getBoolean("hc_enabled", false)
        set(v) = sp.edit().putBoolean("hc_enabled", v).apply()

    /** ts последнего измерения, отправленного в Health Connect. */
    var hcSyncedUntil: Long
        get() = sp.getLong("hc_synced_until", 0)
        set(v) = sp.edit().putLong("hc_synced_until", v).apply()
}
