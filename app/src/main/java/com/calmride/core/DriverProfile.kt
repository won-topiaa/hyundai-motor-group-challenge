package com.calmride.core

enum class DrivingStyle(val label: String) {
    SMOOTH("부드러운형"),
    LATE_BRAKER("늦은 제동형"),
    ONE_PEDAL("원페달형"),
    QUICK_LANE_CHANGER("잦은 차선 변경형"),
    HARD_STARTER("급출발형"),
    SHARP_TURNER("급회전형"),
    BUMP_RUSHER("방지턱 과속형"),
}

data class TripRecord(
    val endedAtMs: Long,
    val stats: TripStats,
    /** Passenger comfort for the ride, 1 (bad) to 5 (good). Null if the passenger did not answer. */
    val comfortScore: Int? = null,
    /** The passenger had the comfort mode on during this ride. */
    val calmModeOn: Boolean = false,
)

data class DriverProfile(
    val id: String,
    val name: String,
    val seatForeAft: Int? = null,
    val seatHeight: Int? = null,
    val leadTimeSec: Double = 1.5,
    val cueVolume: Float = 0.6f,
    val style: DrivingStyle = DrivingStyle.SMOOTH,
    val trips: List<TripRecord> = emptyList(),
) {
    companion object {
        /** About ten working days of taxi rides. */
        const val MAX_TRIPS = 300
    }
}

/** Picks the driving style that stands out most over recent rides. */
object StyleClassifier {

    /** Roughly a working day: taxi rides are short and many, so a few of them say little about the driver. */
    private const val RECENT_RIDES = 20

    fun classify(trips: List<TripStats>): DrivingStyle {
        val recent = trips.takeLast(RECENT_RIDES)
        val brakes = recent.sumOf { it.brakeEvents }
        val late = recent.sumOf { it.lateBrakeCount }
        val coast = recent.sumOf { it.coastDecelCount }
        val lanes = recent.sumOf { it.laneChanges }
        val quickLanes = recent.sumOf { it.quickLaneChanges }
        val regenHigh = recent.lastOrNull()?.regenState == RegenState.FULL
        val starts = recent.sumOf { it.departures }
        val hardStarts = recent.sumOf { it.hardStartCount }
        val turns = recent.sumOf { it.turns }
        val sharpTurns = recent.sumOf { it.sharpTurnCount }
        val bumps = recent.sumOf { it.bumpPasses }
        val fastBumps = recent.sumOf { it.fastBumpCount }

        // Each score is the metric divided by its threshold; 1.0 or more means the style applies.
        val scores = mapOf(
            DrivingStyle.LATE_BRAKER to if (brakes >= 3) (late.toDouble() / brakes) / 0.4 else 0.0,
            DrivingStyle.ONE_PEDAL to (coast.toDouble() / maxOf(brakes, 1)) / 1.5 * if (regenHigh) 1.2 else 1.0,
            DrivingStyle.QUICK_LANE_CHANGER to if (lanes >= 3) (quickLanes.toDouble() / lanes) / 0.5 else 0.0,
            DrivingStyle.HARD_STARTER to if (starts >= 3) (hardStarts.toDouble() / starts) / 0.3 else 0.0,
            DrivingStyle.SHARP_TURNER to if (turns >= 3) (sharpTurns.toDouble() / turns) / 0.3 else 0.0,
            DrivingStyle.BUMP_RUSHER to if (bumps >= 2) (fastBumps.toDouble() / bumps) / 0.5 else 0.0,
        )
        val best = scores.maxByOrNull { it.value } ?: return DrivingStyle.SMOOTH
        return if (best.value >= 1.0) best.key else DrivingStyle.SMOOTH
    }
}

/** Adjusts cue timing and frequency to the driver after every ride. */
object ProfileTuner {

    // Small steps: a taxi has many rides a day, and one passenger's score should not swing the timing for the next.
    private const val TUNE_STEP = 0.2
    private const val LOW_COMFORT_LEAD_SEC = 0.1

    private fun baseLeadTime(style: DrivingStyle) = when (style) {
        DrivingStyle.LATE_BRAKER -> 2.2
        DrivingStyle.ONE_PEDAL -> 1.8
        DrivingStyle.QUICK_LANE_CHANGER -> 1.6
        DrivingStyle.SHARP_TURNER, DrivingStyle.BUMP_RUSHER -> 2.0
        // Cues forecast turns and bumps, not starts, so a hard starter keeps the default timing.
        DrivingStyle.HARD_STARTER -> 1.5
        DrivingStyle.SMOOTH -> 1.2
    }

    fun afterTrip(profile: DriverProfile, record: TripRecord): DriverProfile {
        val trips = (profile.trips + record).takeLast(DriverProfile.MAX_TRIPS)
        val style = StyleClassifier.classify(trips.map { it.stats })
        var lead = profile.leadTimeSec
        val base = baseLeadTime(style)
        lead += (base - lead) * TUNE_STEP
        val comfort = record.comfortScore
        if (comfort != null && comfort <= 2) lead += LOW_COMFORT_LEAD_SEC
        lead = lead.coerceIn(0.8, 3.0)
        return profile.copy(style = style, leadTimeSec = lead, trips = trips)
    }

    /**
     * With route guidance the known slowdowns are already announced, and the driver hears every cue all day,
     * so the brake alert stays off. Without guidance it is the only slowdown signal left.
     */
    fun cueConfig(profile: DriverProfile, routeGuided: Boolean): CueConfig {
        // Fewer cues only for a smooth driver whose passenger was comfortable last time.
        val calm = profile.style == DrivingStyle.SMOOTH && (profile.trips.lastOrNull()?.comfortScore ?: 0) >= 4
        return CueConfig(
            leadTimeSec = profile.leadTimeSec,
            minIntervalMs = if (calm) 2500 else 1500,
            brakeCuesEnabled = !calm && !routeGuided,
            laneCuesEnabled = true,
        )
    }
}

/** Recognizes the driver from the seat position, since each driver sets the seat differently. */
object DriverIdentifier {

    fun match(profiles: List<DriverProfile>, foreAft: Int?, height: Int?, tolerance: Int = 15): DriverProfile? {
        if (foreAft == null && height == null) return null
        return profiles
            .filter { it.seatForeAft != null || it.seatHeight != null }
            .map { p ->
                val d1 = if (foreAft != null && p.seatForeAft != null) kotlin.math.abs(foreAft - p.seatForeAft) else 0
                val d2 = if (height != null && p.seatHeight != null) kotlin.math.abs(height - p.seatHeight) else 0
                p to d1 + d2
            }
            .filter { it.second <= tolerance * 2 }
            .minByOrNull { it.second }
            ?.first
    }
}
