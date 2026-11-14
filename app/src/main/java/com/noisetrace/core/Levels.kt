package com.noisetrace.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

object Levels {
    /** Level reported for digital silence: a muted or blocked microphone delivers exact zeros. */
    const val SILENCE_DB = -100f

    /** RMS level in dB relative to full scale. */
    fun rmsDb(buf: ShortArray, offset: Int = 0, length: Int = buf.size - offset): Float {
        if (length <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        return toDb(sqrt(sum / length) / Short.MAX_VALUE)
    }

    fun rmsDb(buf: FloatArray, length: Int = buf.size): Float {
        if (length <= 0) return SILENCE_DB
        var sum = 0.0
        for (i in 0 until length) sum += buf[i].toDouble() * buf[i]
        return toDb(sqrt(sum / length))
    }

    fun toDb(amplitude: Double): Float = if (amplitude <= 1e-5) SILENCE_DB else (20 * log10(amplitude)).toFloat().coerceAtLeast(SILENCE_DB)
}

/**
 * Second-order Butterworth high-pass (RBJ biquad). Rattles and squeaks sit above a few hundred hertz, while road
 * rumble, which changes with every road surface, sits below; filtering it out makes the short noises stand out.
 */
class HighPass(sampleRate: Int, cutoffHz: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2 * sqrt(0.5))
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0
        b1 = -(1 + c) / a0
        b2 = (1 + c) / 2 / a0
        a1 = -2 * c / a0
        a2 = (1 - alpha) / a0
    }

    /** Filters [length] samples of [src] into [dst] (as -1..1 floats). */
    fun process(src: ShortArray, dst: FloatArray, length: Int = src.size) {
        for (i in 0 until length) {
            val x = src[i].toDouble() / Short.MAX_VALUE
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            dst[i] = y.toFloat()
        }
    }

    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}
