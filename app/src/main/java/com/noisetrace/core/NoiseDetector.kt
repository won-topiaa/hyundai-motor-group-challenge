package com.noisetrace.core

/**
 * Two views of the cabin level: the whole band, where a low hum ("웅웅") shows, and above 400 Hz, where rattles and
 * squeaks show without the road rumble that changes with every surface.
 */
enum class Channel { HIGH, FULL }

enum class Sensitivity(val label: String, val marginDb: Float) {
    LOW("낮게", 14f),
    NORMAL("보통", 10f),
    HIGH("높게", 7f),
}

/** One 100 ms frame of cabin level, with what this car usually sounds like at the same speed. */
data class FrameLevel(
    val atMs: Long,
    val fullDb: Float,
    val highDb: Float,
    /** Usual level at this speed; null while the app is still learning it. */
    val fullBaseDb: Float?,
    val highBaseDb: Float?,
    /** Music, the app's own voice, a blocked microphone: not used for learning or detection. */
    val suppressed: Boolean,
) {
    /** How much louder than usual, on whichever channel stands out more. */
    val excessDb: Float?
        get() = listOfNotNull(fullBaseDb?.let { fullDb - it }, highBaseDb?.let { highDb - it }).maxOrNull()
}

data class Trigger(val atMs: Long, val channel: Channel, val levelDb: Float, val baselineDb: Float) {
    val excessDb: Float get() = levelDb - baselineDb
}

/**
 * What this car usually sounds like, learned separately for each speed range because road and wind noise grow with
 * speed. Each (channel, speed band) keeps a histogram of frame levels in 1 dB bins that slowly forgets old frames, so
 * the usual level follows rain, a new set of tyres or a fan turned up.
 */
class Baseline {
    data class Stats(val medianDb: Float, val p90Db: Float)

    private val hist = Array(Channel.entries.size) { Array(BAND_COUNT) { FloatArray(BINS) } }
    private val weight = Array(Channel.entries.size) { FloatArray(BAND_COUNT) }

    fun band(speedKmh: Float?): Int {
        val v = speedKmh ?: return 0
        val i = SPEED_BANDS_KMH.indexOfFirst { v < it }
        return if (i < 0) SPEED_BANDS_KMH.size else i
    }

    fun mature(channel: Channel, band: Int) = weight[channel.ordinal][band] >= MATURE_WEIGHT

    /** Usual level for this speed band, or for the nearest band already learned while this one is still new. */
    fun stats(channel: Channel, band: Int): Stats? {
        val order = (0 until BAND_COUNT).sortedWith(compareBy({ kotlin.math.abs(it - band) }, { -it }))
        val b = order.firstOrNull { mature(channel, it) } ?: return null
        return Stats(percentile(channel, b, 0.5f), percentile(channel, b, 0.9f))
    }

    fun add(channel: Channel, band: Int, db: Float, w: Float = 1f) {
        val h = hist[channel.ordinal][band]
        for (i in h.indices) h[i] *= DECAY
        weight[channel.ordinal][band] = weight[channel.ordinal][band] * DECAY + w
        h[bin(db)] += w
    }

    private fun bin(db: Float) = (db - MIN_DB).toInt().coerceIn(0, BINS - 1)

    private fun percentile(channel: Channel, band: Int, p: Float): Float {
        val h = hist[channel.ordinal][band]
        val total = h.sum()
        var acc = 0f
        for (i in h.indices) {
            acc += h[i]
            if (acc >= total * p) return MIN_DB + i + 0.5f
        }
        return MIN_DB + BINS - 0.5f
    }

    /** Flat copy for saving between drives: the car keeps its usual sound across restarts. */
    fun export(): FloatArray {
        val out = FloatArray(STATE_SIZE)
        var k = 0
        for (c in Channel.entries.indices) for (b in 0 until BAND_COUNT) {
            out[k++] = weight[c][b]
            hist[c][b].copyInto(out, k)
            k += BINS
        }
        return out
    }

    fun import(state: FloatArray) {
        if (state.size != STATE_SIZE) return
        var k = 0
        for (c in Channel.entries.indices) for (b in 0 until BAND_COUNT) {
            weight[c][b] = state[k++]
            state.copyInto(hist[c][b], 0, k, k + BINS)
            k += BINS
        }
    }

    companion object {
        /** Upper bounds of the speed bands in km/h; the first band is "stopped", the last is open-ended. */
        val SPEED_BANDS_KMH = floatArrayOf(1f, 20f, 40f, 60f, 90f)
        val BAND_COUNT = SPEED_BANDS_KMH.size + 1
        const val BINS = 100
        const val MIN_DB = -100f
        /** Per frame: old frames fade with a time constant of about 50 s of driving in that band. */
        const val DECAY = 0.998f
        /** About 8 s of frames before a band is trusted. */
        const val MATURE_WEIGHT = 80f
        val STATE_SIZE = Channel.entries.size * BAND_COUNT * (BINS + 1)
    }
}

/**
 * Finds the moments the cabin gets clearly louder than this car usually is at the same speed.
 *
 * - Burst: 3 of the last 10 frames (1 s) above the threshold. Catches a rattle over a rough patch, a squeak, a hum.
 *   A single turn-signal tick or a door-pocket click stays below it.
 * - Impulse: one frame far above usual. Catches a single clunk over a bump.
 *
 * Auto captures are rate-limited so a noisy day cannot fill the car's storage.
 */
class NoiseDetector(
    var sensitivity: Sensitivity = Sensitivity.NORMAL,
    private val cooldownMs: Long = 30_000,
    private val maxPerHour: Int = 6,
) {
    val baseline = Baseline()
    private val hits = Array(Channel.entries.size) { ArrayDeque<Boolean>() }
    private val autoTriggers = ArrayDeque<Long>()
    private var holdUntilMs = Long.MIN_VALUE

    /**
     * Learns from one frame and says whether it should start an automatic capture.
     * [detect] is false while a capture is already running or auto detection is off; the frame still teaches the baseline.
     */
    fun onFrame(atMs: Long, fullDb: Float, highDb: Float, speedKmh: Float?, suppressed: Boolean, detect: Boolean): Pair<FrameLevel, Trigger?> {
        val band = baseline.band(speedKmh)
        val fullStats = baseline.stats(Channel.FULL, band)
        val highStats = baseline.stats(Channel.HIGH, band)
        val silent = fullDb <= Levels.SILENCE_DB + 1
        val frame = FrameLevel(atMs, fullDb, highDb, fullStats?.medianDb, highStats?.medianDb, suppressed || silent)
        if (frame.suppressed) {
            hits.forEach { it.clear() }
            return frame to null
        }
        var trigger: Trigger? = null
        val margin = sensitivity.marginDb
        for (channel in Channel.entries) {
            val level = if (channel == Channel.FULL) fullDb else highDb
            val stats = if (channel == Channel.FULL) fullStats else highStats
            if (stats == null) {
                baseline.add(channel, band, level)
                continue
            }
            val threshold = stats.medianDb + maxOf(margin, stats.p90Db - stats.medianDb + SPREAD_MARGIN_DB)
            val window = hits[channel.ordinal]
            window.addLast(level >= threshold)
            if (window.size > BURST_WINDOW) window.removeFirst()
            val burst = window.count { it } >= BURST_HITS
            val impulse = level >= stats.medianDb + margin + IMPULSE_EXTRA_DB
            if (detect && trigger == null && (burst || impulse) && canTrigger(atMs)) {
                trigger = Trigger(atMs, channel, level, stats.medianDb)
            }
            // Loud frames still count a little, so a lasting change (rain, an open window) becomes the new usual.
            baseline.add(channel, band, level, if (level >= stats.medianDb + OUTLIER_DB) OUTLIER_WEIGHT else 1f)
        }
        if (trigger != null) {
            autoTriggers.addLast(atMs)
            hits.forEach { it.clear() }
        }
        return frame to trigger
    }

    /** No automatic capture right after any capture: the same noise would be saved twice. */
    fun holdOff(untilMs: Long) {
        holdUntilMs = maxOf(holdUntilMs, untilMs)
    }

    fun holdOffAfterCapture(endedAtMs: Long) = holdOff(endedAtMs + cooldownMs)

    private fun canTrigger(atMs: Long): Boolean {
        if (atMs < holdUntilMs) return false
        while (autoTriggers.isNotEmpty() && atMs - autoTriggers.first() > HOUR_MS) autoTriggers.removeFirst()
        return autoTriggers.size < maxPerHour
    }

    companion object {
        const val BURST_WINDOW = 10
        const val BURST_HITS = 3
        const val IMPULSE_EXTRA_DB = 8f
        const val SPREAD_MARGIN_DB = 5f
        const val OUTLIER_DB = 6f
        const val OUTLIER_WEIGHT = 0.2f
        const val HOUR_MS = 3_600_000L
    }
}
