package com.noisetrace.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RoadType(val label: String) {
    HIGHWAY("고속도로"),
    EXPRESSWAY("자동차 전용도로"),
    ARTERIAL("일반 도로"),
    LOCAL("이면도로·주차장 주변"),
}

/** Everything the car knew at the moment of the noise. */
data class DrivingContext(
    val speedKmh: Float? = null,
    /** Change of speed over the last second, m/s². Negative is slowing down. */
    val accelMps2: Float? = null,
    val steeringDeg: Float? = null,
    val brake: Boolean = false,
    val gear: Gear = Gear.UNKNOWN,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val wiperOn: Boolean? = null,
    val acOn: Boolean? = null,
    val windowOpen: Boolean? = null,
    val outsideTempC: Float? = null,
    val odometerKm: Float? = null,
    /** Road gradient from GPS altitude over the last stretch, %. Positive is uphill. */
    val slopePct: Float? = null,
    /** A bump, damaged road or level crossing passed within a few seconds of the noise. */
    val hazard: RoadHazard? = null,
    val roadType: RoadType? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    /** No fresh GPS fix: often underground parking or a tunnel. */
    val gpsWeak: Boolean = false,
) {
    /**
     * Motor speed from road speed. An EV drives the wheels through one fixed reduction gear, so motor rpm is a fixed
     * multiple of road speed; the AAOS rpm property is not open to third-party apps. Values for a Hyundai E-GMP car.
     */
    val motorRpmEstimate: Int?
        get() = speedKmh?.let { (it / 3.6 / (2 * PI * WHEEL_RADIUS_M) * 60 * REDUCTION_RATIO).roundToInt() }

    fun conditions(): Set<Condition> = buildSet {
        val v = speedKmh
        if (gear == Gear.REVERSE) add(Condition.REVERSE)
        when {
            v == null -> Unit
            v < 1f -> add(Condition.STOPPED)
            v < 20f -> add(Condition.LOW_SPEED)
            v < 60f -> add(Condition.MID_SPEED)
            else -> add(Condition.HIGH_SPEED)
        }
        val moving = v != null && v >= 1f
        steeringDeg?.let { if (moving && it <= -TURN_DEG) add(Condition.TURN_LEFT) else if (moving && it >= TURN_DEG) add(Condition.TURN_RIGHT) }
        accelMps2?.let { if (moving && it <= -ACCEL_MPS2) add(Condition.DECEL) else if (moving && it >= ACCEL_MPS2) add(Condition.ACCEL) }
        if (brake && moving) add(Condition.BRAKE)
        slopePct?.let { if (it <= -SLOPE_PCT) add(Condition.DOWNHILL) else if (it >= SLOPE_PCT) add(Condition.UPHILL) }
        if (hazard == RoadHazard.DOWNHILL) add(Condition.DOWNHILL)
        if (hazard == RoadHazard.SPEED_BUMP || hazard == RoadHazard.ROAD_DAMAGE || hazard == RoadHazard.RAILROAD) add(Condition.BUMP)
        if (wiperOn == true) add(Condition.WIPER)
        if (acOn == true) add(Condition.AC)
        if (windowOpen == true) add(Condition.WINDOW_OPEN)
        outsideTempC?.let { if (it < COLD_C) add(Condition.COLD) }
    }

    companion object {
        const val TURN_DEG = 15f
        const val ACCEL_MPS2 = 0.5f
        const val SLOPE_PCT = 3f
        const val COLD_C = 5f
        const val WHEEL_RADIUS_M = 0.36
        const val REDUCTION_RATIO = 10.65
    }
}

/**
 * Conditions a noise can be tied to. [load] says where the car's weight shifts, which is what usually makes a loose
 * part touch: braking and downhill push it forward, a left turn pushes it to the right.
 */
enum class Condition(val label: String, val short: String, val load: String? = null) {
    STOPPED("정차 중", "정차"),
    LOW_SPEED("저속(20 km/h 미만)", "저속"),
    MID_SPEED("중속(20~60 km/h)", "중속"),
    HIGH_SPEED("고속(60 km/h 이상)", "고속"),
    TURN_LEFT("좌회전", "좌회전", "오른쪽"),
    TURN_RIGHT("우회전", "우회전", "왼쪽"),
    DECEL("감속", "감속", "앞"),
    ACCEL("가속", "가속", "뒤"),
    BRAKE("브레이크 밟음", "제동", "앞"),
    DOWNHILL("내리막", "내리막", "앞"),
    UPHILL("오르막", "오르막", "뒤"),
    BUMP("방지턱·노면 충격", "방지턱", "위아래"),
    REVERSE("후진", "후진"),
    WIPER("와이퍼 작동", "와이퍼 ON"),
    AC("에어컨 작동", "에어컨 ON"),
    WINDOW_OPEN("창문 열림", "창문 열림"),
    COLD("기온 5°C 미만", "추운 날"),
    ;

    companion object {
        /** Most telling first: a road feature or manoeuvre says more than a speed range. */
        val PRIORITY = listOf(BUMP, TURN_LEFT, TURN_RIGHT, DOWNHILL, UPHILL, REVERSE, LOW_SPEED, HIGH_SPEED, DECEL, ACCEL, BRAKE, WIPER, AC, COLD, WINDOW_OPEN, MID_SPEED, STOPPED)
        /** How the conditions read in a sentence: speed, manoeuvre, road, then the car's own functions. */
        val READING_ORDER = listOf(STOPPED, LOW_SPEED, MID_SPEED, HIGH_SPEED, REVERSE, TURN_LEFT, TURN_RIGHT, DECEL, ACCEL, BRAKE, DOWNHILL, UPHILL, BUMP, WIPER, AC, WINDOW_OPEN, COLD)
    }
}

/** Where the weight goes under these conditions, e.g. "앞·오른쪽". */
fun loadShift(conditions: Collection<Condition>): String? {
    val dirs = conditions.mapNotNull { it.load }.toSet()
    val order = listOf("앞", "뒤", "왼쪽", "오른쪽", "위아래")
    val picked = order.filter { it in dirs }.filterNot { (it == "앞" && "뒤" in dirs) || (it == "뒤" && "앞" in dirs) }
    return picked.takeIf { it.isNotEmpty() }?.joinToString("·")
}

/** Builds the [DrivingContext] at a moment from the 10 Hz vehicle trace and the hazards passed. */
object ContextBuilder {
    private const val HAZARD_WINDOW_MS = 3_000L
    private const val DOWNHILL_AREA_MS = 60_000L
    private const val SLOPE_LOOKBACK_MS = 20_000L
    private const val SLOPE_DISTANCE_M = 25.0
    private const val SLOPE_FIX_MAX_AGE_MS = 8_000L
    private const val FRESH_FIX_MS = 1_500L
    private const val GPS_STALE_MS = 10_000L

    fun at(trace: List<VehicleSnapshot>, hazards: List<HazardPass>, atMs: Long): DrivingContext {
        val now = trace.lastOrNull { it.timestampMs <= atMs } ?: trace.firstOrNull() ?: return DrivingContext()
        val second = trace.lastOrNull { it.timestampMs <= atMs - 1000 }
        val accel = if (second != null && now.speedMps != null && second.speedMps != null) {
            val dt = (now.timestampMs - second.timestampMs) / 1000f
            if (dt > 0.3f) (now.speedMps - second.speedMps) / dt else null
        } else null
        val hazard = hazards.filter { it.kind != RoadHazard.DOWNHILL && abs(it.atMs - atMs) <= HAZARD_WINDOW_MS }
            .minByOrNull { abs(it.atMs - atMs) }?.kind
            ?: hazards.lastOrNull { it.kind == RoadHazard.DOWNHILL && atMs - it.atMs in 0..DOWNHILL_AREA_MS }?.kind
        val moving = (now.speedMps ?: 0f) > 1f
        return DrivingContext(
            speedKmh = now.speedKmh,
            accelMps2 = accel,
            steeringDeg = now.steeringDeg,
            brake = now.brakePressed,
            gear = now.gear,
            turnSignal = now.turnSignal,
            wiperOn = now.wiperOn,
            acOn = now.acOn,
            windowOpen = now.windowOpen,
            outsideTempC = now.outsideTempC,
            odometerKm = now.odometerKm,
            slopePct = slope(trace, atMs),
            hazard = hazard,
            roadType = roadType(now.speedLimitKmh),
            latitude = now.latitude,
            longitude = now.longitude,
            address = now.address,
            gpsWeak = moving && (now.locationAtMs == null || atMs - now.locationAtMs > GPS_STALE_MS),
        )
    }

    /**
     * Gradient over the last [SLOPE_DISTANCE_M] driven before the noise, from GPS altitude. Only fresh fixes count:
     * underground the last altitude stays on screen but no longer moves. Null when there is no recent fix or the car
     * has not moved far enough for GPS altitude to mean anything.
     */
    fun slope(trace: List<VehicleSnapshot>, atMs: Long): Float? {
        val span = trace.filter { it.timestampMs in (atMs - SLOPE_LOOKBACK_MS)..(atMs + 1000) }
        fun fresh(s: VehicleSnapshot) = s.altitudeM != null && s.locationAtMs != null && s.timestampMs - s.locationAtMs <= FRESH_FIX_MS
        val endIndex = span.indexOfLast { fresh(it) && it.timestampMs <= atMs + 1000 }
        if (endIndex < 0 || atMs - span[endIndex].timestampMs > SLOPE_FIX_MAX_AGE_MS) return null
        val end = span[endIndex]
        var distance = 0.0
        for (i in endIndex downTo 1) {
            val a = span[i - 1]
            val b = span[i]
            distance += ((a.speedMps ?: 0f) + (b.speedMps ?: 0f)) / 2.0 * (b.timestampMs - a.timestampMs) / 1000.0
            if (distance >= SLOPE_DISTANCE_M && fresh(a)) {
                return ((end.altitudeM!! - a.altitudeM!!) / distance * 100).toFloat().coerceIn(-30f, 30f)
            }
        }
        return null
    }

    fun roadType(limitKmh: Int?): RoadType? = when {
        limitKmh == null || limitKmh <= 0 -> null
        limitKmh >= 90 -> RoadType.HIGHWAY
        limitKmh >= 70 -> RoadType.EXPRESSWAY
        limitKmh >= 40 -> RoadType.ARTERIAL
        else -> RoadType.LOCAL
    }
}

/**
 * Tells when the car drives over a caution point the navigation announced: when it is right under the car, or when
 * it drops off the list right after being close (navigation apps remove passed points).
 */
class HazardTracker(private val keepMs: Long = 5 * 60_000L) {
    private var last: Map<String, HazardAhead> = emptyMap()
    private val passed = mutableSetOf<String>()
    private val _passes = ArrayDeque<HazardPass>()
    val passes: List<HazardPass> get() = _passes.toList()

    fun update(ahead: List<HazardAhead>, atMs: Long) {
        val now = ahead.associateBy { it.id }
        val crossed = now.values.filter { it.distanceM <= UNDER_CAR_M } +
            last.values.filter { it.id !in now && it.distanceM <= GONE_WITHIN_M }
        for (h in crossed) if (passed.add(h.id)) _passes.addLast(HazardPass(atMs, h.kind))
        last = now
        while (_passes.isNotEmpty() && atMs - _passes.first().atMs > keepMs) _passes.removeFirst()
    }

    fun clear() {
        last = emptyMap()
        passed.clear()
        _passes.clear()
    }

    private companion object {
        const val UNDER_CAR_M = 5.0
        const val GONE_WITHIN_M = 25.0
    }
}
