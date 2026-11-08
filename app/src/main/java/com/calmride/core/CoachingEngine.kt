package com.calmride.core

data class CoachingTip(val title: String, val message: String)

/**
 * Picks one habit to suggest for the day, using fixed sentence templates.
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
                    "오늘 브레이크 없이 강하게 감속한 구간이 ${s.coastDecelCount}번 있었어요. " +
                        "회생제동을 한 단계 낮추면 승객가 덜 울렁거려요.",
                )
            profile.style == DrivingStyle.LATE_BRAKER ->
                CoachingTip(
                    "회전 전 감속을 조금 일찍 시작해 보세요",
                    "회전이나 방지턱 바로 앞에서 브레이크를 밟은 경우가 ${s.lateBrakeCount}번 있었어요. " +
                        "앞으로는 예고음을 조금 더 일찍 울릴게요.",
                )
            profile.style == DrivingStyle.QUICK_LANE_CHANGER ->
                CoachingTip(
                    "방향지시등을 켜고 잠깐 기다려 보세요",
                    "방향지시등을 켜자마자 차선을 바꾼 경우가 ${s.quickLaneChanges}번 있었어요. " +
                        "1초 정도 기다리면 승객가 움직임을 미리 느낄 수 있어요.",
                )
            profile.style == DrivingStyle.HARD_STARTER ->
                CoachingTip(
                    "출발할 때 가속 페달을 천천히 밟아 보세요",
                    "멈췄다가 급하게 출발한 경우가 ${s.hardStartCount}번 있었어요. " +
                        "전기차는 밟는 즉시 힘이 나서, 천천히 나눠 밟으면 승객가 덜 쏠려요.",
                )
            profile.style == DrivingStyle.SHARP_TURNER ->
                CoachingTip(
                    "회전할 때 속도를 조금 더 줄여 보세요",
                    "회전하면서 몸이 옆으로 크게 쏠린 경우가 ${s.sharpTurnCount}번 있었어요. " +
                        "앞으로는 회전 예고음을 조금 더 일찍 울릴게요.",
                )
            profile.style == DrivingStyle.BUMP_RUSHER ->
                CoachingTip(
                    "과속방지턱 앞에서 충분히 줄여 보세요",
                    "과속방지턱을 시속 30km보다 빠르게 넘은 경우가 ${s.fastBumpCount}번 있었어요. " +
                        "앞으로는 방지턱 예고음을 조금 더 일찍 울릴게요.",
                )
            s.hardDecelCount > 0 ->
                CoachingTip(
                    "감속을 조금 더 길게 나눠 보세요",
                    "강한 감속이 ${s.hardDecelCount}번 있었어요. 브레이크를 일찍, 약하게 밟으면 승객가 편해져요.",
                )
            // Smooth driving but an uncomfortable passenger: do not praise and turn cues down.
            (last.comfortScore ?: 5) <= 2 ->
                CoachingTip(
                    "앞으로 예고음을 더 일찍 울릴게요",
                    "운전은 부드러웠지만 승객가 불편했어요. 회전과 감속을 조금 더 일찍 알려 드릴게요.",
                )
            else ->
                CoachingTip(
                    "부드럽게 운전하셨어요",
                    "급한 감속이 거의 없었어요. 앞으로 예고음을 조금 줄일게요.",
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
            now > avg + 0.4 -> "승객 컨디션이 지난 운행보다 좋아졌어요."
            now < avg - 0.4 -> "승객 컨디션이 지난 운행보다 조금 떨어졌어요."
            else -> null
        }
    }

    /** Read out at the driver's request, never with a passenger aboard. */
    fun spoken(tip: CoachingTip): String = "${tip.title}. ${tip.message}"
}
