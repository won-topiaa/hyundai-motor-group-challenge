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
        assertTrue(ProfileTuner.cueConfig(profile, routeGuided = false).brakeCuesEnabled)
    }

    @Test
    fun `brake alerts stay off while route guidance announces the slowdowns`() {
        val profile = DriverProfile("d", "기사")
        assertEquals(false, ProfileTuner.cueConfig(profile, routeGuided = true).brakeCuesEnabled)
        assertEquals(true, ProfileTuner.cueConfig(profile, routeGuided = false).brakeCuesEnabled)
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
        assertEquals(VoiceCommand.StartPriming, VoiceCommandParser.parse("1분 들을게요"))
        assertEquals(VoiceCommand.CuesOnly, VoiceCommandParser.parse("알림음만 켜 줘"))
        assertEquals(VoiceCommand.ModeOn, VoiceCommandParser.parse("편안한 승차 모드 켜 주세요"))
        assertEquals(VoiceCommand.ModeOff, VoiceCommandParser.parse("승차 모드 꺼 주세요"))
        assertNull(VoiceCommandParser.parse("오늘 날씨 어때"))
    }

    @Test
    fun `passenger answers are understood`() {
        listOf("네", "네.", "예", "응", "네 켜 주세요", "부탁드려요").forEach { assertEquals(it, VoiceCommand.Yes, VoiceCommandParser.parse(it)) }
        listOf("아니요", "괜찮아요", "안 켜도 돼요", "멀미 안 나요").forEach { assertEquals(it, VoiceCommand.No, VoiceCommandParser.parse(it)) }
        assertEquals(VoiceCommand.Score(4), VoiceCommandParser.parse("4점이요"))
        assertEquals(VoiceCommand.Score(3), VoiceCommandParser.parse("삼 점"))
        assertEquals(VoiceCommand.Score(4), VoiceCommandParser.parse("네 점이요"))
    }

    @Test
    fun `talk with the driver is not taken for an answer`() {
        listOf("강남역으로 가 주세요", "네비 보고 가시면 돼요", "예약했어요", "어디로 갈까요").forEach { assertNull(it, VoiceCommandParser.parse(it)) }
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

    @Test
    fun `hard starts classify as hard starter and keep the default cue timing`() {
        val stats = TripStats(durationSec = 600.0, departures = 5, hardStartCount = 3)
        val profile = ProfileTuner.afterTrip(DriverProfile("d", "운전자", leadTimeSec = 1.5), TripRecord(1, stats, comfortScore = 3))
        assertEquals(DrivingStyle.HARD_STARTER, profile.style)
        assertEquals(1.5, profile.leadTimeSec, 1e-9)
        assertTrue(CoachingEngine.tipFor(profile, profile.trips.last()).title.contains("출발"))
    }

    @Test
    fun `sharp turns classify as sharp turner and move cues earlier`() {
        val stats = TripStats(durationSec = 600.0, turns = 6, sharpTurnCount = 3)
        val profile = ProfileTuner.afterTrip(DriverProfile("d", "운전자", leadTimeSec = 1.5), TripRecord(1, stats, comfortScore = 3))
        assertEquals(DrivingStyle.SHARP_TURNER, profile.style)
        assertTrue(profile.leadTimeSec > 1.5)
        assertTrue(CoachingEngine.tipFor(profile, profile.trips.last()).title.contains("회전"))
    }

    @Test
    fun `fast bump crossings classify as bump rusher`() {
        val stats = TripStats(durationSec = 600.0, bumpPasses = 3, fastBumpCount = 2)
        val profile = ProfileTuner.afterTrip(DriverProfile("d", "운전자"), TripRecord(1, stats, comfortScore = 3))
        assertEquals(DrivingStyle.BUMP_RUSHER, profile.style)
        assertTrue(CoachingEngine.tipFor(profile, profile.trips.last()).message.contains("30km"))
    }

    @Test
    fun `a single rough moment is not enough to change the style`() {
        val stats = TripStats(durationSec = 600.0, departures = 2, hardStartCount = 2, turns = 2, sharpTurnCount = 2, bumpPasses = 1, fastBumpCount = 1)
        assertEquals(DrivingStyle.SMOOTH, StyleClassifier.classify(listOf(stats)))
    }

    @Test
    fun `trip recorder counts hard and gentle starts`() {
        val r = TripRecorder(0)
        var t = 0L
        fun feed(speed: Float) {
            r.onVehicle(VehicleSnapshot(timestampMs = t, speedMps = speed, gear = Gear.DRIVE))
            t += 200
        }
        // 3 m/s² away from a stop, then a stop and a 1 m/s² start.
        repeat(3) { feed(0f) }
        (1..15).forEach { feed(it * 0.6f) }
        listOf(6f, 4f, 2f, 0f, 0f, 0f).forEach { feed(it) }
        (1..15).forEach { feed(it * 0.2f) }
        val stats = r.finish()
        assertEquals(2, stats.departures)
        assertEquals(1, stats.hardStartCount)
    }

    @Test
    fun `trip recorder counts sharp turns from speed and steering`() {
        val r = TripRecorder(0)
        var t = 0L
        fun feed(speed: Float, steering: Float) {
            r.onVehicle(VehicleSnapshot(timestampMs = t, speedMps = speed, gear = Gear.DRIVE, steeringDeg = steering))
            t += 200
        }
        // About 25 km/h with the wheel at 200°, then about 15 km/h with the wheel at 90°.
        repeat(3) { feed(7f, 0f) }
        repeat(5) { feed(7f, 200f) }
        repeat(3) { feed(7f, 0f) }
        repeat(5) { feed(4f, -90f) }
        repeat(3) { feed(4f, 0f) }
        val stats = r.finish()
        assertEquals(2, stats.turns)
        assertEquals(1, stats.sharpTurnCount)
        assertTrue(TripRecorder.sidewaysAccel(7f, 200f) > 3.0)
        assertTrue(TripRecorder.sidewaysAccel(4f, 90f) < 1.5)
    }

    @Test
    fun `trip recorder counts bump crossings and their speed`() {
        val r = TripRecorder(0)
        r.onVehicle(VehicleSnapshot(timestampMs = 0, speedMps = 11f, gear = Gear.DRIVE))
        r.onRouteEvents(listOf(RouteEvent("b1", RouteEventKind.SPEED_BUMP, 40.0)))
        r.onRouteEvents(listOf(RouteEvent("b1", RouteEventKind.SPEED_BUMP, 2.0)))
        r.onRouteEvents(emptyList())
        r.onVehicle(VehicleSnapshot(timestampMs = 1000, speedMps = 5f, gear = Gear.DRIVE))
        r.onRouteEvents(listOf(RouteEvent("b2", RouteEventKind.SPEED_BUMP, 12.0)))
        r.onRouteEvents(emptyList())
        // A bump that disappears while still far away (a reroute) is not counted.
        r.onRouteEvents(listOf(RouteEvent("b3", RouteEventKind.SPEED_BUMP, 200.0)))
        r.onRouteEvents(emptyList())
        val stats = r.finish()
        assertEquals(2, stats.bumpPasses)
        assertEquals(1, stats.fastBumpCount)
    }
}
