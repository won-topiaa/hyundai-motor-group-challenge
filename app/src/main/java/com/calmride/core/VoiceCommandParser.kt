package com.calmride.core

sealed interface VoiceCommand {
    data object ModeOn : VoiceCommand
    data object ModeOff : VoiceCommand
    data object StartPriming : VoiceCommand
    data object CuesOnly : VoiceCommand
    data object Yes : VoiceCommand
    data object No : VoiceCommand
    /** The passenger's 1-5 comfort answer. */
    data class Score(val value: Int) : VoiceCommand
    data class SwitchDriver(val name: String) : VoiceCommand
}

/** Maps recognized Korean speech to app commands with simple keyword rules. */
object VoiceCommandParser {

    private val driverPattern = Regex("""(?:나|저)\s*([가-힣A-Za-z]{1,10}?)\s*(?:이야|야|예요|이에요|입니다)""")
    private val scorePattern = Regex("""([1-5]|일|이|삼|사|오|한|두|세|네|다섯)점""")
    private val scoreWords = mapOf("일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5, "한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5)
    private val noPhrases = listOf("괜찮", "됐어", "안해도", "안켜", "필요없", "싫어", "멀미안")
    private val yesWords = setOf("네", "넵", "네네", "예", "응", "그래", "좋아")
    private val yesPhrases = listOf("켜줘", "켜주세요", "부탁", "좋아요", "그래요")

    fun parse(text: String): VoiceCommand? {
        val t = text.replace(" ", "")
        driverPattern.find(text)?.let { return VoiceCommand.SwitchDriver(it.groupValues[1]) }
        // Answers to the boarding question: "알림음만 켜 줘" / "1분 들을게요".
        if ("알림음만" in t) return VoiceCommand.CuesOnly
        if ("사운드" in t || "소리준비" in t || "준비해" in t || "들을게" in t || "듣고출발" in t) return VoiceCommand.StartPriming
        scorePattern.find(t)?.let { m ->
            val v = m.groupValues[1]
            return VoiceCommand.Score(v.toIntOrNull() ?: scoreWords.getValue(v))
        }
        // Before the mode keywords: "멀미 안 나요" is a no, though it contains the word for motion sickness.
        if (t.startsWith("아니") || noPhrases.any { it in t }) return VoiceCommand.No
        if ("멀미" in t || "승차모드" in t) {
            if ("꺼" in t || "끄" in t || "종료" in t) return VoiceCommand.ModeOff
            if ("켜" in t || "시작" in t || "나" in t) return VoiceCommand.ModeOn
        }
        if (t.trimEnd('.', ',', '!', '?') in yesWords || yesPhrases.any { it in t }) return VoiceCommand.Yes
        return null
    }
}
