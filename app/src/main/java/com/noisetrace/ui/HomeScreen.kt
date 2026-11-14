package com.noisetrace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisetrace.core.CapturePipeline
import com.noisetrace.core.Gear
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoiseReport
import com.noisetrace.core.NoiseType
import com.noisetrace.core.Sensitivity
import com.noisetrace.core.Spot
import com.noisetrace.core.TriggerKind
import com.noisetrace.core.TurnSignal
import com.noisetrace.runtime.NoiseTraceController
import com.noisetrace.runtime.TagStep
import com.noisetrace.runtime.UiState
import kotlin.math.roundToInt

@Composable
fun NoiseTraceScreen(state: UiState, controller: NoiseTraceController) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.mechanicMode -> MechanicScreen(state, controller)
            state.drivingRestricted -> DrivingScreen(state, controller)
            else -> HomeScreen(state, controller)
        }
        if (state.showConsent) ConsentDialog(controller)
    }
}

/**
 * Shown before the microphone is ever opened (Pleos review: an explicit consent step, and why the data is needed).
 * The OS permission is asked only after "동의하고 켜기".
 */
@Composable
private fun ConsentDialog(controller: NoiseTraceController) {
    var location by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = controller::dismissConsent,
        title = { Text("소음 추적을 켜기 전에 확인해 주세요", fontSize = 26.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "왜 마이크가 필요한가요? 소리는 버튼을 누르기 전에 나요. 그래서 실내 소리를 최근 20초만 메모리에 담아 두고 계속 덮어써요.",
                    "언제 파일로 남나요? ‘방금 소리 저장’을 누르거나, 평소보다 확 큰 소리가 날 때만 그 앞뒤 30초가 남아요.",
                    "어디에 저장되나요? 이 차 안에만 저장되고 밖으로 보내지 않아요. 정비소에서도 차 화면으로만 봐요.",
                    "대화가 녹음될 수 있나요? 자동 저장에는 대화가 섞일 수 있어요. ‘확인 필요’로 표시되고, 언제든 지울 수 있어요.",
                    "언제든 끌 수 있어요. 설정에서 동의를 철회하면 녹음 파일을 모두 지워요.",
                ).forEach { Text(it, fontSize = 19.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = location, onCheckedChange = { location = it })
                    Text("위치(주소·좌표)도 함께 저장 (선택)", fontSize = 19.sp)
                }
                Text("녹음 없이 써도 버튼을 누르면 그때의 차량 상태(속도·조향·경사 등)는 남아요.", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { controller.acceptConsent(location) }, modifier = Modifier.height(56.dp)) { Text("동의하고 켜기", fontSize = 20.sp) } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = controller::declineConsent, modifier = Modifier.height(56.dp)) { Text("녹음 없이 쓰기", fontSize = 20.sp) }
                TextButton(onClick = controller::dismissConsent) { Text("취소", fontSize = 20.sp) }
            }
        },
    )
}

/**
 * While driving the car restricts the screen. Only one large button stays, like a dashcam's event button,
 * so a passenger or the driver can save with a single tap; questions come by voice.
 */
@Composable
fun DrivingScreen(state: UiState, controller: NoiseTraceController) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (state.trackingOn || state.demoRunning) "소음 추적 중" else "녹음 없이 차량 상태만 저장", style = MaterialTheme.typography.headlineMedium)
        SaveButton(state, controller, Modifier.fillMaxWidth().height(260.dp))
        state.question?.let { Text("“${it.text}”", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
        state.message?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
    }
}

@Composable
private fun SaveButton(state: UiState, controller: NoiseTraceController, modifier: Modifier) {
    val capture = state.capture
    Button(
        onClick = controller::saveNow,
        enabled = capture == null || capture.kind == TriggerKind.AUTO,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    capture == null -> "방금 소리 저장"
                    capture.kind == TriggerKind.AUTO -> "자동 저장 중 · 눌러서 확인"
                    else -> "저장 중 · ${capture.secondsLeft}초"
                },
                fontSize = 40.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    capture != null && !capture.audio -> "이후 10초의 차량 상태를 기록하고 있어요"
                    capture != null -> "이후 10초를 더 듣고 있어요"
                    state.micOn || state.demoRunning -> "직전 20초 + 이후 10초를 차량 상태와 함께 남겨요"
                    else -> "녹음 없이 지금 앞뒤 30초의 차량 상태를 남겨요"
                },
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
fun HomeScreen(state: UiState, controller: NoiseTraceController) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state, controller)
            state.message?.let { Banner(it) }
            state.question?.let { QuestionCard(state, controller) }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SaveCard(state, controller)
                        LiveCard(state)
                        DemoCard(state, controller)
                        SettingsCard(state, controller)
                    }
                    Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PatternCard(state, controller)
                        EventsCard(state, controller)
                    }
                }
            } else {
                SaveCard(state, controller)
                PatternCard(state, controller)
                EventsCard(state, controller)
                LiveCard(state)
                DemoCard(state, controller)
                SettingsCard(state, controller)
            }
        }
    }
}

@Composable
private fun Header(state: UiState, controller: NoiseTraceController) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("달그Log · 이상 소음 기록", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "가끔 나는 소리를 그때의 주행 상황과 함께 남겨, 정비소에서 재현할 수 있게 해요",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge,
            )
        }
        FilledTonalButton(onClick = controller::listenVoice, modifier = Modifier.height(64.dp)) { Text("음성 명령", fontSize = 20.sp) }
        Spacer(Modifier.width(12.dp))
        Button(onClick = { controller.setMechanicMode(true) }, enabled = state.events.isNotEmpty(), modifier = Modifier.height(64.dp)) {
            Text("정비사 모드", fontSize = 20.sp)
        }
    }
}

@Composable
private fun SaveCard(state: UiState, controller: NoiseTraceController) {
    InfoCard(title = "방금 난 소리") {
        SaveButton(state, controller, Modifier.fillMaxWidth().height(150.dp))
        state.capture?.let { c ->
            LinearProgressIndicator(
                progress = { 1f - c.secondsLeft * 1000f / CapturePipeline.POST_MS },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("소음 추적", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.trackingOn) "마이크가 최근 20초만 메모리에 담아 두고 계속 덮어써요. 저장할 때만 파일로 남아요."
                    else "꺼져 있으면 녹음 없이 차량 상태만 남겨요. 켜면 버튼 직전의 소리까지 남겨요(동의 후). 소리는 차 밖으로 나가지 않아요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp,
                )
            }
            Switch(checked = state.trackingOn, onCheckedChange = controller::requestTracking)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("평소보다 큰 소리 자동 저장", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.trackingOn) "이 차의 속도별 평소 소리를 배워 두고, 그보다 확 커지면 저절로 남겨요. 음악·안내 음성 중에는 멈춰요."
                    else "소음 추적이 켜져 있을 때만 동작해요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp,
                )
            }
            Switch(checked = state.autoDetect, onCheckedChange = controller::setAutoDetect, enabled = state.trackingOn)
        }
        if (state.autoDetect && state.trackingOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("민감도", fontSize = 18.sp)
                Sensitivity.entries.forEach { s ->
                    FilterChip(selected = state.sensitivity == s, onClick = { controller.setSensitivity(s) }, label = { Text("${s.label} (+${s.marginDb.roundToInt()} dB)", fontSize = 18.sp) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCard(state: UiState) {
    val v = state.vehicle
    InfoCard(title = "지금 상태") {
        Text(
            when {
                !state.micOn && !state.demoRunning -> "실내 소리: 듣고 있지 않음"
                state.learning -> "실내 소리: 이 속도의 평소 소리를 배우는 중"
                state.excessDb == null -> "실내 소리: 잠시 쉬는 중 (음악·안내 음성)"
                state.excessDb < 3f -> "실내 소리: 평소 수준"
                else -> "실내 소리: 평소보다 +${state.excessDb.roundToInt()} dB"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("차속 ${v.speedKmh?.let { "${it.roundToInt()} km/h" } ?: "-"}")
            Chip("기어 ${when (v.gear) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "-" }}")
            Chip("조향 ${v.steeringDeg?.let { NoiseReport.steeringText(it) } ?: "-"}")
            Chip(if (v.brakePressed) "브레이크 밟음" else "브레이크 해제")
            if (v.turnSignal != TurnSignal.OFF) Chip(if (v.turnSignal == TurnSignal.LEFT) "왼쪽 깜빡이" else "오른쪽 깜빡이")
            Chip("와이퍼 ${v.wiperOn?.let { if (it) "ON" else "OFF" } ?: "-"}")
            Chip("에어컨 ${v.acOn?.let { if (it) "ON" else "OFF" } ?: "-"}")
            v.windowOpen?.let { if (it) Chip("창문 열림") }
            v.outsideTempC?.let { Chip("기온 ${it.roundToInt()}°C") }
            v.address?.let { Chip(it) }
        }
    }
}

@Composable
private fun PatternCard(state: UiState, controller: NoiseTraceController) {
    val p = state.patterns.firstOrNull()
    InfoCard(title = "반복되는 소리", accent = p != null) {
        if (p == null) {
            Text("같은 소리를 두 번 이상 저장하면, 언제 나는지 공통 조건을 찾아 드려요.", style = MaterialTheme.typography.bodyLarge)
            return@InfoCard
        }
        PatternSummary(p, showGuide = false)
        if (state.patterns.size > 1) Text("다른 반복 소리 ${state.patterns.size - 1}개는 정비사 모드에서 볼 수 있어요.", fontSize = 18.sp)
        OutlinedButton(onClick = { controller.setMechanicMode(true) }, modifier = Modifier.height(56.dp)) { Text("정비사 모드에서 자세히", fontSize = 20.sp) }
    }
}

@Composable
private fun EventsCard(state: UiState, controller: NoiseTraceController) {
    InfoCard(title = "최근 이상 소음 (${state.events.size}건)") {
        if (state.events.isEmpty()) {
            Text("아직 저장한 소리가 없어요. 이상한 소리가 나면 ‘방금 소리 저장’을 눌러 주세요.", style = MaterialTheme.typography.bodyLarge)
            return@InfoCard
        }
        state.events.take(6).forEach { e -> EventItem(e, state, controller) }
        if (state.events.size > 6) Text("나머지 ${state.events.size - 6}건은 정비사 모드에서 볼 수 있어요.", fontSize = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventItem(e: NoiseEvent, state: UiState, controller: NoiseTraceController) {
    Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EventReport(e, state.exposure)
            if (!e.tagged && state.question?.eventId != e.id) TagEditor(e, controller)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!e.confirmed) OutlinedButton(onClick = { controller.confirmEvent(e.id) }) { Text("맞아요, 보관", fontSize = 18.sp) }
                TextButton(onClick = { controller.select(e.id); controller.setMechanicMode(true) }) { Text("자세히·듣기", fontSize = 18.sp) }
                TextButton(onClick = { controller.deleteEvent(e.id) }) { Text("삭제", fontSize = 18.sp, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Tagging later on the screen, for events whose voice question went unanswered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(e: NoiseEvent, controller: NoiseTraceController) {
    var spots by remember(e.id) { mutableStateOf(e.spots) }
    Text("어디서, 어떤 소리였나요?", fontSize = 18.sp, color = MaterialTheme.colorScheme.secondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Spot.entries.forEach { s ->
            FilterChip(selected = s in spots, onClick = { spots = if (s in spots) spots - s else spots + s }, label = { Text(s.label, fontSize = 18.sp) })
        }
        NoiseType.entries.forEach { t ->
            FilterChip(selected = false, onClick = { controller.setTags(e.id, t, spots) }, label = { Text(t.label, fontSize = 18.sp) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(state: UiState, controller: NoiseTraceController) {
    val q = state.question ?: return
    InfoCard(title = "방금 저장한 소리에 대해 묻고 있어요", accent = true) {
        Text("“${q.text}”", style = MaterialTheme.typography.titleLarge)
        Text("말로 대답하거나 아래를 눌러 주세요.", fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (q.step) {
                TagStep.SAME -> {
                    Button(onClick = { controller.answerSame(true) }, modifier = Modifier.height(64.dp)) { Text("네, 같은 소리", fontSize = 22.sp) }
                    OutlinedButton(onClick = { controller.answerSame(false) }, modifier = Modifier.height(64.dp)) { Text("아니요", fontSize = 22.sp) }
                }
                TagStep.WHERE -> {
                    var spots by remember(q) { mutableStateOf(emptySet<Spot>()) }
                    Spot.entries.forEach { s ->
                        FilterChip(selected = s in spots, onClick = { spots = if (s in spots) spots - s else spots + s }, label = { Text(s.label, fontSize = 22.sp) }, modifier = Modifier.height(56.dp))
                    }
                    Button(onClick = { controller.answerWhere(spots) }, modifier = Modifier.height(64.dp)) { Text(if (spots.isEmpty()) "잘 모르겠어요" else "확인", fontSize = 22.sp) }
                }
                TagStep.KIND -> NoiseType.entries.forEach { t ->
                    Button(onClick = { controller.answerKind(t) }, modifier = Modifier.height(64.dp)) { Text(t.label, fontSize = 22.sp) }
                }
            }
            TextButton(onClick = controller::skipQuestion) { Text("나중에", fontSize = 20.sp) }
        }
    }
}

@Composable
private fun DemoCard(state: UiState, controller: NoiseTraceController) {
    InfoCard(title = "데모") {
        Text(
            "차량 없이 비 오는 저녁, 지하주차장 경사로를 내려가다 앞쪽에서 달그락 소리가 두 번 나는 주행을 재생해요(약 75초). 첫 소리는 자동으로, 두 번째는 버튼으로 저장해요.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FilledTonalButton(onClick = controller::toggleDemo, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(if (state.demoRunning) "데모 중지" else "데모 주행 시작", fontSize = 20.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::loadSamples, enabled = !state.demoRunning) { Text("지난 일주일 기록 불러오기", fontSize = 18.sp) }
            TextButton(
                onClick = controller::clearAll,
                enabled = !state.demoRunning,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("기록 모두 지우기", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun SettingsCard(state: UiState, controller: NoiseTraceController) {
    var open by remember { mutableStateOf(false) }
    InfoCard(title = "설정") {
        TextButton(onClick = { open = !open }) { Text(if (open) "접기" else "개인정보와 연결 상태 보기", fontSize = 20.sp) }
        if (!open) return@InfoCard
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("위치 함께 저장", style = MaterialTheme.typography.titleMedium)
                Text("기본은 꺼짐이에요. 켜면 주소·좌표도 남겨요. 꺼도 도로 경사·노면·도로 유형·속도는 남아요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            }
            Switch(checked = state.saveLocation, onCheckedChange = controller::setSaveLocation)
        }
        if (state.micConsented) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("마이크 사용 동의함", style = MaterialTheme.typography.titleMedium)
                    Text("철회하면 마이크를 끄고 녹음 파일을 모두 지워요. 차량 상태 기록은 남아요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
                }
                OutlinedButton(
                    onClick = controller::withdrawConsent,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("동의 철회", fontSize = 18.sp) }
            }
        }
        Text(
            "기록은 이 차 안에만 저장되고 밖으로 보내지 않아요. 최대 ${com.noisetrace.core.StoragePolicy.MAX_EVENTS}건, 넘으면 확인하지 않은 자동 저장부터 지워요.",
            fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.sources.toSortedMap().forEach { (k, v) -> Text("$k · $v", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp) }
    }
}
