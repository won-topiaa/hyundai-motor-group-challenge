package com.noisetrace.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

/** Latest known vehicle state. Null fields: the car does not report them, or the app may not read them. */
data class VehicleSnapshot(
    val timestampMs: Long = 0L,
    val speedMps: Float? = null,
    val gear: Gear = Gear.UNKNOWN,
    val brakePressed: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    /** Steering wheel angle in degrees. Negative is to the left, as in AAOS PERF_STEERING_ANGLE. */
    val steeringDeg: Float? = null,
    val wiperOn: Boolean? = null,
    val acOn: Boolean? = null,
    /** Any side window is open. */
    val windowOpen: Boolean? = null,
    val outsideTempC: Float? = null,
    val odometerKm: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeM: Double? = null,
    /** When the last position fix arrived; an old fix while driving means underground or in a tunnel. */
    val locationAtMs: Long? = null,
    val address: String? = null,
    val speedLimitKmh: Int? = null,
) {
    val speedKmh: Float? get() = speedMps?.let { it * 3.6f }
}

/** Road features the navigation knows about ahead of the car (Pleos NaviHelper caution points). */
enum class RoadHazard(val label: String) {
    SPEED_BUMP("과속방지턱"),
    ROAD_DAMAGE("노면 파손 구간"),
    RAILROAD("철길 건널목"),
    DOWNHILL("내리막 구간"),
    NARROW_ROAD("좁은 길"),
}

data class HazardAhead(val id: String, val kind: RoadHazard, val distanceM: Double)

/** A hazard the car drove over (or into, for an area such as a downhill stretch). */
data class HazardPass(val atMs: Long, val kind: RoadHazard)

/** The noise types the user picks from, kept to three plus "other" so it takes one word or one tap. */
enum class NoiseType(val label: String) {
    RATTLE("달그락"),
    SQUEAK("삐걱"),
    HUM("웅웅"),
    OTHER("기타"),
}

enum class Spot(val label: String) {
    FRONT("앞쪽"),
    REAR("뒤쪽"),
    LEFT("왼쪽"),
    RIGHT("오른쪽"),
}

fun Set<Spot>.label(): String = if (isEmpty()) "모름" else Spot.entries.filter { it in this }.joinToString("·") { it.label.removeSuffix("쪽") } + "쪽"

enum class TriggerKind(val label: String) {
    /** Someone pressed "방금 소리 저장" (or said it). */
    MANUAL("직접 저장"),
    /** The cabin got much louder than this car usually is at this speed. */
    AUTO("자동 감지"),
}
