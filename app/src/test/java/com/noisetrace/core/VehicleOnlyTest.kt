package com.noisetrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saves without the microphone: the button still keeps the car around the press, so patterns still form. */
class VehicleOnlyTest {
    private val press = 100_000L

    /** 40 s at 10 Hz: steady 40 km/h, then slowing to 14 km/h with the wheel 18° left in the last 4 s before the press. */
    private val trace = (0..400).map { i ->
        val t = press - 30_000 + i * 100L
        val slowing = t >= press - 4_000
        VehicleSnapshot(
            timestampMs = t,
            speedMps = if (slowing) (14f - 0.6f * ((t - press) / 1000f)) / 3.6f else 40f / 3.6f,
            gear = Gear.DRIVE,
            steeringDeg = if (slowing) -18f else 0f,
            wiperOn = true,
        )
    }

    @Test
    fun keepsTheCarTwoSecondsBeforeThePress() {
        val e = EventFactory.buildVehicleOnly("v1", 9, press, trace, emptyList())
        assertFalse(e.hasAudio)
        assertTrue(e.vehicleOnly)
        assertFalse(e.momentCertain)
        assertEquals(press - CapturePipeline.UNCERTAIN_GUESS_MS, e.noiseAtMs)
        assertEquals(TriggerKind.MANUAL, e.trigger)
        assertNull(e.spectrum)
        assertTrue(e.confirmed)
        assertEquals(-18f, e.context.steeringDeg!!, 0.01f)
        assertTrue(Condition.TURN_LEFT in e.conditions && Condition.LOW_SPEED in e.conditions && Condition.WIPER in e.conditions)
        // 20 s before to 10 s after the press, at 5 per second.
        assertEquals(-18_000, e.trace.first().tMs)
        assertEquals(12_000, e.trace.last().tMs)
        assertEquals(30_000L, e.clipDurationMs)
    }

    @Test
    fun reportSaysThereIsNoRecording() {
        val e = EventFactory.buildVehicleOnly("v1", 9, press, trace, emptyList()).copy(userType = NoiseType.RATTLE)
        assertEquals("📝 달그락 소리 기록 (녹음 없음)", NoiseReport.headline(e))
        assertEquals("직접 저장 · 녹음 없이 버튼 2초 전 차량 상태", NoiseReport.detailLines(e).first().second)
        // Speed was nearly steady two seconds before the press, so the wipers fill the third place.
        assertEquals("저속 + 좌회전 + 와이퍼 ON에서 발생 가능성 높음", NoiseReport.estimate(e, null))
    }

    @Test
    fun aRecordingDeletedAfterWithdrawalIsNotMistakenForAVehicleOnlySave() {
        val recorded = NoiseEvent("r", 1, press, press, TriggerKind.AUTO, audioFile = "r.wav", excessTimeline = listOf(0f, 12f, 3f))
        val withdrawn = recorded.copy(audioFile = null, audioBytes = 0)
        assertFalse(withdrawn.hasAudio)
        assertFalse(withdrawn.vehicleOnly)
        assertEquals("자동 감지 (평소보다 크게 들림) · 녹음 파일은 삭제됨", NoiseReport.detailLines(withdrawn).first().second)
    }

    @Test
    fun vehicleOnlySavesStillFormAPattern() {
        val events = (1..3).map { n ->
            EventFactory.buildVehicleOnly("v$n", n, press + n * 86_400_000L, trace.map { it.copy(timestampMs = it.timestampMs + n * 86_400_000L) }, emptyList())
                .copy(userType = NoiseType.RATTLE, spots = setOf(Spot.FRONT))
        }
        val p = PatternFinder.find(events, DrivingExposure()).single()
        assertEquals(3, p.size)
        assertTrue(Condition.TURN_LEFT in p.common)
    }
}
