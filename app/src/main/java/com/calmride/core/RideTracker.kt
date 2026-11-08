package com.calmride.core

enum class RideEvent { BOARDED, ALIGHTED }

/**
 * A taxi ride runs from a passenger taking a seat until every seat is empty again. The gear says nothing here,
 * since passengers often get in and out while the car stays in Drive. A seat that reads empty while the car moves
 * is ignored (an early unbuckle on a car that only reports belts), and alighting needs a short hold so a
 * flickering sensor does not split one ride in two.
 */
class RideTracker(private val alightHoldMs: Long = ALIGHT_HOLD_MS) {

    var inRide = false
        private set
    private var emptySinceMs: Long? = null

    fun update(occupied: Boolean, stopped: Boolean?, nowMs: Long): RideEvent? {
        if (occupied) {
            emptySinceMs = null
            if (inRide) return null
            inRide = true
            return RideEvent.BOARDED
        }
        if (!inRide) return null
        if (stopped == false) {
            emptySinceMs = null
            return null
        }
        val since = emptySinceMs ?: nowMs.also { emptySinceMs = it }
        if (nowMs - since < alightHoldMs) return null
        inRide = false
        emptySinceMs = null
        return RideEvent.ALIGHTED
    }

    companion object {
        const val ALIGHT_HOLD_MS = 3000L
    }
}
