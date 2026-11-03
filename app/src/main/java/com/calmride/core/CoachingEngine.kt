package com.calmride.core

data class CoachingTip(val title: String, val message: String)

/**
 * Picks one habit to suggest after a trip, using fixed sentence templates.
 * Principles: one tip at a time, no scoring or blame, show the passenger's comfort trend.
 */
object CoachingEngine {

    fun tipFor(profile: DriverProfile, last: TripRecord): CoachingTip {
        val s = last.stats
        val trend = comfortTrend(profile, last)
        val tip = when {
            profile.style == DrivingStyle.ONE_PEDAL && (s.regenState == RegenState.FULL || s.coastDecelCount >= 3) ->
                CoachingTip(
                    "회생제동을 한 단계 낮춰 보세요",
                    "이번 주행에서 브레이크 없이 강하게 감속한 구간이 ${s.coastDecelCount}번 있었어요. " +
                        "회생제동을 한 단계 낮추면 동승자가 덜 울렁거려요.",
                )
            profile.style == DrivingStyle.LATE_BRAKER ->
                CoachingTip(
                    "회전 전 감속을 조금 일찍 시작해 보세요",
                    "회전이나 방지턱 바로 앞에서 브레이크를 밟은 경우가 ${s.lateBrakeCount}번 있었어요. " +
                        "다음 주행부터는 예고음을 조금 더 일찍 울릴게요.",
                )
            profile.style == DrivingStyle.QUICK_LANE_CHANGER ->
                CoachingTip(
                    "방향지시등을 켜고 잠깐 기다려 보세요",
                    "방향지시등을 켜자마자 차선을 바꾼 경우가 ${s.quickLaneChanges}번 있었어요. " +
                        "1초 정도 기다리면 동승자가 움직임을 미리 느낄 수 있어요.",
                )
            s.hardDecelCount > 0 ->
                CoachingTip(
                    "감속을 조금 더 길게 나눠 보세요",
                    "강한 감속이 ${s.hardDecelCount}번 있었어요. 브레이크를 일찍, 약하게 밟으면 동승자가 편해져요.",
                )
            else ->
                CoachingTip(
                    "부드럽게 운전하셨어요",
                    "급한 감속이 거의 없었어요. 다음 주행에서는 예고음을 조금 줄일게요.",
                )
        }
        return if (trend == null) tip else tip.copy(message = tip.message + " " + trend)
    }

    private fun comfortTrend(profile: DriverProfile, last: TripRecord): String? {
        val now = last.comfortScore ?: return null
        val before = profile.trips
            .filter { it.endedAtMs < last.endedAtMs }
            .mapNotNull { it.comfortScore }
            .takeLast(3)
        if (before.isEmpty()) return null
        val avg = before.average()
        return when {
            now > avg + 0.4 -> "동승자 컨디션이 지난 주행보다 좋아졌어요."
            now < avg - 0.4 -> "동승자 컨디션이 지난 주행보다 조금 떨어졌어요."
            else -> null
        }
    }

    /** Spoken right after the trip ends. */
    fun spoken(tip: CoachingTip): String = "${tip.title}. ${tip.message}"
}
