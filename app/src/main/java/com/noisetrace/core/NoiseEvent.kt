package com.noisetrace.core

/** One point of the vehicle trace kept with an event, relative to the noise moment. */
data class TracePoint(val tMs: Int, val speedKmh: Float?, val steeringDeg: Float?, val brake: Boolean)

/** One saved "소음 사건": the sound, the car around it, and what the user said about it. */
data class NoiseEvent(
    val id: String,
    /** Running number shown as "이상 소음 #03". */
    val number: Int,
    /** Best estimate of when the sound happened; all context is taken at this moment. */
    val noiseAtMs: Long,
    val savedAtMs: Long,
    val trigger: TriggerKind,
    val momentCertain: Boolean = true,
    /** File name of the WAV clip in the app's private storage. */
    val audioFile: String? = null,
    val audioBytes: Long = 0,
    val clipStartMs: Long = noiseAtMs,
    val clipDurationMs: Long = 0,
    val levelDb: Float = 0f,
    val baselineDb: Float? = null,
    val spectrum: SpectrumSummary? = null,
    val context: DrivingContext = DrivingContext(),
    val trace: List<TracePoint> = emptyList(),
    /** Excess over usual (dB) every 100 ms across the clip, for the mechanic's timeline. */
    val excessTimeline: List<Float> = emptyList(),
    val userType: NoiseType? = null,
    val spots: Set<Spot> = emptySet(),
    /** Someone confirmed it (a press, a tag, or "keep" in the list); unconfirmed auto captures are deleted first. */
    val confirmed: Boolean = trigger == TriggerKind.MANUAL,
    val demo: Boolean = false,
) {
    val excessDb: Float? get() = baselineDb?.let { levelDb - it }
    val type: NoiseType? get() = userType ?: spectrum?.estimatedType
    val tagged: Boolean get() = userType != null
    val noiseOffsetMs: Long get() = noiseAtMs - clipStartMs
    val conditions: Set<Condition> get() = context.conditions()
}

/** Keeps the car's storage bounded: unconfirmed automatic captures go first, then the oldest. */
object StoragePolicy {
    const val MAX_EVENTS = 50
    const val MAX_BYTES = 64L * 1024 * 1024

    fun toDelete(events: List<NoiseEvent>, maxEvents: Int = MAX_EVENTS, maxBytes: Long = MAX_BYTES): List<String> {
        if (events.isEmpty()) return emptyList()
        val newest = events.maxBy { it.savedAtMs }.id
        val order = events.filter { it.id != newest }.sortedWith(compareBy({ it.confirmed }, { it.savedAtMs }))
        var count = events.size
        var bytes = events.sumOf { it.audioBytes }
        val out = mutableListOf<String>()
        for (e in order) {
            if (count <= maxEvents && bytes <= maxBytes) break
            out += e.id
            count--
            bytes -= e.audioBytes
        }
        return out
    }
}
