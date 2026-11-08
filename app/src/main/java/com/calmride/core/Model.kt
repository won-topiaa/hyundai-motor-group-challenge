package com.calmride.core

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

/** Mirrors AAOS EV_REGENERATIVE_BRAKING_STATE. */
enum class RegenState { UNKNOWN, DISABLED, PARTIAL, FULL }

/** Latest known vehicle state. Fields are null when the vehicle does not report them. */
data class VehicleSnapshot(
    val timestampMs: Long = 0L,
    val speedMps: Float? = null,
    val gear: Gear = Gear.UNKNOWN,
    val brakePressed: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val steeringDeg: Float = 0f,
    val regenState: RegenState = RegenState.UNKNOWN,
    val regenLevel: Int? = null,
    /** Any passenger seat is taken. */
    val passengerOnBoard: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

enum class RouteEventKind { TURN_LEFT, TURN_RIGHT, U_TURN, KEEP_LEFT, KEEP_RIGHT, SPEED_BUMP, DOWNHILL, SLOW_ZONE, ARRIVE }

/** Something on the route ahead that will make the car turn or slow down. */
data class RouteEvent(val id: String, val kind: RouteEventKind, val distanceM: Double)

/**
 * Turn, slow-down and bump cues come from the route ahead, so they play before the motion.
 * [BRAKE] comes from the brake pedal and plays as the car starts to slow, so it is an alert, not a forecast.
 */
enum class CueKind { TURN_LEFT, TURN_RIGHT, SLOW_DOWN, BUMP, LANE_LEFT, LANE_RIGHT, BRAKE }

/** A cue sound to play. [pan] runs from -1 (left speakers) to 1 (right speakers). */
data class Cue(val kind: CueKind, val pan: Float, val reason: String, val atMs: Long)

fun CueKind.pan(): Float = when (this) {
    CueKind.TURN_LEFT, CueKind.LANE_LEFT -> -0.8f
    CueKind.TURN_RIGHT, CueKind.LANE_RIGHT -> 0.8f
    CueKind.SLOW_DOWN, CueKind.BUMP, CueKind.BRAKE -> 0f
}

fun RouteEventKind.toCueKind(): CueKind = when (this) {
    RouteEventKind.TURN_LEFT, RouteEventKind.KEEP_LEFT, RouteEventKind.U_TURN -> CueKind.TURN_LEFT
    RouteEventKind.TURN_RIGHT, RouteEventKind.KEEP_RIGHT -> CueKind.TURN_RIGHT
    RouteEventKind.SPEED_BUMP -> CueKind.BUMP
    RouteEventKind.DOWNHILL, RouteEventKind.SLOW_ZONE, RouteEventKind.ARRIVE -> CueKind.SLOW_DOWN
}

fun RouteEventKind.label(): String = when (this) {
    RouteEventKind.TURN_LEFT -> "좌회전"
    RouteEventKind.TURN_RIGHT -> "우회전"
    RouteEventKind.U_TURN -> "유턴"
    RouteEventKind.KEEP_LEFT -> "왼쪽 방향"
    RouteEventKind.KEEP_RIGHT -> "오른쪽 방향"
    RouteEventKind.SPEED_BUMP -> "과속방지턱"
    RouteEventKind.DOWNHILL -> "내리막"
    RouteEventKind.SLOW_ZONE -> "감속 구간"
    RouteEventKind.ARRIVE -> "도착"
}

fun CueKind.label(): String = when (this) {
    CueKind.TURN_LEFT -> "좌회전 예고"
    CueKind.TURN_RIGHT -> "우회전 예고"
    CueKind.SLOW_DOWN -> "감속 예고"
    CueKind.BUMP -> "방지턱 예고"
    CueKind.LANE_LEFT -> "왼쪽 차선 변경"
    CueKind.LANE_RIGHT -> "오른쪽 차선 변경"
    CueKind.BRAKE -> "감속 알림"
}
