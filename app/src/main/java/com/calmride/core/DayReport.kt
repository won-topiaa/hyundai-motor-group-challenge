package com.calmride.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** The driver's working day at a glance. Holds driving numbers only, nothing about any passenger. */
data class DayReport(
    val dayStartMs: Long = 0L,
    val rides: Int = 0,
    /** Rides where the passenger turned the comfort mode on. */
    val calmRides: Int = 0,
    val distanceKm: Double = 0.0,
    val hardDecels: Int = 0,
    val coastDecels: Int = 0,
    val hardStarts: Int = 0,
    val sharpTurns: Int = 0,
    val fastBumps: Int = 0,
    val cues: Int = 0,
    val avgComfort: Double? = null,
    val comfortAnswers: Int = 0,
    /** Rough moments per ride against the previous working day; negative is better. Null without one. */
    val roughPerRideChange: Double? = null,
    val tip: CoachingTip? = null,
)

object DayReports {
    /** Night shifts run past midnight, so a working day starts at 4 a.m. */
    private const val DAY_STARTS_AT_HOUR = 4

    fun dayStart(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val start = now.toLocalDate().atTime(DAY_STARTS_AT_HOUR, 0).atZone(zone)
        return (if (now.isBefore(start)) start.minusDays(1) else start).toInstant().toEpochMilli()
    }

    fun build(profile: DriverProfile, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): DayReport {
        val start = dayStart(nowMs, zone)
        val today = profile.trips.filter { it.endedAtMs >= start }
        if (today.isEmpty()) return DayReport(dayStartMs = start)

        val earlier = profile.trips.filter { it.endedAtMs < start }
        val previous = earlier.lastOrNull()?.let { last ->
            val previousStart = dayStart(last.endedAtMs, zone)
            earlier.filter { it.endedAtMs >= previousStart }
        }
        val merged = today.map { it.stats }.reduce(::add)
        val comforts = today.mapNotNull { it.comfortScore }
        val avgComfort = comforts.takeIf { it.isNotEmpty() }?.average()
        return DayReport(
            dayStartMs = start,
            rides = today.size,
            calmRides = today.count { it.calmModeOn },
            distanceKm = merged.distanceM / 1000.0,
            hardDecels = merged.hardDecelCount,
            coastDecels = merged.coastDecelCount,
            hardStarts = merged.hardStartCount,
            sharpTurns = merged.sharpTurnCount,
            fastBumps = merged.fastBumpCount,
            cues = merged.cuesPlayed,
            avgComfort = avgComfort,
            comfortAnswers = comforts.size,
            roughPerRideChange = previous?.let { roughPerRide(today) - roughPerRide(it) },
            tip = CoachingEngine.tipFor(profile, TripRecord(start, merged, avgComfort?.roundToInt())),
        )
    }

    private fun roughPerRide(trips: List<TripRecord>): Double =
        trips.sumOf { it.stats.run { hardDecelCount + coastDecelCount + hardStartCount + sharpTurnCount + fastBumpCount } }
            .toDouble() / trips.size

    private fun add(a: TripStats, b: TripStats) = b.copy(
        durationSec = a.durationSec + b.durationSec,
        distanceM = a.distanceM + b.distanceM,
        hardDecelCount = a.hardDecelCount + b.hardDecelCount,
        brakeEvents = a.brakeEvents + b.brakeEvents,
        lateBrakeCount = a.lateBrakeCount + b.lateBrakeCount,
        coastDecelCount = a.coastDecelCount + b.coastDecelCount,
        laneChanges = a.laneChanges + b.laneChanges,
        quickLaneChanges = a.quickLaneChanges + b.quickLaneChanges,
        departures = a.departures + b.departures,
        hardStartCount = a.hardStartCount + b.hardStartCount,
        turns = a.turns + b.turns,
        sharpTurnCount = a.sharpTurnCount + b.sharpTurnCount,
        bumpPasses = a.bumpPasses + b.bumpPasses,
        fastBumpCount = a.fastBumpCount + b.fastBumpCount,
        cuesPlayed = a.cuesPlayed + b.cuesPlayed,
    )
}
