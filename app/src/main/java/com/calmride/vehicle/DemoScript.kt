package com.calmride.vehicle

import com.calmride.core.RouteEvent
import com.calmride.core.RouteEventKind
import com.calmride.core.TurnSignal

/** How the scripted demo drive is driven. The rough ones each show one driving style the app recognizes. */
enum class DemoStyle(val label: String) {
    GENTLE("부드럽게"),
    HARD_START("급출발"),
    SHARP_TURN("급회전"),
    BUMP_RUSH("방지턱 과속"),
}

/** One 100 ms step of the demo drive: what the car signals and what the navigation sees ahead. */
data class DemoStep(
    val speedMps: Float,
    val brake: Boolean,
    val signal: TurnSignal,
    val steeringDeg: Float,
    val route: List<RouteEvent>,
)

/**
 * Builds the demo drives step by step, so [SimulatedDrive] can play them and tests can check what the app learns from them.
 * Gentle: departure, a left turn, a speed bump, a lane change, a camera zone and arrival (about 80 s).
 * Rough: three turns, two bumps and two traffic lights (about 110 s), with one habit made rough.
 */
object DemoScript {
    const val STEP_MS = 100L
    private const val DT = STEP_MS / 1000.0
    private const val CRUISE = 12.0
    private const val SLOW = 5.0
    private const val START_UNTIL = 30 / 3.6
    private const val LIGHT_WAIT_STEPS = 30

    private class Plan(
        val route: List<RouteEvent>,
        val lightsAtM: List<Double> = emptyList(),
        val laneChangeAtM: Double? = null,
        val startAccel: Double = 1.5,
        val turnSpeed: Double = SLOW,
        val turnSteeringDeg: Float = 90f,
        val bumpSpeed: Double = SLOW,
    )

    private val gentleRoute = listOf(
        RouteEvent("sim-turn-left", RouteEventKind.TURN_LEFT, 180.0),
        RouteEvent("sim-bump", RouteEventKind.SPEED_BUMP, 330.0),
        RouteEvent("sim-camera", RouteEventKind.SLOW_ZONE, 520.0),
        RouteEvent("sim-turn-right", RouteEventKind.TURN_RIGHT, 650.0),
        RouteEvent("sim-arrive", RouteEventKind.ARRIVE, 760.0),
    )

    private val roughRoute = listOf(
        RouteEvent("sim-turn-left", RouteEventKind.TURN_LEFT, 150.0),
        RouteEvent("sim-bump", RouteEventKind.SPEED_BUMP, 360.0),
        RouteEvent("sim-turn-right", RouteEventKind.TURN_RIGHT, 470.0),
        RouteEvent("sim-bump-2", RouteEventKind.SPEED_BUMP, 670.0),
        RouteEvent("sim-turn-left-2", RouteEventKind.TURN_LEFT, 780.0),
        RouteEvent("sim-arrive", RouteEventKind.ARRIVE, 880.0),
    )
    private val roughLights = listOf(260.0, 570.0)

    private fun plan(style: DemoStyle) = when (style) {
        DemoStyle.GENTLE -> Plan(gentleRoute, laneChangeAtM = 420.0)
        // About 3 m/s² away from every stop.
        DemoStyle.HARD_START -> Plan(roughRoute, roughLights, startAccel = 3.0)
        // About 25 km/h through the turns with the wheel at 200°, roughly 4 m/s² sideways.
        DemoStyle.SHARP_TURN -> Plan(roughRoute, roughLights, turnSpeed = 7.0, turnSteeringDeg = 200f)
        // About 36 km/h over the bumps, above the 30 km/h they are shaped for.
        DemoStyle.BUMP_RUSH -> Plan(roughRoute, roughLights, bumpSpeed = 10.0)
    }

    fun steps(style: DemoStyle): List<DemoStep> {
        val p = plan(style)
        val end = p.route.last().distanceM
        val lights = p.lightsAtM.toMutableList()
        val out = mutableListOf<DemoStep>()
        var position = 0.0
        var speed = 0.0
        var laneStep = -1
        while (position < end) {
            val next = p.route.firstOrNull { it.distanceM > position }
            val toNext = next?.let { it.distanceM - position } ?: Double.MAX_VALUE
            val toLight = lights.firstOrNull()?.let { it - position } ?: Double.MAX_VALUE
            val target = when {
                next == null -> 0.0
                toLight < 40 -> 0.0
                next.kind == RouteEventKind.ARRIVE && toNext < 60 -> 0.0
                toNext < 35 -> when (next.kind) {
                    RouteEventKind.TURN_LEFT, RouteEventKind.TURN_RIGHT -> p.turnSpeed
                    RouteEventKind.SPEED_BUMP -> p.bumpSpeed
                    else -> SLOW
                }
                else -> CRUISE
            }
            val accel = when {
                target <= speed -> -2.2
                speed < START_UNTIL -> p.startAccel
                else -> 1.5
            }
            speed = (speed + accel * DT).coerceIn(0.0, CRUISE).let { if (accel > 0) minOf(it, target) else maxOf(it, target) }
            position += speed * DT
            val braking = target < speed - 0.5 && speed > 1.0

            // Lane change: signal for 1.2 s, then steer for 0.6 s.
            if (laneStep < 0 && p.laneChangeAtM != null && position >= p.laneChangeAtM) laneStep = 0
            val laneSignal = laneStep in 0 until 18
            val laneSteer = laneStep in 12 until 18
            if (laneStep >= 0) laneStep++

            val nearTurn = next != null && toNext < 15 && (next.kind == RouteEventKind.TURN_LEFT || next.kind == RouteEventKind.TURN_RIGHT)
            val steering = when {
                nearTurn -> if (next!!.kind == RouteEventKind.TURN_LEFT) -p.turnSteeringDeg else p.turnSteeringDeg
                laneSteer -> 20f
                else -> 0f
            }
            val route = p.route.filter { it.distanceM > position }.map { it.copy(distanceM = it.distanceM - position) }
            out += DemoStep(speed.toFloat(), braking, if (laneSignal) TurnSignal.RIGHT else TurnSignal.OFF, steering, route)

            if (speed == 0.0 && toLight < 40) {
                // Wait at the light with the brake held, then go.
                repeat(LIGHT_WAIT_STEPS) { out += DemoStep(0f, true, TurnSignal.OFF, 0f, route) }
                lights.removeAt(0)
            }
            if (speed == 0.0 && next?.kind == RouteEventKind.ARRIVE && toNext < 60) break
        }
        out += DemoStep(0f, false, TurnSignal.OFF, 0f, emptyList())
        return out
    }
}
