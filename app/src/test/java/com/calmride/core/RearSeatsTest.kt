package com.calmride.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearSeatsTest {

    private val none = emptyMap<SeatSide, Boolean>()

    @Test
    fun `sdk false is a taken seat once another seat reported vacant`() {
        // SDK true = raw VACANT; a passenger already seated before the app started never reports vacant.
        val occupancy = mapOf(SeatSide.LEFT to false, SeatSide.CENTER to true, SeatSide.RIGHT to true)
        assertTrue(RearSeats.taken(occupancy, none, SeatSide.LEFT))
        assertFalse(RearSeats.taken(occupancy, none, SeatSide.RIGHT))
    }

    @Test
    fun `all false means no working sensor, not a full back row`() {
        val occupancy = mapOf(SeatSide.LEFT to false, SeatSide.CENTER to false, SeatSide.RIGHT to false)
        assertFalse(RearSeats.taken(occupancy, none, SeatSide.LEFT))
    }

    @Test
    fun `a buckled belt counts without an occupancy sensor`() {
        assertTrue(RearSeats.taken(none, mapOf(SeatSide.RIGHT to true), SeatSide.RIGHT))
        assertFalse(RearSeats.taken(none, mapOf(SeatSide.RIGHT to false), SeatSide.RIGHT))
    }
}
