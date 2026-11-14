package com.noisetrace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisetrace.core.DrivingExposure
import com.noisetrace.core.MonthlyReport
import com.noisetrace.core.MonthlyReports
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoisePattern
import com.noisetrace.core.NoiseReport
import com.noisetrace.core.OCTAVE_CENTERS_HZ
import com.noisetrace.core.TriggerKind
import com.noisetrace.core.bandLabel
import com.noisetrace.core.label
import kotlin.math.abs
import kotlin.math.roundToInt

/** A section panel; [accent] gives it the Active Blue glow used for what matters most on the screen. */
@Composable
fun InfoCard(title: String? = null, modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) =
    GlassCard(title, modifier, glow = if (accent) Palette.Accent else null, content = content)

@Composable
fun Banner(text: String, color: Color = Palette.Accent) {
    GlassCard(glow = color) { Text(text, color = Palette.Text, fontSize = 20.sp) }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = color, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun Chip(text: String) {
    AssistChip(onClick = {}, label = { Text(text, fontSize = 18.sp) })
}

/** The noise report as the planning example shows it: number, time, what was heard, the car, and the estimate. */
@Composable
fun EventReport(e: NoiseEvent, exposure: DrivingExposure, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(NoiseReport.title(e), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Tag(e.trigger.label, if (e.trigger == TriggerKind.AUTO) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
        if (!e.confirmed) Tag("확인 필요", MaterialTheme.colorScheme.errorContainer)
        if (!e.hasAudio) Tag("녹음 없음")
        if (e.demo) Tag("데모")
    }
    Text(NoiseReport.time(e), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    Text(NoiseReport.headline(e), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    val lines = NoiseReport.summaryLines(e)
    (if (compact) lines.take(4) else lines).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (e.spots.isNotEmpty() || e.userType != null) {
        Text("들린 곳: ${e.spots.label()}${e.userType?.let { " · ${it.label}" } ?: ""}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("재현 조건 추정", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("→ ${NoiseReport.estimate(e, exposure)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatternSummary(p: NoisePattern, showGuide: Boolean) {
    fun day(e: NoiseEvent) = NoiseReport.time(e).substringBeforeLast(' ')
    val span = if (day(p.events.last()) == day(p.events.first())) day(p.events.first()) else "${day(p.events.last())} ~ ${day(p.events.first())}"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${p.type?.label ?: "종류 미확인"} 소리 · ${p.size}번 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (p.spots.isNotEmpty()) Tag(p.spots.label())
    }
    Text(span, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
    Text(p.summary, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    ConditionBars(p, max = 4)
    if (showGuide) {
        p.reproduceGuide?.let {
            Text("재현 주행 방법", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
        val hints = p.inspection
        if (hints.isNotEmpty()) {
            Text("먼저 볼 곳 (참고)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            hints.take(2).forEach { Text("· $it", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

/**
 * The 30 s around the noise, stacked: how much louder than usual the cabin was, speed and steering, with the noise
 * moment (amber), the button press (dashed) and the playback position (white).
 */
@Composable
fun Timeline(e: NoiseEvent, playbackMs: Long?) {
    val startS = -e.noiseOffsetMs / 1000f
    val endS = startS + e.clipDurationMs / 1000f
    val pressS = if (e.trigger == TriggerKind.MANUAL) (e.savedAtMs - e.noiseAtMs) / 1000f else null
    val playS = playbackMs?.let { startS + it / 1000f }
    val excess = e.excessTimeline.mapIndexed { i, v -> (startS + (i + 1) * 0.1f) to v }
    val speeds = e.trace.mapNotNull { p -> p.speedKmh?.let { p.tMs / 1000f to it } }
    val steer = e.trace.mapNotNull { p -> p.steeringDeg?.let { p.tMs / 1000f to it } }
    val amber = MaterialTheme.colorScheme.primary
    val teal = MaterialTheme.colorScheme.secondary
    val blue = MaterialTheme.colorScheme.tertiary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (excess.isNotEmpty()) ChartRow("소리 (평소 대비 dB)", excess, startS, endS, 0f, maxOf(20f, (excess.maxOfOrNull { it.second } ?: 0f) + 2), amber, pressS, playS)
        ChartRow("속도 (km/h) ${speeds.minByOrNull { abs(it.first) }?.second?.roundToInt() ?: "-"}", speeds, startS, endS, 0f, maxOf(30f, (speeds.maxOfOrNull { it.second } ?: 0f) + 5), teal, pressS, playS)
        val maxSteer = maxOf(30f, steer.maxOfOrNull { abs(it.second) } ?: 0f)
        ChartRow("조향각 (°, 위쪽이 오른쪽)", steer, startS, endS, -maxSteer, maxSteer, blue, pressS, playS, zeroLine = true)
        Row(Modifier.fillMaxWidth()) {
            Text("${startS.roundToInt()}초", Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (e.vehicleOnly) "버튼 2초 전" else "소리 발생", Modifier.weight(1f), fontSize = 16.sp, color = amber, textAlign = TextAlign.Center)
            Text("+${endS.roundToInt()}초", Modifier.weight(1f), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun ChartRow(
    label: String, points: List<Pair<Float, Float>>, startS: Float, endS: Float, minY: Float, maxY: Float, color: Color,
    pressS: Float?, playS: Float?, zeroLine: Boolean = false,
) {
    val grid = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.primary
    val play = MaterialTheme.colorScheme.onSurface
    Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(70.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
    ) {
        fun x(t: Float) = (t - startS) / (endS - startS) * size.width
        fun y(v: Float) = size.height - (v - minY) / (maxY - minY) * size.height
        if (zeroLine) drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), 1f)
        if (points.size >= 2) {
            val path = Path()
            points.forEachIndexed { i, (t, v) ->
                val px = x(t)
                val py = y(v.coerceIn(minY, maxY))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = Stroke(width = 3f))
        }
        drawLine(marker, Offset(x(0f), 0f), Offset(x(0f), size.height), 4f)
        pressS?.let { drawLine(marker, Offset(x(it), 0f), Offset(x(it), size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))) }
        playS?.let { drawLine(play, Offset(x(it), 0f), Offset(x(it), size.height), 2f) }
    }
}

/** Octave bands: how much each rose above the quiet part of the same clip. The band that rose most is amber. */
@Composable
fun SpectrumBars(e: NoiseEvent) {
    val s = e.spectrum ?: return Text(if (e.vehicleOnly) "녹음 없이 저장한 기록이라 주파수 분석이 없어요." else "주파수 분석 없음", color = MaterialTheme.colorScheme.onSurfaceVariant)
    val delta = s.deltaDb
    val maxDb = maxOf(20f, delta.maxOrNull() ?: 0f)
    val amber = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        delta.forEachIndexed { i, d ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (d >= 1f) "+${d.roundToInt()}" else "0", fontSize = 14.sp, color = if (i == s.risingBand) amber else MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    Modifier
                        .width(28.dp)
                        .height((80 * (d.coerceAtLeast(0f) / maxDb)).coerceAtLeast(2f).dp)
                        .background(if (i == s.risingBand) amber else muted, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OCTAVE_CENTERS_HZ.forEach { Text(bandLabel(it), Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Text("평소(같은 녹음의 조용한 구간)보다 커진 정도, Hz 대역별 dB", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailGrid(e: NoiseEvent) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        NoiseReport.detailLines(e).forEach { (k, v) ->
            Row(Modifier.weight(1f)) {
                Text(k, Modifier.width(150.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                Text(v, fontSize = 18.sp)
            }
        }
    }
}

/**
 * The monthly report: saves in the last 30 days, the main repeated noise and how long it has stayed away, and the
 * speed bands whose usual sound moved by 2 dB or more against the 30 days before, with the trend of the biggest one.
 */
@Composable
fun MonthlyContent(r: MonthlyReport) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        BigStat("${r.events}건", "최근 30일 (자동 ${r.autoEvents} · 직접 ${r.manualEvents})", size = 40)
        r.topPattern?.let { p -> BigStat("${p.size}번", "가장 많은 소리 · ${p.type?.label ?: "미확인"}", size = 40) }
        r.daysSinceTop?.takeIf { it >= 3 }?.let { d -> BigStat("${d}일", "반복 소리 이후 조용", size = 40, color = MaterialTheme.colorScheme.secondary) }
    }
    Text("속도별 평소 소리 · 지난 30일 대비", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
    when {
        !r.hasTrend -> Text("두 달 가까이 배운 뒤 보여요", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        r.notable.isEmpty() -> Text("2 dB 넘는 변화 없음", fontSize = 18.sp)
        else -> {
            r.notable.take(2).forEach { c ->
                Text(
                    "${c.bandLabel} ${c.channelLabel} ${if (c.deltaDb > 0) "+" else ""}${"%.1f".format(c.deltaDb)} dB ${if (c.deltaDb > 0) "↑" else "↓"}",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = if (c.deltaDb > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
                Text(c.hint, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TrendSpark(r.notable.first())
        }
    }
}

/** Daily usual level of one band over the last 60 days; the line in the middle splits the two 30-day periods. */
@Composable
private fun TrendSpark(c: com.noisetrace.core.BandChange) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val pts = c.series
    if (pts.size < 2) return
    val minV = pts.minOf { it.second } - 1
    val maxV = pts.maxOf { it.second } + 1
    Canvas(Modifier.fillMaxWidth().height(70.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
        val span = (MonthlyReports.PERIOD_DAYS * 2 - 1).toFloat()
        fun x(ago: Int) = (span - ago) / span * size.width
        fun y(v: Float) = size.height - (v - minV) / (maxV - minV) * size.height
        drawLine(grid, Offset(x(MonthlyReports.PERIOD_DAYS), 0f), Offset(x(MonthlyReports.PERIOD_DAYS), size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val path = Path()
        pts.forEachIndexed { i, (ago, v) -> if (i == 0) path.moveTo(x(ago), y(v)) else path.lineTo(x(ago), y(v)) }
        drawPath(path, line, style = Stroke(width = 3f))
    }
    Row(Modifier.fillMaxWidth()) {
        Text("60일 전", Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("30일 전", Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text("오늘", Modifier.weight(1f), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}
