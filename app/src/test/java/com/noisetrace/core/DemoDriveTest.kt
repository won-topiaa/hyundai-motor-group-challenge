package com.noisetrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Runs the whole demo drive the way the app does (vehicle trace at 10 Hz, synthetic cabin audio through the capture
 * pipeline, the button press) and checks the two noise reports it should produce.
 */
class DemoDriveTest {

    private fun runDemo(): List<NoiseEvent> {
        val audio = DemoCabinAudio()
        val pipeline = CapturePipeline()
        val frame = ShortArray(pipeline.frameSamples)
        val trace = mutableListOf<VehicleSnapshot>()
        val events = mutableListOf<NoiseEvent>()
        val t0 = 5_000_000L
        var snap = VehicleSnapshot()
        fun tick(t: Long) {
            audio.next(frame)
            pipeline.onFrame(frame, t, snap.speedKmh, false)?.let { clip ->
                events += EventFactory.build("e${events.size}", events.size + 1, clip, trace, emptyList(), null)
            }
        }
        val steps = DemoScript.steps()
        steps.forEachIndexed { i, s ->
            val t = t0 + i * DemoScript.STEP_MS
            snap = snap.copy(
                timestampMs = t, speedMps = s.speedMps, gear = s.gear, steeringDeg = s.steeringDeg, brakePressed = s.brake,
                turnSignal = s.turnSignal, wiperOn = DemoScript.WIPER_ON, acOn = DemoScript.AC_ON, outsideTempC = DemoScript.OUTSIDE_TEMP_C,
                address = s.address, speedLimitKmh = s.speedLimitKmh,
            ).let { if (s.gpsFix) it.copy(altitudeM = s.altitudeM, locationAtMs = t, latitude = 37.5, longitude = 127.0) else it }
            trace += snap
            audio.speedKmh = snap.speedKmh ?: 0f
            s.sound?.let(audio::inject)
            if (s.pressSave) pipeline.requestManual(t)
            tick(t)
        }
        // Parked until the last capture completes.
        var t = t0 + steps.size * DemoScript.STEP_MS
        repeat(120) {
            trace += snap.copy(timestampMs = t)
            tick(t)
            t += DemoScript.STEP_MS
        }
        return events
    }

    @Test
    fun firstRattleIsCaughtAutomaticallyWithThePlanningExampleContext() {
        val events = runDemo()
        assertEquals(2, events.size)
        val e = events[0]
        assertEquals(TriggerKind.AUTO, e.trigger)
        val c = e.context
        assertEquals(14, c.speedKmh!!.roundToInt())
        assertEquals(-18, c.steeringDeg!!.roundToInt())
        assertEquals(-8, c.slopePct!!.roundToInt())
        assertEquals("언덕길 주택가 골목", c.address)
        assertEquals(setOf(Condition.LOW_SPEED, Condition.TURN_LEFT, Condition.DECEL, Condition.DOWNHILL, Condition.WIPER), c.conditions())
        assertEquals(NoiseType.RATTLE, e.spectrum!!.estimatedType)
        assertEquals("저속 + 좌회전 + 내리막에서 발생 가능성 높음", NoiseReport.estimate(e, null))
        assertTrue(e.trace.isNotEmpty() && e.excessTimeline.size in 290..310)
    }

    @Test
    fun secondRattleIsSavedByTheButtonAfterItWasHeard() {
        val e = runDemo()[1]
        assertEquals(TriggerKind.MANUAL, e.trigger)
        assertTrue(e.momentCertain)
        // Somewhere in the 0.9 s rattle that started 2.5 s before the press.
        val beforePress = (e.savedAtMs - e.noiseAtMs) / 1000.0
        val sinceStart = DemoScript.PRESS_SEC - DemoScript.SECOND_RATTLE_SEC
        assertTrue("found $beforePress s before the press", beforePress in (sinceStart - 1.0)..(sinceStart + 0.1))
        assertEquals(-25, e.context.steeringDeg!!.roundToInt())
        assertTrue("accel ${e.context.accelMps2} at ${(e.noiseAtMs - e.clipStartMs) / 1000.0} s into the clip", Condition.DECEL in e.conditions)
        assertTrue("slope ${e.context.slopePct}", Condition.DOWNHILL in e.conditions)
    }

    @Test
    fun withTheSampleWeekTheFiveRattlesFormThePlanningPattern() {
        val demo = runDemo().map { it.copy(userType = NoiseType.RATTLE, spots = setOf(Spot.FRONT)) }
        val samples = SampleHistory.build(System.currentTimeMillis() - 86_400_000L * 7, 1).map { it.event }
        val patterns = PatternFinder.find(samples + demo, SampleHistory.exposure)
        val rattles = patterns.first { it.type == NoiseType.RATTLE }
        assertEquals(5, rattles.size)
        assertEquals("저속 좌회전 + 차량 하중 이동(앞·오른쪽) 상황에서 반복 발생", rattles.summary)
        assertEquals("5번 모두: 10~20 km/h · 좌회전 · 내리막", rattles.evidence.first())
        // The motorway hum is a different noise.
        assertTrue(patterns.none { it.type == NoiseType.HUM })
    }
}
