package com.calmride.core

/** How early and how often cues play. Tuned per driver by [ProfileTuner]. */
data class CueConfig(
    val leadTimeSec: Double = 1.5,
    val minIntervalMs: Long = 1500,
    /** At most this many cues in any 60 seconds, so city driving does not turn into constant beeping. */
    val maxCuesPerMinute: Int = 6,
    val brakeCuesEnabled: Boolean = true,
    val laneCuesEnabled: Boolean = true,
    /** Below this speed the car counts as stopped and no cues play. */
    val minMovingSpeedMps: Double = 1.5,
    /** Route events farther than this are ignored even at high speed. */
    val maxLeadDistanceM: Double = 150.0,
)

/**
 * Decides when to play a cue so passengers know about a turn or a slowdown before it happens.
 *
 * Route events (next turn, speed bump) fire once when the time to reach them drops below the
 * lead time. Brake presses and turn signals fire on their rising edge. Every cue respects the
 * minimum gap and the per-minute cap in [CueConfig].
 */
class CueEngine(private var config: CueConfig = CueConfig()) {

    private val fired = mutableSetOf<String>()
    private val lastDistance = mutableMapOf<String, Double>()
    private var lastCueAtMs = Long.MIN_VALUE / 2
    private val recentCueTimes = ArrayDeque<Long>()
    private var lastSlowCueAtMs = Long.MIN_VALUE / 2
    private var lastBrake = false
    private var lastSignal = TurnSignal.OFF

    fun updateConfig(newConfig: CueConfig) {
        config = newConfig
    }

    fun reset() {
        fired.clear()
        lastDistance.clear()
        lastCueAtMs = Long.MIN_VALUE / 2
        recentCueTimes.clear()
        lastSlowCueAtMs = Long.MIN_VALUE / 2
        lastBrake = false
        lastSignal = TurnSignal.OFF
    }

    /** True when both the minimum gap and the per-minute cap allow a cue at [now]. */
    private fun canPlay(now: Long): Boolean {
        while (recentCueTimes.isNotEmpty() && now - recentCueTimes.first() >= 60_000) recentCueTimes.removeFirst()
        return now - lastCueAtMs >= config.minIntervalMs && recentCueTimes.size < config.maxCuesPerMinute
    }

    private fun markPlayed(now: Long) {
        lastCueAtMs = now
        recentCueTimes.addLast(now)
    }

    /** Seconds until the car reaches [event] at the current speed, or null when stopped or unknown. */
    fun timeToReach(event: RouteEvent, vehicle: VehicleSnapshot): Double? {
        val speed = vehicle.speedMps?.toDouble() ?: return null
        if (speed < config.minMovingSpeedMps) return null
        return event.distanceM / speed
    }

    fun onRouteEvents(events: List<RouteEvent>, vehicle: VehicleSnapshot): List<Cue> {
        val now = vehicle.timestampMs
        val cues = mutableListOf<Cue>()
        for (event in events.sortedBy { it.distanceM }) {
            val previous = lastDistance[event.id]
            lastDistance[event.id] = event.distanceM
            // The same maneuver id came back farther away: it is a new occurrence (for example a re-route).
            if (previous != null && event.distanceM > previous + 50.0) fired.remove(event.id)
            if (event.id in fired) continue
            if (event.distanceM > config.maxLeadDistanceM) continue
            val tta = timeToReach(event, vehicle) ?: continue
            if (tta > config.leadTimeSec) continue
            if (!canPlay(now)) continue
            val kind = event.kind.toCueKind()
            cues += Cue(kind, kind.pan(), "${event.kind.label()} ${event.distanceM.toInt()}m 앞", now)
            fired += event.id
            markPlayed(now)
            if (kind == CueKind.SLOW_DOWN || kind == CueKind.BUMP) lastSlowCueAtMs = now
        }
        return cues
    }

    fun onVehicle(vehicle: VehicleSnapshot): List<Cue> {
        val now = vehicle.timestampMs
        val moving = (vehicle.speedMps ?: 0f) >= config.minMovingSpeedMps
        val cues = mutableListOf<Cue>()

        val brakeRising = vehicle.brakePressed && !lastBrake
        lastBrake = vehicle.brakePressed
        // Skip when a route cue already announced this slowdown in the last 3 seconds.
        if (brakeRising && moving && config.brakeCuesEnabled &&
            now - lastSlowCueAtMs > 3000 && canPlay(now)
        ) {
            cues += Cue(CueKind.BRAKE, 0f, "브레이크 작동", now)
            markPlayed(now)
            lastSlowCueAtMs = now
        }

        val signalRising = vehicle.turnSignal != TurnSignal.OFF && vehicle.turnSignal != lastSignal
        lastSignal = vehicle.turnSignal
        if (signalRising && moving && config.laneCuesEnabled && canPlay(now)) {
            val kind = if (vehicle.turnSignal == TurnSignal.LEFT) CueKind.LANE_LEFT else CueKind.LANE_RIGHT
            cues += Cue(kind, kind.pan(), "방향지시등", now)
            markPlayed(now)
        }
        return cues
    }
}
