package com.calmride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {

    @Test
    fun `late braking trips classify as late braker and move cues earlier`() {
        val stats = TripStats(durationSec = 600.0, brakeEvents = 10, lateBrakeCount = 6)
        assertEquals(DrivingStyle.LATE_BRAKER, StyleClassifier.classify(listOf(stats)))

        val profile = DriverProfile("d", "운전자", leadTimeSec = 1.5)
        val tuned = ProfileTuner.afterTrip(profile, TripRecord(1, stats, comfortScore = 2))
        assertEquals(DrivingStyle.LATE_BRAKER, tuned.style)
        assertTrue(tuned.leadTimeSec > 1.5)
        assertEquals(1, tuned.trips.size)
    }

    @Test
    fun `coasting with full regen classifies as one pedal and suggests lower regen`() {
        val stats = TripStats(durationSec = 600.0, brakeEvents = 2, coastDecelCount = 6, regenState = RegenState.FULL)
        val profile = ProfileTuner.afterTrip(DriverProfile("d", "운전자"), TripRecord(1, stats, comfortScore = 3))
        assertEquals(DrivingStyle.ONE_PEDAL, profile.style)
        val tip = CoachingEngine.tipFor(profile, profile.trips.last())
        assertTrue(tip.title.contains("회생제동"))
    }

    @Test
    fun `quiet trips stay smooth and get praise`() {
        val stats = TripStats(durationSec = 600.0, brakeEvents = 8, lateBrakeCount = 0, coastDecelCount = 1)
        val profile = ProfileTuner.afterTrip(DriverProfile("d", "운전자"), TripRecord(1, stats, comfortScore = 5))
        assertEquals(DrivingStyle.SMOOTH, profile.style)
        assertTrue(CoachingEngine.tipFor(profile, profile.trips.last()).title.contains("부드럽게"))
    }

    @Test
    fun `smooth driving with an uncomfortable passenger is not praised`() {
        val stats = TripStats(durationSec = 600.0, brakeEvents = 8)
        val profile = ProfileTuner.afterTrip(DriverProfile("d", "운전자"), TripRecord(1, stats, comfortScore = 2))
        val tip = CoachingEngine.tipFor(profile, profile.trips.last())
        assertTrue(tip.title.contains("더 일찍"))
        assertTrue(ProfileTuner.cueConfig(profile).brakeCuesEnabled)
    }

    @Test
    fun `comfort trend is mentioned when it improves`() {
        val base = DriverProfile(
            "d", "운전자",
            trips = listOf(TripRecord(1, TripStats(), 2), TripRecord(2, TripStats(), 2)),
        )
        val last = TripRecord(3, TripStats(), 5)
        val tip = CoachingEngine.tipFor(base.copy(trips = base.trips + last), last)
        assertTrue(tip.message.contains("좋아졌어요"))
    }

    @Test
    fun `lead time stays within bounds`() {
        var p = DriverProfile("d", "운전자", leadTimeSec = 2.9)
        repeat(10) {
            p = ProfileTuner.afterTrip(p, TripRecord(it.toLong(), TripStats(brakeEvents = 10, lateBrakeCount = 9), comfortScore = 1))
        }
        assertTrue(p.leadTimeSec <= 3.0)
        assertTrue(p.trips.size <= DriverProfile.MAX_TRIPS)
    }

    @Test
    fun `driver is recognized from the seat position`() {
        val a = DriverProfile("a", "지우", seatForeAft = 30, seatHeight = 10)
        val b = DriverProfile("b", "민수", seatForeAft = 80, seatHeight = 40)
        assertEquals("b", DriverIdentifier.match(listOf(a, b), 78, 42)?.id)
        assertNull(DriverIdentifier.match(listOf(a, b), 55, 25, tolerance = 5))
        assertNull(DriverIdentifier.match(listOf(a, b), null, null))
    }

    @Test
    fun `voice commands are parsed`() {
        assertEquals(VoiceCommand.ModeOn, VoiceCommandParser.parse("멀미 모드 켜줘"))
        assertEquals(VoiceCommand.ModeOff, VoiceCommandParser.parse("멀미 모드 꺼 줘"))
        assertEquals(VoiceCommand.StartPriming, VoiceCommandParser.parse("사운드 준비해 줘"))
        assertEquals(VoiceCommand.SwitchDriver("지우"), VoiceCommandParser.parse("나 지우야"))
        assertEquals(VoiceCommand.ReplayAtStops(true), VoiceCommandParser.parse("신호에서 소리 들려줘"))
        assertEquals(VoiceCommand.ReplayAtStops(false), VoiceCommandParser.parse("신호 대기 사운드 꺼 줘"))
        assertNull(VoiceCommandParser.parse("오늘 날씨 어때"))
    }

    @Test
    fun `stop detector needs a short hold below the stop speed`() {
        val d = StopDetector(holdMs = 2000)
        assertEquals(false, d.update(VehicleSnapshot(timestampMs = 0, speedMps = 5f, gear = Gear.DRIVE)))
        assertEquals(false, d.update(VehicleSnapshot(timestampMs = 100, speedMps = 0f, gear = Gear.DRIVE)))
        assertEquals(true, d.update(VehicleSnapshot(timestampMs = 2200, speedMps = 0f, gear = Gear.DRIVE)))
        assertEquals(true, d.update(VehicleSnapshot(timestampMs = 2300, gear = Gear.PARK)))
        assertNull(StopDetector().update(VehicleSnapshot(timestampMs = 0)))
    }

    @Test
    fun `shifting from Park to Drive at a standstill stays stopped`() {
        val d = StopDetector(holdMs = 2000)
        assertEquals(true, d.update(VehicleSnapshot(timestampMs = 0, speedMps = 0f, gear = Gear.PARK)))
        assertEquals(true, d.update(VehicleSnapshot(timestampMs = 100, speedMps = 0f, gear = Gear.DRIVE)))
        assertEquals(false, d.update(VehicleSnapshot(timestampMs = 600, speedMps = 3f, gear = Gear.DRIVE)))
    }

    @Test
    fun `trip recorder counts hard and coasting decelerations`() {
        val r = TripRecorder(0)
        var t = 0L
        fun feed(speed: Float, brake: Boolean) {
            r.onVehicle(VehicleSnapshot(timestampMs = t, speedMps = speed, gear = Gear.DRIVE, brakePressed = brake))
            t += 200
        }
        // Cruise, then a hard braked stop, then cruise and a coasting slowdown without the pedal.
        repeat(5) { feed(15f, false) }
        listOf(14f, 13f, 12f, 11f, 10f).forEach { feed(it, true) }
        repeat(5) { feed(10f, false) }
        listOf(9.6f, 9.2f, 8.8f, 8.4f).forEach { feed(it, false) }
        val stats = r.finish()
        assertEquals(1, stats.hardDecelCount)
        assertEquals(1, stats.coastDecelCount)
        assertEquals(1, stats.brakeEvents)
        assertTrue(stats.distanceM > 0)
    }
}
