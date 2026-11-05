package com.calmride.vehicle

import com.calmride.core.DriverProfile
import com.calmride.core.DrivingStyle
import com.calmride.core.Gear
import com.calmride.core.ProfileTuner
import com.calmride.core.TripRecord
import com.calmride.core.TripRecorder
import com.calmride.core.TripStats
import com.calmride.core.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoScriptTest {

    private fun record(style: DemoStyle): TripStats {
        val r = TripRecorder(0)
        DemoScript.steps(style).forEachIndexed { i, step ->
            val t = i * DemoScript.STEP_MS
            r.onVehicle(
                VehicleSnapshot(
                    timestampMs = t,
                    speedMps = step.speedMps,
                    gear = Gear.DRIVE,
                    brakePressed = step.brake,
                    turnSignal = step.signal,
                    steeringDeg = step.steeringDeg,
                ),
            )
            r.onRouteEvents(step.route)
        }
        return r.finish()
    }

    /** The style a new driver gets after one demo drive. */
    private fun styleAfterOneDrive(style: DemoStyle): DrivingStyle =
        ProfileTuner.afterTrip(DriverProfile("d", "운전자"), TripRecord(1, record(style), comfortScore = 3)).style

    @Test
    fun `gentle demo stays smooth even after many drives`() {
        val stats = record(DemoStyle.GENTLE)
        println("gentle: ${stats.durationSec} s")
        assertEquals(0, stats.hardStartCount)
        assertEquals(0, stats.sharpTurnCount)
        assertEquals(0, stats.fastBumpCount)
        assertEquals(0, stats.quickLaneChanges)
        assertEquals(1, stats.laneChanges)
        assertEquals(DrivingStyle.SMOOTH, styleAfterOneDrive(DemoStyle.GENTLE))
        var p = DriverProfile("d", "운전자")
        repeat(5) { p = ProfileTuner.afterTrip(p, TripRecord(it.toLong(), stats, comfortScore = 4)) }
        assertEquals(DrivingStyle.SMOOTH, p.style)
    }

    @Test
    fun `each rough demo is recognized as its own style after one drive`() {
        assertEquals(DrivingStyle.HARD_STARTER, styleAfterOneDrive(DemoStyle.HARD_START))
        assertEquals(DrivingStyle.SHARP_TURNER, styleAfterOneDrive(DemoStyle.SHARP_TURN))
        assertEquals(DrivingStyle.BUMP_RUSHER, styleAfterOneDrive(DemoStyle.BUMP_RUSH))
    }

    @Test
    fun `rough demos stop at both lights and cover every turn and bump`() {
        DemoStyle.entries.filter { it != DemoStyle.GENTLE }.forEach { style ->
            val stats = record(style)
            println("$style: ${stats.durationSec} s, starts ${stats.departures}, turns ${stats.turns}, bumps ${stats.bumpPasses}")
            assertEquals(3, stats.departures)
            assertEquals(3, stats.turns)
            assertEquals(2, stats.bumpPasses)
            assertEquals(0, stats.lateBrakeCount)
            assertTrue(stats.durationSec in 60.0..120.0)
        }
    }
}
