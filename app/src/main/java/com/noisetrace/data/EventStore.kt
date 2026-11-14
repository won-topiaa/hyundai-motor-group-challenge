package com.noisetrace.data

import android.content.Context
import com.noisetrace.core.Condition
import com.noisetrace.core.DayLevels
import com.noisetrace.core.DrivingContext
import com.noisetrace.core.DrivingExposure
import com.noisetrace.core.Gear
import com.noisetrace.core.NoiseEvent
import com.noisetrace.core.NoiseType
import com.noisetrace.core.RoadHazard
import com.noisetrace.core.RoadType
import com.noisetrace.core.Sensitivity
import com.noisetrace.core.SpectrumSummary
import com.noisetrace.core.Spot
import com.noisetrace.core.TracePoint
import com.noisetrace.core.TriggerKind
import com.noisetrace.core.TurnSignal
import com.noisetrace.core.Wav
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps noise events in the app's private storage on the car: one JSON and one WAV per event. Nothing leaves the
 * car; the owner shows it to the mechanic on the car's own screen. Settings, the learned usual cabin level and the
 * driving exposure live in SharedPreferences.
 */
class EventStore(context: Context) {

    private val dir = File(context.filesDir, "events").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("noisetrace", Context.MODE_PRIVATE)

    fun audioFile(name: String) = File(dir, name)

    fun loadAll(): List<NoiseEvent> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.noiseAtMs }

    /** Writes the clip first, then the event: an event never points at a missing file. Call off the main thread. */
    fun save(event: NoiseEvent, pcm: ShortArray?, sampleRate: Int) {
        if (pcm != null && event.audioFile != null) {
            val tmp = File(dir, event.audioFile + ".tmp")
            tmp.outputStream().buffered().use { Wav.write(it, pcm, sampleRate) }
            tmp.renameTo(File(dir, event.audioFile))
        }
        update(event)
    }

    fun update(event: NoiseEvent) {
        val tmp = File(dir, "${event.id}.json.tmp")
        tmp.writeText(toJson(event).toString())
        tmp.renameTo(File(dir, "${event.id}.json"))
    }

    /** Deletes only the recording; the event keeps its vehicle state. */
    fun deleteAudio(event: NoiseEvent) {
        event.audioFile?.let { File(dir, it).delete() }
    }

    fun delete(event: NoiseEvent) {
        event.audioFile?.let { File(dir, it).delete() }
        File(dir, "${event.id}.json").delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        prefs.edit().remove(KEY_EXPOSURE).remove(KEY_NUMBER).remove(KEY_LEVELS).apply()
    }

    fun nextNumber(): Int {
        val n = prefs.getInt(KEY_NUMBER, 0) + 1
        prefs.edit().putInt(KEY_NUMBER, n).apply()
        return n
    }

    fun setNumber(last: Int) = prefs.edit().putInt(KEY_NUMBER, last).apply()

    var trackingOn: Boolean
        get() = prefs.getBoolean(KEY_TRACKING, false)
        set(v) = prefs.edit().putBoolean(KEY_TRACKING, v).apply()

    var autoDetect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    var sensitivity: Sensitivity
        get() = runCatching { Sensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, null) ?: "") }.getOrDefault(Sensitivity.NORMAL)
        set(v) = prefs.edit().putString(KEY_SENSITIVITY, v.name).apply()

    /**
     * Save the address and coordinates of the noise. Off by default (Pleos: keep no personal data that is not needed):
     * the mechanic needs the road, which slope, road type and bumps already describe, not the trip.
     */
    var saveLocation: Boolean
        get() = prefs.getBoolean(KEY_LOCATION, false)
        set(v) = prefs.edit().putBoolean(KEY_LOCATION, v).apply()

    /** When the owner agreed to the microphone notice; null when never agreed or withdrawn. */
    var micConsentAtMs: Long?
        get() = prefs.getLong(KEY_CONSENT, 0L).takeIf { it > 0 }
        set(v) = prefs.edit().putLong(KEY_CONSENT, v ?: 0L).apply()

    var baseline: FloatArray?
        get() = prefs.getString(KEY_BASELINE, null)?.let { s -> runCatching { s.split(',').map { it.toFloat() }.toFloatArray() }.getOrNull() }
        set(v) = prefs.edit().putString(KEY_BASELINE, v?.joinToString(",") { "%.3f".format(java.util.Locale.US, it) }).apply()

    /** Daily usual cabin level per speed band, for the monthly report. */
    var levelHistory: List<DayLevels>
        get() {
            val a = prefs.getString(KEY_LEVELS, null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
            fun floats(arr: JSONArray?) = if (arr == null) emptyList() else List(arr.length()) { i -> if (arr.isNull(i)) null else arr.getDouble(i).toFloat() }
            return List(a.length()) { i ->
                val o = a.getJSONObject(i)
                DayLevels(o.getString("d"), floats(o.optJSONArray("f")), floats(o.optJSONArray("h")), o.optBoolean("demo"))
            }
        }
        set(v) {
            fun arr(list: List<Float?>) = JSONArray().apply { list.forEach { put(it?.let { f -> Math.round(f * 10) / 10.0 } ?: JSONObject.NULL) } }
            val a = JSONArray()
            v.forEach { d -> a.put(JSONObject().put("d", d.day).put("f", arr(d.full)).put("h", arr(d.high)).put("demo", d.demo)) }
            prefs.edit().putString(KEY_LEVELS, a.toString()).apply()
        }

    var exposure: DrivingExposure
        get() {
            val o = prefs.getString(KEY_EXPOSURE, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DrivingExposure()
            val seconds = Condition.entries.filter { o.has(it.name) }.associateWith { o.getLong(it.name) }
            return DrivingExposure(o.optLong("total"), seconds)
        }
        set(v) {
            val o = JSONObject().put("total", v.totalSec)
            v.seconds.forEach { (c, s) -> o.put(c.name, s) }
            prefs.edit().putString(KEY_EXPOSURE, o.toString()).apply()
        }

    companion object {
        private const val KEY_NUMBER = "number"
        private const val KEY_TRACKING = "tracking"
        private const val KEY_AUTO = "autoDetect"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_LOCATION = "saveLocation"
        private const val KEY_CONSENT = "micConsentAt"
        private const val KEY_BASELINE = "baseline"
        private const val KEY_EXPOSURE = "exposure"
        private const val KEY_LEVELS = "levelHistory"

        fun toJson(e: NoiseEvent): JSONObject = JSONObject().apply {
            put("id", e.id)
            put("number", e.number)
            put("noiseAt", e.noiseAtMs)
            put("savedAt", e.savedAtMs)
            put("trigger", e.trigger.name)
            put("certain", e.momentCertain)
            e.audioFile?.let { put("audio", it) }
            put("audioBytes", e.audioBytes)
            put("clipStart", e.clipStartMs)
            put("clipMs", e.clipDurationMs)
            put("level", e.levelDb.toDouble())
            e.baselineDb?.let { put("baseline", it.toDouble()) }
            e.spectrum?.let { s ->
                put("spectrum", JSONObject().apply {
                    put("bands", floats(s.bandsDb))
                    put("ref", floats(s.referenceDb))
                    put("peakHz", s.peakHz)
                    put("impulsive", s.impulsivenessDb.toDouble())
                    put("tonal", s.tonalityDb.toDouble())
                    s.estimatedType?.let { put("type", it.name) }
                })
            }
            put("context", contextJson(e.context))
            put("trace", JSONArray().apply {
                e.trace.forEach { p ->
                    put(JSONArray().put(p.tMs).put(p.speedKmh?.toDouble() ?: JSONObject.NULL).put(p.steeringDeg?.toDouble() ?: JSONObject.NULL).put(p.brake))
                }
            })
            put("excess", floats(e.excessTimeline))
            e.userType?.let { put("userType", it.name) }
            put("spots", JSONArray().apply { e.spots.forEach { put(it.name) } })
            put("confirmed", e.confirmed)
            put("demo", e.demo)
        }

        fun fromJson(o: JSONObject): NoiseEvent {
            val trace = o.optJSONArray("trace") ?: JSONArray()
            val spots = o.optJSONArray("spots") ?: JSONArray()
            return NoiseEvent(
                id = o.getString("id"),
                number = o.getInt("number"),
                noiseAtMs = o.getLong("noiseAt"),
                savedAtMs = o.getLong("savedAt"),
                trigger = TriggerKind.valueOf(o.getString("trigger")),
                momentCertain = o.optBoolean("certain", true),
                audioFile = o.optString("audio").ifEmpty { null },
                audioBytes = o.optLong("audioBytes"),
                clipStartMs = o.optLong("clipStart"),
                clipDurationMs = o.optLong("clipMs"),
                levelDb = o.optDouble("level").toFloat(),
                baselineDb = optFloat(o, "baseline"),
                spectrum = o.optJSONObject("spectrum")?.let { s ->
                    SpectrumSummary(
                        bandsDb = floatList(s.getJSONArray("bands")),
                        referenceDb = floatList(s.getJSONArray("ref")),
                        peakHz = s.optInt("peakHz"),
                        impulsivenessDb = s.optDouble("impulsive").toFloat(),
                        tonalityDb = s.optDouble("tonal").toFloat(),
                        estimatedType = s.optString("type").takeIf { it.isNotEmpty() }?.let { runCatching { NoiseType.valueOf(it) }.getOrNull() },
                    )
                },
                context = o.optJSONObject("context")?.let(::contextFromJson) ?: DrivingContext(),
                trace = List(trace.length()) { i ->
                    val p = trace.getJSONArray(i)
                    TracePoint(p.getInt(0), if (p.isNull(1)) null else p.getDouble(1).toFloat(), if (p.isNull(2)) null else p.getDouble(2).toFloat(), p.optBoolean(3))
                },
                excessTimeline = o.optJSONArray("excess")?.let(::floatList).orEmpty(),
                userType = o.optString("userType").takeIf { it.isNotEmpty() }?.let { NoiseType.valueOf(it) },
                spots = List(spots.length()) { Spot.valueOf(spots.getString(it)) }.toSet(),
                confirmed = o.optBoolean("confirmed"),
                demo = o.optBoolean("demo"),
            )
        }

        private fun contextJson(c: DrivingContext) = JSONObject().apply {
            c.speedKmh?.let { put("speed", it.toDouble()) }
            c.accelMps2?.let { put("accel", it.toDouble()) }
            c.steeringDeg?.let { put("steering", it.toDouble()) }
            put("brake", c.brake)
            put("gear", c.gear.name)
            put("signal", c.turnSignal.name)
            c.wiperOn?.let { put("wiper", it) }
            c.acOn?.let { put("ac", it) }
            c.windowOpen?.let { put("window", it) }
            c.outsideTempC?.let { put("temp", it.toDouble()) }
            c.odometerKm?.let { put("odometer", it.toDouble()) }
            c.slopePct?.let { put("slope", it.toDouble()) }
            c.hazard?.let { put("hazard", it.name) }
            c.roadType?.let { put("road", it.name) }
            c.latitude?.let { put("lat", it) }
            c.longitude?.let { put("lon", it) }
            c.address?.let { put("address", it) }
            put("gpsWeak", c.gpsWeak)
        }

        private fun contextFromJson(o: JSONObject) = DrivingContext(
            speedKmh = optFloat(o, "speed"),
            accelMps2 = optFloat(o, "accel"),
            steeringDeg = optFloat(o, "steering"),
            brake = o.optBoolean("brake"),
            gear = runCatching { Gear.valueOf(o.optString("gear")) }.getOrDefault(Gear.UNKNOWN),
            turnSignal = runCatching { TurnSignal.valueOf(o.optString("signal")) }.getOrDefault(TurnSignal.OFF),
            wiperOn = if (o.has("wiper")) o.getBoolean("wiper") else null,
            acOn = if (o.has("ac")) o.getBoolean("ac") else null,
            windowOpen = if (o.has("window")) o.getBoolean("window") else null,
            outsideTempC = optFloat(o, "temp"),
            odometerKm = optFloat(o, "odometer"),
            slopePct = optFloat(o, "slope"),
            hazard = o.optString("hazard").takeIf { it.isNotEmpty() }?.let { runCatching { RoadHazard.valueOf(it) }.getOrNull() },
            roadType = o.optString("road").takeIf { it.isNotEmpty() }?.let { runCatching { RoadType.valueOf(it) }.getOrNull() },
            latitude = if (o.has("lat")) o.getDouble("lat") else null,
            longitude = if (o.has("lon")) o.getDouble("lon") else null,
            address = o.optString("address").ifEmpty { null },
            gpsWeak = o.optBoolean("gpsWeak"),
        )

        private fun optFloat(o: JSONObject, key: String): Float? = if (o.has(key) && !o.isNull(key)) o.getDouble(key).toFloat() else null
        private fun floats(list: List<Float>) = JSONArray().apply { list.forEach { put(Math.round(it * 10) / 10.0) } }
        private fun floatList(a: JSONArray): List<Float> = List(a.length()) { a.getDouble(it).toFloat() }
    }
}
