package com.puls.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Chart point: t - interval start, lo/hi - spread inside it, avg - the line. */
data class ChartPoint(val t: Long, val lo: Int, val avg: Double, val hi: Int)

/**
 * Time segment [from, to) with its own range (null - no zone coloring)
 * and background color (null - no background): this way the history shows which profile was in effect then.
 */
data class ChartSpan(val from: Long, val to: Long, val corridor: IntRange?, val tint: Color? = null)

/**
 * Heart rate chart: a line of averages and a translucent min-max band.
 * A data gap longer than gapMs breaks the line instead of connecting points across the void.
 * A tap shows the value at the point; after a long press the tooltip can be dragged with a finger.
 */
@Composable
fun HrChart(
    points: List<ChartPoint>,
    from: Long,
    to: Long,
    gapMs: Long,
    modifier: Modifier = Modifier,
    /** Height in dp; null - all the height the parent gives. */
    height: Int? = 220,
    /** Segments with ranges; outside segments the line is one color, without bounds. */
    spans: List<ChartSpan> = emptyList(),
    /**
     * Two-finger zoom: zoom > 1 - spread apart; pan - shift in fractions of the chart width;
     * focus - where between the fingers, as a fraction from the left edge. null - the gesture is not handled.
     */
    onTransform: ((zoom: Float, pan: Float, focus: Float) -> Unit)? = null,
) {
    val transform by rememberUpdatedState(onTransform)
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.onSurfaceVariant)
    val tipStyle = TextStyle(fontSize = 12.sp, color = colors.inverseOnSurface)
    var touchX by remember { mutableStateOf<Float?>(null) }
    val dayLine = dayLineColor()
    val bpmUnit = stringResource(R.string.bpm_unit)

    val span = to - from
    val tipFmt = remember(span) {
        SimpleDateFormat(if (span > 36 * 3600_000L) dayMonthPattern() + " HH:mm" else "HH:mm:ss", Locale.getDefault())
    }

    val visible = spans.filter { it.to > from && it.from < to }
    // Without data the scale is by the ranges or the usual resting heart rate.
    val rawLo = minOf(points.minOfOrNull { it.lo } ?: Int.MAX_VALUE, visible.minOfOrNull { it.corridor?.first ?: Int.MAX_VALUE } ?: Int.MAX_VALUE)
        .takeIf { it != Int.MAX_VALUE } ?: EMPTY_LO
    val rawHi = maxOf(points.maxOfOrNull { it.hi } ?: Int.MIN_VALUE, visible.maxOfOrNull { it.corridor?.last ?: Int.MIN_VALUE } ?: Int.MIN_VALUE)
        .takeIf { it != Int.MIN_VALUE } ?: EMPTY_HI
    val step = niceStep(rawHi - rawLo)
    val yMin = (floor((rawLo - 3) / step.toDouble()) * step).toInt().coerceAtLeast(0)
    val yMax = (ceil((rawHi + 3) / step.toDouble()) * step).toInt()

    // An empty segment is drawn with axes and gestures: otherwise one could neither pan away from it nor zoom out.
    Box(modifier.fillMaxWidth().chartHeight(height), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(points) {
                    // The touch is only read, not consumed, otherwise a swipe over the chart would not reach the pager.
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
                    // Long press, otherwise a regular swipe goes to the tab pager.
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

            // Grid and Y axis labels
            var v = yMin
            while (v <= yMax) {
                val yy = y(v.toDouble())
                drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(left, yy), Offset(right, yy), 1f)
                val tl = measurer.measure(v.toString(), axisStyle)
                drawText(tl, topLeft = Offset(left - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2))
                v += step
            }

            drawTimeAxis(from, to, left, right, bottom, ::x) { measurer.measure(it, axisStyle) }
            drawDayLines(from, to, top, bottom, dayLine, ::x)

            // Continuous segments
            val segments = ArrayList<List<ChartPoint>>()
            var cur = ArrayList<ChartPoint>()
            for (p in points) {
                if (cur.isNotEmpty() && p.t - cur.last().t > gapMs) {
                    segments += cur; cur = ArrayList()
                }
                cur += p
            }
            if (cur.isNotEmpty()) segments += cur

            // Zone colors: above the range red, below yellow, inside green.
            // Hard gradient stops exactly at the bound heights.
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

            // Each segment is drawn within its own X bounds with its own range; outside segments - no zones.
            // A line a couple of dp thick sticks out past the edge by half its width, which is not noticeable.
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

            // Crosshair and tooltip
            val tx = touchX
            if (tx != null && points.isNotEmpty()) {
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
        if (points.isEmpty()) Text(stringResource(R.string.chart_no_data), color = colors.onSurfaceVariant)
    }
}

private val ZoneHigh = ZoneColors.High
private val ZoneIn = ZoneColors.In
private val ZoneLow = ZoneColors.Low

internal const val DAY_MS = 24 * 3_600_000L
private const val EMPTY_LO = 60
private const val EMPTY_HI = 100

/**
 * Color of the new day line: it matches neither the zones (red, yellow, green), nor the
 * mode backgrounds, nor the chart lines (primary, secondary), so the text color is used.
 */
@Composable
private fun dayLineColor() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

/** Local midnights within (from, to]. Days are counted by the calendar: around a clock change they are not 24 h. */
internal fun midnights(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    val out = ArrayList<Long>()
    var d = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().plusDays(1)
    while (true) {
        val t = d.atStartOfDay(zone).toInstant().toEpochMilli()
        if (t > to) break
        out += t
        d = d.plusDays(1)
    }
    return out
}

/** A vertical line at each midnight: on multi-day charts it shows where a new day began. */
private fun DrawScope.drawDayLines(from: Long, to: Long, top: Float, bottom: Float, color: Color, x: (Long) -> Float) {
    // At a week and beyond there are too many lines, and the axis labels there are dates anyway.
    if (to - from > 8 * DAY_MS) return
    for (t in midnights(from, to)) {
        val xx = x(t)
        drawLine(color, Offset(xx, top), Offset(xx, bottom), 1.5.dp.toPx())
    }
}

private fun Modifier.chartHeight(dp: Int?) = if (dp != null) height(dp.dp) else fillMaxHeight()

/**
 * Time zoom and scroll: two fingers - zoom and pan, one finger horizontally -
 * pan. These gestures are consumed, so tabs do not swipe over the chart. Vertical
 * one-finger movement is left alone - it is page scrolling; a long press - the tooltip.
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
                    // Decide by the first noticeable movement: sideways - our scroll, up-down - the page's.
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
 * X axis labels at round marks of local time: a step under a day - time,
 * and midnight is labeled with the date; a step of a day or more - dates.
 */
private fun DrawScope.drawTimeAxis(
    from: Long, to: Long, left: Float, right: Float, bottom: Float,
    x: (Long) -> Float, measure: (String) -> TextLayoutResult,
) {
    val tz = TimeZone.getDefault()
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    val date = SimpleDateFormat(dayMonthPattern(), Locale.getDefault())
    val (step, ticks) = timeTicks(from, to, tz)
    for (t in ticks) {
        val tl = measure(if (step >= DAY_MS || isMidnight(t, tz)) date.format(Date(t)) else hm.format(Date(t)))
        val cx = x(t) - tl.size.width / 2
        if (cx < left || cx + tl.size.width > right) continue
        drawText(tl, topLeft = Offset(cx, bottom + 4.dp.toPx()))
    }
}

/**
 * Speed chart, km/h, under the heart rate chart: the same time scale and the same paddings,
 * so moments line up vertically.
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
    val dayLine = dayLineColor()
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
        drawDayLines(from, to, top, bottom, dayLine, ::x)
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

/** Mark step and the marks themselves: at most 5 per chart, at round values of local time. */
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
