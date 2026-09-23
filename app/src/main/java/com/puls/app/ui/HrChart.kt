package com.puls.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.drawscope.clipRect
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
 * Участок времени [from, to) со своим коридором (null - без раскраски по зонам)
 * и цветом фона (null - без фона): так на истории видно, какой профиль тогда действовал.
 */
data class ChartSpan(val from: Long, val to: Long, val corridor: IntRange?, val tint: Color? = null)

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
    /** Высота в dp; null - вся высота, которую отвёл родитель. */
    height: Int? = 220,
    /** Участки с коридорами; вне участков линия одного цвета, без границ. */
    spans: List<ChartSpan> = emptyList(),
    /**
     * Масштаб двумя пальцами: zoom > 1 - раздвинули; pan - сдвиг в долях ширины графика;
     * focus - где между пальцами, доля от левого края. null - жест не ловим.
     */
    onTransform: ((zoom: Float, pan: Float, focus: Float) -> Unit)? = null,
) {
    val transform by rememberUpdatedState(onTransform)
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 12.sp, color = colors.inverseOnSurface)
    var touchX by remember { mutableStateOf<Float?>(null) }
    val bpmUnit = stringResource(R.string.bpm_unit)

    if (points.isEmpty()) {
        Box(modifier.fillMaxWidth().chartHeight(height), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.chart_no_data), color = colors.onSurfaceVariant)
        }
        return
    }

    val span = to - from
    val tipFmt = remember(span) {
        SimpleDateFormat(if (span > 36 * 3600_000L) "dd.MM HH:mm" else "HH:mm:ss", Locale.getDefault())
    }

    val visible = spans.filter { it.to > from && it.from < to }
    val rawLo = minOf(points.minOf { it.lo }, visible.minOfOrNull { it.corridor?.first ?: Int.MAX_VALUE } ?: Int.MAX_VALUE)
    val rawHi = maxOf(points.maxOf { it.hi }, visible.maxOfOrNull { it.corridor?.last ?: Int.MIN_VALUE } ?: Int.MIN_VALUE)
    val step = niceStep(rawHi - rawLo)
    val yMin = (floor((rawLo - 3) / step.toDouble()) * step).toInt().coerceAtLeast(0)
    val yMax = (ceil((rawHi + 3) / step.toDouble()) * step).toInt()

    Canvas(
        modifier.fillMaxWidth().chartHeight(height)
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
            .pinch { transform }
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
        val left = CHART_LEFT.toPx()
        val bottom = size.height - 20.dp.toPx()
        val top = 8.dp.toPx()
        val right = size.width - CHART_RIGHT.toPx()
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

        drawTimeAxis(from, to, left, right, bottom, ::x) { measurer.measure(it, axisStyle) }

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
        fun zones(corridor: IntRange, a: Float): Brush {
            val fHi = (y(corridor.last.toDouble()) / size.height).coerceIn(0f, 1f)
            val fLo = (y(corridor.first.toDouble()) / size.height).coerceIn(0f, 1f)
            return Brush.verticalGradient(
                0f to ZoneHigh.copy(alpha = a), fHi to ZoneHigh.copy(alpha = a),
                fHi to ZoneIn.copy(alpha = a), fLo to ZoneIn.copy(alpha = a),
                fLo to ZoneLow.copy(alpha = a), 1f to ZoneLow.copy(alpha = a),
                startY = 0f, endY = size.height,
            )
        }
        val plainLine = SolidColor(colors.primary)
        val plainBand = SolidColor(colors.primary.copy(alpha = 0.18f))
        val line = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))

        fun drawSegments(lineBrush: Brush, bandBrush: Brush) {
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
        }

        // Каждый участок рисуем в своих границах по X со своим коридором; вне участков - без зон.
        // Линия толщиной в пару dp вылезает за край на полтолщины, это незаметно.
        var cursor = left
        val brushes = ArrayList<Pair<ChartSpan, Brush>>()
        for (sp in visible.sortedBy { it.from }) {
            val x0 = x(sp.from).coerceIn(left, right)
            val x1 = x(sp.to).coerceIn(left, right)
            if (x0 > cursor) clipRect(cursor, 0f, x0, size.height) { drawSegments(plainLine, plainBand) }
            cursor = maxOf(cursor, x1)
            if (x1 <= x0) continue
            clipRect(x0, 0f, x1, size.height) {
                sp.tint?.let { drawRect(it.copy(alpha = 0.22f), Offset(x0, top), Size(x1 - x0, bottom - top)) }
                val c = sp.corridor
                if (c == null) {
                    drawSegments(plainLine, plainBand)
                } else {
                    for ((v, col) in listOf(c.last to ZoneHigh, c.first to ZoneLow)) {
                        val yy = y(v.toDouble())
                        drawLine(col.copy(alpha = 0.8f), Offset(x0, yy), Offset(x1, yy), 1.5.dp.toPx(), pathEffect = dash)
                    }
                    val lb = zones(c, 1f)
                    brushes += sp to lb
                    drawSegments(lb, zones(c, 0.18f))
                }
            }
        }
        if (cursor < right) clipRect(cursor, 0f, right, size.height) { drawSegments(plainLine, plainBand) }

        // Перекрестие и подсказка
        val tx = touchX
        if (tx != null) {
            val p = points.minBy { abs(x(it.t) - tx) }
            val px = x(p.t)
            val py = y(p.avg)
            drawLine(colors.onSurfaceVariant.copy(alpha = 0.6f), Offset(px, top), Offset(px, bottom), 1.dp.toPx())
            drawCircle(colors.surface, 6.dp.toPx(), Offset(px, py))
            val pointBrush = brushes.firstOrNull { (sp, _) -> p.t >= sp.from && p.t < sp.to }?.second ?: plainLine
            drawCircle(pointBrush, 4.dp.toPx(), Offset(px, py))
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

internal const val DAY_MS = 24 * 3_600_000L

private fun Modifier.chartHeight(dp: Int?) = if (dp != null) height(dp.dp) else fillMaxHeight()

/**
 * Масштаб и прокрутка времени: два пальца - масштаб и сдвиг, один палец по горизонтали -
 * сдвиг. Такие жесты поглощаются, поэтому вкладки по графику не листаются. Вертикальное
 * движение одним пальцем не трогаем - это прокрутка страницы; долгое нажатие - подсказка.
 */
private fun Modifier.pinch(transform: () -> ((Float, Float, Float) -> Unit)?) = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var total = Offset.Zero
        var panning = false
        do {
            val event = awaitPointerEvent()
            val cb = transform() ?: continue
            val l = CHART_LEFT.toPx()
            val w = size.width - CHART_RIGHT.toPx() - l
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                val c = event.calculateCentroid(useCurrent = true)
                cb(event.calculateZoom(), event.calculatePan().x / w, ((c.x - l) / w).coerceIn(0f, 1f))
                event.changes.forEach { it.consume() }
                panning = true
            } else if (pressed == 1) {
                val ch = event.changes.firstOrNull { it.id == down.id } ?: continue
                if (ch.isConsumed && !panning) break
                val d = ch.position - ch.previousPosition
                if (!panning) {
                    total += d
                    // Решаем по первому заметному смещению: вбок - наша прокрутка, вверх-вниз - страницы.
                    if (total.getDistance() < viewConfiguration.touchSlop) continue
                    if (abs(total.x) <= abs(total.y)) break
                    panning = true
                }
                cb(1f, d.x / w, 0.5f)
                ch.consume()
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Подписи по оси X на круглых отметках местного времени: шаг меньше суток - время,
 * а полночь подписываем датой; шаг от суток - даты.
 */
private fun DrawScope.drawTimeAxis(
    from: Long, to: Long, left: Float, right: Float, bottom: Float,
    x: (Long) -> Float, measure: (String) -> TextLayoutResult,
) {
    val tz = TimeZone.getDefault()
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    val date = SimpleDateFormat("dd.MM", Locale.getDefault())
    val (step, ticks) = timeTicks(from, to, tz)
    for (t in ticks) {
        val tl = measure(if (step >= DAY_MS || isMidnight(t, tz)) date.format(Date(t)) else hm.format(Date(t)))
        val cx = x(t) - tl.size.width / 2
        if (cx < left || cx + tl.size.width > right) continue
        drawText(tl, topLeft = Offset(cx, bottom + 4.dp.toPx()))
    }
}

/**
 * График скорости, км/ч, под графиком пульса: та же шкала времени и те же отступы,
 * чтобы моменты совпадали по вертикали.
 */
@Composable
fun SpeedChart(
    points: List<Pair<Long, Double>>,
    from: Long,
    to: Long,
    gapMs: Long,
    modifier: Modifier = Modifier,
    height: Int? = 120,
    onTransform: ((zoom: Float, pan: Float, focus: Float) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val transform by rememberUpdatedState(onTransform)
    if (points.isEmpty()) return
    val maxV = points.maxOf { it.second }
    val step = when {
        maxV <= 6 -> 2
        maxV <= 15 -> 5
        else -> 10
    }
    val yMax = (ceil(maxOf(maxV, 1.0) / step) * step).toInt()
    val lineColor = colors.secondary
    Canvas(modifier.fillMaxWidth().chartHeight(height).pinch { transform }) {
        val left = CHART_LEFT.toPx()
        val bottom = size.height - 20.dp.toPx()
        val top = 8.dp.toPx()
        val right = size.width - CHART_RIGHT.toPx()
        val span = (to - from).toFloat()
        fun x(t: Long) = left + (t - from) / span * (right - left)
        fun y(v: Double) = bottom - (v / yMax).toFloat() * (bottom - top)
        var v = 0
        while (v <= yMax) {
            val yy = y(v.toDouble())
            drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(left, yy), Offset(right, yy), 1f)
            val tl = measurer.measure(v.toString(), axisStyle)
            drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2))
            v += step
        }
        drawTimeAxis(from, to, left, right, bottom, ::x) { measurer.measure(it, axisStyle) }
        clipRect(left, 0f, right, size.height) {
            var seg = ArrayList<Pair<Long, Double>>()
            fun flushSeg() {
                if (seg.isEmpty()) return
                val path = Path()
                val area = Path()
                seg.forEachIndexed { i, (t, s) ->
                    if (i == 0) {
                        path.moveTo(x(t), y(s))
                        area.moveTo(x(t), bottom)
                    } else {
                        path.lineTo(x(t), y(s))
                    }
                    area.lineTo(x(t), y(s))
                }
                area.lineTo(x(seg.last().first), bottom)
                area.close()
                drawPath(area, lineColor.copy(alpha = 0.18f))
                if (seg.size == 1) drawCircle(lineColor, 2.5.dp.toPx(), Offset(x(seg[0].first), y(seg[0].second)))
                else drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                seg = ArrayList()
            }
            for (p in points) {
                if (seg.isNotEmpty() && p.first - seg.last().first > gapMs) flushSeg()
                seg += p
            }
            flushSeg()
        }
    }
}

private val TICK_STEPS_MS = longArrayOf(
    60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
    3_600_000, 2 * 3_600_000, 3 * 3_600_000, 6 * 3_600_000, 12 * 3_600_000, DAY_MS, 2 * DAY_MS, 7 * DAY_MS,
)

private val CHART_LEFT = 36.dp
private val CHART_RIGHT = 4.dp

internal fun isMidnight(t: Long, tz: TimeZone): Boolean = Math.floorMod(t + tz.getOffset(t), DAY_MS) == 0L

/** Шаг меток и сами метки: не больше 5 на график, на круглых значениях местного времени. */
internal fun timeTicks(from: Long, to: Long, tz: TimeZone = TimeZone.getDefault()): Pair<Long, List<Long>> {
    val step = TICK_STEPS_MS.firstOrNull { (to - from) / it <= 5 } ?: TICK_STEPS_MS.last()
    val offset = tz.getOffset(from).toLong()
    var t = Math.floorDiv(from + offset + step - 1, step) * step - offset
    val out = ArrayList<Long>()
    while (t <= to) {
        out += t
        t += step
    }
    return step to out
}

private fun niceStep(range: Int): Int = when {
    range <= 20 -> 5
    range <= 50 -> 10
    range <= 100 -> 20
    else -> 40
}
