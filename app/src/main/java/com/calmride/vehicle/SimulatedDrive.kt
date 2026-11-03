package com.calmride.vehicle

import com.calmride.core.Gear
import com.calmride.core.RouteEvent
import com.calmride.core.RouteEventKind
import com.calmride.core.TurnSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A scripted 70-second drive for demos when no car or Virtual Console scenario is available:
 * departure, a left turn, a speed bump, a lane change, a camera zone and arrival.
 */
class SimulatedDrive(
    private val scope: CoroutineScope,
    private val onSpeed: (Float) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onBrake: (Boolean) -> Unit,
    private val onSignal: (TurnSignal) -> Unit,
    private val onSteering: (Float) -> Unit,
    private val onRoute: (List<RouteEvent>) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    val running get() = job?.isActive == true

    private data class Waypoint(val id: String, val kind: RouteEventKind, val atM: Double)

    fun start() {
        if (running) return
        job = scope.launch {
            val route = listOf(
                Waypoint("sim-turn-left", RouteEventKind.TURN_LEFT, 180.0),
                Waypoint("sim-bump", RouteEventKind.SPEED_BUMP, 330.0),
                Waypoint("sim-camera", RouteEventKind.SLOW_ZONE, 520.0),
                Waypoint("sim-turn-right", RouteEventKind.TURN_RIGHT, 650.0),
                Waypoint("sim-arrive", RouteEventKind.ARRIVE, 760.0),
            )
            onGear(Gear.PARK); onSpeed(0f); delay(1500)
            onGear(Gear.DRIVE); delay(800)

            var position = 0.0
            var speed = 0.0
            var t = 0.0
            val dt = 0.1
            var laneChangeDone = false
            while (isActive && position < 760.0) {
                val next = route.firstOrNull { it.atM > position }
                val toNext = next?.let { it.atM - position } ?: Double.MAX_VALUE
                // Cruise at 12 m/s, slow to 5 m/s near turns, bumps and the camera.
                val target = when {
                    next == null -> 0.0
                    next.kind == RouteEventKind.ARRIVE && toNext < 60 -> 0.0
                    toNext < 35 -> 5.0
                    else -> 12.0
                }
                val accel = if (target > speed) 1.5 else -2.2
                val braking = target < speed - 0.5
                speed = (speed + accel * dt).coerceIn(0.0, 12.0).let { if (accel > 0) minOf(it, target) else maxOf(it, target) }
                position += speed * dt
                t += dt
                onSpeed(speed.toFloat())
                onBrake(braking && speed > 1.0)

                // Lane change halfway between the bump and the camera.
                if (!laneChangeDone && position in 420.0..430.0) {
                    onSignal(TurnSignal.RIGHT); delay(1200); onSteering(20f); delay(600); onSteering(0f); onSignal(TurnSignal.OFF)
                    laneChangeDone = true
                }
                val nearTurn = next != null && toNext < 15 && (next.kind == RouteEventKind.TURN_LEFT || next.kind == RouteEventKind.TURN_RIGHT)
                onSteering(if (nearTurn) (if (next!!.kind == RouteEventKind.TURN_LEFT) -90f else 90f) else 0f)

                onRoute(route.filter { it.atM > position }.map { RouteEvent(it.id, it.kind, it.atM - position) })
                if (speed == 0.0 && next?.kind == RouteEventKind.ARRIVE && toNext < 60) break
                delay((dt * 1000).toLong())
            }
            onBrake(false); onSpeed(0f); onRoute(emptyList())
            delay(1000)
            onGear(Gear.PARK)
            onFinished()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
