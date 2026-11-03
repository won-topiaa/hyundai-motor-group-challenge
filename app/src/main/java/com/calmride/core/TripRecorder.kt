package com.calmride.core

import kotlin.math.abs

/** What the agent learned from one drive. */
data class TripStats(
    val durationSec: Double = 0.0,
    val distanceM: Double = 0.0,
    /** Deceleration episodes stronger than 2.5 m/s². */
    val hardDecelCount: Int = 0,
    /** Brake presses while moving. */
    val brakeEvents: Int = 0,
    /** Brake presses less than 2 s before a turn or bump. */
    val lateBrakeCount: Int = 0,
    /** Deceleration episodes stronger than 1.2 m/s² without the brake pedal (regen / one-pedal). */
    val coastDecelCount: Int = 0,
    val laneChanges: Int = 0,
    /** Lane changes that started less than 1 s after the turn signal. */
    val quickLaneChanges: Int = 0,
    val cuesPlayed: Int = 0,
    val regenState: RegenState = RegenState.UNKNOWN,
    val regenLevel: Int? = null,
    /** Where hard decelerations happened, as (latitude, longitude). */
    val hardDecelSpots: List<Pair<Double, Double>> = emptyList(),
)

/** Collects driving-style signals during a trip. */
class TripRecorder(private val startMs: Long) {

    private var stats = TripStats()
    private var lastSpeed: Float? = null
    private var lastSpeedAtMs = 0L
    private var smoothedAccel = 0.0
    private var inHardDecel = false
    private var inCoastDecel = false
    private var lastBrake = false
    private var lastBrakeAtMs = Long.MIN_VALUE / 2
    private var lastSignal = TurnSignal.OFF
    private var signalAtMs: Long? = null
    private var steeringAtSignal = 0f
    private var routeAhead: List<RouteEvent> = emptyList()
    private var lastTimestampMs = startMs

    fun onRouteEvents(events: List<RouteEvent>) {
        routeAhead = events
    }

    fun onCue() {
        stats = stats.copy(cuesPlayed = stats.cuesPlayed + 1)
    }

    fun onVehicle(v: VehicleSnapshot) {
        lastTimestampMs = maxOf(lastTimestampMs, v.timestampMs)
        stats = stats.copy(regenState = v.regenState, regenLevel = v.regenLevel ?: stats.regenLevel)
        if (v.brakePressed) lastBrakeAtMs = v.timestampMs
        trackSpeed(v)
        trackBrake(v)
        trackLaneChange(v)
    }

    private fun trackSpeed(v: VehicleSnapshot) {
        val speed = v.speedMps ?: return
        val prev = lastSpeed
        if (prev != null) {
            val dt = (v.timestampMs - lastSpeedAtMs) / 1000.0
            if (dt < 0.05) return
            stats = stats.copy(distanceM = stats.distanceM + (prev + speed) / 2.0 * dt)
            val accel = (speed - prev) / dt
            smoothedAccel = 0.5 * smoothedAccel + 0.5 * accel
            if (!inHardDecel && smoothedAccel <= -2.5) {
                inHardDecel = true
                val spot = if (v.latitude != null && v.longitude != null) listOf(v.latitude to v.longitude) else emptyList()
                stats = stats.copy(
                    hardDecelCount = stats.hardDecelCount + 1,
                    hardDecelSpots = (stats.hardDecelSpots + spot).takeLast(20),
                )
            } else if (inHardDecel && smoothedAccel > -1.0) {
                inHardDecel = false
            }
            // Ignore the tail of a braked stop: only count slowdowns at least 1 s after the pedal was released.
            val coasting = !v.brakePressed && v.timestampMs - lastBrakeAtMs > 1000
            if (!inCoastDecel && coasting && smoothedAccel <= -1.2) {
                inCoastDecel = true
                stats = stats.copy(coastDecelCount = stats.coastDecelCount + 1)
            } else if (inCoastDecel && (smoothedAccel > -0.5 || v.brakePressed)) {
                inCoastDecel = false
            }
        }
        lastSpeed = speed
        lastSpeedAtMs = v.timestampMs
    }

    private fun trackBrake(v: VehicleSnapshot) {
        val rising = v.brakePressed && !lastBrake
        lastBrake = v.brakePressed
        val speed = v.speedMps ?: return
        if (!rising || speed < 1.5f) return
        val nearest = routeAhead
            .filter { it.kind.toCueKind() != CueKind.SLOW_DOWN || it.kind == RouteEventKind.ARRIVE }
            .minByOrNull { it.distanceM }
        val late = nearest != null && nearest.distanceM / speed < 2.0
        stats = stats.copy(
            brakeEvents = stats.brakeEvents + 1,
            lateBrakeCount = stats.lateBrakeCount + if (late) 1 else 0,
        )
    }

    private fun trackLaneChange(v: VehicleSnapshot) {
        if (v.turnSignal != TurnSignal.OFF && v.turnSignal != lastSignal) {
            signalAtMs = v.timestampMs
            steeringAtSignal = v.steeringDeg
        }
        lastSignal = v.turnSignal
        val at = signalAtMs ?: return
        if (abs(v.steeringDeg - steeringAtSignal) >= 15f) {
            val quick = v.timestampMs - at < 1000
            stats = stats.copy(
                laneChanges = stats.laneChanges + 1,
                quickLaneChanges = stats.quickLaneChanges + if (quick) 1 else 0,
            )
            signalAtMs = null
        } else if (v.timestampMs - at > 10_000) {
            signalAtMs = null
        }
    }

    fun finish(): TripStats = stats.copy(durationSec = (lastTimestampMs - startMs) / 1000.0)
}
