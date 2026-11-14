package com.noisetrace.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Octave bands up to the 8 kHz limit of 16 kHz audio. */
val OCTAVE_CENTERS_HZ = intArrayOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)

fun bandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

fun hzLabel(hz: Int): String = if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz"

/**
 * What a saved noise sounds like, measured once after the capture (never continuously, to keep CPU low):
 * the octave-band levels around the noise, the same bands in a quiet stretch of the same clip, and a guess at the type.
 */
data class SpectrumSummary(
    /** Level per band in [OCTAVE_CENTERS_HZ] around the noise, dB (full scale based). */
    val bandsDb: List<Float>,
    /** The same bands in the quietest part of the clip away from the noise: the car's usual sound right then. */
    val referenceDb: List<Float>,
    /** Frequency with the most energy in the band that rose the most. */
    val peakHz: Int,
    /** Peak against average in 5 ms steps: high for rattles and clunks, low for a steady hum. */
    val impulsivenessDb: Float,
    /** Strongest bin against the average of its band: high for a squeak or whine. */
    val tonalityDb: Float,
    val estimatedType: NoiseType?,
) {
    val deltaDb: List<Float> get() = bandsDb.zip(referenceDb) { a, b -> a - b }
    val risingBand: Int get() = deltaDb.indices.maxByOrNull { deltaDb[it] } ?: 0
}

object SpectrumAnalyzer {
    const val FFT_SIZE = 2048
    private const val HOP = FFT_SIZE / 2
    /** Seconds analyzed around the noise and in the reference stretch. */
    private const val WINDOW_MS = 1500L

    fun analyze(pcm: ShortArray, sampleRate: Int, noiseOffsetMs: Long): SpectrumSummary? {
        val windowSamples = (sampleRate * WINDOW_MS / 1000).toInt()
        if (pcm.size < FFT_SIZE * 2) return null
        val center = (noiseOffsetMs * sampleRate / 1000).toInt().coerceIn(0, pcm.size - 1)
        val start = (center - windowSamples / 3).coerceIn(0, maxOf(0, pcm.size - windowSamples))
        val refStart = quietestStart(pcm, sampleRate, windowSamples, center)
        val power = averagePower(pcm, start, windowSamples)
        val refPower = averagePower(pcm, refStart, windowSamples)
        val bands = bandLevels(power, sampleRate)
        val refBands = bandLevels(refPower, sampleRate)
        val delta = bands.zip(refBands) { a, b -> a - b }
        val rising = delta.indices.maxByOrNull { delta[it] } ?: 0
        val (lo, hi) = bandBins(rising, sampleRate)
        val peakBin = (lo until hi).maxByOrNull { power[it] } ?: lo
        val peakHz = (peakBin.toLong() * sampleRate / FFT_SIZE).toInt()
        val bandMean = (lo until hi).sumOf { power[it] } / maxOf(1, hi - lo)
        val tonality = db(power[peakBin] / maxOf(bandMean, 1e-20))
        val impulsiveness = impulsiveness(pcm, start, windowSamples, sampleRate)
        return SpectrumSummary(bands, refBands, peakHz, impulsiveness, tonality, estimateType(rising, delta[rising], impulsiveness, tonality))
    }

    /**
     * Rules of thumb used in squeak-and-rattle work: low and steady is a hum, a narrow peak is a squeak, sharp hits
     * are a rattle. Shown as a hint only; what the driver heard always wins.
     */
    fun estimateType(risingBand: Int, risingDb: Float, impulsivenessDb: Float, tonalityDb: Float): NoiseType? {
        if (risingDb < 3f) return null
        val hz = OCTAVE_CENTERS_HZ[risingBand]
        return when {
            hz <= 250 && impulsivenessDb < IMPULSIVE_DB -> NoiseType.HUM
            impulsivenessDb >= IMPULSIVE_DB -> NoiseType.RATTLE
            hz >= 500 && tonalityDb >= TONAL_DB -> NoiseType.SQUEAK
            hz <= 250 -> NoiseType.HUM
            else -> null
        }
    }

    private const val IMPULSIVE_DB = 11f
    private const val TONAL_DB = 9f

    /** The quietest window at least 3 s away from the noise. */
    private fun quietestStart(pcm: ShortArray, sampleRate: Int, window: Int, center: Int): Int {
        val keepAway = sampleRate * 3
        var best = 0
        var bestDb = Float.MAX_VALUE
        var s = 0
        while (s + window <= pcm.size) {
            val far = s + window < center - keepAway || s > center + keepAway
            if (far) {
                val level = Levels.rmsDb(pcm, s, window)
                if (level < bestDb) {
                    bestDb = level
                    best = s
                }
            }
            s += sampleRate / 2
        }
        return best
    }

    /** Welch average of Hann-windowed power spectra. */
    private fun averagePower(pcm: ShortArray, start: Int, length: Int): DoubleArray {
        val power = DoubleArray(FFT_SIZE / 2)
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        var frames = 0
        var s = start
        while (s + FFT_SIZE <= minOf(pcm.size, start + length) || frames == 0) {
            if (s + FFT_SIZE > pcm.size) break
            for (i in 0 until FFT_SIZE) {
                re[i] = pcm[s + i].toDouble() / Short.MAX_VALUE * HANN[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in power.indices) power[k] += re[k] * re[k] + im[k] * im[k]
            frames++
            s += HOP
        }
        if (frames > 0) for (k in power.indices) power[k] /= frames
        return power
    }

    private fun bandBins(band: Int, sampleRate: Int): Pair<Int, Int> {
        val c = OCTAVE_CENTERS_HZ[band]
        val loHz = c / sqrt(2.0)
        val hiHz = minOf(c * sqrt(2.0), sampleRate / 2.0)
        val lo = (loHz * FFT_SIZE / sampleRate).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
        val hi = (hiHz * FFT_SIZE / sampleRate).toInt().coerceIn(lo + 1, FFT_SIZE / 2)
        return lo to hi
    }

    private fun bandLevels(power: DoubleArray, sampleRate: Int): List<Float> = OCTAVE_CENTERS_HZ.indices.map { b ->
        val (lo, hi) = bandBins(b, sampleRate)
        db((lo until hi).sumOf { power[it] } * 2 / (FFT_SIZE * HANN_POWER))
    }

    private fun impulsiveness(pcm: ShortArray, start: Int, length: Int, sampleRate: Int): Float {
        val block = sampleRate / 200
        val levels = mutableListOf<Double>()
        var s = start
        while (s + block <= minOf(pcm.size, start + length)) {
            var sum = 0.0
            for (i in s until s + block) sum += pcm[i].toDouble() * pcm[i]
            levels += sum / block
            s += block
        }
        if (levels.isEmpty()) return 0f
        val mean = levels.average()
        return db(levels.max() / maxOf(mean, 1e-12))
    }

    private fun db(ratio: Double): Float = if (ratio <= 1e-20) -200f else (10 * log10(ratio)).toFloat()

    private val HANN = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
    private val HANN_POWER = HANN.sumOf { it * it }

    /** In-place radix-2 FFT. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wRe = cos(ang)
            val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
