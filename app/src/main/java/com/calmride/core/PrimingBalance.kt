package com.calmride.core

import kotlin.math.pow

/** Which side of the car the passenger sits on. */
enum class SeatSide { LEFT, CENTER, RIGHT }

/**
 * Left/right balance of the 100Hz tone. Gu et al. (2025) saw the effect when both ears got the same level
 * and little effect when the tone came from one side only. A passenger in an outer seat is closer to that
 * side's speakers, so the far side plays a little louder. The app can only shape its own stereo signal:
 * the car's balance and fader settings are system APIs that third-party apps cannot change.
 */
object PrimingBalance {
    /** Starting value until it is measured at both ears in the actual car. */
    const val DEFAULT_BOOST_DB = 2f
    const val MAX_BOOST_DB = 6f

    /** One side taken → that side (the front passenger sits on the right). Both sides, the middle or nobody → centered. */
    fun side(left: Boolean, center: Boolean, right: Boolean, front: Boolean = false): SeatSide {
        val rightSide = right || front
        return when {
            left && !center && !rightSide -> SeatSide.LEFT
            rightSide && !center && !left -> SeatSide.RIGHT
            else -> SeatSide.CENTER
        }
    }

    /** (left, right) gains: the near side stays at 1 and the far side is raised by [boostDb]. */
    fun gains(side: SeatSide, boostDb: Float): Pair<Float, Float> {
        val far = 10f.pow(boostDb.coerceIn(0f, MAX_BOOST_DB) / 20f)
        return when (side) {
            SeatSide.LEFT -> 1f to far
            SeatSide.RIGHT -> far to 1f
            SeatSide.CENTER -> 1f to 1f
        }
    }
}
