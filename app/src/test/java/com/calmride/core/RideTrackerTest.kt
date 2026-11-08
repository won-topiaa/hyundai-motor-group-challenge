package com.calmride.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackerTest {

    @Test
    fun `a ride runs from boarding until the seats stay empty`() {
        val t = RideTracker(alightHoldMs = 3000)
        assertNull(t.update(occupied = false, stopped = true, nowMs = 0))
        assertEquals(RideEvent.BOARDED, t.update(occupied = true, stopped = true, nowMs = 1000))
        assertNull(t.update(occupied = true, stopped = false, nowMs = 2000))
        assertTrue(t.inRide)
        assertNull(t.update(occupied = false, stopped = true, nowMs = 10_000))
        assertNull(t.update(occupied = false, stopped = true, nowMs = 12_000))
        assertEquals(RideEvent.ALIGHTED, t.update(occupied = false, stopped = true, nowMs = 13_000))
        assertFalse(t.inRide)
    }

    @Test
    fun `alighting does not depend on the gear, so the next passenger starts a new ride`() {
        val t = RideTracker(alightHoldMs = 1000)
        t.update(occupied = true, stopped = true, nowMs = 0)
        t.update(occupied = false, stopped = true, nowMs = 100)
        assertEquals(RideEvent.ALIGHTED, t.update(occupied = false, stopped = true, nowMs = 1100))
        assertEquals(RideEvent.BOARDED, t.update(occupied = true, stopped = true, nowMs = 5000))
    }

    @Test
    fun `an empty seat while moving is ignored`() {
        val t = RideTracker(alightHoldMs = 1000)
        t.update(occupied = true, stopped = true, nowMs = 0)
        assertNull(t.update(occupied = false, stopped = false, nowMs = 100))
        assertNull(t.update(occupied = false, stopped = false, nowMs = 5000))
        assertTrue(t.inRide)
    }

    @Test
    fun `a flickering seat sensor does not split the ride`() {
        val t = RideTracker(alightHoldMs = 3000)
        t.update(occupied = true, stopped = true, nowMs = 0)
        assertNull(t.update(occupied = false, stopped = true, nowMs = 1000))
        assertNull(t.update(occupied = true, stopped = true, nowMs = 2000))
        assertNull(t.update(occupied = false, stopped = true, nowMs = 2500))
        assertNull(t.update(occupied = false, stopped = true, nowMs = 5000))
        assertTrue(t.inRide)
    }
}
