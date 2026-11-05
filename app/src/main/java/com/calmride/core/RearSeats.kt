package com.calmride.core

/**
 * Rear seat occupancy from the Pleos Vehicle SDK. SDK 2.3.2 reports true only for a raw SEAT_OCCUPANCY of 1,
 * which AAOS defines as VACANT (0 UNKNOWN, 1 VACANT, 2 OCCUPIED); the emulator's Virtual Console sends 1 for an
 * empty seat and 2 for a taken one. So false means taken, or UNKNOWN on a car without rear sensors: it counts as
 * taken only once some rear seat has reported vacant, which shows the sensors work. A buckled belt always counts.
 */
object RearSeats {
    fun taken(sdkOccupancy: Map<SeatSide, Boolean>, buckled: Map<SeatSide, Boolean>, seat: SeatSide): Boolean {
        if (buckled[seat] == true) return true
        val sensorsWork = sdkOccupancy.values.any { it }
        return sensorsWork && sdkOccupancy[seat] == false
    }
}
