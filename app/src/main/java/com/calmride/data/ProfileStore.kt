package com.calmride.data

import android.content.Context
import com.calmride.core.DriverProfile
import com.calmride.core.DrivingStyle
import com.calmride.core.PrimingBalance
import com.calmride.core.RegenState
import com.calmride.core.TripRecord
import com.calmride.core.TripStats
import org.json.JSONArray
import org.json.JSONObject

/** Keeps driver profiles and car settings on the vehicle only (SharedPreferences). Nothing is sent off the car. */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("calmride_profiles", Context.MODE_PRIVATE)

    fun load(): List<DriverProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return listOf(defaultProfile())
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { profileFromJson(arr.getJSONObject(it)) }
        }.getOrNull()?.ifEmpty { null } ?: listOf(defaultProfile())
    }

    fun save(profiles: List<DriverProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(profileToJson(it)) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    /** How much louder the far-side speakers play the 100Hz tone (dB), measured once per car. */
    var balanceBoostDb: Float
        get() = prefs.getFloat(KEY_BALANCE, PrimingBalance.DEFAULT_BOOST_DB)
        set(value) = prefs.edit().putFloat(KEY_BALANCE, value).apply()

    /** Taxi option: ask the passenger at boarding whether to hear the 1-minute tone before departure. */
    var taxiMode: Boolean
        get() = prefs.getBoolean(KEY_TAXI, false)
        set(value) = prefs.edit().putBoolean(KEY_TAXI, value).apply()

    fun clearAll() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active"
        private const val KEY_BALANCE = "balanceBoostDb"
        private const val KEY_TAXI = "taxiMode"

        fun defaultProfile() = DriverProfile(id = "driver-1", name = "운전자 1")

        fun profileToJson(p: DriverProfile): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            p.seatForeAft?.let { put("seatForeAft", it) }
            p.seatHeight?.let { put("seatHeight", it) }
            put("leadTimeSec", p.leadTimeSec)
            put("cueVolume", p.cueVolume.toDouble())
            put("style", p.style.name)
            put("trips", JSONArray().apply { p.trips.forEach { put(tripToJson(it)) } })
        }

        fun profileFromJson(o: JSONObject): DriverProfile {
            val tripsJson = o.optJSONArray("trips") ?: JSONArray()
            return DriverProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                seatForeAft = if (o.has("seatForeAft")) o.getInt("seatForeAft") else null,
                seatHeight = if (o.has("seatHeight")) o.getInt("seatHeight") else null,
                leadTimeSec = o.optDouble("leadTimeSec", 1.5),
                cueVolume = o.optDouble("cueVolume", 0.6).toFloat(),
                style = runCatching { DrivingStyle.valueOf(o.optString("style")) }.getOrDefault(DrivingStyle.SMOOTH),
                trips = List(tripsJson.length()) { tripFromJson(tripsJson.getJSONObject(it)) },
            )
        }

        private fun tripToJson(t: TripRecord): JSONObject = JSONObject().apply {
            put("endedAtMs", t.endedAtMs)
            t.comfortScore?.let { put("comfort", it) }
            val s = t.stats
            put("durationSec", s.durationSec)
            put("distanceM", s.distanceM)
            put("hardDecel", s.hardDecelCount)
            put("brakes", s.brakeEvents)
            put("lateBrakes", s.lateBrakeCount)
            put("coastDecel", s.coastDecelCount)
            put("laneChanges", s.laneChanges)
            put("quickLanes", s.quickLaneChanges)
            put("departures", s.departures)
            put("hardStarts", s.hardStartCount)
            put("turns", s.turns)
            put("sharpTurns", s.sharpTurnCount)
            put("bumps", s.bumpPasses)
            put("fastBumps", s.fastBumpCount)
            put("cues", s.cuesPlayed)
            put("regenState", s.regenState.name)
            s.regenLevel?.let { put("regenLevel", it) }
            put("spots", JSONArray().apply { s.hardDecelSpots.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        }

        private fun tripFromJson(o: JSONObject): TripRecord {
            val spots = o.optJSONArray("spots") ?: JSONArray()
            return TripRecord(
                endedAtMs = o.getLong("endedAtMs"),
                comfortScore = if (o.has("comfort")) o.getInt("comfort") else null,
                stats = TripStats(
                    durationSec = o.optDouble("durationSec"),
                    distanceM = o.optDouble("distanceM"),
                    hardDecelCount = o.optInt("hardDecel"),
                    brakeEvents = o.optInt("brakes"),
                    lateBrakeCount = o.optInt("lateBrakes"),
                    coastDecelCount = o.optInt("coastDecel"),
                    laneChanges = o.optInt("laneChanges"),
                    quickLaneChanges = o.optInt("quickLanes"),
                    departures = o.optInt("departures"),
                    hardStartCount = o.optInt("hardStarts"),
                    turns = o.optInt("turns"),
                    sharpTurnCount = o.optInt("sharpTurns"),
                    bumpPasses = o.optInt("bumps"),
                    fastBumpCount = o.optInt("fastBumps"),
                    cuesPlayed = o.optInt("cues"),
                    regenState = runCatching { RegenState.valueOf(o.optString("regenState")) }.getOrDefault(RegenState.UNKNOWN),
                    regenLevel = if (o.has("regenLevel")) o.getInt("regenLevel") else null,
                    hardDecelSpots = List(spots.length()) { spots.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } },
                ),
            )
        }
    }
}
