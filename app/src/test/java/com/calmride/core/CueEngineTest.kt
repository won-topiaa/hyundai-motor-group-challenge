package com.calmride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueEngineTest {

    private fun moving(t: Long, speed: Float = 10f, brake: Boolean = false, signal: TurnSignal = TurnSignal.OFF) =
        VehicleSnapshot(timestampMs = t, speedMps = speed, gear = Gear.DRIVE, brakePressed = brake, turnSignal = signal)

    @Test
    fun `turn cue fires once when time to reach drops below lead time`() {
        val engine = CueEngine(CueConfig(leadTimeSec = 1.5))
        val left = RouteEvent("t1", RouteEventKind.TURN_LEFT, 40.0)
        // 40 m at 10 m/s = 4 s: too early
        assertTrue(engine.onRouteEvents(listOf(left), moving(0)).isEmpty())
        // 14 m at 10 m/s = 1.4 s: fire
        val cues = engine.onRouteEvents(listOf(left.copy(distanceM = 14.0)), moving(2600))
        assertEquals(1, cues.size)
        assertEquals(CueKind.TURN_LEFT, cues[0].kind)
        assertTrue(cues[0].pan < 0)
        // Same maneuver does not fire again
        assertTrue(engine.onRouteEvents(listOf(left.copy(distanceM = 8.0)), moving(3200)).isEmpty())
    }

    @Test
    fun `no route cues while stopped`() {
        val engine = CueEngine()
        val bump = RouteEvent("b", RouteEventKind.SPEED_BUMP, 5.0)
        assertTrue(engine.onRouteEvents(listOf(bump), moving(0, speed = 0f)).isEmpty())
    }

    @Test
    fun `maneuver that moves farther away fires again`() {
        val engine = CueEngine(CueConfig(leadTimeSec = 2.0, minIntervalMs = 0))
        val e = RouteEvent("t", RouteEventKind.TURN_RIGHT, 15.0)
        assertEquals(1, engine.onRouteEvents(listOf(e), moving(0)).size)
        engine.onRouteEvents(listOf(e.copy(distanceM = 120.0)), moving(1000))
        assertEquals(1, engine.onRouteEvents(listOf(e.copy(distanceM = 15.0)), moving(9000)).size)
    }

    @Test
    fun `brake press cues a slowdown unless a route cue just announced it`() {
        val engine = CueEngine(CueConfig(minIntervalMs = 0))
        engine.onVehicle(moving(0))
        assertEquals(CueKind.SLOW_DOWN, engine.onVehicle(moving(100, brake = true)).single().kind)

        val engine2 = CueEngine(CueConfig(leadTimeSec = 2.0, minIntervalMs = 0))
        engine2.onRouteEvents(listOf(RouteEvent("b", RouteEventKind.SPEED_BUMP, 10.0)), moving(0))
        assertTrue(engine2.onVehicle(moving(1000, brake = true)).isEmpty())
    }

    @Test
    fun `turn signal cues the lane change side`() {
        val engine = CueEngine(CueConfig(minIntervalMs = 0))
        engine.onVehicle(moving(0))
        val cue = engine.onVehicle(moving(100, signal = TurnSignal.RIGHT)).single()
        assertEquals(CueKind.LANE_RIGHT, cue.kind)
        assertTrue(cue.pan > 0)
        // Holding the signal does not repeat the cue
        assertTrue(engine.onVehicle(moving(2000, signal = TurnSignal.RIGHT)).isEmpty())
    }

    @Test
    fun `cues respect the minimum interval`() {
        val engine = CueEngine(CueConfig(leadTimeSec = 3.0, minIntervalMs = 1500))
        val events = listOf(
            RouteEvent("a", RouteEventKind.TURN_LEFT, 10.0),
            RouteEvent("b", RouteEventKind.SPEED_BUMP, 20.0),
        )
        assertEquals(1, engine.onRouteEvents(events, moving(0)).size)
        // The second event waits until the interval has passed
        assertEquals(1, engine.onRouteEvents(events.drop(1), moving(1600)).size)
    }
}
