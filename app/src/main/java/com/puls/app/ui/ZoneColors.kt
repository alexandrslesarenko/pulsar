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
