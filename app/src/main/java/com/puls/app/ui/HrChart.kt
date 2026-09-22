package com.puls.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.puls.app.R
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Точка графика: t - начало интервала, lo/hi - разброс внутри него, avg - линия. */
data class ChartPoint(val t: Long, val lo: Int, val avg: Double, val hi: Int)

/**
 * График пульса: линия средних и полупрозрачная полоса min-max.
 * Разрыв в данных больше gapMs рвёт линию, а не соединяет точки через пустоту.
 * Касание показывает значение в точке; после долгого нажатия подсказку можно вести пальцем.
 */
@Composable
fun HrChart(
    points: List<ChartPoint>,
    from: Long,
    to: Long,
    gapMs: Long,
    modifier: Modifier = Modifier,
    height: Int = 220,
    /** Коридор сигнализации; null - линия одного цвета, без границ. */
    corridor: IntRange? = null,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 12.sp, color = colors.inverseOnSurface)
    var touchX by remember { mutableStateOf<Float?>(null) }
    val bpmUnit = stringResource(R.string.bpm_unit)

    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chart_no_data), color = colors.onSurfaceVariant)
        }
        return
    }

    val span = to - from
    val timeFmt = remember(span) {
        SimpleDateFormat(if (span > 36 * 3600_000L) "dd.MM" else "HH:mm", Locale.getDefault())
    }
    val tipFmt = remember(span) {
        SimpleDateFormat(if (span > 36 * 3600_000L) "dd.MM HH:mm" else "HH:mm:ss", Locale.getDefault())
    }

    val rawLo = minOf(points.minOf { it.lo }, corridor?.first ?: Int.MAX_VALUE)
    val rawHi = maxOf(points.maxOf { it.hi }, corridor?.last ?: Int.MIN_VALUE)
    val step = niceStep(rawHi - rawLo)
    val yMin = (floor((rawLo - 3) / step.toDouble()) * step).toInt().coerceAtLeast(0)
    val yMax = (ceil((rawHi + 3) / step.toDouble()) * step).toInt()

    Canvas(
        modifier.fillMaxWidth().height(height.dp)
            .pointerInput(points) {
                // Касание только читаем и не поглощаем, иначе свайп по графику не дойдёт до пейджера.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touchX = down.position.x
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed || c.isConsumed) break
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    touchX = null
                }
            }
            .pointerInput(points) {
                // Долгое нажатие, иначе обычный свайп уходит пейджеру вкладок.
                detectDragGesturesAfterLongPress(
                    onDragStart = { touchX = it.x },
                    onDragEnd = { touchX = null },
                    onDragCancel = { touchX = null },
                    onDrag = { change, _ -> touchX = change.position.x },
                )
            }
    ) {
        val left = 36.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val top = 8.dp.toPx()
        val right = size.width - 4.dp.toPx()
        fun x(t: Long) = left + (t - from).toFloat() / span * (right - left)
        fun y(v: Double) = bottom - ((v - yMin) / (yMax - yMin)).toFloat() * (bottom - top)

        // Сетка и подписи по оси Y
        var v = yMin
        while (v <= yMax) {
            val yy = y(v.toDouble())
            drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(left, yy), Offset(right, yy), 1f)
            val tl = measurer.measure(v.toString(), axisStyle)
            drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2))
            v += step
        }

        // Подписи по оси X на круглых отметках местного времени
        for (t in timeTicks(from, to)) {
            val tl = measurer.measure(timeFmt.format(Date(t)), axisStyle)
            val cx = x(t) - tl.size.width / 2
            if (cx < left || cx + tl.size.width > right) continue
            drawText(tl, topLeft = Offset(cx, bottom + 4.dp.toPx()))
        }

        // Непрерывные участки
        val segments = ArrayList<List<ChartPoint>>()
        var cur = ArrayList<ChartPoint>()
        for (p in points) {
            if (cur.isNotEmpty() && p.t - cur.last().t > gapMs) {
                segments += cur; cur = ArrayList()
            }
            cur += p
        }
        if (cur.isNotEmpty()) segments += cur

        // Цвет по зонам: выше коридора красный, ниже жёлтый, внутри зелёный.
        // Жёсткие переходы градиента ровно на высоте границ.
        val lineBrush: Brush
        val bandBrush: Brush
        if (corridor != null) {
            val fHi = (y(corridor.last.toDouble()) / size.height).coerceIn(0f, 1f)
            val fLo = (y(corridor.first.toDouble()) / size.height).coerceIn(0f, 1f)
            fun zones(a: Float) = Brush.verticalGradient(
                0f to ZoneHigh.copy(alpha = a), fHi to ZoneHigh.copy(alpha = a),
                fHi to ZoneIn.copy(alpha = a), fLo to ZoneIn.copy(alpha = a),
                fLo to ZoneLow.copy(alpha = a), 1f to ZoneLow.copy(alpha = a),
                startY = 0f, endY = size.height,
            )
            lineBrush = zones(1f)
            bandBrush = zones(0.18f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            for ((v, c) in listOf(corridor.last to ZoneHigh, corridor.first to ZoneLow)) {
                val yy = y(v.toDouble())
                drawLine(c.copy(alpha = 0.8f), Offset(left, yy), Offset(right, yy), 1.5.dp.toPx(), pathEffect = dash)
            }
        } else {
            lineBrush = SolidColor(colors.primary)
            bandBrush = SolidColor(colors.primary.copy(alpha = 0.18f))
        }
        val line = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (seg in segments) {
            if (seg.size == 1) {
                drawCircle(lineBrush, 2.5.dp.toPx(), Offset(x(seg[0].t), y(seg[0].avg)))
                continue
            }
            if (seg.any { it.hi != it.lo }) {
                val area = Path()
                seg.forEachIndexed { i, p -> if (i == 0) area.moveTo(x(p.t), y(p.hi.toDouble())) else area.lineTo(x(p.t), y(p.hi.toDouble())) }
                seg.asReversed().forEach { p -> area.lineTo(x(p.t), y(p.lo.toDouble())) }
                area.close()
                drawPath(area, bandBrush)
            }
            val path = Path()
            seg.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.t), y(p.avg)) else path.lineTo(x(p.t), y(p.avg)) }
            drawPath(path, lineBrush, style = line)
        }

        // Перекрестие и подсказка
        val tx = touchX
        if (tx != null) {
            val p = points.minBy { abs(x(it.t) - tx) }
            val px = x(p.t)
            val py = y(p.avg)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(px, top), Offset(px, bottom), 1.dp.toPx())
            drawCircle(colors.surface, 6.dp.toPx(), Offset(px, py))
            drawCircle(lineBrush, 4.dp.toPx(), Offset(px, py))
            val range = if (p.lo != p.hi) "  (${p.lo}-${p.hi})" else ""
            val tl = measurer.measure("${tipFmt.format(Date(p.t))}   ${p.avg.roundToInt()} $bpmUnit$range", tipStyle)
            val pad = 6.dp.toPx()
            val w = tl.size.width + pad * 2
            val h = tl.size.height + pad * 2
            val bx = (px - w / 2).coerceIn(left, right - w)
            drawRoundRect(colors.inverseSurface, Offset(bx, top), Size(w, h), CornerRadius(6.dp.toPx()))
            drawText(tl, topLeft = Offset(bx + pad, top + pad))
        }
    }
}

private val ZoneHigh = ZoneColors.High
private val ZoneIn = ZoneColors.In
private val ZoneLow = ZoneColors.Low

private val TICK_STEPS_MS = longArrayOf(
    60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
    3_600_000, 2 * 3_600_000, 3 * 3_600_000, 6 * 3_600_000, 12 * 3_600_000, 24 * 3_600_000,
)

private fun timeTicks(from: Long, to: Long): List<Long> {
    val step = TICK_STEPS_MS.firstOrNull { (to - from) / it <= 5 } ?: TICK_STEPS_MS.last()
    val offset = TimeZone.getDefault().getOffset(from).toLong()
    var t = Math.floorDiv(from + offset + step - 1, step) * step - offset
    val out = ArrayList<Long>()
    while (t <= to) {
        out += t
        t += step
    }
    return out
}

private fun niceStep(range: Int): Int = when {
    range <= 20 -> 5
    range <= 50 -> 10
    range <= 100 -> 20
    else -> 40
}
