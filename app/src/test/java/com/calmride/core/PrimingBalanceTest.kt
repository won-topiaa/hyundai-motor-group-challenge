package com.calmride.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimingBalanceTest {

    @Test
    fun `one outer seat gives that side, anything else is centered`() {
        assertEquals(SeatSide.LEFT, PrimingBalance.side(left = true, center = false, right = false))
        assertEquals(SeatSide.RIGHT, PrimingBalance.side(left = false, center = false, right = true))
        assertEquals(SeatSide.CENTER, PrimingBalance.side(left = true, center = false, right = true))
        assertEquals(SeatSide.CENTER, PrimingBalance.side(left = false, center = true, right = false))
        assertEquals(SeatSide.CENTER, PrimingBalance.side(left = false, center = false, right = false))
    }

    @Test
    fun `far side is raised and the near side stays put`() {
        val (l, r) = PrimingBalance.gains(SeatSide.LEFT, 2f)
        assertEquals(1f, l, 1e-6f)
        assertEquals(1.259f, r, 1e-3f)
        val (l2, r2) = PrimingBalance.gains(SeatSide.RIGHT, 2f)
        assertEquals(r, l2, 1e-6f)
        assertEquals(1f, r2, 1e-6f)
        assertEquals(1f to 1f, PrimingBalance.gains(SeatSide.CENTER, 2f))
    }

    @Test
    fun `boost is capped`() {
        val (_, r) = PrimingBalance.gains(SeatSide.LEFT, 20f)
        assertEquals(PrimingBalance.gains(SeatSide.LEFT, PrimingBalance.MAX_BOOST_DB).second, r, 1e-6f)
    }
}
