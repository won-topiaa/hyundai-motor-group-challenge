package com.calmride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideIntroTest {

    @Test
    fun `the cue sounds take no more than five seconds, chimes included`() {
        val ms = RideIntro.durationMs(RideIntro.cues)
        assertTrue("cue intro takes $ms ms", ms <= RideIntro.PART_LIMIT_MS)
    }

    @Test
    fun `the offer of the 1-minute tone takes no more than five seconds`() {
        val ms = SpeechTiming.estimateMs(RideIntro.PRIMING_QUESTION)
        assertTrue("priming question takes $ms ms", ms <= RideIntro.PART_LIMIT_MS)
    }

    @Test
    fun `each sample chime follows the words that name it`() {
        val chimes = RideIntro.cues.filterIsInstance<RideIntro.Step.Chime>().map { it.kind }
        assertEquals(listOf(CueKind.TURN_RIGHT, CueKind.SLOW_DOWN), chimes)
        RideIntro.cues.forEachIndexed { i, step ->
            if (step is RideIntro.Step.Chime) assertTrue(RideIntro.cues[i - 1] is RideIntro.Step.Say)
        }
    }

    @Test
    fun `speech time grows with syllables and pauses, not with closing punctuation`() {
        assertEquals(SpeechTiming.START_MS + 2 * SpeechTiming.SYLLABLE_MS, SpeechTiming.estimateMs("소리"))
        assertEquals(SpeechTiming.estimateMs("소리"), SpeechTiming.estimateMs("소리."))
        assertEquals(SpeechTiming.estimateMs("네 소리") + SpeechTiming.COMMA_MS, SpeechTiming.estimateMs("네, 소리"))
        assertEquals(SpeechTiming.START_MS + 2 * SpeechTiming.SYLLABLE_MS, SpeechTiming.estimateMs("1분"))
    }

    @Test
    fun `chime lengths match the synthesized sound`() {
        CueKind.entries.forEach { kind ->
            val frames = ToneSynth.cue(kind, 0.5f).size / 2
            assertEquals(ToneSynth.cueMs(kind), frames * 1000L / ToneSynth.SAMPLE_RATE)
        }
    }
}
