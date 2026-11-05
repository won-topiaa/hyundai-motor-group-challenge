package com.calmride.core

sealed interface VoiceCommand {
    data object ModeOn : VoiceCommand
    data object ModeOff : VoiceCommand
    data object StartPriming : VoiceCommand
    data object CuesOnly : VoiceCommand
    data class SwitchDriver(val name: String) : VoiceCommand
}

/** Maps recognized Korean speech to app commands with simple keyword rules. */
object VoiceCommandParser {

    private val driverPattern = Regex("""(?:나|저)\s*([가-힣A-Za-z]{1,10}?)\s*(?:이야|야|예요|이에요|입니다)""")

    fun parse(text: String): VoiceCommand? {
        val t = text.replace(" ", "")
        driverPattern.find(text)?.let { return VoiceCommand.SwitchDriver(it.groupValues[1]) }
        // Answers to the taxi boarding question: "알림음만 켜 줘" / "1분 들을게요".
        if ("알림음만" in t) return VoiceCommand.CuesOnly
        if ("사운드" in t || "소리준비" in t || "준비해" in t || "들을게" in t || "듣고출발" in t) return VoiceCommand.StartPriming
        if ("멀미" in t) {
            if ("꺼" in t || "끄" in t || "종료" in t) return VoiceCommand.ModeOff
            if ("켜" in t || "시작" in t || "나" in t) return VoiceCommand.ModeOn
        }
        return null
    }
}
