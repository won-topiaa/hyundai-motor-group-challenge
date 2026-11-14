package com.noisetrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The planning example: #03, 14 km/h, slowing, wheel 18° left, wipers on, downhill, car park entrance. */
private val example = DrivingContext(
    speedKmh = 14f, accelMps2 = -0.6f, steeringDeg = -18f, gear = Gear.DRIVE, wiperOn = true, acOn = false,
    slopePct = -8f, address = "지하주차장 진입구",
)

private fun event(n: Int, ctx: DrivingContext, type: NoiseType? = NoiseType.RATTLE, at: Long = n * 86_400_000L) = NoiseEvent(
    id = "e$n", number = n, noiseAtMs = at, savedAtMs = at, trigger = TriggerKind.MANUAL, context = ctx, userType = type,
    spots = setOf(Spot.FRONT),
)

class ReportTest {
    @Test
    fun summaryReadsLikeThePlanningExample() {
        val e = event(3, example)
        assertEquals("이상 소음 #03", NoiseReport.title(e))
        assertEquals("🔊 달그락 소리 녹음됨", NoiseReport.headline(e))
        assertEquals(
            listOf("차량 속도: 14 km/h", "감속 중", "조향각: 좌측 18°", "와이퍼: ON", "도로 경사: 내리막 (약 8%)", "위치: 지하주차장 진입구"),
            NoiseReport.summaryLines(e),
        )
        assertEquals("저속 + 좌회전 + 내리막에서 발생 가능성 높음", NoiseReport.estimate(e, null))
    }

    @Test
    fun withDrivingHistoryTheRarestManoeuvresAreChosen() {
        // A hilly commute: downhill is everyday here, slowing down is rare. Speed always stays; wipers only fill gaps.
        val hilly = DrivingExposure(10_000, mapOf(Condition.LOW_SPEED to 3_000L, Condition.TURN_LEFT to 600L, Condition.DOWNHILL to 4_000L, Condition.DECEL to 300L, Condition.WIPER to 100L))
        assertEquals("저속 + 좌회전 + 감속에서 발생 가능성 높음", Reproduction.estimate(example.conditions(), hilly))
        assertEquals("정차 + 에어컨 ON에서 발생 가능성 높음", Reproduction.estimate(setOf(Condition.STOPPED, Condition.AC), hilly))
    }

    @Test
    fun singleEventGuide() {
        val all = Condition.READING_ORDER.filter { it in example.conditions() }
        assertEquals(
            "내리막(지하주차장 진입로 같은 경사로)에서 10~15 km/h로 핸들을 왼쪽으로 돌리며 살짝 감속해 보세요. 와이퍼를 켠 상태에서 났어요.",
            Reproduction.guide(all, Reproduction.rangeAround(14f)),
        )
    }

    @Test
    fun conditionsFromContext() {
        assertEquals(
            setOf(Condition.LOW_SPEED, Condition.TURN_LEFT, Condition.DECEL, Condition.DOWNHILL, Condition.WIPER),
            example.conditions(),
        )
        assertEquals(setOf(Condition.STOPPED, Condition.AC), DrivingContext(speedKmh = 0f, steeringDeg = -40f, acOn = true).conditions())
    }

    @Test
    fun motorRpmFollowsSpeed() {
        assertEquals(1098.0, example.motorRpmEstimate!!.toDouble(), 5.0)
    }

    @Test
    fun loadShiftCombinesDirections() {
        assertEquals("앞·오른쪽", loadShift(listOf(Condition.TURN_LEFT, Condition.DOWNHILL, Condition.DECEL)))
        assertEquals("왼쪽", loadShift(listOf(Condition.TURN_RIGHT, Condition.DECEL, Condition.ACCEL)))
        assertNull(loadShift(listOf(Condition.WIPER)))
    }
}

class PatternFinderTest {
    private val week = listOf(
        event(1, example.copy(speedKmh = 12f, steeringDeg = -22f, accelMps2 = -0.7f, wiperOn = false, acOn = true)),
        event(2, example.copy(speedKmh = 17f, steeringDeg = -16f, slopePct = -6f)),
        event(3, example.copy(speedKmh = 15f, steeringDeg = -30f, accelMps2 = -0.1f, wiperOn = false)),
        event(4, example),
        event(5, example.copy(speedKmh = 13f, steeringDeg = -25f)),
        event(6, DrivingContext(speedKmh = 96f, steeringDeg = 1f, acOn = true), type = NoiseType.HUM),
    )

    @Test
    fun fiveRattlesShareSlowLeftDownhill() {
        val p = PatternFinder.find(week, DrivingExposure()).single()
        assertEquals(NoiseType.RATTLE, p.type)
        assertEquals(5, p.size)
        assertEquals(10..20, p.speedRange)
        assertEquals(listOf(Condition.LOW_SPEED, Condition.TURN_LEFT, Condition.DECEL, Condition.DOWNHILL), p.common)
        assertEquals("저속 좌회전 + 차량 하중 이동(앞·오른쪽) 상황에서 반복 발생", p.summary)
        assertEquals("5번 모두: 10~20 km/h · 좌회전 · 내리막", p.evidence.first())
        assertTrue(p.evidence.contains("5번 중 4번: 감속"))
        assertEquals(setOf(Spot.FRONT), p.spots)
    }

    @Test
    fun conditionsCommonInEverydayDrivingAreSetAside() {
        val events = (1..3).map { event(it, example.copy(acOn = true)) }
        val exposure = DrivingExposure(20_000, mapOf(Condition.AC to 18_000L, Condition.LOW_SPEED to 3_000L, Condition.TURN_LEFT to 1_000L, Condition.DOWNHILL to 400L, Condition.DECEL to 2_000L, Condition.WIPER to 1_000L))
        val p = PatternFinder.find(events, exposure).single()
        assertFalse(Condition.AC in p.common)
        assertEquals(listOf(Condition.AC), p.ordinary)
        assertTrue(p.evidence.any { it.startsWith("평소 주행에서") && "내리막은 2%" in it })
    }

    @Test
    fun reproduceGuideAndInspectionHints() {
        val p = PatternFinder.find(week, DrivingExposure()).single()
        assertEquals("내리막(지하주차장 진입로 같은 경사로)에서 10~20 km/h로 핸들을 왼쪽으로 돌리며 살짝 감속해 보세요.", p.reproduceGuide)
        assertEquals("하중이 쏠리는 쪽 서스펜션의 스태빌라이저 링크·로어암 부싱", p.inspection.first())
        assertTrue(p.inspection.contains("대시보드·글로브박스·센터페시아 내장재 고정 클립"))
    }

    @Test
    fun aSingleNoiseIsNoPattern() {
        assertTrue(PatternFinder.find(week.take(1), DrivingExposure()).isEmpty())
    }
}

class ContextBuilderTest {
    private fun drive(): List<VehicleSnapshot> {
        // 20 s at 5 m/s down an 8 % slope with fresh GPS fixes, wheel 18° left, slowing in the last second.
        var alt = 50.0
        return (0..200).map { i ->
            val t = i * 100L
            val v = if (i > 190) 5f - (i - 190) * 0.06f else 5f
            alt -= 0.08 * v * 0.1
            VehicleSnapshot(timestampMs = t, speedMps = v, gear = Gear.DRIVE, steeringDeg = -18f, altitudeM = alt, locationAtMs = t, wiperOn = true)
        }
    }

    @Test
    fun contextAtTheNoise() {
        val c = ContextBuilder.at(drive(), emptyList(), 20_000)
        assertEquals(-0.6f, c.accelMps2!!, 0.05f)
        assertEquals(-8f, c.slopePct!!, 0.5f)
        assertFalse(c.gpsWeak)
        assertEquals(setOf(Condition.LOW_SPEED, Condition.TURN_LEFT, Condition.DECEL, Condition.DOWNHILL, Condition.WIPER), c.conditions())
    }

    @Test
    fun staleAltitudeUndergroundGivesNoSlopeAndWeakGps() {
        val trace = drive().map { if (it.timestampMs > 5_000) it.copy(altitudeM = 49.0, locationAtMs = 5_000) else it }
        val c = ContextBuilder.at(trace, emptyList(), 20_000)
        assertNull(c.slopePct)
        assertTrue(c.gpsWeak)
    }

    @Test
    fun bumpPassedNearTheNoise() {
        val tracker = HazardTracker()
        tracker.update(listOf(HazardAhead("b", RoadHazard.SPEED_BUMP, 20.0)), 18_000)
        tracker.update(emptyList(), 19_000)
        val c = ContextBuilder.at(drive(), tracker.passes, 20_000)
        assertEquals(RoadHazard.SPEED_BUMP, c.hazard)
        assertTrue(Condition.BUMP in c.conditions())
        assertNull(ContextBuilder.at(drive(), tracker.passes, 10_000).hazard)
    }

    @Test
    fun roadTypeFromSpeedLimit() {
        assertEquals(RoadType.HIGHWAY, ContextBuilder.roadType(100))
        assertEquals(RoadType.ARTERIAL, ContextBuilder.roadType(50))
        assertEquals(RoadType.LOCAL, ContextBuilder.roadType(10))
        assertNull(ContextBuilder.roadType(null))
    }
}

class StoragePolicyTest {
    private fun e(id: String, at: Long, confirmed: Boolean) =
        NoiseEvent(id, 0, at, at, TriggerKind.AUTO, audioBytes = 1_000_000, confirmed = confirmed)

    @Test
    fun unconfirmedAutoCapturesGoFirstThenTheOldest() {
        val events = listOf(e("old-kept", 1, true), e("old-auto", 2, false), e("mid", 3, true), e("new-auto", 4, false), e("newest", 5, true))
        assertEquals(listOf("old-auto", "new-auto"), StoragePolicy.toDelete(events, maxEvents = 3))
        assertEquals(listOf("old-auto", "new-auto", "old-kept"), StoragePolicy.toDelete(events, maxEvents = 10, maxBytes = 2_000_000))
    }

    @Test
    fun theNewestIsNeverDeleted() {
        assertTrue(StoragePolicy.toDelete(listOf(e("only", 1, false)), maxEvents = 0).isEmpty())
    }
}

class VoiceCommandParserTest {
    @Test
    fun save() {
        assertEquals(VoiceCommand.SaveNoise, VoiceCommandParser.parse("방금 소리 저장"))
        assertEquals(VoiceCommand.SaveNoise, VoiceCommandParser.parse("녹음해 줘"))
    }

    @Test
    fun where() {
        assertEquals(VoiceCommand.Where(setOf(Spot.FRONT)), VoiceCommandParser.parse("앞쪽이요", VoiceCommandParser.Expect.WHERE))
        assertEquals(VoiceCommand.Where(setOf(Spot.FRONT, Spot.LEFT)), VoiceCommandParser.parse("앞 왼쪽", VoiceCommandParser.Expect.WHERE))
        assertEquals(VoiceCommand.Where(setOf(Spot.FRONT, Spot.RIGHT)), VoiceCommandParser.parse("조수석 쪽", VoiceCommandParser.Expect.WHERE))
        assertEquals(VoiceCommand.Where(setOf(Spot.REAR)), VoiceCommandParser.parse("트렁크", VoiceCommandParser.Expect.WHERE))
        assertEquals(VoiceCommand.Where(emptySet()), VoiceCommandParser.parse("잘 모르겠어요", VoiceCommandParser.Expect.WHERE))
    }

    @Test
    fun kind() {
        assertEquals(VoiceCommand.Kind(NoiseType.RATTLE), VoiceCommandParser.parse("달그락이요", VoiceCommandParser.Expect.KIND))
        assertEquals(VoiceCommand.Kind(NoiseType.SQUEAK), VoiceCommandParser.parse("끼익 했어요", VoiceCommandParser.Expect.KIND))
        assertEquals(VoiceCommand.Kind(NoiseType.HUM), VoiceCommandParser.parse("웅웅 거려요", VoiceCommandParser.Expect.KIND))
        assertEquals(VoiceCommand.Kind(NoiseType.OTHER), VoiceCommandParser.parse("모르겠어요", VoiceCommandParser.Expect.KIND))
    }

    @Test
    fun yesNo() {
        assertEquals(VoiceCommand.Yes, VoiceCommandParser.parse("네", VoiceCommandParser.Expect.YES_NO))
        assertEquals(VoiceCommand.Yes, VoiceCommandParser.parse("네 맞아요", VoiceCommandParser.Expect.YES_NO))
        assertEquals(VoiceCommand.No, VoiceCommandParser.parse("아니요 다른 소리예요", VoiceCommandParser.Expect.YES_NO))
    }
}
