package com.puls.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.puls.app.R
import com.puls.app.ble.ConnState
import com.puls.app.service.LiveState

fun statusText(ctx: Context, s: LiveState): String = ctx.getString(
    when (s.conn) {
        ConnState.IDLE -> R.string.status_idle
        ConnState.CONNECTING -> R.string.status_connecting
        ConnState.RECONNECTING -> R.string.status_reconnecting
        ConnState.NO_SIGNAL -> R.string.status_no_signal
        ConnState.CONNECTED -> when {
            s.skinContact == false -> R.string.status_no_contact
            s.bpm == null -> R.string.status_waiting
            else -> R.string.status_connected
        }
    }
)

/** "имя датчика - батарея N%" для подписей. */
fun deviceLine(ctx: Context, name: String?, battery: Int?): String =
    listOfNotNull(name, battery?.let { ctx.getString(R.string.battery, it) }).joinToString(" - ")

fun formatDuration(ctx: Context, seconds: Long): String {
    val h = (seconds / 3600).toInt()
    val m = (seconds % 3600 / 60).toInt()
    return if (h > 0) ctx.getString(R.string.duration_hm, h, m) else ctx.getString(R.string.duration_m, m)
}

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Строка кнопок, которая старается уместиться в одну строку: сначала подписи обычного
 * размера, затем помельче, затем ещё мельче. Если не помещается и так (очень крупный шрифт в системе),
 * кнопки переносятся на следующую строку, а не сжимаются до нечитаемых.
 * content получает стиль подписей, который надо применить к Text внутри кнопок.
 */
@Composable
fun FitRow(spacing: Dp = 8.dp, content: @Composable (TextStyle) -> Unit) {
    val styles = listOf(MaterialTheme.typography.labelLarge, MaterialTheme.typography.labelMedium, MaterialTheme.typography.labelSmall)
    SubcomposeLayout { constraints ->
        val gap = spacing.roundToPx()
        val limit = Constraints(maxWidth = constraints.maxWidth)
        var items: List<Placeable> = emptyList()
        for ((i, style) in styles.withIndex()) {
            items = subcompose(i) { content(style) }.map { it.measure(limit) }
            val total = items.sumOf { it.width } + gap * (items.size - 1).coerceAtLeast(0)
            if (total <= constraints.maxWidth) break
        }
        var x = 0
        var y = 0
        var lineHeight = 0
        val positions = items.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) {
                x = 0
                y += lineHeight + gap
                lineHeight = 0
            }
            val at = IntOffset(x, y)
            x += p.width + gap
            lineHeight = maxOf(lineHeight, p.height)
            at
        }
        val width = if (y == 0) (x - gap).coerceAtLeast(0) else constraints.maxWidth
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), y + lineHeight) {
            items.forEachIndexed { i, p -> p.placeRelative(positions[i]) }
        }
    }
}

/**
 * Текст в одну строку, который при нехватке места уменьшается (не меньше 10 sp), а не
 * переносится. Стиль и цвет берутся из окружения, как у обычного Text.
 */
@Composable
fun OneLineText(text: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current
    val max = if (style.fontSize.isSp) style.fontSize else 14.sp
    BasicText(
        text,
        modifier,
        style = style.copy(color = LocalContentColor.current),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = max),
    )
}

/**
 * Заголовок плитки: в одну строку с уменьшением, пока шрифт не мельче 12 sp, иначе - в две
 * строки обычным размером. "Экспорт и импорт" и японские названия в строку не влезают и
 * при 10 sp, а обрезанный заголовок хуже переноса.
 */
@Composable
fun TileTitle(text: String, modifier: Modifier = Modifier) {
    val style = LocalTextStyle.current
    val measurer = rememberTextMeasurer()
    // Layout, а не BoxWithConstraints: плитки меряются через IntrinsicSize, а SubcomposeLayout этого не умеет.
    Layout(
        content = {
            OneLineText(text)
            Text(text, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val fits = !constraints.hasBoundedWidth ||
            measurer.measure(text, style.copy(fontSize = 12.sp), maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
        val p = measurables[if (fits) 0 else 1].measure(constraints.copy(minHeight = 0))
        layout(p.width.coerceAtLeast(constraints.minWidth), p.height) { p.place(0, 0) }
    }
}

/** День и месяц в порядке, привычном для языка: 24.09 по-русски, 09/24 по-английски. */
fun dayMonthPattern(): String =
    android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "ddMM")
