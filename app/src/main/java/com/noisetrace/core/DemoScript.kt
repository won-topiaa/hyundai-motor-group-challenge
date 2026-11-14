package com.noisetrace.core

/** One 100 ms step of the demo drive. */
data class DemoStep(
    val atSec: Double,
    val speedMps: Float,
    val gear: Gear,
    val steeringDeg: Float,
    val brake: Boolean,
    val turnSignal: TurnSignal,
    val altitudeM: Double,
    val gpsFix: Boolean,
    val address: String,
    val speedLimitKmh: Int,
    /** A noise starts in the cabin at this step. */
    val sound: NoiseType? = null,
    /** Someone presses "방금 소리 저장" at this step. */
    val pressSave: Boolean = false,
)

/**
 * A rainy evening drive home, for demos with no car: out of a car park, along a road, then down a steep left-curving
 * lane in a hillside neighbourhood where something at the front rattles twice. It stays above ground so GPS keeps
 * giving the slope. The first rattle is caught automatically; the
 * second is saved with the button a couple of seconds after it was heard.
 */
object DemoScript {
    const val STEP_MS = 100L
    private const val DT = STEP_MS / 1000.0
    const val RAMP_SLOPE = -0.08
    const val FIRST_RATTLE_SEC = 44.0
    const val SECOND_RATTLE_SEC = 57.0
    const val PRESS_SEC = 59.5
    const val PARK_SEC = 66.0
    const val END_SEC = 72.0

    /** Speed target in km/h at time t (s), with the rate the car may change toward it. */
    private fun target(t: Double): Pair<Double, Double> = when {
        t < 4 -> 0.0 to 2.0
        t < 16 -> 12.0 to 1.0
        t < 32 -> 45.0 to 2.0
        t < 37 -> 16.5 to 1.7
        t < 43 -> 16.5 to 0.6
        t < 44.8 -> 13.5 to 0.6 // easing off on the ramp: -0.6 m/s²
        t < 55.8 -> 14.5 to 0.3
        t < 58.2 -> 10.0 to 0.55 // easing off again around the second rattle
        t < 61 -> 10.0 to 0.5
        t < PARK_SEC - 1 -> 0.0 to 1.2
        else -> 0.0 to 2.0
    }

    fun steps(): List<DemoStep> {
        val out = mutableListOf<DemoStep>()
        var speed = 0.0
        var altitude = 32.0
        var t = 0.0
        while (t < END_SEC) {
            val (targetKmh, rate) = target(t)
            val goal = targetKmh / 3.6
            speed = if (speed < goal) minOf(goal, speed + rate * DT) else maxOf(goal, speed - rate * DT)
            val onRamp = t in 37.0..61.0
            if (onRamp) altitude += RAMP_SLOPE * speed * DT
            val steering = when {
                t < 39.5 || t > 61 -> 0f
                t < 41 -> (-18 * (t - 39.5) / 1.5).toFloat()
                t < 50 -> -18f
                t < 52 -> (-18 - 7 * (t - 50) / 2).toFloat()
                else -> -25f
            }
            val gear = when {
                t < 2 || t >= PARK_SEC -> Gear.PARK
                else -> Gear.DRIVE
            }
            val address = when {
                t < 16 -> "회사 주차장 출구"
                t < 35 -> "일반 도로"
                t < 61 -> "언덕길 주택가 골목"
                else -> "집 앞 골목"
            }
            out += DemoStep(
                atSec = t,
                speedMps = speed.toFloat(),
                gear = gear,
                steeringDeg = steering,
                brake = t in 33.0..36.0 || t in 61.5..PARK_SEC,
                turnSignal = if (t in 33.0..39.5) TurnSignal.LEFT else TurnSignal.OFF,
                altitudeM = altitude,
                // Above ground the whole way, so GPS altitude keeps giving the slope.
                gpsFix = true,
                address = address,
                speedLimitKmh = when {
                    t < 16 -> 10
                    t < 35 -> 50
                    else -> 30
                },
                sound = when {
                    near(t, FIRST_RATTLE_SEC) || near(t, SECOND_RATTLE_SEC) -> NoiseType.RATTLE
                    else -> null
                },
                pressSave = near(t, PRESS_SEC),
            )
            t += DT
        }
        return out
    }

    private fun near(t: Double, at: Double) = t >= at - DT / 2 && t < at + DT / 2

    const val WIPER_ON = true
    const val AC_ON = false
    const val OUTSIDE_TEMP_C = 8f
    const val ODOMETER_KM = 23_418f
    const val LATITUDE = 37.5013
    const val LONGITUDE = 127.0396
}
