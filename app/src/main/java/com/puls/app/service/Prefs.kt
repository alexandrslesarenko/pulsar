package com.puls.app.service

import android.content.Context

class Prefs(context: Context) {
    companion object {
        /** Норма пульса в покое для взрослых (American Heart Association). */
        const val REST_LOW = 60
        const val REST_HIGH = 100
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

    var alarmEnabled: Boolean
        get() = sp.getBoolean("alarm_enabled", true)
        set(v) = sp.edit().putBoolean("alarm_enabled", v).apply()

    var alarmLow: Int
        get() = sp.getInt("alarm_low", REST_LOW)
        set(v) = sp.edit().putInt("alarm_low", v).apply()

    var alarmHigh: Int
        get() = sp.getInt("alarm_high", REST_HIGH)
        set(v) = sp.edit().putInt("alarm_high", v).apply()

    /** Голос в наушниках; без наушников молчит в любом случае. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean("voice_enabled", true)
        set(v) = sp.edit().putBoolean("voice_enabled", v).apply()

    /** Как часто проговаривать текущий пульс, минут; 0 - только при смене зоны. */
    var voiceIntervalMin: Int
        get() = sp.getInt("voice_interval_min", 1)
        set(v) = sp.edit().putInt("voice_interval_min", v).apply()

    var hcEnabled: Boolean
        get() = sp.getBoolean("hc_enabled", false)
        set(v) = sp.edit().putBoolean("hc_enabled", v).apply()

    /** ts последнего измерения, отправленного в Health Connect. */
    var hcSyncedUntil: Long
        get() = sp.getLong("hc_synced_until", 0)
        set(v) = sp.edit().putLong("hc_synced_until", v).apply()
}
