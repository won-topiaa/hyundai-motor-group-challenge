package com.noisetrace.core

/**
 * How long a Korean sentence takes to say, for pacing prompts and captions when no voice engine is available.
 * Assumes about six syllables a second, a little slower than a news reader.
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
