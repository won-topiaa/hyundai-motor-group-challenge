package com.noisetrace.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import com.noisetrace.core.TurnSignal

/**
 * Pleos Vehicle SDK, read only: brake pedal, turn signal, steering angle (driving state), front wiper, air
 * conditioning and side windows (car functions that make or change cabin noise), and the odometer.
 * Speed and gear come from AAOS instead ([CarApiSource]), as Pleos recommends for standard data.
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
        data class Wiper(val on: Boolean) : VehicleSignal
        data class Ac(val on: Boolean) : VehicleSignal
        data class WindowOpen(val open: Boolean) : VehicleSignal
        data class Odometer(val km: Float) : VehicleSignal
    }

    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var attempts = 0
    private val frontWiper = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private val sideWindows = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).map { WindowArea(it) }
    private val windowPositions = mutableMapOf<WindowArea, Int>()
    private var acArea: SeatArea? = null

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) = onUpdate(VehicleSignal.Brake(p0 == true))
        override fun onFailed(p0: Exception) = log("brake", p0)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) = onUpdate(VehicleSignal.Signal(map(p0)))
        override fun onFailed(p0: Exception) = log("turn signal", p0)
    }
    /**
     * The emulator reports turning the wheel left as positive (Virtual Console -90° arrives as +90), the ISO 8855
     * direction and the opposite of the AAOS PERF_STEERING_ANGLE description. The app uses negative for left, so the
     * value is flipped here, in one place. To be checked on a real car.
     */
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) {
            p0?.let { onUpdate(VehicleSignal.Steering(if (SDK_LEFT_POSITIVE) -it else it)) }
        }
        override fun onFailed(p0: Exception) = log("steering", p0)
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(p0: WiperState?) = onUpdate(VehicleSignal.Wiper(p0 == WiperState.ON))
        override fun onFailed(p0: Exception) = log("wiper", p0)
    }
    private val acListener = object : HvacAcStateListener {
        override fun onHvacAcStateUpdated(p0: Boolean?) {
            p0?.let { onUpdate(VehicleSignal.Ac(it)) }
        }
        override fun onFailed(p0: Exception) = log("ac", p0)
    }
    private val windowListeners = sideWindows.associateWith { area ->
        object : WindowPositionListener {
            override fun onWindowPositionUpdated(p0: Int?) = onWindow(area, p0)
            override fun onFailed(p0: Exception) = log("window", p0)
        }
    }
    private val odometerListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(p0: Float?) {
            p0?.let { onUpdate(VehicleSignal.Odometer(it)) }
        }
        override fun onFailed(p0: Exception) = log("odometer", p0)
    }

    /** AAOS WINDOW_POS: 0 is closed, anything above is open by that much. */
    private fun onWindow(area: WindowArea, position: Int?) {
        windowPositions[area] = position ?: return
        onUpdate(VehicleSignal.WindowOpen(windowPositions.values.any { it > 0 }))
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
            onStatus("Vehicle SDK 연결됨")
        } catch (t: Throwable) {
            attempts++
            Log.w(TAG, "register failed (attempt $attempts)", t)
            if (attempts < 5) handler.postDelayed(::register, 1000L * attempts) else onStatus("Vehicle SDK 사용 불가")
            return
        }
        // Optional signals: a car without one of them still records the rest.
        runCatching {
            v.getWiper().registerWiperState(frontWiper, wiperListener)
            v.getWiper().getCurrentWiperState(frontWiper, { onUpdate(VehicleSignal.Wiper(it == WiperState.ON)) }, { log("wiper now", it) })
        }.onFailure { Log.w(TAG, "wiper unavailable", it) }
        runCatching {
            v.getHVAC().checkHvacAcCapabilities({ areas: List<SeatArea> ->
                val area = areas.firstOrNull() ?: return@checkHvacAcCapabilities
                acArea = area
                v.getHVAC().registerHvacAcState(area, acListener)
                v.getHVAC().getHvacAcState(area, { it?.let { on -> onUpdate(VehicleSignal.Ac(on)) } }, { log("ac now", it) })
            }, { log("ac capability", it) })
        }.onFailure { Log.w(TAG, "ac unavailable", it) }
        windowListeners.forEach { (area, listener) ->
            runCatching { v.getWindow().registerWindowPosition(area, listener) }
        }
        runCatching {
            v.getOdometer().registerDistanceDriven(odometerListener)
            v.getOdometer().getCurrentDistanceDriven({ it?.let { km -> onUpdate(VehicleSignal.Odometer(km)) } }, { log("odometer now", it) })
        }.onFailure { Log.w(TAG, "odometer unavailable", it) }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val v = vehicle ?: return
        runCatching { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
        runCatching { v.getTurnSignal().unregisterTurnSignalState(turnListener) }
        runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
        runCatching { v.getWiper().unregisterWiperState(frontWiper, wiperListener) }
        runCatching { acArea?.let { v.getHVAC().unregisterHvacAcState(it, acListener) } }
        windowListeners.forEach { (area, listener) -> runCatching { v.getWindow().unregisterWindowPosition(area, listener) } }
        runCatching { v.getOdometer().unregisterDistanceDriven(odometerListener) }
        runCatching { v.release() }
        vehicle = null
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
        const val TAG = "NoiseTraceVehicle"
        const val SDK_LEFT_POSITIVE = true
    }
}
