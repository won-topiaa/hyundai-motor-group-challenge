package com.noisetrace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisetrace.core.Condition
import com.noisetrace.core.InspectionHints
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoiseReport
import com.noisetrace.core.Reproduction
import com.noisetrace.core.label
import com.noisetrace.runtime.NoiseTraceController
import com.noisetrace.runtime.UiState
import kotlin.math.roundToInt

/**
 * For the workshop: every recent noise with its recording, the 30 s of driving around it, the frequency content,
 * and for repeated noises what they share and how to bring them back on a test drive.
 */
@Composable
fun MechanicScreen(state: UiState, controller: NoiseTraceController) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("정비사 모드", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "최근 이상 소음 ${state.events.size}건 · 반복 패턴 ${state.patterns.size}개 · 녹음을 듣고 재현 조건을 확인하세요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(onClick = { controller.setMechanicMode(false) }, modifier = Modifier.height(64.dp)) { Text("닫기", fontSize = 22.sp) }
        }
        state.message?.let { Banner(it) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(0.9f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.patterns.forEach { p -> InfoCard(title = "반복 패턴", accent = true) { PatternSummary(p, showGuide = true) } }
                InfoCard(title = "기록 목록") {
                    state.events.forEach { e -> EventRow(e, selected = e.id == state.selected?.id) { controller.select(e.id) } }
                }
            }
            Column(Modifier.weight(1.3f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.selected?.let { EventDetail(it, state, controller) }
            }
        }
    }
}

@Composable
private fun EventRow(e: NoiseEvent, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("#%02d".format(e.number), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f)) {
                Text("${NoiseReport.time(e)} · ${e.type?.label ?: "종류 미확인"}${if (e.userType == null && e.type != null) "(추정)" else ""}", fontSize = 19.sp)
                Text(
                    listOfNotNull(
                        e.context.speedKmh?.let { "${it.roundToInt()} km/h" },
                        e.context.steeringDeg?.let { NoiseReport.steeringText(it) },
                        e.spots.takeIf { it.isNotEmpty() }?.label(),
                        e.trigger.label,
                    ).joinToString(" · "),
                    fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (e.demo) Tag("데모")
        }
    }
}

@Composable
private fun EventDetail(e: NoiseEvent, state: UiState, controller: NoiseTraceController) {
    val playing = state.playback?.takeIf { it.eventId == e.id }
    InfoCard {
        EventReport(e, state.exposure)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (playing == null) {
                Button(onClick = { controller.play(e.id) }, enabled = e.audioFile != null && state.stopped != false, modifier = Modifier.height(64.dp)) {
                    Text("발생 5초 전부터 듣기", fontSize = 22.sp)
                }
                OutlinedButton(onClick = { controller.play(e.id, fromStart = true) }, enabled = e.audioFile != null && state.stopped != false, modifier = Modifier.height(64.dp)) {
                    Text("처음부터 (30초)", fontSize = 22.sp)
                }
            } else {
                Button(onClick = controller::stopPlayback, modifier = Modifier.height(64.dp)) { Text("정지", fontSize = 22.sp) }
                val t = (playing.positionMs - e.noiseOffsetMs) / 1000f
                Text(
                    if (t < 0) "소리까지 ${"%.1f".format(-t)}초" else "소리 발생 후 ${"%.1f".format(t)}초",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        if (playing != null && e.clipDurationMs > 0) {
            LinearProgressIndicator(progress = { playing.positionMs.toFloat() / e.clipDurationMs }, modifier = Modifier.fillMaxWidth())
        }
        if (state.stopped == false) Text("차를 세우면 녹음을 들을 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
    }
    InfoCard(title = "소리 전후 30초") { Timeline(e, playing?.positionMs) }
    InfoCard(title = "주파수 특성") {
        SpectrumBars(e)
        e.spectrum?.estimatedType?.let { Text("소리 모양으로 본 종류: ${it.label} (참고용)", fontSize = 18.sp) }
    }
    InfoCard(title = "기록된 차량 상태") { DetailGrid(e) }
    InfoCard(title = "이 기록 하나로 본 재현 방법") {
        val all = Condition.READING_ORDER.filter { it in e.conditions && it != Condition.MID_SPEED }
        Text(Reproduction.guide(all, Reproduction.rangeAround(e.context.speedKmh)) ?: "특별한 주행 조건 없이 났어요.", style = MaterialTheme.typography.bodyLarge)
        val hints = InspectionHints.forConditions(e.type, e.conditions, e.spots)
        if (hints.isNotEmpty()) {
            Text("먼저 확인해 볼 곳 (일반적인 경향, 진단 아님)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            hints.forEach { Text("· $it", style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
