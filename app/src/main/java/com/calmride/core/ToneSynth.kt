package com.calmride.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Generates 16-bit stereo PCM for the 100Hz priming tone and the cue chimes. */
object ToneSynth {

    const val SAMPLE_RATE = 44_100
    const val PRIMING_HZ = 100.0
    const val PRIMING_SECONDS = 60

    /** Equal-power panning gains (left, right) for pan in -1..1. */
    fun panGains(pan: Float): Pair<Double, Double> {
        val angle = (pan.coerceIn(-1f, 1f) + 1.0) * PI / 4.0
        return cos(angle) to sin(angle)
    }

    /**
     * Writes frames [startFrame, startFrame + frames) of the priming tone into [out] (interleaved stereo).
     * Fades in and out over [fadeSec] so the tone never clicks.
     */
    fun fillPriming(
        out: ShortArray,
        startFrame: Long,
        frames: Int,
        totalFrames: Long,
        amplitude: Float,
        freqHz: Double = PRIMING_HZ,
        fadeSec: Double = 2.0,
    ) {
        val fadeFrames = (fadeSec * SAMPLE_RATE).toLong()
        for (i in 0 until frames) {
            val n = startFrame + i
            val envelope = when {
                n < fadeFrames -> n.toDouble() / fadeFrames
                n > totalFrames - fadeFrames -> (totalFrames - n).coerceAtLeast(0).toDouble() / fadeFrames
                else -> 1.0
            }
            val value = sin(2.0 * PI * freqHz * n / SAMPLE_RATE) * envelope * amplitude
            val s = (value * Short.MAX_VALUE).toInt().toShort()
            out[2 * i] = s
            out[2 * i + 1] = s
        }
    }

    private data class Note(val hz: Double, val ms: Int)

    private fun notes(kind: CueKind): List<Note> = when (kind) {
        // Rising two-note chime for a turn, falling for a slowdown, two soft taps for a bump.
        CueKind.TURN_LEFT, CueKind.TURN_RIGHT -> listOf(Note(523.25, 130), Note(659.25, 170))
        CueKind.SLOW_DOWN, CueKind.BRAKE -> listOf(Note(659.25, 130), Note(440.0, 200))
        CueKind.BUMP -> listOf(Note(392.0, 90), Note(0.0, 60), Note(392.0, 90))
        CueKind.LANE_LEFT, CueKind.LANE_RIGHT -> listOf(Note(587.33, 160))
    }

    /** A short chime for [kind], panned toward the side of the movement. */
    fun cue(kind: CueKind, amplitude: Float, pan: Float = kind.pan()): ShortArray {
        val (gl, gr) = panGains(pan)
        val parts = notes(kind)
        val totalFrames = parts.sumOf { it.ms } * SAMPLE_RATE / 1000
        val out = ShortArray(totalFrames * 2)
        var frame = 0
        for (note in parts) {
            val frames = note.ms * SAMPLE_RATE / 1000
            val attack = min(frames / 4, SAMPLE_RATE / 100)
            for (i in 0 until frames) {
                // Quick attack, exponential-like decay: soft and short, closer to a chime than a beep.
                val env = if (i < attack) i.toDouble() / attack else (1.0 - (i - attack).toDouble() / (frames - attack)).let { it * it }
                val v = if (note.hz == 0.0) 0.0 else sin(2.0 * PI * note.hz * i / SAMPLE_RATE) * env * amplitude
                out[2 * (frame + i)] = (v * gl * Short.MAX_VALUE).toInt().toShort()
                out[2 * (frame + i) + 1] = (v * gr * Short.MAX_VALUE).toInt().toShort()
            }
            frame += frames
        }
        return out
    }
}
