package com.calmride.core

sealed interface VoiceCommand {
    data object ModeOn : VoiceCommand
    data object ModeOff : VoiceCommand
    data object StartPriming : VoiceCommand
    data class ReplayAtStops(val on: Boolean) : VoiceCommand
    data class SwitchDriver(val name: String) : VoiceCommand
}

/** Maps recognized Korean speech to app commands with simple keyword rules. */
object VoiceCommandParser {

    private val driverPattern = Regex("""(?:나|저)\s*([가-힣A-Za-z]{1,10}?)\s*(?:이야|야|예요|이에요|입니다)""")

    fun parse(text: String): VoiceCommand? {
        val t = text.replace(" ", "")
        driverPattern.find(text)?.let { return VoiceCommand.SwitchDriver(it.groupValues[1]) }
        // "신호에서 소리 들려줘" / "신호 대기 사운드 꺼줘" (taxi replay); checked before the plain priming command.
        if ("신호" in t && ("소리" in t || "사운드" in t)) return VoiceCommand.ReplayAtStops(on = !("꺼" in t || "끄" in t))
        if ("사운드" in t || "소리준비" in t || "준비해" in t) return VoiceCommand.StartPriming
        if ("멀미" in t) {
            if ("꺼" in t || "끄" in t || "종료" in t) return VoiceCommand.ModeOff
            if ("켜" in t || "시작" in t || "나" in t) return VoiceCommand.ModeOn
        }
        return null
    }
}
