package com.puls.app.ui

import androidx.compose.ui.graphics.Color
import com.puls.app.service.Profile

/** Цвета зон коридора пульса: общие для графиков и виджета. */
object ZoneColors {
    val High = Color(0xFFE5484D)
    val In = Color(0xFF2FB36B)
    val Low = Color(0xFFE0A100)
}

/** Цвета профилей для фона истории: бледные, чтобы линия пульса поверх них читалась. */
fun profileColor(p: Profile): Color = when (p) {
    Profile.REST -> Color(0xFF4C8DF6)
    Profile.WALK -> Color(0xFF9EDFA4)
    Profile.TRAINING -> Color(0xFFF5A9C8)
}

/**
 * Цвет профиля для текста на выделенном чипе. Бледные цвета фона истории на светлом
 * чипе не читаются, поэтому в светлой теме тона темнее; подобраны на контраст от 4.5.
 */
fun profileTextColor(p: Profile, dark: Boolean): Color = if (dark) {
    when (p) {
        Profile.REST -> Color(0xFF9DBFFA)
        else -> profileColor(p)
    }
} else {
    when (p) {
        Profile.REST -> Color(0xFF1F5FC8)
        Profile.WALK -> Color(0xFF1B6E20)
        Profile.TRAINING -> Color(0xFFC2185B)
    }
}
