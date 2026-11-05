package com.calmride.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmride.core.Gear
import com.calmride.core.RegenState
import com.calmride.core.SeatSide
import com.calmride.core.TurnSignal
import com.calmride.core.label
import com.calmride.runtime.CalmRideController
import com.calmride.runtime.UiState
import kotlin.math.roundToInt

@Composable
fun CalmRideScreen(state: UiState, controller: CalmRideController) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > 900.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Header(state, controller)
                if (state.drivingRestricted) {
                    Banner("주행 중에는 화면 대신 소리와 음성으로만 안내해요.")
                }
                if (state.suggestMode) SuggestionCard(controller)
                state.pendingTrip?.let { ComfortCard(controller) }
                state.coaching?.let { tip ->
                    InfoCard(title = "맞춤 코칭 · ${tip.title}") {
                        Text(tip.message, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = controller::dismissCoaching) { Text("확인", fontSize = 18.sp) }
                    }
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ModeCard(state, controller)
                            PrimingCard(state, controller)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            LiveCard(state)
                            DriverCard(state, controller)
                            DemoCard(state, controller)
                        }
                    }
                } else {
                    ModeCard(state, controller)
                    PrimingCard(state, controller)
                    LiveCard(state)
                    DriverCard(state, controller)
                    DemoCard(state, controller)
                }
                SourcesCard(state)
            }
        }
    }
}

@Composable
private fun Header(state: UiState, controller: CalmRideController) {
    var menuOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Calm Ride", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("전기차 멀미를 소리로 줄여요", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = controller::listenVoice, modifier = Modifier.height(56.dp)) { Text("음성 명령", fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.height(56.dp)) {
                Text(state.activeProfile?.name ?: "운전자", fontSize = 18.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                state.profiles.forEach { p ->
                    DropdownMenuItem(text = { Text(p.name, fontSize = 18.sp) }, onClick = {
                        controller.selectProfile(p.id)
                        menuOpen = false
                    })
                }
                DropdownMenuItem(text = { Text("+ 운전자 추가", fontSize = 18.sp) }, onClick = {
                    menuOpen = false
                    addOpen = true
                })
            }
        }
    }
    if (addOpen) AddDriverDialog(onDismiss = { addOpen = false }) { name ->
        controller.addProfile(name)
        addOpen = false
    }
}

@Composable
private fun AddDriverDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("운전자 추가") },
        text = {
            Column {
                Text("지금 시트 위치를 저장해서, 다음부터는 자동으로 알아봐요.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onAdd(name) }) { Text("추가") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun ModeCard(state: UiState, controller: CalmRideController) {
    InfoCard(title = "멀미 모드") {
        Text(
            if (state.modeOn) "켜짐 · 회전과 감속 전에 소리로 알려 드려요. 환기도 함께 조절해요."
            else "꺼짐 · 켜면 예고음과 환기가 시작돼요.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = { controller.setMode(!state.modeOn) },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = if (state.modeOn) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)
            else ButtonDefaults.buttonColors(),
        ) { Text(if (state.modeOn) "멀미 모드 끄기" else "멀미 모드 켜기", fontSize = 22.sp) }
    }
}

@Composable
private fun PrimingCard(state: UiState, controller: CalmRideController) {
    InfoCard(title = "출발 전 1분 사운드 (100Hz)") {
        val left = state.primingSecondsLeft
        if (left != null) {
            Text("남은 시간 ${left}초", style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress = { (60 - left) / 60f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = controller::stopPriming, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("중지", fontSize = 20.sp) }
        } else {
            val allowed = state.stopped == true || state.stopped == null
            Text(
                when (state.stopped) {
                    true -> "정차 중이에요. 낮은 소리를 1분 들으면 평형감각이 준비돼요."
                    false -> "안전을 위해 차가 멈춘 상태에서만 재생돼요."
                    null -> "차량 신호가 없어요. 데모 환경에서는 바로 재생할 수 있어요."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = controller::startPriming, enabled = allowed, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("1분 사운드 시작", fontSize = 20.sp)
            }
        }
        // Gu et al. (2025): the tone helped when both ears heard the same level, so the far side plays a bit louder.
        val seat = when (state.seat) {
            SeatSide.LEFT -> "뒷좌석 왼쪽 · 오른쪽 스피커 +${state.balanceBoostDb.roundToInt()}dB"
            SeatSide.RIGHT -> "뒷좌석 오른쪽 · 왼쪽 스피커 +${state.balanceBoostDb.roundToInt()}dB"
            SeatSide.CENTER -> "가운데 · 좌우 같은 크기"
        }
        Text("좌우 보정: $seat${if (state.seatOverride == null) " (자동)" else ""}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::cycleSeat) { Text("자리 바꾸기") }
            OutlinedButton(onClick = { controller.adjustBalance(-1f) }) { Text("보정 −1dB") }
            OutlinedButton(onClick = { controller.adjustBalance(1f) }) { Text("보정 +1dB") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("신호 대기 때 다시 듣기 (택시)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = state.replayAtStops, onCheckedChange = controller::setReplayAtStops)
        }
        if (state.replayAtStops && state.tripActive) {
            Text(
                if (state.primedThisRide) "이번 주행에서 1분을 다 들었어요." else "3초 넘게 멈추면 재생하고, 출발하면 서서히 꺼요. 끊기면 다음 정차 때 처음부터 다시 들어요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCard(state: UiState) {
    val v = state.vehicle
    InfoCard(title = "지금 상태") {
        val next = state.nextEvent
        Text(
            if (next != null) "다음 안내: ${next.kind.label()} ${next.distanceM.roundToInt()}m" else "다음 안내: 경로 안내 없음",
            style = MaterialTheme.typography.titleLarge,
        )
        state.lastCue?.let { Text("최근 알림음: ${it.kind.label()} (${it.reason})", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("차속 ${v.speedMps?.let { (it * 3.6f).roundToInt().toString() + " km/h" } ?: "-"}")
            Chip("기어 ${gearLabel(v.gear)}")
            Chip(if (v.brakePressed) "브레이크 밟음" else "브레이크 해제")
            Chip(
                when (v.turnSignal) {
                    TurnSignal.LEFT -> "왼쪽 깜빡이"
                    TurnSignal.RIGHT -> "오른쪽 깜빡이"
                    TurnSignal.OFF -> "깜빡이 꺼짐"
                },
            )
            Chip(if (v.rearSeatOccupied) "뒷좌석 탑승" else "뒷좌석 빔")
            Chip("회생제동 ${regenLabel(v.regenState, v.regenLevel)}")
        }
    }
}

@Composable
private fun DriverCard(state: UiState, controller: CalmRideController) {
    val p = state.activeProfile ?: return
    InfoCard(title = "운전자 맞춤 · ${p.name}") {
        Text("운전 스타일: ${p.style.label}", style = MaterialTheme.typography.bodyLarge)
        Text("예고 시점: 도달 ${"%.1f".format(p.leadTimeSec)}초 전 · 기록한 주행 ${p.trips.size}회", style = MaterialTheme.typography.bodyLarge)
        Text("예고음 크기", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = p.cueVolume, onValueChange = controller::setVolume, valueRange = 0.2f..1f)
        OutlinedButton(onClick = { controller.rememberSeatFor(p.id) }) { Text("지금 시트 위치로 나를 기억하기") }
    }
}

@Composable
private fun DemoCard(state: UiState, controller: CalmRideController) {
    InfoCard(title = "데모 주행") {
        Text("차량 없이 70초짜리 주행(좌회전, 방지턱, 차선 변경, 단속 구간, 도착)을 재생해 예고음을 확인해요.", style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(
            onClick = {
                if (!state.modeOn && !state.demoRunning) controller.setMode(true)
                controller.toggleDemo()
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(if (state.demoRunning) "데모 중지" else "데모 주행 시작", fontSize = 18.sp) }
    }
}

@Composable
private fun SuggestionCard(controller: CalmRideController) {
    InfoCard(title = "뒷좌석에 탑승했어요") {
        Text("멀미 모드를 켤까요?", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.setMode(true) }, modifier = Modifier.height(56.dp)) { Text("켜기", fontSize = 18.sp) }
            TextButton(onClick = controller::dismissSuggestion) { Text("괜찮아요", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun ComfortCard(controller: CalmRideController) {
    InfoCard(title = "동승자 컨디션은 어땠나요?") {
        Text("1점(많이 울렁거림) ~ 5점(아주 편안함)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { score ->
                Button(onClick = { controller.submitComfort(score) }, modifier = Modifier.weight(1f).height(64.dp)) {
                    Text("$score", fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun SourcesCard(state: UiState) {
    if (state.sources.isEmpty()) return
    InfoCard(title = "연결 상태") {
        state.sources.toSortedMap().forEach { (k, v) ->
            Text("$k · $v", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 18.sp)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Chip(text: String) {
    AssistChip(onClick = {}, label = { Text(text, fontSize = 16.sp) })
}

private fun gearLabel(gear: Gear) = when (gear) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.UNKNOWN -> "-"
}

private fun regenLabel(state: RegenState, level: Int?) = when (state) {
    RegenState.DISABLED -> "꺼짐"
    RegenState.PARTIAL -> "부분"
    RegenState.FULL -> "최대"
    RegenState.UNKNOWN -> level?.let { "${it}단계" } ?: "-"
}
