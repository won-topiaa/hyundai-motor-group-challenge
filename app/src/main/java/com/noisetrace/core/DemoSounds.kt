package com.noisetrace.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import java.util.Random

/**
 * Synthetic cabin audio for the demo drive and the tests: road noise that grows with speed, plus the three noise
 * types the app knows. The emulator has no real cabin, so the demo feeds this into the same pipeline as the microphone.
 */
class DemoCabinAudio(private val sampleRate: Int = CapturePipeline.SAMPLE_RATE, seed: Long = 7) {
    private val rng = Random(seed)
    private var low = 0.0
    private var low2 = 0.0
    private var t = 0L
    @Volatile var speedKmh: Float = 0f
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<NoiseType>()
    private var playing: NoiseType? = null
    private var playedSamples = 0
    private var nextClick = 0
    private var clickAge = Int.MAX_VALUE
    private var clickGain = 1.0

    /** Plays [type] from the next frame on. */
    fun inject(type: NoiseType) {
        queue.add(type)
    }

    /** Fills [out] with the next [out].size samples. */
    fun next(out: ShortArray) {
        val v = speedKmh.coerceAtLeast(0f)
        // About -52 dBFS when parked (HVAC fan), +10 dB at 40 km/h, as cabin noise rises roughly with log of speed.
        val roadDb = -52.0 + 14.0 * kotlin.math.log10(1.0 + v / 10.0)
        val roadAmp = 10.0.pow(roadDb / 20) * 3.0
        val hissAmp = 10.0.pow((roadDb - 14) / 20)
        if (playing == null) queue.poll()?.let {
            playing = it
            playedSamples = 0
            nextClick = 0
        }
        for (i in out.indices) {
            val white = rng.nextGaussian()
            // Two one-pole low-passes: rumble below ~200 Hz with a little hiss on top.
            low += 0.08 * (white - low)
            low2 += 0.08 * (low - low2)
            var s = low2 * roadAmp * 6 + rng.nextGaussian() * hissAmp
            s += noiseSample()
            t++
            out[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun noiseSample(): Double {
        val type = playing ?: return 0.0
        val time = playedSamples.toDouble() / sampleRate
        val duration = when (type) {
            NoiseType.RATTLE -> 0.9
            NoiseType.SQUEAK -> 0.5
            NoiseType.HUM -> 2.5
            NoiseType.OTHER -> 0.3
        }
        playedSamples++
        if (time >= duration) {
            playing = null
            return 0.0
        }
        val fade = minOf(1.0, time / 0.03, (duration - time) / 0.05)
        return when (type) {
            NoiseType.RATTLE -> rattleSample()
            NoiseType.SQUEAK -> 0.06 * fade * sin(2 * PI * (1250 * time + 0.9 * sin(2 * PI * 7 * time)))
            NoiseType.HUM -> 0.12 * fade * (sin(2 * PI * 118 * time) + 0.5 * sin(2 * PI * 236 * time))
            NoiseType.OTHER -> 0.05 * fade * rng.nextGaussian()
        }
    }

    /** Plastic-on-plastic taps: short ringing clicks at 2.3 and 3.6 kHz, 35-90 ms apart. */
    private fun rattleSample(): Double {
        if (playedSamples >= nextClick) {
            clickAge = 0
            clickGain = 0.5 + rng.nextDouble() * 0.5
            nextClick = playedSamples + (sampleRate * (0.035 + rng.nextDouble() * 0.055)).toInt()
        }
        val age = clickAge.toDouble() / sampleRate
        clickAge++
        if (age > 0.02) return 0.0
        val env = exp(-age / 0.004)
        return 0.2 * clickGain * env * (sin(2 * PI * 2300 * age) + 0.6 * sin(2 * PI * 3600 * age) + 0.3 * rng.nextGaussian())
    }

    companion object {
        /** A whole clip, e.g. for the sample history: road noise at [speedKmh] with [type] at [atMs]. */
        fun clip(durationMs: Long, speedKmh: Float, type: NoiseType, atMs: Long, seed: Long, sampleRate: Int = CapturePipeline.SAMPLE_RATE): ShortArray {
            val gen = DemoCabinAudio(sampleRate, seed)
            gen.speedKmh = speedKmh
            val out = ShortArray((durationMs * sampleRate / 1000).toInt())
            val frame = ShortArray(sampleRate / 10)
            var pos = 0
            var injected = false
            while (pos < out.size) {
                if (!injected && pos * 1000L / sampleRate >= atMs) {
                    gen.inject(type)
                    injected = true
                }
                gen.next(frame)
                val n = minOf(frame.size, out.size - pos)
                frame.copyInto(out, pos, 0, n)
                pos += n
            }
            return out
        }
    }
}
