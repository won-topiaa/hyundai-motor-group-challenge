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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.calmride.core.DayReport
import com.calmride.core.Gear
import com.calmride.core.RegenState
import com.calmride.core.RideIntro
import com.calmride.core.SeatSide
import com.calmride.core.TurnSignal
import com.calmride.core.label
import com.calmride.runtime.CalmRideController
import com.calmride.runtime.IntroPart
import com.calmride.runtime.UiState
import com.calmride.vehicle.DemoStyle
import kotlin.math.abs
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
                if (state.modeOn && state.nextEvent == null) {
                    Banner("차량 내비에서 경로 안내를 켜면 회전과 방지턱도 미리 알려 드려요.")
                }
                if (state.suggestMode) SuggestionCard(controller)
                if (state.intro == IntroPart.CUES) IntroCard(state)
                if (state.askPriming) AskPrimingCard(controller)
                if (state.askComfort) ComfortCard(controller)
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            RideCard(state, controller)
                            LiveCard(state)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DayReportCard(state, controller)
                            DemoCard(state, controller)
                            SettingsCard(state, controller)
                        }
                    }
                } else {
                    RideCard(state, controller)
                    DayReportCard(state, controller)
                    LiveCard(state)
                    DemoCard(state, controller)
                    SettingsCard(state, controller)
                }
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
            Text("Calm Ride 택시", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("전기 택시 승객의 멀미를 줄여요", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        FilledTonalButton(onClick = controller::listenVoice, modifier = Modifier.height(64.dp)) { Text("음성 명령", fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.height(64.dp)) {
                Text(state.activeProfile?.name ?: "기사", fontSize = 20.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                state.profiles.forEach { p ->
                    DropdownMenuItem(text = { Text(p.name, fontSize = 20.sp) }, onClick = {
                        controller.selectProfile(p.id)
                        menuOpen = false
                    })
                }
                DropdownMenuItem(text = { Text("+ 교대 기사 추가", fontSize = 20.sp) }, onClick = {
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
        title = { Text("교대 기사 추가") },
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
private fun RideCard(state: UiState, controller: CalmRideController) {
    InfoCard(title = if (state.rideActive) "승객 탑승 중" else "빈차") {
        Text(
            when {
                state.modeOn -> "편안한 승차 모드 켜짐 · 회전과 방지턱 전에 짧은 소리로 알리고, 환기도 조절해요."
                state.rideActive -> "편안한 승차 모드 꺼짐 · 승객이 원할 때만 켜요."
                else -> "승객이 타면 앱이 먼저 물어봐요. 기사님은 조작할 게 없어요."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        val left = state.primingSecondsLeft
        if (left != null) {
            Text("1분 사운드 · 남은 시간 ${left}초", style = MaterialTheme.typography.titleLarge)
            LinearProgressIndicator(progress = { (60 - left) / 60f }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = controller::stopPriming, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("사운드 중지", fontSize = 22.sp) }
        }
        Button(
            onClick = { controller.setMode(!state.modeOn) },
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = if (state.modeOn) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)
            else ButtonDefaults.buttonColors(),
        ) { Text(if (state.modeOn) "편안한 승차 모드 끄기" else "편안한 승차 모드 켜기", fontSize = 24.sp) }
        if (state.modeOn && left == null && !state.askPriming && state.intro == null && state.stopped != false && !state.rideMoved) {
            OutlinedButton(onClick = controller::startPriming, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("출발 전 1분 사운드 시작", fontSize = 22.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayReportCard(state: UiState, controller: CalmRideController) {
    val r = state.dayReport
    InfoCard(title = "오늘 운행 리포트") {
        if (r.rides == 0) {
            Text("아직 기록이 없어요. 승객이 타고 내릴 때마다 저절로 쌓여요.", style = MaterialTheme.typography.bodyLarge)
            return@InfoCard
        }
        Text("운행 ${r.rides}회 · ${"%.1f".format(r.distanceKm)}km · 승차 모드 ${r.calmRides}회", style = MaterialTheme.typography.titleLarge)
        r.avgComfort?.let {
            Text("승객 편안함 평균 ${"%.1f".format(it)}점 (${r.comfortAnswers}명 응답)", style = MaterialTheme.typography.bodyLarge)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("급감속 ${r.hardDecels}")
            Chip("회생 울컥임 ${r.coastDecels}")
            Chip("급출발 ${r.hardStarts}")
            Chip("급회전 ${r.sharpTurns}")
            Chip("방지턱 과속 ${r.fastBumps}")
        }
        trendText(r)?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge) }
        state.activeProfile?.let { Text("운전 스타일: ${it.style.label}", style = MaterialTheme.typography.bodyLarge) }
        r.tip?.let { tip ->
            Text("오늘의 코칭 · ${tip.title}", style = MaterialTheme.typography.titleMedium)
            Text(tip.message, style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(
                onClick = controller::speakCoaching,
                enabled = !state.vehicle.passengerOnBoard,
                modifier = Modifier.height(64.dp),
            ) { Text(if (state.vehicle.passengerOnBoard) "승객이 내리면 들을 수 있어요" else "코칭 듣기", fontSize = 20.sp) }
        }
    }
}

private fun trendText(r: DayReport): String? {
    val change = r.roughPerRideChange ?: return null
    return when {
        abs(change) < 0.1 -> "거친 순간은 지난 근무일과 비슷해요."
        change < 0 -> "운행당 거친 순간이 지난 근무일보다 ${"%.1f".format(-change)}번 줄었어요."
        else -> "운행당 거친 순간이 지난 근무일보다 ${"%.1f".format(change)}번 늘었어요."
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
        state.lastCue?.let { Text("최근 알림음: ${it.kind.label()} (${it.reason})", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp) }
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
            Chip(if (v.passengerOnBoard) "승객 있음" else "빈차")
            Chip("회생제동 ${regenLabel(v.regenState, v.regenLevel)}")
        }
    }
}

@Composable
private fun SettingsCard(state: UiState, controller: CalmRideController) {
    var open by remember { mutableStateOf(false) }
    InfoCard(title = "설정") {
        TextButton(onClick = { open = !open }) { Text(if (open) "접기" else "소리 크기와 연결 상태 보기", fontSize = 20.sp) }
        if (!open) return@InfoCard
        val p = state.activeProfile
        if (p != null) {
            Text("예고 시점: 도달 ${"%.1f".format(p.leadTimeSec)}초 전", style = MaterialTheme.typography.bodyLarge)
            Text("예고음 크기", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = p.cueVolume, onValueChange = controller::setVolume, valueRange = 0.2f..1f)
            OutlinedButton(onClick = { controller.rememberSeatFor(p.id) }) { Text("지금 시트 위치로 나를 기억하기") }
        }
        // Gu et al. (2025): the tone helped when both ears heard the same level, so the far side plays a bit louder.
        val seat = when (state.seat) {
            SeatSide.LEFT -> "왼쪽 좌석 · 오른쪽 스피커 +${state.balanceBoostDb.roundToInt()}dB"
            SeatSide.RIGHT -> "오른쪽 좌석 · 왼쪽 스피커 +${state.balanceBoostDb.roundToInt()}dB"
            SeatSide.CENTER -> "가운데 · 좌우 같은 크기"
        }
        Text("1분 사운드 좌우 보정: $seat${if (state.seatOverride == null) " (자동)" else ""}", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::cycleSeat) { Text("자리 바꾸기") }
            OutlinedButton(onClick = { controller.adjustBalance(-1f) }) { Text("보정 −1dB") }
            OutlinedButton(onClick = { controller.adjustBalance(1f) }) { Text("보정 +1dB") }
        }
        state.sources.toSortedMap().forEach { (k, v) ->
            Text("$k · $v", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoCard(state: UiState, controller: CalmRideController) {
    var style by remember { mutableStateOf(DemoStyle.GENTLE) }
    InfoCard(title = "데모 운행") {
        Text(
            if (style == DemoStyle.GENTLE) "차량 없이 승객 탑승부터 하차까지 한 번의 운행(좌회전, 방지턱, 차선 변경, 단속 구간)을 재생해요."
            else "차량 없이 거친 운행(${style.label}, 회전 3번, 방지턱 2번, 신호 대기 2번)을 재생해요. 승객이 내리면 리포트에서 운전 스타일과 코칭을 확인해요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoStyle.entries.forEach { s ->
                FilterChip(
                    selected = style == s,
                    onClick = { style = s },
                    enabled = !state.demoRunning,
                    label = { Text(s.label, fontSize = 18.sp) },
                )
            }
        }
        FilledTonalButton(
            onClick = { controller.toggleDemo(style) },
            enabled = state.demoRunning || !state.rideActive,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(if (state.demoRunning) "데모 중지" else "데모 운행 시작", fontSize = 20.sp) }
    }
}

@Composable
private fun SuggestionCard(controller: CalmRideController) {
    InfoCard(title = "승객이 탔어요") {
        Text("편안한 승차 모드를 켤까요? 승객이 “네”라고 답해도 켜져요.", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.setMode(true) }, modifier = Modifier.height(64.dp)) { Text("켜기", fontSize = 22.sp) }
            TextButton(onClick = controller::dismissSuggestion) { Text("괜찮아요", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun IntroCard(state: UiState) {
    InfoCard(title = "승객에게 예고음을 들려주고 있어요") {
        state.introCaption?.let { Text("“$it”", style = MaterialTheme.typography.titleLarge) }
        Text("돌기 전: 올라가는 두 음 · 멈추기 전: 내려가는 두 음", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AskPrimingCard(controller: CalmRideController) {
    InfoCard(title = "출발 전에 1분 소리를 들을까요?") {
        Text("“${RideIntro.PRIMING_QUESTION}”", style = MaterialTheme.typography.titleLarge)
        Text("차가 서 있는 동안 낮은 소리(100Hz)를 1분 듣고 출발해요. 알림음만 켜면 바로 출발해요.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { controller.answerPriming(true) }, modifier = Modifier.height(64.dp)) { Text("1분 듣고 출발", fontSize = 22.sp) }
            OutlinedButton(onClick = { controller.answerPriming(false) }, modifier = Modifier.height(64.dp)) { Text("알림음만", fontSize = 22.sp) }
        }
    }
}

@Composable
private fun ComfortCard(controller: CalmRideController) {
    InfoCard(title = "승객에게 오늘 승차가 어땠는지 묻고 있어요") {
        Text("1점(많이 울렁거림) ~ 5점(아주 편안함)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { score ->
                Button(onClick = { controller.submitComfort(score) }, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text("$score", fontSize = 26.sp)
                }
            }
        }
        TextButton(onClick = controller::dismissComfort) { Text("건너뛰기", fontSize = 20.sp) }
    }
}

@Composable
private fun Banner(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 20.sp)
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
    AssistChip(onClick = {}, label = { Text(text, fontSize = 18.sp) })
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
