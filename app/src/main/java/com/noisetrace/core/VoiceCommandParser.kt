package com.noisetrace.core

sealed interface VoiceCommand {
    /** "방금 소리 저장". */
    data object SaveNoise : VoiceCommand
    data class Where(val spots: Set<Spot>) : VoiceCommand
    data class Kind(val type: NoiseType) : VoiceCommand
    data object Yes : VoiceCommand
    data object No : VoiceCommand
}

/**
 * Maps recognized Korean speech to commands with keyword rules. The answers are one or two words on purpose:
 * the driver answers while driving.
 */
object VoiceCommandParser {

    private val rattle = listOf("달그락", "덜그럭", "달각", "딸각", "덜컹", "덜덜", "딸그락", "탈탈", "툭툭", "쿵")
    private val squeak = listOf("삐걱", "삐그덕", "끼익", "끽", "삑", "찌걱", "뽀드득")
    private val hum = listOf("웅웅", "웅", "윙", "우웅", "붕", "부웅", "윙윙")
    private val other = listOf("기타", "다른소리", "모르겠", "잘모르")
    private val yes = setOf("네", "넵", "예", "응", "어", "맞아", "맞아요", "같아요", "그래", "네맞아요")
    private val no = listOf("아니", "아뇨", "달라", "다른", "아냐")

    fun parse(text: String, expect: Expect = Expect.ANY): VoiceCommand? {
        val t = text.replace(" ", "").trimEnd('.', ',', '!', '?')
        if (t.isEmpty()) return null
        if (("저장" in t || "녹음" in t || "기록" in t) && ("소리" in t || "방금" in t || "해" in t)) return VoiceCommand.SaveNoise
        when (expect) {
            Expect.WHERE -> spots(t)?.let { return VoiceCommand.Where(it) }
            Expect.KIND -> kind(t)?.let { return VoiceCommand.Kind(it) }
            Expect.YES_NO, Expect.ANY -> Unit
        }
        if (no.any { t.startsWith(it) }) return VoiceCommand.No
        if (t in yes || t.startsWith("네") || "같은소리" in t) return VoiceCommand.Yes
        kind(t)?.let { return VoiceCommand.Kind(it) }
        spots(t)?.let { return VoiceCommand.Where(it) }
        return null
    }

    fun kind(t: String): NoiseType? = when {
        rattle.any { it in t } -> NoiseType.RATTLE
        squeak.any { it in t } -> NoiseType.SQUEAK
        hum.any { it in t } -> NoiseType.HUM
        other.any { it in t } -> NoiseType.OTHER
        else -> null
    }

    /** "앞 왼쪽", "운전석 쪽", "트렁크", "모르겠어요" (an empty set: heard, but not sure where). */
    fun spots(t: String): Set<Spot>? {
        val out = mutableSetOf<Spot>()
        if ("앞" in t || "전방" in t || "대시보드" in t || "보닛" in t) out += Spot.FRONT
        if ("뒤" in t || "후방" in t || "트렁크" in t || "뒷좌석" in t) out += Spot.REAR
        if ("왼" in t || "좌측" in t || "운전석" in t) out += Spot.LEFT
        if ("오른" in t || "우측" in t || "조수석" in t) out += Spot.RIGHT
        if ("운전석" in t || "조수석" in t) out += Spot.FRONT
        if (out.isEmpty() && ("모르" in t || "전체" in t)) return emptySet()
        return out.ifEmpty { null }
    }

    enum class Expect { ANY, WHERE, KIND, YES_NO }
}
