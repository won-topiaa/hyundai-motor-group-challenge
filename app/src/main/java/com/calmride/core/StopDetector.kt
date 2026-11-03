package com.calmride.core

/**
 * Tells whether the car is safely stopped, which gates the 1-minute 100Hz sound.
 * Returns null when the vehicle gives no gear or speed at all.
 */
class StopDetector(
    private val holdMs: Long = 2000,
    private val stopSpeedMps: Float = 0.5f,
) {
    private var belowSinceMs: Long? = null

    fun update(vehicle: VehicleSnapshot): Boolean? {
        if (vehicle.gear == Gear.PARK) return true
        val speed = vehicle.speedMps
        if (speed == null) {
            belowSinceMs = null
            return if (vehicle.gear == Gear.UNKNOWN) null else false
        }
        if (speed > stopSpeedMps) {
            belowSinceMs = null
            return false
        }
        val since = belowSinceMs ?: vehicle.timestampMs.also { belowSinceMs = it }
        return vehicle.timestampMs - since >= holdMs
    }
}
