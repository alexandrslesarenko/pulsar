package com.puls.app.service

import androidx.annotation.StringRes
import com.puls.app.R

/**
 * Профиль нагрузки: свой коридор пульса и своя настройка вибрации.
 * key хранится в настройках и в журнале профилей в БД и уходит в Calorie через
 * ActivityProvider ("walk", "training") - не менять.
 */
enum class Profile(val key: String, @StringRes val label: Int, val defaultVibrate: Boolean) {
    REST("rest", R.string.profile_rest, false),
    WALK("walk", R.string.profile_walk, true),
    TRAINING("training", R.string.profile_training, true);

    fun defaultRange(age: Int?): IntRange = when (this) {
        REST -> Prefs.REST_LOW..Prefs.REST_HIGH
        WALK -> age?.let { HrZones.walkZone(it) } ?: 90..120
        TRAINING -> age?.let { HrZones.trainingZone(it) } ?: 110..150
    }

    companion object {
        fun byKey(key: String?): Profile? = entries.firstOrNull { it.key == key }
    }
}
