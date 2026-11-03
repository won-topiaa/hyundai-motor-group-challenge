package com.calmride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneSynthTest {

    @Test
    fun `priming tone is 100Hz`() {
        val frames = ToneSynth.SAMPLE_RATE // 1 second in the middle of the tone
        val out = ShortArray(frames * 2)
        val total = ToneSynth.PRIMING_SECONDS.toLong() * ToneSynth.SAMPLE_RATE
        ToneSynth.fillPriming(out, startFrame = 10L * ToneSynth.SAMPLE_RATE, frames = frames, totalFrames = total, amplitude = 0.5f)
        var crossings = 0
        for (i in 1 until frames) {
            val a = out[2 * (i - 1)]
            val b = out[2 * i]
            if (a < 0 && b >= 0) crossings++
        }
        assertTrue("expected ~100 rising zero crossings, got $crossings", crossings in 99..101)
    }

    @Test
    fun `priming tone fades in from silence`() {
        val out = ShortArray(200)
        ToneSynth.fillPriming(out, 0, 100, 60L * ToneSynth.SAMPLE_RATE, 1f)
        assertEquals(0, out[0].toInt())
        assertTrue(out.maxOf { abs(it.toInt()) } < 200)
    }

    @Test
    fun `left cue is louder on the left channel`() {
        val pcm = ToneSynth.cue(CueKind.TURN_LEFT, 0.8f)
        var left = 0L
        var right = 0L
        for (i in 0 until pcm.size / 2) {
            left += abs(pcm[2 * i].toInt())
            right += abs(pcm[2 * i + 1].toInt())
        }
        assertTrue(left > right * 3)
    }

    @Test
    fun `center pan splits power equally`() {
        val (l, r) = ToneSynth.panGains(0f)
        assertEquals(l, r, 1e-9)
        assertEquals(1.0, l * l + r * r, 1e-9)
    }
}
