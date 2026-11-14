package com.noisetrace.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AudioRingTest {
    @Test
    fun keepsNewestSamplesInOrderAcrossTheWrap() {
        val ring = AudioRing(5)
        ring.write(shortArrayOf(1, 2, 3))
        ring.write(shortArrayOf(4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.latest(5))
        assertArrayEquals(shortArrayOf(6, 7), ring.latest(2))
        assertEquals(7L, ring.written)
    }

    @Test
    fun oversizedWriteKeepsOnlyTheTail() {
        val ring = AudioRing(3)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(5, 6, 7), ring.latest(10))
    }

    @Test
    fun notYetFullReturnsWhatItHas() {
        val ring = AudioRing(10)
        ring.write(shortArrayOf(9, 8))
        assertArrayEquals(shortArrayOf(9, 8), ring.latest(5))
    }
}

class WavTest {
    @Test
    fun roundTrip() {
        val pcm = ShortArray(1000) { (it * 31 % 2000 - 1000).toShort() }
        val out = ByteArrayOutputStream()
        Wav.write(out, pcm, 16_000)
        assertEquals(Wav.sizeBytes(pcm.size), out.size().toLong())
        val (back, rate) = Wav.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(16_000, rate)
        assertArrayEquals(pcm, back)
    }
}

/** Feeds synthetic cabin audio through the pipeline, 100 ms at a time. */
private class Rig(speedKmh: Float = 15f, sensitivity: Sensitivity = Sensitivity.NORMAL) {
    val audio = DemoCabinAudio(seed = 3).also { it.speedKmh = speedKmh }
    val pipeline = CapturePipeline(detector = NoiseDetector(sensitivity = sensitivity))
    val frame = ShortArray(pipeline.frameSamples)
    var t = 1_000_000L
    val clips = mutableListOf<CapturedClip>()

    fun run(seconds: Double, speedKmh: Float = audio.speedKmh, suppressed: Boolean = false) {
        audio.speedKmh = speedKmh
        repeat((seconds * 10).toInt()) {
            audio.next(frame)
            t += 100
            pipeline.onFrame(frame, t, speedKmh, suppressed)?.let(clips::add)
        }
    }
}

class NoiseDetectorTest {
    @Test
    fun steadyCabinNoiseNeverTriggers() {
        val rig = Rig()
        rig.run(120.0)
        assertTrue(rig.clips.isEmpty())
        assertNull(rig.pipeline.captureLeftMs)
    }

    @Test
    fun learnsBeforeDetecting() {
        val rig = Rig()
        rig.run(3.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(15.0)
        assertTrue("no usual level yet, so nothing to compare with", rig.clips.isEmpty())
    }

    @Test
    fun rattleTriggersAnAutomaticCaptureOnTheHighChannel() {
        val rig = Rig()
        rig.run(15.0)
        val at = rig.t
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(12.0)
        val clip = rig.clips.single()
        assertEquals(TriggerKind.AUTO, clip.kind)
        assertEquals(Channel.HIGH, clip.channel)
        assertTrue(clip.noiseAtMs - at in 0..1000)
        assertTrue("well above usual: ${clip.levelDb - clip.baselineDb!!}", clip.levelDb - clip.baselineDb!! > 10)
    }

    @Test
    fun clipHoldsTwentySecondsBeforeAndTenAfter() {
        val rig = Rig()
        rig.run(25.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(12.0)
        val clip = rig.clips.single()
        assertEquals(30_000L, clip.durationMs)
        assertEquals(20_000.0, (clip.noiseAtMs - clip.clipStartMs).toDouble(), 300.0)
    }

    @Test
    fun humTriggersOnTheFullBand() {
        val rig = Rig(speedKmh = 60f)
        rig.run(15.0)
        rig.audio.inject(NoiseType.HUM)
        rig.run(12.0)
        assertEquals(1, rig.clips.size)
    }

    @Test
    fun aRepeatedNoiseIsNotSavedTwiceWithinTheCooldown() {
        val rig = Rig()
        rig.run(15.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(12.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(12.0)
        assertEquals(1, rig.clips.size)
        rig.run(30.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(12.0)
        assertEquals(2, rig.clips.size)
    }

    @Test
    fun suppressedFramesNeitherLearnNorTrigger() {
        val rig = Rig()
        rig.run(15.0, suppressed = true)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(12.0, suppressed = true)
        assertTrue(rig.clips.isEmpty())
        assertFalse(rig.pipeline.detector.baseline.mature(Channel.HIGH, rig.pipeline.detector.baseline.band(15f)))
    }

    @Test
    fun autoDetectOffStillLetsTheButtonSave() {
        val rig = Rig()
        rig.pipeline.autoDetect = false
        rig.run(15.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(2.0)
        assertTrue(rig.clips.isEmpty())
        rig.pipeline.requestManual(rig.t)
        rig.run(11.0)
        assertEquals(TriggerKind.MANUAL, rig.clips.single().kind)
    }

    @Test
    fun rateLimitCapsAutomaticCapturesPerHour() {
        val detector = NoiseDetector(cooldownMs = 0, maxPerHour = 2)
        var t = 0L
        repeat(200) { detector.onFrame(t, -50f, -60f, 10f, suppressed = false, detect = true); t += 100 }
        var fired = 0
        repeat(5) {
            val (_, trig) = detector.onFrame(t, -10f, -10f, 10f, suppressed = false, detect = true)
            if (trig != null) fired++
            t += 100
            repeat(50) { detector.onFrame(t, -50f, -60f, 10f, suppressed = false, detect = true); t += 100 }
        }
        assertEquals(2, fired)
    }

    @Test
    fun baselineSurvivesExportAndImport() {
        val rig = Rig()
        rig.run(15.0)
        val copy = NoiseDetector()
        copy.baseline.import(rig.pipeline.detector.baseline.export())
        val band = copy.baseline.band(15f)
        assertTrue(copy.baseline.mature(Channel.HIGH, band))
        assertEquals(rig.pipeline.detector.baseline.stats(Channel.HIGH, band), copy.baseline.stats(Channel.HIGH, band))
    }

    @Test
    fun newSpeedBandBorrowsTheNearestLearnedOne() {
        val b = Baseline()
        repeat(200) { b.add(Channel.FULL, b.band(30f), -45f) }
        assertNotNull(b.stats(Channel.FULL, b.band(70f)))
        assertEquals(-44.5f, b.stats(Channel.FULL, b.band(70f))!!.medianDb, 0.01f)
    }
}

class ManualCaptureTest {
    @Test
    fun pressAfterTheNoiseFindsTheNoise() {
        val rig = Rig()
        rig.pipeline.autoDetect = false
        rig.run(15.0)
        val noiseAt = rig.t
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(2.5)
        rig.pipeline.requestManual(rig.t)
        val pressAt = rig.t
        rig.run(11.0)
        val clip = rig.clips.single()
        assertTrue(clip.momentCertain)
        assertTrue("noise found at ${clip.noiseAtMs - noiseAt} ms", clip.noiseAtMs - noiseAt in 0..1000)
        assertEquals(pressAt, clip.savedAtMs)
    }

    @Test
    fun theLatestOfTwoNoisesIsTheOneMeant() {
        val rig = Rig()
        rig.pipeline.autoDetect = false
        rig.run(15.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(6.0)
        val second = rig.t
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(2.0)
        rig.pipeline.requestManual(rig.t)
        rig.run(11.0)
        assertTrue(rig.clips.single().noiseAtMs - second in 0..1000)
    }

    @Test
    fun nothingStandingOutIsMarkedUncertain() {
        val rig = Rig()
        rig.run(15.0)
        rig.pipeline.requestManual(rig.t)
        val pressAt = rig.t
        rig.run(11.0)
        val clip = rig.clips.single()
        assertFalse(clip.momentCertain)
        assertEquals(pressAt - CapturePipeline.UNCERTAIN_GUESS_MS, clip.noiseAtMs)
    }

    @Test
    fun pressDuringAnAutomaticCaptureConfirmsIt() {
        val rig = Rig()
        rig.run(15.0)
        rig.audio.inject(NoiseType.RATTLE)
        rig.run(3.0)
        assertEquals(TriggerKind.AUTO, rig.pipeline.captureKind)
        rig.pipeline.requestManual(rig.t)
        rig.run(10.0)
        assertEquals(TriggerKind.MANUAL, rig.clips.single().kind)
    }
}

class SpectrumAnalyzerTest {
    private fun analyze(type: NoiseType, speed: Float = 15f): SpectrumSummary =
        SpectrumAnalyzer.analyze(DemoCabinAudio.clip(30_000, speed, type, 20_000, seed = 5), 16_000, 20_000)!!

    @Test
    fun rattleIsImpulsiveAndHigh() {
        val s = analyze(NoiseType.RATTLE)
        assertEquals(NoiseType.RATTLE, s.estimatedType)
        assertTrue("rising band ${OCTAVE_CENTERS_HZ[s.risingBand]}", OCTAVE_CENTERS_HZ[s.risingBand] in 2000..4000)
    }

    @Test
    fun squeakIsTonalAroundOneKilohertz() {
        val s = analyze(NoiseType.SQUEAK)
        assertEquals(NoiseType.SQUEAK, s.estimatedType)
        assertEquals(1000, OCTAVE_CENTERS_HZ[s.risingBand])
        assertEquals(1250.0, s.peakHz.toDouble(), 60.0)
    }

    @Test
    fun humIsLow() {
        val s = analyze(NoiseType.HUM, speed = 60f)
        assertEquals(NoiseType.HUM, s.estimatedType)
        assertEquals(125, OCTAVE_CENTERS_HZ[s.risingBand])
    }

    @Test
    fun fftFindsASineBin() {
        val n = 1024
        val re = DoubleArray(n) { kotlin.math.sin(2 * Math.PI * 64 * it / n) }
        val im = DoubleArray(n)
        SpectrumAnalyzer.fft(re, im)
        val peak = (0 until n / 2).maxBy { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, peak)
    }
}
