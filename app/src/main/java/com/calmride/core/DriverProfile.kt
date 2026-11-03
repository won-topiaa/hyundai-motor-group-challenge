package com.calmride.core

enum class DrivingStyle(val label: String) {
    SMOOTH("부드러운형"),
    LATE_BRAKER("늦은 제동형"),
    ONE_PEDAL("원페달형"),
    QUICK_LANE_CHANGER("잦은 차선 변경형"),
}

data class TripRecord(
    val endedAtMs: Long,
    val stats: TripStats,
    /** Passenger comfort after the trip, 1 (bad) to 5 (good). Null until answered. */
    val comfortScore: Int? = null,
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
        const val MAX_TRIPS = 20
    }
}

/** Picks the driving style that stands out most over recent trips. */
object StyleClassifier {

    fun classify(trips: List<TripStats>): DrivingStyle {
        val recent = trips.takeLast(5)
        val brakes = recent.sumOf { it.brakeEvents }
        val late = recent.sumOf { it.lateBrakeCount }
        val coast = recent.sumOf { it.coastDecelCount }
        val lanes = recent.sumOf { it.laneChanges }
        val quickLanes = recent.sumOf { it.quickLaneChanges }
        val regenHigh = recent.lastOrNull()?.regenState == RegenState.FULL

        // Each score is the metric divided by its threshold; 1.0 or more means the style applies.
        val scores = mapOf(
            DrivingStyle.LATE_BRAKER to if (brakes >= 3) (late.toDouble() / brakes) / 0.4 else 0.0,
            DrivingStyle.ONE_PEDAL to (coast.toDouble() / maxOf(brakes, 1)) / 1.5 * if (regenHigh) 1.2 else 1.0,
            DrivingStyle.QUICK_LANE_CHANGER to if (lanes >= 3) (quickLanes.toDouble() / lanes) / 0.5 else 0.0,
        )
        val best = scores.maxByOrNull { it.value } ?: return DrivingStyle.SMOOTH
        return if (best.value >= 1.0) best.key else DrivingStyle.SMOOTH
    }
}

/** Adjusts cue timing and frequency to the driver after every trip. */
object ProfileTuner {

    private fun baseLeadTime(style: DrivingStyle) = when (style) {
        DrivingStyle.LATE_BRAKER -> 2.2
        DrivingStyle.ONE_PEDAL -> 1.8
        DrivingStyle.QUICK_LANE_CHANGER -> 1.6
        DrivingStyle.SMOOTH -> 1.2
    }

    fun afterTrip(profile: DriverProfile, record: TripRecord): DriverProfile {
        val trips = (profile.trips + record).takeLast(DriverProfile.MAX_TRIPS)
        val style = StyleClassifier.classify(trips.map { it.stats })
        var lead = profile.leadTimeSec
        val base = baseLeadTime(style)
        // Move halfway toward the style's base lead time.
        lead += (base - lead) * 0.5
        val comfort = record.comfortScore
        if (comfort != null && comfort <= 2) lead += 0.3
        lead = lead.coerceIn(0.8, 3.0)
        return profile.copy(style = style, leadTimeSec = lead, trips = trips)
    }

    fun cueConfig(profile: DriverProfile) = CueConfig(
        leadTimeSec = profile.leadTimeSec,
        minIntervalMs = if (profile.style == DrivingStyle.SMOOTH) 2500 else 1500,
        brakeCuesEnabled = profile.style != DrivingStyle.SMOOTH || profile.trips.isEmpty(),
        laneCuesEnabled = true,
    )
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
