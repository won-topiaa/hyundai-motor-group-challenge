package com.noisetrace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MonthlyReportTest {
    private val zone = TimeZone.getTimeZone("Asia/Seoul")
    private val now = 1_794_600_000_000L // 2026-11-14 (KST)
    private val day = 86_400_000L

    @Test
    fun sampleTrendShowsHighSpeedHumRising() {
        val r = MonthlyReports.build(emptyList(), DrivingExposure(), MonthlyReports.sampleHistory(now, zone), now, zone)
        assertTrue(r.hasTrend)
        val top = r.notable.first()
        assertEquals(Channel.FULL, top.channel)
        assertTrue("band ${top.band}", top.band >= 4)
        assertEquals(3.2, top.deltaDb.toDouble(), 0.6)
        assertTrue("타이어" in top.hint)
        // Nothing else moved.
        assertTrue(r.notable.all { it.channel == Channel.FULL && it.band >= 4 })
    }

    @Test
    fun tooFewDaysGiveNoTrend() {
        val short = MonthlyReports.sampleHistory(now, zone).takeLast(20)
        assertFalse(MonthlyReports.build(emptyList(), DrivingExposure(), short, now, zone).hasTrend)
    }

    @Test
    fun countsTheLastThirtyDaysAndDaysSinceTheRepeatedNoise() {
        fun ev(n: Int, daysAgo: Int, trigger: TriggerKind) = NoiseEvent(
            "e$n", n, now - daysAgo * day, now - daysAgo * day, trigger, userType = NoiseType.RATTLE,
            context = DrivingContext(speedKmh = 14f, steeringDeg = -20f, slopePct = -7f),
        )
        val events = listOf(ev(1, 40, TriggerKind.MANUAL), ev(2, 20, TriggerKind.AUTO), ev(3, 9, TriggerKind.MANUAL))
        val r = MonthlyReports.build(events, DrivingExposure(), emptyList(), now, zone)
        assertEquals(2, r.events)
        assertEquals(1, r.autoEvents)
        assertEquals(NoiseType.RATTLE, r.topPattern?.type)
        assertEquals(9, r.daysSinceTop)
    }

    @Test
    fun mergeKeepsKnownBandsAndDropsOldDays() {
        val today = MonthlyReports.dayKey(now, zone)
        val morning = DayLevels(today, listOf(-47f, null, null, null, null, null), List(6) { null })
        val evening = DayLevels(today, listOf(null, -44f, null, null, null, null), List(6) { null })
        val old = DayLevels(MonthlyReports.dayKey(now - 200 * day, zone), List(6) { -40f }, List(6) { -50f })
        val merged = MonthlyReports.merge(MonthlyReports.merge(listOf(old), morning, now, zone), evening, now, zone)
        assertEquals(1, merged.size)
        assertEquals(-47f, merged[0].full[0]!!, 0.01f)
        assertEquals(-44f, merged[0].full[1]!!, 0.01f)
        assertNull(merged[0].full[2])
    }

    @Test
    fun todayTakesOnlyBandsTheCarDroveItself() {
        val b = Baseline()
        repeat(200) { b.add(Channel.FULL, b.band(30f), -41f); b.add(Channel.HIGH, b.band(30f), -52f) }
        val d = MonthlyReports.today(b, now, zone)!!
        assertEquals(-40.5f, d.full[b.band(30f)]!!, 0.01f)
        assertNull("no borrowed value for an undriven band", d.full[b.band(80f)])
    }
}
