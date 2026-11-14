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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noisetrace.core.CapturePipeline
import com.noisetrace.core.Gear
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoiseReport
import com.noisetrace.core.NoiseType
import com.noisetrace.core.Sensitivity
import com.noisetrace.core.Spot
import com.noisetrace.core.StoragePolicy
import com.noisetrace.core.TriggerKind
import com.noisetrace.core.TurnSignal
import com.noisetrace.core.loadShift
import com.noisetrace.runtime.NoiseTraceController
import com.noisetrace.runtime.TagStep
import com.noisetrace.runtime.UiState
import kotlin.math.roundToInt

private val TABS = listOf("홈", "기록", "리포트", "설정")

@Composable
fun NoiseTraceScreen(state: UiState, controller: NoiseTraceController) {
    var tab by remember { mutableIntStateOf(0) }
    NightBackground {
        when {
            state.mechanicMode -> MechanicScreen(state, controller)
            state.drivingRestricted -> DrivingScreen(state, controller)
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Header(state, controller, tab) { tab = it }
                state.message?.let { MessageBar(it) }
                state.question?.let { QuestionCard(state, controller) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (tab) {
                        0 -> HomeTab(state, controller) { tab = it }
                        1 -> RecordsTab(state, controller)
                        2 -> ReportTab(state)
                        else -> SettingsTab(state, controller)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (state.showConsent) ConsentDialog(controller)
    }
}

@Composable
private fun Header(state: UiState, controller: NoiseTraceController, tab: Int, onTab: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("달그Log", fontSize = 40.sp, fontWeight = FontWeight.Light, color = Palette.Text, letterSpacing = 1.sp)
                Spacer(Modifier.width(14.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (state.micOn || state.demoRunning) Palette.Cyan else Palette.TextMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        state.demoRunning -> "데모 주행 중"
                        state.micOn -> "소음 추적 중"
                        else -> "녹음 꺼짐 · 차량 상태만 저장"
                    },
                    color = Palette.TextMuted, fontSize = 17.sp,
                )
            }
        }
        SkewTabs(TABS, tab, onTab)
        Spacer(Modifier.width(18.dp))
        Pill("정비사 모드", onClick = { controller.setMechanicMode(true) }, enabled = state.events.isNotEmpty())
    }
}

@Composable
private fun MessageBar(text: String) {
    GlassCard(glow = Palette.Cyan) {
        Text(text, color = Palette.Text, fontSize = 20.sp)
    }
}

// ---------------- home ----------------

@Composable
private fun HomeTab(state: UiState, controller: NoiseTraceController, onTab: (Int) -> Unit) {
    BoxWithConstraints {
        if (maxWidth > 1100.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    RecordCard(state, controller)
                    NowCard(state)
                }
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    SummaryCard(state, controller)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        LatestCard(state, Modifier.weight(1f)) { onTab(1) }
                        MonthlyCard(state, Modifier.weight(1f)) { onTab(2) }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                RecordCard(state, controller)
                SummaryCard(state, controller)
                LatestCard(state, Modifier) { onTab(1) }
                MonthlyCard(state, Modifier) { onTab(2) }
                NowCard(state)
            }
        }
    }
}

@Composable
private fun SaveButton(state: UiState, controller: NoiseTraceController, modifier: Modifier, height: Dp, large: Boolean = false) {
    val capture = state.capture
    GlowButton(
        title = when {
            capture == null -> "방금 소리 저장"
            capture.kind == TriggerKind.AUTO -> "자동 저장 중"
            else -> "저장 중 · ${capture.secondsLeft}초"
        },
        subtitle = when {
            large -> null
            capture != null -> null
            state.micOn || state.demoRunning -> "직전 20초 + 이후 10초"
            else -> "차량 상태 30초"
        },
        onClick = controller::saveNow,
        enabled = capture == null || capture.kind == TriggerKind.AUTO,
        progress = capture?.let { 1f - it.secondsLeft * 1000f / CapturePipeline.POST_MS },
        modifier = modifier,
        height = height,
        titleSize = if (large) 64 else (height.value / 4.2f).coerceAtMost(46f).toInt(),
        subtitleSize = 19,
    )
}

@Composable
private fun RecordCard(state: UiState, controller: NoiseTraceController) {
    GlassCard(title = "방금 난 소리") {
        SaveButton(state, controller, Modifier.fillMaxWidth(), 230.dp)
        if (state.micOn || state.demoRunning) {
            Text(
                when {
                    state.learning -> "실내 소리 · 배우는 중"
                    state.excessDb == null -> "실내 소리 · 잠시 멈춤"
                    state.excessDb < 3f -> "실내 소리 · 평소 수준"
                    else -> "실내 소리 · 평소보다 +${state.excessDb.roundToInt()} dB"
                },
                color = Palette.TextMuted, fontSize = 18.sp,
            )
            LevelBar(state.excessDb)
        }
        ToggleRow("소음 추적", state.trackingOn, controller::requestTracking)
        ToggleRow("자동 저장", state.autoDetect, controller::setAutoDetect, enabled = state.trackingOn)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, detail: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Palette.Text else Palette.TextMuted, fontSize = 21.sp)
            detail?.let { Text(it, color = Palette.TextMuted, fontSize = 16.sp) }
        }
        Spacer(Modifier.width(12.dp))
        GlowSwitch(checked, onChange, enabled)
    }
}

@Composable
private fun NowCard(state: UiState) {
    val v = state.vehicle
    GlassCard(title = "지금 차량") {
        Row {
            BigStat(v.speedKmh?.let { "${it.roundToInt()}" } ?: "-", "km/h", Modifier.weight(1f), size = 60)
            BigStat(
                when (v.gear) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "-" },
                "기어", Modifier.weight(1f), color = if (v.gear == Gear.DRIVE) Palette.MagentaBright else Palette.Text, size = 60,
            )
            BigStat(v.steeringDeg?.let { NoiseReport.steeringText(it).substringBefore(" (") } ?: "-", "조향", Modifier.weight(1f), size = 40)
        }
        Row {
            StatCell(if (v.brakePressed) "밟음" else "해제", "브레이크", Modifier.weight(1f), highlight = v.brakePressed)
            StatCell(v.wiperOn?.let { if (it) "ON" else "OFF" } ?: "-", "와이퍼", Modifier.weight(1f), highlight = v.wiperOn == true)
            StatCell(v.acOn?.let { if (it) "ON" else "OFF" } ?: "-", "에어컨", Modifier.weight(1f), highlight = v.acOn == true)
        }
    }
}

@Composable
private fun SummaryCard(state: UiState, controller: NoiseTraceController) {
    val p = state.patterns.firstOrNull()
    GlassCard(title = "반복되는 소리", glow = if (p != null) Palette.Magenta else null) {
        if (p == null) {
            Text("같은 소리가 두 번 이상 쌓이면 공통 조건을 찾아요.", fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("지난 기록 불러오기", onClick = controller::loadSamples, enabled = !state.demoRunning)
                Pill(if (state.demoRunning) "데모 중지" else "데모 주행", selected = true, onClick = controller::toggleDemo)
            }
            return@GlassCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${p.size}", fontSize = 88.sp, fontWeight = FontWeight.Light)
                    Spacer(Modifier.width(10.dp))
                    Text("번 · ${p.type?.label ?: "종류 미확인"}", fontSize = 24.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 12.dp))
                }
                Text(p.summary, fontSize = 27.sp, color = Palette.MagentaBright, fontWeight = FontWeight.Medium)
                p.evidence.firstOrNull()?.let { Text(it, fontSize = 21.sp) }
                Pill("정비사 모드에서 보기", onClick = { controller.setMechanicMode(true) })
            }
            CarLoadView(p.spots, loadShift(p.common), Modifier.weight(1f), height = 330.dp)
        }
    }
}

@Composable
private fun LatestCard(state: UiState, modifier: Modifier, onMore: () -> Unit) {
    val e = state.events.firstOrNull()
    GlassCard(title = "최근 기록", modifier = modifier) {
        if (e == null) {
            Text("아직 없어요", fontSize = 20.sp, color = Palette.TextMuted)
            return@GlassCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(NoiseReport.title(e).removePrefix("이상 소음 "), fontSize = 44.sp, fontWeight = FontWeight.Light)
            Text(e.type?.label ?: "소리", fontSize = 22.sp, color = Palette.MagentaBright)
            Tag(e.trigger.label, if (e.trigger == TriggerKind.AUTO) Palette.Cyan.copy(alpha = 0.25f) else Palette.Magenta.copy(alpha = 0.35f))
        }
        Text(NoiseReport.time(e), color = Palette.TextMuted, fontSize = 17.sp)
        Text(NoiseReport.estimate(e, state.exposure).removeSuffix("에서 발생 가능성 높음"), fontSize = 23.sp, color = Palette.Cyan)
        Pill("기록 ${state.events.size}건", onClick = onMore)
    }
}

@Composable
private fun MonthlyCard(state: UiState, modifier: Modifier, onMore: () -> Unit) {
    val r = state.monthly
    GlassCard(title = "월간 리포트", modifier = modifier) {
        if (r == null) {
            Text("아직 없어요", fontSize = 20.sp, color = Palette.TextMuted)
            return@GlassCard
        }
        BigStat("${r.events}건", "최근 30일", size = 44)
        val c = r.notable.firstOrNull()
        if (c != null) Text("${c.bandLabel} ${c.channelLabel} ${if (c.deltaDb > 0) "+" else ""}${"%.1f".format(c.deltaDb)} dB", fontSize = 23.sp, color = Palette.MagentaBright)
        else r.daysSinceTop?.takeIf { it >= 3 }?.let { Text("반복 소리 ${it}일째 조용", fontSize = 20.sp, color = Palette.Cyan) }
        Pill("자세히", onClick = onMore)
    }
}

// ---------------- records ----------------

@Composable
private fun RecordsTab(state: UiState, controller: NoiseTraceController) {
    if (state.events.isEmpty()) {
        GlassCard(title = "이상 소음 기록") { Text("아직 저장한 소리가 없어요. 이상한 소리가 나면 ‘방금 소리 저장’을 눌러 주세요.", fontSize = 20.sp) }
        return
    }
    BoxWithConstraints {
        val columns = if (maxWidth > 1100.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            state.events.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { e -> EventCard(e, state, controller, Modifier.weight(1f)) }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventCard(e: NoiseEvent, state: UiState, controller: NoiseTraceController, modifier: Modifier) {
    var tagging by remember(e.id) { mutableStateOf(false) }
    GlassCard(modifier = modifier, glow = if (!e.confirmed) Palette.Warning else null) {
        EventReport(e, state.exposure)
        if (tagging) TagEditor(e, controller)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!e.confirmed) Pill("보관", selected = true, onClick = { controller.confirmEvent(e.id) })
            if (!e.tagged && state.question?.eventId != e.id) Pill(if (tagging) "닫기" else "종류·위치 입력", color = Palette.Cyan, onClick = { tagging = !tagging })
            Pill("자세히·듣기", onClick = { controller.select(e.id); controller.setMechanicMode(true) })
            Pill("삭제", color = Palette.Warning, onClick = { controller.deleteEvent(e.id) })
        }
    }
}

/** Tagging later on the screen, for events whose voice question went unanswered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(e: NoiseEvent, controller: NoiseTraceController) {
    var spots by remember(e.id) { mutableStateOf(e.spots) }
    Text("어디서, 어떤 소리였나요?", fontSize = 18.sp, color = Palette.Cyan)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spot.entries.forEach { s -> Pill(s.label, selected = s in spots, onClick = { spots = if (s in spots) spots - s else spots + s }) }
        NoiseType.entries.forEach { t -> Pill(t.label, color = Palette.Cyan, onClick = { controller.setTags(e.id, t, spots) }) }
    }
}

// ---------------- report ----------------

@Composable
private fun ReportTab(state: UiState) {
    BoxWithConstraints {
        val wide = maxWidth > 1100.dp
        val monthly: @Composable (Modifier) -> Unit = { m ->
            GlassCard(title = "월간 소음 리포트", modifier = m) {
                state.monthly?.let { MonthlyContent(it) } ?: Text("기록이 쌓이면 보여 드려요.", fontSize = 19.sp)
            }
        }
        val patterns: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (state.patterns.isEmpty()) GlassCard(title = "반복 패턴") { Text("같은 소리를 두 번 이상 저장하면 보여 드려요.", fontSize = 19.sp) }
                state.patterns.forEach { p ->
                    GlassCard(title = "반복 패턴", glow = Palette.Magenta) {
                        PatternSummary(p, showGuide = true)
                        CarLoadView(p.spots, loadShift(p.common), Modifier.fillMaxWidth(), height = 220.dp)
                    }
                }
            }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { monthly(Modifier.weight(1f)); patterns(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { monthly(Modifier); patterns(Modifier) }
    }
}

// ---------------- settings ----------------

@Composable
private fun SettingsTab(state: UiState, controller: NoiseTraceController) {
    BoxWithConstraints {
        val wide = maxWidth > 1100.dp
        val privacy: @Composable (Modifier) -> Unit = { m ->
            GlassCard(title = "개인정보", modifier = m) {
                ToggleRow("위치 함께 저장", state.saveLocation, controller::setSaveLocation, detail = "꺼도 경사·노면·속도는 남아요")
                if (state.micConsented) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("마이크 사용 동의함", fontSize = 21.sp)
                            Text("철회하면 녹음 파일을 모두 지워요", color = Palette.TextMuted, fontSize = 16.sp)
                        }
                        Pill("동의 철회", color = Palette.Warning, onClick = controller::withdrawConsent)
                    }
                }
                Text("기록은 차 안에만 저장 · 최대 ${StoragePolicy.MAX_EVENTS}건", fontSize = 17.sp, color = Palette.TextMuted)
            }
        }
        val demo: @Composable (Modifier) -> Unit = { m ->
            GlassCard(title = "데모", modifier = m) {
                Text("비 오는 저녁, 내리막 골목에서 달그락 두 번 (약 75초)", fontSize = 18.sp, color = Palette.TextMuted)
                GlowButton(if (state.demoRunning) "데모 중지" else "데모 주행 시작", null, controller::toggleDemo, Modifier.fillMaxWidth(), height = 72.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("지난 기록 불러오기", onClick = controller::loadSamples, enabled = !state.demoRunning)
                    Pill("기록 모두 지우기", color = Palette.Warning, onClick = controller::clearAll, enabled = !state.demoRunning)
                }
            }
        }
        val detection: @Composable (Modifier) -> Unit = { m ->
            GlassCard(title = "자동 저장 민감도", modifier = m) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sensitivity.entries.forEach { s ->
                        Pill("${s.label} +${s.marginDb.roundToInt()}dB", selected = state.sensitivity == s, onClick = { controller.setSensitivity(s) })
                    }
                }
            }
        }
        val sources: @Composable (Modifier) -> Unit = { m ->
            GlassCard(title = "연결 상태", modifier = m) {
                state.sources.toSortedMap().forEach { (k, v) -> Text("$k · $v", color = Palette.TextMuted, fontSize = 17.sp) }
            }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { demo(Modifier); privacy(Modifier) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { detection(Modifier); sources(Modifier) }
        } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { demo(Modifier); privacy(Modifier); detection(Modifier); sources(Modifier) }
    }
}

// ---------------- driving, question, consent ----------------

/**
 * While driving the car restricts the screen. Only one large button stays, like a dashcam's event button,
 * so a passenger or the driver can save with a single tap; questions come by voice.
 */
@Composable
fun DrivingScreen(state: UiState, controller: NoiseTraceController) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("달그Log", fontSize = 44.sp, fontWeight = FontWeight.Light, color = Palette.Text, letterSpacing = 1.sp)
        Text(if (state.trackingOn || state.demoRunning) "소음 추적 중" else "녹음 꺼짐 · 차량 상태만 저장", fontSize = 26.sp, color = Palette.TextMuted)
        SaveButton(state, controller, Modifier.fillMaxWidth(), 300.dp, large = true)
        state.question?.let { Text("“${it.text}”", fontSize = 28.sp, color = Palette.Text, textAlign = TextAlign.Center) }
        state.message?.let { Text(it, fontSize = 22.sp, color = Palette.Cyan, textAlign = TextAlign.Center) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(state: UiState, controller: NoiseTraceController) {
    val q = state.question ?: return
    GlassCard(title = "방금 저장한 소리", glow = Palette.Magenta) {
        Text("“${q.text}”", fontSize = 26.sp, color = Palette.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (q.step) {
                TagStep.SAME -> {
                    Pill("네, 같은 소리", selected = true, onClick = { controller.answerSame(true) })
                    Pill("아니요", onClick = { controller.answerSame(false) })
                }
                TagStep.WHERE -> {
                    var spots by remember(q) { mutableStateOf(emptySet<Spot>()) }
                    Spot.entries.forEach { s -> Pill(s.label, selected = s in spots, onClick = { spots = if (s in spots) spots - s else spots + s }) }
                    Pill(if (spots.isEmpty()) "잘 모르겠어요" else "확인", color = Palette.Cyan, onClick = { controller.answerWhere(spots) })
                }
                TagStep.KIND -> NoiseType.entries.forEach { t -> Pill(t.label, onClick = { controller.answerKind(t) }) }
            }
            Pill("나중에", onClick = controller::skipQuestion)
        }
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
        containerColor = Palette.GlassStrong,
        title = { Text("소음 추적을 켜기 전에 확인해 주세요", fontSize = 26.sp, color = Palette.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    "소리는 버튼보다 먼저 나서, 실내 소리를 최근 20초만 메모리에 두고 계속 덮어써요.",
                    "저장할 때만 앞뒤 30초가 이 차 안에 남고, 밖으로 보내지 않아요.",
                    "자동 저장에는 대화가 섞일 수 있어요. 언제든 지우거나 철회할 수 있어요.",
                ).forEach { Text("· $it", fontSize = 20.sp, color = Palette.Text) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = location, onCheckedChange = { location = it }, colors = CheckboxDefaults.colors(checkedColor = Palette.Magenta))
                    Text("위치(주소·좌표)도 함께 저장 (선택)", fontSize = 19.sp, color = Palette.Text)
                }
            }
        },
        confirmButton = { Pill("동의하고 켜기", selected = true, onClick = { controller.acceptConsent(location) }) },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("녹음 없이 쓰기", onClick = controller::declineConsent)
                Pill("취소", onClick = controller::dismissConsent)
            }
        },
    )
}
