package com.noisetrace.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How long the car drove under each condition: tells a telling condition (rare in normal driving) from a common one. */
data class DrivingExposure(val totalSec: Long = 0, val seconds: Map<Condition, Long> = emptyMap()) {
    val known: Boolean get() = totalSec >= MIN_TOTAL_SEC

    fun fraction(c: Condition): Float? = if (known) (seconds[c] ?: 0L).toFloat() / totalSec else null

    fun plus(conditions: Set<Condition>, sec: Long): DrivingExposure =
        DrivingExposure(totalSec + sec, seconds.toMutableMap().apply { conditions.forEach { merge(it, sec, Long::plus) } })

    companion object {
        /** Ten minutes of driving before "평소에는 n%" is worth saying. */
        const val MIN_TOTAL_SEC = 600L
    }
}

/** A noise the user saved several times, and what those times had in common. */
data class NoisePattern(
    val type: NoiseType?,
    /** Newest first. */
    val events: List<NoiseEvent>,
    /** Conditions in at least 80 % of the events, minus those that are just as common in normal driving. */
    val common: List<Condition>,
    /** Conditions that were common to the events but are also common in normal driving, so they tell little. */
    val ordinary: List<Condition>,
    val counts: Map<Condition, Int>,
    val speedRange: IntRange?,
    val exposure: DrivingExposure,
) {
    val size: Int get() = events.size
    val spots: Set<Spot> get() = events.flatMap { it.spots }.groupingBy { it }.eachCount().filterValues { it * 2 >= size }.keys

    /** E.g. "저속 좌회전 + 차량 하중 이동(앞·오른쪽) 상황에서 반복 발생". */
    val summary: String
        get() {
            if (common.isEmpty()) return "${size}번 모두 겹치는 주행 조건은 아직 없어요"
            val speed = common.firstOrNull { it in SPEEDS }?.short
            val moves = common.filter { it in MOVES }.joinToString(" ") { it.short }
            val first = listOfNotNull(speed, moves.ifBlank { null }).joinToString(" ")
            val load = loadShift(common)?.let { "차량 하중 이동($it)" }
            val functions = common.filter { it in FUNCTIONS }.joinToString(" + ") { it.short }
            val leftovers = if (load == null) common.filter { it in SHIFTS }.joinToString(" + ") { it.short } else ""
            val body = listOf(first, leftovers, load ?: "", functions).filter { it.isNotBlank() }.joinToString(" + ")
            return "$body 상황에서 ${if (size >= 3) "반복 " else ""}발생"
        }

    /** E.g. "5번 모두: 10~20 km/h · 좌회전 · 내리막". */
    val evidence: List<String>
        get() {
            val all = counts.filterValues { it == size }.keys
            val most = counts.filterValues { it < size && it * 10 >= size * 6 }
            val out = mutableListOf<String>()
            val allText = Condition.READING_ORDER.filter { it in all }.map { c ->
                if (c in SPEEDS && speedRange != null) "${speedRange.first}~${speedRange.last} km/h" else c.short
            }
            if (allText.isNotEmpty()) out += "${size}번 모두: ${allText.joinToString(" · ")}"
            Condition.READING_ORDER.filter { it in most }.forEach { c -> out += "${size}번 중 ${most.getValue(c)}번: ${c.label}" }
            val rare = common.mapNotNull { c -> exposure.fraction(c)?.let { c to it } }.filter { it.second < 0.2f }
            if (rare.isNotEmpty()) {
                out += "평소 주행에서 " + rare.joinToString(", ") { (c, f) -> "${c.short}은 ${percent(f)}" } + "뿐이에요"
            }
            if (ordinary.isNotEmpty()) out += "평소에도 흔해서 원인 후보에서 뺀 조건: ${ordinary.joinToString(", ") { it.short }}"
            return out
        }

    /** How a mechanic can bring the noise back on a test drive. */
    val reproduceGuide: String? get() = Reproduction.guide(common, speedRange)

    val inspection: List<String> get() = InspectionHints.forConditions(type, common.toSet(), spots)

    companion object {
        val SPEEDS = setOf(Condition.STOPPED, Condition.LOW_SPEED, Condition.MID_SPEED, Condition.HIGH_SPEED)
        val MOVES = listOf(Condition.REVERSE, Condition.TURN_LEFT, Condition.TURN_RIGHT, Condition.BUMP)
        val SHIFTS = listOf(Condition.DECEL, Condition.ACCEL, Condition.BRAKE, Condition.DOWNHILL, Condition.UPHILL)
        val FUNCTIONS = listOf(Condition.WIPER, Condition.AC, Condition.WINDOW_OPEN, Condition.COLD)
    }
}

private fun percent(f: Float): String = "${(f * 100).roundToInt().coerceAtLeast(1)}%"

/** Finds what repeated saves of the same noise have in common. */
object PatternFinder {
    const val COMMON_SHARE = 0.8f
    /** A condition must be at least this many times more frequent around the noise than in normal driving. */
    const val MIN_LIFT = 1.5f

    fun find(events: List<NoiseEvent>, exposure: DrivingExposure): List<NoisePattern> =
        events.groupBy { it.type }
            .filter { it.value.size >= 2 }
            .map { (type, group) -> pattern(type, group.sortedByDescending { it.noiseAtMs }, exposure) }
            .sortedByDescending { it.size }

    fun pattern(type: NoiseType?, events: List<NoiseEvent>, exposure: DrivingExposure): NoisePattern {
        val counts = events.flatMap { it.conditions }.groupingBy { it }.eachCount()
        val frequent = counts.filterValues { it >= COMMON_SHARE * events.size }.keys
        val (telling, ordinary) = frequent.partition { c ->
            val f = exposure.fraction(c) ?: return@partition true
            f <= 0f || (counts.getValue(c).toFloat() / events.size) / f >= MIN_LIFT
        }
        val speeds = events.mapNotNull { it.context.speedKmh }
        val range = if (speeds.size == events.size && speeds.isNotEmpty()) {
            val lo = (speeds.min() / 5).toInt() * 5
            val hi = ((speeds.max() + 4.99f) / 5).toInt() * 5
            if (hi - lo <= 30) lo..maxOf(hi, lo + 5) else null
        } else null
        return NoisePattern(
            type, events,
            Condition.READING_ORDER.filter { it in telling },
            Condition.READING_ORDER.filter { it in ordinary },
            counts, range, exposure,
        )
    }
}

object Reproduction {
    private val SPEED_WORDS = setOf(Condition.STOPPED, Condition.LOW_SPEED, Condition.HIGH_SPEED)
    private val DYNAMICS = setOf(
        Condition.REVERSE, Condition.TURN_LEFT, Condition.TURN_RIGHT, Condition.DECEL, Condition.ACCEL, Condition.BRAKE,
        Condition.DOWNHILL, Condition.UPHILL, Condition.BUMP,
    )

    /**
     * Picks up to three conditions for one event. Speed comes first, since a test drive starts from it; then how the
     * car moved, rarest in this car's driving first (or by [Condition.PRIORITY] before there is enough driving);
     * the car's own functions (wipers, AC) only fill what is left.
     */
    fun pick(conditions: Set<Condition>, exposure: DrivingExposure?): List<Condition> {
        fun rank(list: Collection<Condition>) = if (exposure?.known == true) {
            list.sortedBy { exposure.fraction(it) ?: 1f }
        } else {
            list.sortedBy { Condition.PRIORITY.indexOf(it) }
        }
        val speed = conditions.filter { it in SPEED_WORDS }
        val moves = rank(conditions.filter { it in DYNAMICS })
        val functions = rank(conditions.filter { it !in SPEED_WORDS && it !in DYNAMICS && it != Condition.MID_SPEED })
        val picked = (speed + moves + functions).take(3)
        return Condition.READING_ORDER.filter { it in picked }
    }

    /** A 5 km/h window around one event's speed, for a test drive. */
    fun rangeAround(speedKmh: Float?): IntRange? {
        val v = speedKmh ?: return null
        if (v < 1f) return null
        val lo = (v / 5).toInt() * 5
        return lo..(lo + 5)
    }

    /** E.g. "저속 + 좌회전 + 내리막에서 발생 가능성 높음". */
    fun estimate(conditions: Set<Condition>, exposure: DrivingExposure?): String {
        val picked = pick(conditions, exposure)
        if (picked.isEmpty()) return "특별한 주행 조건 없이 발생 (같은 소리가 더 쌓이면 다시 찾아 볼게요)"
        return picked.joinToString(" + ") { it.short } + "에서 발생 가능성 높음"
    }

    fun guide(conditions: List<Condition>, speedRange: IntRange?): String? {
        if (conditions.isEmpty()) return null
        val c = conditions.toSet()
        val where = when {
            Condition.DOWNHILL in c -> "내리막(지하주차장 진입로 같은 경사로)에서 "
            Condition.UPHILL in c -> "오르막에서 "
            Condition.BUMP in c -> "방지턱이 있는 길에서 "
            else -> ""
        }
        val speed = when {
            Condition.STOPPED in c -> "차를 세운 채로 "
            speedRange != null -> "${speedRange.first}~${speedRange.last} km/h로 "
            Condition.LOW_SPEED in c -> "20 km/h 아래로 천천히 "
            Condition.HIGH_SPEED in c -> "60 km/h 넘게 "
            else -> ""
        }
        val moves = buildList {
            if (Condition.REVERSE in c) add("후진하며")
            if (Condition.TURN_LEFT in c) add("핸들을 왼쪽으로 돌리며")
            if (Condition.TURN_RIGHT in c) add("핸들을 오른쪽으로 돌리며")
            if (Condition.BUMP in c) add("방지턱을 넘으며")
            if (Condition.DECEL in c || Condition.BRAKE in c) add("살짝 감속해")
            else if (Condition.ACCEL in c) add("가속해")
        }
        val action = if (moves.isEmpty()) "달려 보세요." else moves.joinToString(" ") + " 보세요."
        val extras = buildList {
            if (Condition.WIPER in c) add("와이퍼를 켠 상태")
            if (Condition.AC in c) add("에어컨을 켠 상태")
            if (Condition.WINDOW_OPEN in c) add("창문을 연 상태")
            if (Condition.COLD in c) add("기온이 낮을 때(아침)")
        }
        val tail = if (extras.isEmpty()) "" else " ${extras.joinToString(", ")}에서 났어요."
        return "$where$speed$action$tail".trim()
    }
}

/**
 * Common causes from squeak-and-rattle practice, matched on type, conditions and where it was heard.
 * Only a starting point for the mechanic; the app does not diagnose.
 */
object InspectionHints {
    private class Rule(val type: NoiseType?, val all: Set<Condition>, val any: Set<Condition>, val spot: Spot?, val text: String)

    private val turns = setOf(Condition.TURN_LEFT, Condition.TURN_RIGHT)
    private val forward = setOf(Condition.DOWNHILL, Condition.DECEL, Condition.BRAKE)

    private val rules = listOf(
        Rule(NoiseType.RATTLE, emptySet(), turns, null, "하중이 쏠리는 쪽 서스펜션의 스태빌라이저 링크·로어암 부싱"),
        Rule(NoiseType.RATTLE, setOf(Condition.BUMP), emptySet(), null, "서스펜션 링크·스태빌라이저 링크, 언더커버 고정 볼트"),
        Rule(NoiseType.RATTLE, emptySet(), emptySet(), Spot.FRONT, "대시보드·글로브박스·센터페시아 내장재 고정 클립"),
        Rule(NoiseType.RATTLE, emptySet(), emptySet(), Spot.REAR, "트렁크 적재물·러기지 트림, 2열 시트백 잠금장치"),
        Rule(NoiseType.SQUEAK, setOf(Condition.LOW_SPEED), turns, null, "스트럿 상단 마운트·베어링, 스티어링 연결부"),
        Rule(NoiseType.SQUEAK, setOf(Condition.BUMP), emptySet(), null, "서스펜션 부싱·스태빌라이저 부싱 (고무 경화)"),
        Rule(NoiseType.SQUEAK, setOf(Condition.COLD), emptySet(), null, "고무 부싱·도어 웨더스트립 (기온이 낮으면 굳음)"),
        Rule(NoiseType.HUM, setOf(Condition.HIGH_SPEED), emptySet(), null, "타이어 편마모·휠 베어링 (속도에 따라 커지는 소리)"),
        Rule(NoiseType.HUM, emptySet(), setOf(Condition.ACCEL, Condition.DECEL), null, "구동 모터·감속기 (가속·감속 때 달라지는 소리)"),
        Rule(NoiseType.HUM, setOf(Condition.AC), emptySet(), null, "공조 블로어 모터·에어컨 컴프레서"),
        Rule(null, setOf(Condition.WIPER), emptySet(), null, "와이퍼 암 체결·카울 탑 커버"),
        Rule(null, setOf(Condition.BRAKE), emptySet(), null, "브레이크 패드·캘리퍼 가이드 핀"),
        Rule(null, setOf(Condition.WINDOW_OPEN), emptySet(), null, "창문이 열려 생긴 바람 소리일 수 있음 (창문 닫고 확인)"),
    )

    fun forConditions(type: NoiseType?, conditions: Set<Condition>, spots: Set<Spot>, max: Int = 3): List<String> =
        rules.mapNotNull { r ->
            if (r.type != null && r.type != type) return@mapNotNull null
            if (!conditions.containsAll(r.all)) return@mapNotNull null
            if (r.any.isNotEmpty() && r.any.none { it in conditions }) return@mapNotNull null
            if (r.spot != null && r.spot !in spots) return@mapNotNull null
            val bonus = if (r.any.isNotEmpty() && forward.any { it in conditions } && r.any == turns) 1 else 0
            val score = r.all.size + (if (r.any.isNotEmpty()) 1 else 0) + (if (r.type != null) 1 else 0) + (if (r.spot != null) 1 else 0) + bonus
            score to r.text
        }.sortedByDescending { it.first }.take(max).map { it.second }
}

/** The text of a noise report, shared by the owner's list and the mechanic's screen. */
object NoiseReport {
    fun title(e: NoiseEvent) = "이상 소음 #%02d".format(e.number)

    fun time(e: NoiseEvent): String = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(e.noiseAtMs))

    fun headline(e: NoiseEvent): String {
        val t = e.userType
        val name = when {
            t != null && t != NoiseType.OTHER -> "${t.label} 소리"
            e.spectrum?.estimatedType?.let { it != NoiseType.OTHER } == true -> "${e.spectrum.estimatedType!!.label} 소리(추정)"
            else -> "소리"
        }
        return "🔊 $name 녹음됨"
    }

    /** The short card: the lines a driver reads at a glance, as in the planning example. */
    fun summaryLines(e: NoiseEvent): List<String> {
        val c = e.context
        return buildList {
            c.speedKmh?.let { add("차량 속도: ${it.roundToInt()} km/h") }
            accelText(c)?.let { add(it) }
            c.steeringDeg?.let { add("조향각: ${steeringText(it)}") }
            c.wiperOn?.let { if (it) add("와이퍼: ON") }
            slopeText(c)?.let { add("도로 경사: $it") }
            c.hazard?.let { if (it != RoadHazard.DOWNHILL) add("노면: ${it.label} 통과") }
            locationText(c)?.let { add("위치: $it") }
        }
    }

    /** Everything recorded, for the mechanic. */
    fun detailLines(e: NoiseEvent): List<Pair<String, String>> {
        val c = e.context
        return buildList {
            add("저장 방식" to when {
                e.trigger == TriggerKind.AUTO -> "자동 감지 (평소보다 크게 들림)"
                e.momentCertain -> "직접 저장 · 버튼 %.1f초 전 소리".format((e.savedAtMs - e.noiseAtMs) / 1000f)
                else -> "직접 저장 · 뚜렷한 소리를 찾지 못해 버튼 2초 전으로 봄"
            })
            e.excessDb?.let { add("소리 크기" to "평소보다 +${it.roundToInt()} dB") }
            e.spectrum?.let { s ->
                val band = OCTAVE_CENTERS_HZ[s.risingBand]
                add("주요 주파수" to "${hzLabel(s.peakHz)} 부근 (${bandLabel(band)}Hz 대역 +${s.deltaDb[s.risingBand].roundToInt()} dB)")
            }
            add("들린 곳·종류" to listOfNotNull(e.spots.takeIf { it.isNotEmpty() }?.label(), e.userType?.label).joinToString(" · ").ifEmpty { "아직 입력 안 함" })
            c.speedKmh?.let { add("차량 속도" to "${it.roundToInt()} km/h") }
            accelText(c)?.let { add("가속도" to "$it (%.1f m/s²)".format(c.accelMps2 ?: 0f)) }
            c.steeringDeg?.let { add("조향각" to steeringText(it)) }
            add("브레이크" to if (c.brake) "밟음" else "안 밟음")
            add("기어" to gearText(c.gear))
            add("방향지시등" to when (c.turnSignal) { TurnSignal.LEFT -> "왼쪽"; TurnSignal.RIGHT -> "오른쪽"; TurnSignal.OFF -> "꺼짐" })
            c.motorRpmEstimate?.let { add("모터 회전수" to "약 %,d rpm (차속 기반 추정)".format(it)) }
            c.wiperOn?.let { add("와이퍼" to if (it) "ON" else "OFF") }
            c.acOn?.let { add("에어컨" to if (it) "ON" else "OFF") }
            c.windowOpen?.let { add("창문" to if (it) "열림" else "닫힘") }
            c.outsideTempC?.let { add("바깥 기온" to "%.0f°C".format(it)) }
            slopeText(c)?.let { add("도로 경사" to it) }
            c.roadType?.let { add("도로 유형" to "${it.label} (제한속도 기준)") }
            c.hazard?.let { add("지나간 노면" to it.label) }
            locationText(c)?.let { add("위치" to it) }
            c.odometerKm?.let { add("누적 주행거리" to "%,.0f km".format(it)) }
        }
    }

    fun estimate(e: NoiseEvent, exposure: DrivingExposure?): String = Reproduction.estimate(e.conditions, exposure)

    private fun accelText(c: DrivingContext): String? {
        val a = c.accelMps2 ?: return null
        val v = c.speedKmh ?: 0f
        if (v < 1f && abs(a) < DrivingContext.ACCEL_MPS2) return "정차 중"
        return when {
            a <= -DrivingContext.ACCEL_MPS2 -> "감속 중"
            a >= DrivingContext.ACCEL_MPS2 -> "가속 중"
            else -> "일정한 속도"
        }
    }

    fun steeringText(deg: Float): String = when {
        abs(deg) < 3f -> "직진 (0°)"
        deg < 0 -> "좌측 ${(-deg).roundToInt()}°"
        else -> "우측 ${deg.roundToInt()}°"
    }

    private fun slopeText(c: DrivingContext): String? {
        val s = c.slopePct
        if (s == null) return if (c.hazard == RoadHazard.DOWNHILL) "내리막" else null
        return when {
            s <= -DrivingContext.SLOPE_PCT -> "내리막 (약 ${(-s).roundToInt()}%)"
            s >= DrivingContext.SLOPE_PCT -> "오르막 (약 ${s.roundToInt()}%)"
            else -> "평지"
        }
    }

    private fun locationText(c: DrivingContext): String? {
        val place = c.address?.takeIf { it.isNotBlank() }
            ?: if (c.latitude != null && c.longitude != null) "%.4f, %.4f".format(c.latitude, c.longitude) else null
        val weak = if (c.gpsWeak) "GPS 약함 · 지하나 터널 추정" else null
        return listOfNotNull(place, weak).joinToString(" · ").ifEmpty { null }
    }

    private fun gearText(g: Gear) = when (g) {
        Gear.PARK -> "P"
        Gear.REVERSE -> "R"
        Gear.NEUTRAL -> "N"
        Gear.DRIVE -> "D"
        Gear.UNKNOWN -> "-"
    }
}
