package com.puls.app.ui

import androidx.compose.ui.graphics.Color
import com.puls.app.service.Profile

/** Colors of the heart rate range zones: shared by charts and the widget. */
object ZoneColors {
    val High = Color(0xFFE5484D)
    val In = Color(0xFF2FB36B)
    val Low = Color(0xFFE0A100)
}

/** Profile colors for the history background: pale, so the heart rate line on top of them stays readable. */
fun profileColor(p: Profile): Color = when (p) {
    Profile.REST -> Color(0xFF4C8DF6)
    Profile.WALK -> Color(0xFF9EDFA4)
    Profile.TRAINING -> Color(0xFFF5A9C8)
}

/**
 * Profile color for text on a selected chip. The pale history background colors are unreadable
 * on a light chip, so the light theme uses darker tones; chosen for a contrast of 4.5 or more.
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
