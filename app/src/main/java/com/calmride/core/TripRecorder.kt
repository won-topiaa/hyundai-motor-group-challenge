package com.calmride.core

import kotlin.math.abs
import kotlin.math.tan

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
    /** Starts from a standstill. */
    val departures: Int = 0,
    /** Starts that accelerated harder than 2.5 m/s² before reaching 30 km/h. */
    val hardStartCount: Int = 0,
    /** Turns taken with the steering wheel past 45° while moving. */
    val turns: Int = 0,
    /** Turns whose estimated sideways acceleration passed 3 m/s². */
    val sharpTurnCount: Int = 0,
    /** Speed bumps on the route that the car drove over. */
    val bumpPasses: Int = 0,
    /** Bumps crossed faster than 30 km/h, the speed round bumps are shaped for. */
    val fastBumpCount: Int = 0,
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
    private var departing = false
    private var hardStartCounted = false
    private var inTurn = false
    private var turnCountedSharp = false
    private var lastBumps: Map<String, Double> = emptyMap()
    private val crossedBumps = mutableSetOf<String>()

    fun onRouteEvents(events: List<RouteEvent>) {
        // A bump is crossed when it is right under the car, or when it drops off the route after being close.
        val bumps = events.filter { it.kind == RouteEventKind.SPEED_BUMP }.associate { it.id to it.distanceM }
        val crossed = bumps.filterValues { it <= BUMP_UNDER_CAR_M }.keys +
            lastBumps.filter { (id, d) -> id !in bumps && d <= BUMP_GONE_WITHIN_M }.keys
        for (id in crossed) if (crossedBumps.add(id)) countBump()
        lastBumps = bumps
        routeAhead = events
    }

    private fun countBump() {
        val speed = lastSpeed ?: return
        stats = stats.copy(
            bumpPasses = stats.bumpPasses + 1,
            fastBumpCount = stats.fastBumpCount + if (speed > BUMP_SPEED_MPS) 1 else 0,
        )
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
        trackTurn(v)
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
            // A start runs from leaving a standstill until 30 km/h; a hard one pulls more than 2.5 m/s² on the way.
            if (prev < 0.5f && speed >= 0.5f) {
                departing = true
                hardStartCounted = false
                stats = stats.copy(departures = stats.departures + 1)
            } else if (departing && (speed >= START_UNTIL_MPS || speed < 0.5f)) {
                departing = false
            }
            if (departing && !hardStartCounted && smoothedAccel >= 2.5) {
                hardStartCounted = true
                stats = stats.copy(hardStartCount = stats.hardStartCount + 1)
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

    private fun trackTurn(v: VehicleSnapshot) {
        val speed = v.speedMps ?: return
        val angle = abs(v.steeringDeg)
        if (!inTurn && angle >= TURN_STEERING_DEG && speed > 1.5f) {
            inTurn = true
            turnCountedSharp = false
            stats = stats.copy(turns = stats.turns + 1)
        }
        if (inTurn && !turnCountedSharp && sidewaysAccel(speed, angle) >= SHARP_TURN_MPS2) {
            turnCountedSharp = true
            stats = stats.copy(sharpTurnCount = stats.sharpTurnCount + 1)
        }
        if (inTurn && angle < TURN_STEERING_DEG / 2) inTurn = false
    }

    fun finish(): TripStats = stats.copy(durationSec = (lastTimestampMs - startMs) / 1000.0)

    companion object {
        const val BUMP_SPEED_MPS = 30 / 3.6f
        private const val START_UNTIL_MPS = 30 / 3.6f
        private const val BUMP_UNDER_CAR_M = 3.0
        private const val BUMP_GONE_WITHIN_M = 15.0
        private const val TURN_STEERING_DEG = 45f
        private const val SHARP_TURN_MPS2 = 3.0
        // Typical mid-size EV values (IONIQ 5 wheelbase is 3.0 m). Close enough to tell a gentle turn from a sharp one.
        private const val STEERING_RATIO = 14.0
        private const val WHEELBASE_M = 3.0

        /** Sideways acceleration from a bicycle model: speed² · tan(road wheel angle) / wheelbase. */
        fun sidewaysAccel(speedMps: Float, steeringDeg: Float): Double {
            val wheel = Math.toRadians(abs(steeringDeg) / STEERING_RATIO)
            return speedMps.toDouble() * speedMps * tan(wheel) / WHEELBASE_M
        }
    }
}
