package com.calmride.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import com.calmride.core.TurnSignal

/**
 * Pleos Vehicle SDK: brake pedal, turn signal, steering angle and rear-seat occupancy,
 * plus seat position (driver recognition) and HVAC control (fresh air and fan for motion-sickness mode).
 * Gear comes from the AAOS GEAR_SELECTION property instead, as the SDK's gear API is deprecated.
 */
class PleosVehicleSource(
    private val context: Context,
    private val onUpdate: (VehicleSignal) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    sealed interface VehicleSignal {
        data class Brake(val pressed: Boolean) : VehicleSignal
        data class Signal(val state: TurnSignal) : VehicleSignal
        data class Steering(val degrees: Float) : VehicleSignal
        data class RearSeat(val occupied: Boolean) : VehicleSignal
    }

    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var attempts = 0
    var connected = false
        private set

    private val rearSeats = listOf(SeatArea(SeatValue.ROW_2_LEFT), SeatArea(SeatValue.ROW_2_RIGHT))
    private val rearOccupied = mutableMapOf<SeatArea, Boolean>()
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) = onUpdate(VehicleSignal.Brake(p0 == true))
        override fun onFailed(p0: Exception) = log("brake", p0)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) = onUpdate(VehicleSignal.Signal(map(p0)))
        override fun onFailed(p0: Exception) = log("turn signal", p0)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) = onUpdate(VehicleSignal.Steering(p0 ?: 0f))
        override fun onFailed(p0: Exception) = log("steering", p0)
    }
    private val seatListeners = rearSeats.associateWith { area ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(p0: Boolean?) {
                rearOccupied[area] = p0 == true
                onUpdate(VehicleSignal.RearSeat(rearOccupied.values.any { it }))
            }
            override fun onFailed(p0: Exception) = log("rear seat", p0)
        }
    }

    fun start() {
        try {
            val v = Vehicle(context)
            v.initialize()
            vehicle = v
            // The service binds asynchronously, so register a moment later and retry on failure.
            handler.postDelayed(::register, 500)
        } catch (t: Throwable) {
            onStatus("Vehicle SDK 연결 실패: ${t.javaClass.simpleName}")
            Log.w(TAG, "Vehicle init failed", t)
        }
    }

    private fun register() {
        val v = vehicle ?: return
        try {
            v.getBrake().registerBrakePedalStatus(brakeListener)
            v.getTurnSignal().registerTurnSignalState(turnListener)
            v.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
            seatListeners.forEach { (area, listener) -> v.getSeat().registerSeatOccupancy(area, listener) }
            connected = true
            onStatus("Vehicle SDK 연결됨")
        } catch (t: Throwable) {
            attempts++
            Log.w(TAG, "register failed (attempt $attempts)", t)
            if (attempts < 5) handler.postDelayed(::register, 1000L * attempts) else onStatus("Vehicle SDK 사용 불가")
        }
    }

    /** Reads the driver seat position for driver recognition. */
    fun readDriverSeat(onResult: (foreAft: Int?, height: Int?) -> Unit) {
        val seat = vehicle?.getSeat() ?: return onResult(null, null)
        var foreAft: Int? = null
        val fail: (Exception) -> Unit = { e -> log("seat position", e); onResult(foreAft, null) }
        runCatching {
            seat.getCurrentSeatForeAftPosition(driverSeat, { pos ->
                foreAft = pos
                seat.getCurrentSeatHeightPosition(driverSeat, { h -> onResult(foreAft, h) }, fail)
            }, fail)
        }.onFailure { onResult(null, null) }
    }

    /** Remembers the current recirculation and fan state, then opens fresh air and raises the fan. */
    fun applyFreshAir(onDone: (RestoreState?) -> Unit) {
        val hvac = vehicle?.getHVAC() ?: return onDone(null)
        runCatching {
            hvac.checkHvacFanSpeedCapabilities({ _, bounds: Map<SeatArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> ->
                val entry = bounds.entries.firstOrNull() ?: return@checkHvacFanSpeedCapabilities onDone(null)
                val area = entry.key
                val max = entry.value.maxValue ?: return@checkHvacFanSpeedCapabilities onDone(null)
                val min = entry.value.minValue ?: 0
                hvac.getHvacRecirculationState(area, { recirc ->
                    hvac.getHvacFanSpeed(area, { fan ->
                        val target = maxOf(fan ?: min, min + ((max - min) * 0.6).toInt())
                        hvac.setHvacRecirculation(area, false, { _, _ -> }, { e -> log("recirculation", e) })
                        hvac.setHvacFanSpeed(area, target, { _, _ -> }, { e -> log("fan speed", e) })
                        onDone(RestoreState(area, recirc, fan))
                    }, { e -> log("fan get", e); onDone(null) })
                }, { e -> log("recirculation get", e); onDone(null) })
            }, { e -> log("fan capability", e); onDone(null) })
        }.onFailure { onDone(null) }
    }

    fun restoreAir(state: RestoreState) {
        val hvac = vehicle?.getHVAC() ?: return
        runCatching {
            state.recirculation?.let { hvac.setHvacRecirculation(state.area, it, { _, _ -> }, { e -> log("restore recirculation", e) }) }
            state.fanSpeed?.let { hvac.setHvacFanSpeed(state.area, it, { _, _ -> }, { e -> log("restore fan", e) }) }
        }
    }

    data class RestoreState(val area: SeatArea, val recirculation: Boolean?, val fanSpeed: Int?)

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val v = vehicle ?: return
        runCatching {
            v.getBrake().unregisterBrakePedalStatus(brakeListener)
            v.getTurnSignal().unregisterTurnSignalState(turnListener)
            v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
            seatListeners.forEach { (area, listener) -> v.getSeat().unregisterSeatOccupancy(area, listener) }
        }
        runCatching { v.release() }
        vehicle = null
        connected = false
    }

    private fun log(what: String, e: Exception) {
        Log.w(TAG, "$what failed: ${e.message}")
    }

    private fun map(value: TurnSignalValue?): TurnSignal = when (value) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        else -> TurnSignal.OFF
    }

    private companion object {
        const val TAG = "CalmRideVehicle"
    }
}
