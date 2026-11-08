package com.calmride.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DayReportTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun ride(t: Long, stats: TripStats = TripStats(distanceM = 3000.0), comfort: Int? = null, calm: Boolean = false) =
        TripRecord(t, stats, comfort, calm)

    private fun profile(vararg trips: TripRecord) = DriverProfile("d", "기사", trips = trips.toList())

    @Test
    fun `a night shift past midnight is one working day`() {
        assertEquals(at(9, 4), DayReports.dayStart(at(10, 2), zone))
        assertEquals(at(10, 4), DayReports.dayStart(at(10, 4), zone))
    }

    @Test
    fun `today adds up only this working day's rides`() {
        val p = profile(
            ride(at(9, 12), TripStats(distanceM = 9000.0, hardDecelCount = 9)),
            ride(at(10, 9), TripStats(distanceM = 2000.0, hardDecelCount = 1, coastDecelCount = 2), comfort = 5, calm = true),
            ride(at(10, 10), TripStats(distanceM = 3000.0, hardStartCount = 1), comfort = 2, calm = true),
            ride(at(10, 11), TripStats(distanceM = 1000.0)),
        )
        val r = DayReports.build(p, at(10, 12), zone)
        assertEquals(3, r.rides)
        assertEquals(2, r.calmRides)
        assertEquals(6.0, r.distanceKm, 1e-9)
        assertEquals(1, r.hardDecels)
        assertEquals(2, r.coastDecels)
        assertEquals(1, r.hardStarts)
        assertEquals(3.5, r.avgComfort!!, 1e-9)
        assertEquals(2, r.comfortAnswers)
        assertNotNull(r.tip)
    }

    @Test
    fun `rough moments are compared per ride with the previous working day`() {
        val p = profile(
            ride(at(8, 12), TripStats(hardDecelCount = 4)),
            ride(at(8, 13), TripStats(hardDecelCount = 2)),
            ride(at(10, 9), TripStats(hardDecelCount = 1)),
        )
        assertEquals(-2.0, DayReports.build(p, at(10, 12), zone).roughPerRideChange!!, 1e-9)
    }

    @Test
    fun `a day without rides is empty and has no tip`() {
        val r = DayReports.build(profile(ride(at(9, 12))), at(10, 12), zone)
        assertEquals(0, r.rides)
        assertNull(r.tip)
    }
}
