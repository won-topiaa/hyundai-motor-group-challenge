package com.calmride.core

/**
 * What the passenger hears once per ride, right after they ask for the comfort mode: first the cues they will hear
 * on the way, each played right after its name, then the offer of the 1-minute tone. Each part stays within
 * [PART_LIMIT_MS], so a short ride does not start with a lecture.
 */
object RideIntro {

    sealed interface Step {
        data class Say(val text: String) : Step
        data class Chime(val kind: CueKind) : Step
    }

    val cues: List<Step> = listOf(
        Step.Say("네, 돌기 전엔 이 소리,"),
        Step.Chime(CueKind.TURN_RIGHT),
        Step.Say("멈추기 전엔 이 소리가 나요."),
        Step.Chime(CueKind.SLOW_DOWN),
    )

    /** Asked only while the car stands: the tone belongs right before departure, and costs a minute of fare. */
    const val PRIMING_QUESTION = "출발 전 1분 낮은 소리를 들으면 멀미가 줄 수 있어요. 들을까요?"

    const val PART_LIMIT_MS = 5000L

    /** Silence after each sample chime, so it does not run into the next words. */
    const val CHIME_GAP_MS = 150L

    fun durationMs(steps: List<Step>): Long = steps.sumOf { step ->
        when (step) {
            is Step.Say -> SpeechTiming.estimateMs(step.text)
            is Step.Chime -> ToneSynth.cueMs(step.kind) + CHIME_GAP_MS
        }
    }
}

/**
 * How long a Korean sentence takes to say, for keeping prompts short and for pacing captions when no voice engine
 * is available. Assumes about six syllables a second, a little slower than a news reader.
 */
object SpeechTiming {
    const val SYLLABLE_MS = 170L
    const val COMMA_MS = 250L
    const val SENTENCE_MS = 400L
    /** The engine's own delay before the first word. */
    const val START_MS = 150L

    fun estimateMs(text: String): Long {
        val body = text.trimEnd(',', '.', '?', '!', ' ')
        val syllables = body.count { it in '가'..'힣' || it.isDigit() || it in 'A'..'Z' || it in 'a'..'z' }
        val commas = body.count { it == ',' }
        val stops = body.count { it == '.' || it == '?' || it == '!' }
        return START_MS + syllables * SYLLABLE_MS + commas * COMMA_MS + stops * SENTENCE_MS
    }
}
