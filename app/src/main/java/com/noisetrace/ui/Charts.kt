package com.noisetrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisetrace.core.BandChange
import com.noisetrace.core.Channel
import com.noisetrace.core.Condition
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoisePattern
import com.noisetrace.core.TriggerKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A 240° arc gauge with a glowing value sweep and a thumb, the value in the middle (like a climate dial). */
@Composable
fun ArcGauge(value: Float?, max: Float, center: String, caption: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val f = ((value ?: 0f) / max).coerceIn(0f, 1f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.07f
            val inset = stroke * 1.2f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(Color(0x26FFFFFF), 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (value != null) {
                drawArc(
                    Brush.sweepGradient(listOf(Palette.SkyBlue, Palette.Accent, Palette.Accent, Palette.SkyBlue)),
                    150f, 240f * f.coerceAtLeast(0.01f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val ang = Math.toRadians((150 + 240 * f).toDouble())
                val r = arcSize.width / 2
                val c = Offset(this.size.width / 2, this.size.height / 2)
                val thumb = Offset(c.x + (r * cos(ang)).toFloat(), c.y + (r * sin(ang)).toFloat())
                drawCircle(Palette.Accent.copy(alpha = 0.35f), stroke * 1.4f, thumb)
                drawCircle(Color.White, stroke * 0.75f, thumb)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontSize = (size.value / 4.4f).sp, fontWeight = FontWeight.Light, color = Palette.Text)
            Text(caption, fontSize = 15.sp, color = Palette.TextMuted)
        }
    }
}

/** The steering wheel turned by [deg] (negative is left), with the angle under it. */
@Composable
fun SteeringDial(deg: Float?, modifier: Modifier = Modifier, size: Dp = 150.dp) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2 * 0.86f
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Palette.Accent.copy(alpha = 0.16f), Color.Transparent)), r * 1.15f, c)
            rotate((deg ?: 0f).coerceIn(-540f, 540f), c) {
                drawCircle(Palette.Text.copy(alpha = 0.85f), r, c, style = Stroke(r * 0.14f))
                drawCircle(Color(0xFF0C1A2E), r * 0.28f, c)
                listOf(180f, 0f, 90f).forEach { a ->
                    val rad = Math.toRadians(a.toDouble())
                    drawLine(Palette.Text.copy(alpha = 0.75f), c, Offset(c.x + (r * cos(rad)).toFloat(), c.y + (r * sin(rad)).toFloat()), r * 0.12f, cap = StrokeCap.Round)
                }
                drawCircle(Palette.Accent, r * 0.09f, Offset(c.x, c.y - r))
            }
        }
        val d = deg ?: 0f
        Text(if (abs(d) < 3f) "직진" else "${if (d < 0) "좌" else "우"} ${abs(d).roundToInt()}°", fontSize = 22.sp, color = Palette.Text)
        Text("조향", fontSize = 15.sp, color = Palette.TextMuted)
    }
}

/** An on/off indicator like the reference's toggles: label left, a small lit pill right. */
@Composable
fun Indicator(label: String, on: Boolean?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 18.sp, color = Palette.Text, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(46.dp).height(24.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on == true) Palette.Accent.copy(alpha = 0.85f) else Color(0x22FFFFFF)),
            contentAlignment = if (on == true) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape).background(if (on == null) Color(0x33FFFFFF) else Color.White))
        }
    }
}

/** The last seconds of cabin level against usual, as thin bars (0 to 20 dB louder). */
@Composable
fun LevelTrail(values: List<Float>, modifier: Modifier = Modifier, height: Dp = 64.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = 40
        val w = size.width / n
        values.takeLast(n).forEachIndexed { i, v ->
            val f = (v / 20f).coerceIn(0.03f, 1f)
            val h = size.height * f
            drawRoundRect(
                Brush.verticalGradient(listOf(Palette.SkyBlue, Palette.Accent), startY = size.height - h, endY = size.height),
                Offset(i * w + w * 0.2f, size.height - h), Size(w * 0.6f, h),
                androidx.compose.ui.geometry.CornerRadius(w * 0.3f),
            )
        }
    }
}

/**
 * Why a repeated noise is tied to its conditions: for each condition, how often it was there when the noise came
 * (Active Blue bar) against how often it is there in everyday driving (sand tick).
 */
@Composable
fun ConditionBars(p: NoisePattern, modifier: Modifier = Modifier, max: Int = 5) {
    val rows = p.counts.entries.filter { it.key != Condition.MID_SPEED }.sortedByDescending { it.value }.take(max)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { (c, n) ->
            val share = n.toFloat() / p.size
            val usual = p.exposure.fraction(c)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.short, fontSize = 18.sp, color = Palette.Text, modifier = Modifier.width(92.dp))
                Box(Modifier.weight(1f).height(16.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                        drawRoundRect(Color(0x1FFFFFFF), cornerRadius = r)
                        drawRoundRect(Brush.horizontalGradient(listOf(Palette.Accent.copy(alpha = 0.55f), Palette.Accent)), size = Size(size.width * share, size.height), cornerRadius = r)
                        usual?.let { u -> drawLine(Palette.Data, Offset(size.width * u, -4f), Offset(size.width * u, size.height + 4f), 4f, cap = StrokeCap.Round) }
                    }
                }
                Text("$n/${p.size}", fontSize = 17.sp, color = Palette.TextMuted, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(18.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Accent))
            Text("소리 날 때", fontSize = 15.sp, color = Palette.TextMuted)
            Box(Modifier.width(4.dp).height(14.dp).background(Palette.Data))
            Text("평소 주행", fontSize = 15.sp, color = Palette.TextMuted)
        }
    }
}

/**
 * A plain line chart. [marker] draws a vertical line at that index fraction (0..1). A null [height] fills the height
 * the [modifier] gives it (for example a weight in a column).
 */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier, height: Dp? = 70.dp, color: Color = Palette.Accent, marker: Float? = null, fill: Boolean = true, floor: Float? = null) {
    if (values.size < 2) {
        Spacer(if (height != null) modifier.height(height) else modifier)
        return
    }
    val minV = floor ?: values.min()
    val maxV = values.max().let { if (it - minV < 1f) minV + 1f else it }
    Canvas(modifier.fillMaxWidth().let { if (height != null) it.height(height) else it.fillMaxHeight() }) {
        fun x(i: Int) = i / (values.size - 1f) * size.width
        fun y(v: Float) = size.height - (v - minV) / (maxV - minV) * size.height * 0.9f - size.height * 0.05f
        val line = Path()
        values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        if (fill) {
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        }
        drawPath(line, color, style = Stroke(width = 3f))
        marker?.let { m -> drawLine(Palette.Text.copy(alpha = 0.6f), Offset(size.width * m, 0f), Offset(size.width * m, size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))) }
    }
}

/**
 * Speed over the last minute on a km/h grid (every 20 km/h, at least up to 60), so the chart reads as a chart even
 * while parked. A null [height] fills the height the [modifier] gives it.
 */
@Composable
fun SpeedChart(values: List<Float>, modifier: Modifier = Modifier, height: Dp? = 120.dp, slots: Int = 120) {
    val top = maxOf(60f, ((values.maxOrNull() ?: 0f) / 20f).let { kotlin.math.ceil(it) * 20f })
    BoxWithConstraints(modifier.fillMaxWidth().let { if (height != null) it.height(height) else it }) {
    // Too short for a readable axis (a question card is taking the room): just the line.
    val axis = maxHeight >= 90.dp
    Row(Modifier.fillMaxSize()) {
        if (axis) Box(Modifier.fillMaxHeight().width(36.dp)) {
            var v = 0f
            while (v <= top) {
                val f = 1f - v / top
                Text(
                    "${v.toInt()}", fontSize = 12.sp, color = Palette.TextMuted,
                    modifier = Modifier.align(Alignment.TopStart).layout { m, c ->
                        val p = m.measure(c)
                        layout(p.width, p.height) { p.place(0, ((c.maxHeight.takeIf { it != Constraints.Infinity } ?: 0) * f - p.height / 2f).toInt()) }
                    },
                )
                v += 20f
            }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            var g = if (axis) 0f else top + 1f
            while (g <= top) {
                val y = size.height * (1f - g / top)
                drawLine(Color(0x1FFFFFFF), Offset(0f, y), Offset(size.width, y), 1.5f)
                g += 20f
            }
            if (values.size < 2) return@Canvas
            fun x(i: Int) = size.width - (values.size - 1 - i) * size.width / (slots - 1)
            fun y(v: Float) = size.height * (1f - (v / top).coerceIn(0f, 1f))
            val line = Path()
            values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(x(0), size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(Palette.Data.copy(alpha = 0.30f), Color.Transparent)))
            drawPath(line, Palette.Data, style = Stroke(width = 3f))
            drawCircle(Palette.Data, 6f, Offset(x(values.lastIndex), y(values.last())))
        }
    }
    }
}

/** Saves per day for the last [days] days, automatic (sand) under button saves (Active Blue). */
@Composable
fun DailyBars(events: List<NoiseEvent>, nowMs: Long, modifier: Modifier = Modifier, days: Int = 14, height: Dp? = 120.dp) {
    val day = 86_400_000L
    val zone = java.util.TimeZone.getDefault()
    val today = nowMs - (nowMs + zone.getOffset(nowMs)) % day
    val bins = (0 until days).map { d ->
        val from = today - (days - 1 - d) * day
        val inDay = events.filter { it.noiseAtMs in from until from + day }
        inDay.count { it.trigger == TriggerKind.AUTO } to inDay.count { it.trigger == TriggerKind.MANUAL }
    }
    val top = maxOf(2, bins.maxOf { it.first + it.second })
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().let { if (height != null) it.height(height) else it.weight(1f) }) {
            val w = size.width / days
            bins.forEachIndexed { i, (a, m) ->
                val ha = size.height * a / top
                val hm = size.height * m / top
                val x = i * w + w * 0.22f
                val bw = w * 0.56f
                val r = androidx.compose.ui.geometry.CornerRadius(6f)
                drawRoundRect(Color(0x14FFFFFF), Offset(x, 0f), Size(bw, size.height), r)
                if (a > 0) drawRoundRect(Palette.Data, Offset(x, size.height - ha), Size(bw, ha), r)
                if (m > 0) drawRoundRect(Brush.verticalGradient(listOf(Palette.Accent, Palette.Accent.copy(alpha = 0.6f))), Offset(x, size.height - ha - hm), Size(bw, hm), r)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("${days}일 전", Modifier.weight(1f), fontSize = 14.sp, color = Palette.TextMuted)
            Text("오늘", Modifier.weight(1f), fontSize = 14.sp, color = Palette.TextMuted, textAlign = TextAlign.End)
        }
    }
}

/**
 * How the usual cabin level moved at each speed band against the previous 30 days: low-pitched (Active Blue) and
 * high-pitched (sand) bars up or down from zero, ±5 dB full scale.
 */
@Composable
fun BandDeltaChart(changes: List<BandChange>, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val labels = listOf("정차", "~20", "~40", "~60", "~90", "90+")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val mid = size.height / 2
            drawLine(Color(0x44FFFFFF), Offset(0f, mid), Offset(size.width, mid), 2f)
            val w = size.width / labels.size
            for (b in labels.indices) {
                listOf(Channel.FULL to Palette.Accent, Channel.HIGH to Palette.Data).forEachIndexed { k, (ch, color) ->
                    val d = changes.firstOrNull { it.band == b && it.channel == ch }?.deltaDb ?: return@forEachIndexed
                    val h = (d / 5f).coerceIn(-1f, 1f) * mid * 0.95f
                    val x = b * w + w * (0.22f + 0.3f * k)
                    val top = if (h >= 0) mid - h else mid
                    drawRoundRect(color.copy(alpha = if (abs(d) >= 2f) 1f else 0.4f), Offset(x, top), Size(w * 0.24f, abs(h).coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(5f))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), fontSize = 14.sp, color = Palette.TextMuted, textAlign = TextAlign.Center) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Accent))
            Text("낮은 소리", fontSize = 15.sp, color = Palette.TextMuted)
            Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Data))
            Text("높은 소리", fontSize = 15.sp, color = Palette.TextMuted)
            Spacer(Modifier.weight(1f))
            Text("km/h · 지난 30일 대비 dB", fontSize = 14.sp, color = Palette.TextMuted)
        }
    }
}

/** A value tile for event cards: big number, small caption. */
@Composable
fun Tile(value: String, caption: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, fontSize = 22.sp, color = if (accent) Palette.Accent else Palette.Text, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
        Text(caption, fontSize = 14.sp, color = Palette.TextMuted, maxLines = 1)
    }
}

/** Fills the remaining height of a row; used to align cards of different content. */
@Composable
fun FillSpacer() = Spacer(Modifier.fillMaxHeight())
