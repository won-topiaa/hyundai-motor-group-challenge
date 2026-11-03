package com.calmride.vehicle

import android.Manifest
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.calmride.core.Gear
import com.calmride.core.RegenState

/**
 * AAOS standard Car API. Pleos recommends it for standard data such as vehicle speed and gear:
 * the cluster shows PERF_VEHICLE_SPEED, so the app reads the same value.
 * Regenerative braking can be read here but not changed (CONTROL_CAR_POWERTRAIN is system-only).
 *
 * registerCallback is deprecated from API 35 but is the only option down to minSdk 28.
 */
@Suppress("DEPRECATION")
class CarApiSource(
    private val context: Context,
    private val onSpeed: (Float) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onRegen: (RegenState, Int?) -> Unit,
    private val onRestriction: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let(onSpeed)
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "speed error $propId")
        }
    }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            onGear(
                when (value.value as? Int) {
                    VehicleGear.GEAR_PARK -> Gear.PARK
                    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                    else -> Gear.DRIVE
                },
            )
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "gear error $propId")
        }
    }

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onStatus("AAOS 아님: 차량 신호 없음")
            return
        }
        // Without a lifecycle listener, Android kills this app whenever the car service restarts.
        // With one, the listener gets ready=false on a crash and ready=true again after the restart.
        car = try {
            Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (ready) onCarReady(c) else onCarLost()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Car.createCar failed", t)
            onStatus("AAOS Car API 연결 실패")
            null
        }
    }

    private fun onCarReady(c: Car) {
        car = c
        propertyManager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        // GEAR_SELECTION needs CAR_POWERTRAIN, a normal (install-time) permission.
        runCatching {
            propertyManager?.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "gear subscribe failed", it) }
        subscribeSpeed()
        readRegen()
        watchRestrictions(c)
    }

    private fun onCarLost() {
        Log.w(TAG, "car service disconnected; waiting for it to come back")
        propertyManager = null
        uxManager = null
        onStatus("차량 서비스 재연결 중")
    }

    /** Call again after the runtime permission (CAR_SPEED) is granted. */
    fun subscribeSpeed() {
        val pm = propertyManager ?: return
        if (!granted(PERMISSION_SPEED)) return onStatus("차속 권한 필요")
        try {
            pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
            onStatus("AAOS 차속 연결됨")
        } catch (t: Throwable) {
            Log.w(TAG, "speed subscribe failed", t)
            onStatus("차속 신호 없음")
        }
    }

    fun readRegen() {
        val pm = propertyManager ?: return
        val state = if (granted(PERMISSION_ENERGY)) {
            runCatching { pm.getIntProperty(VehiclePropertyIds.EV_REGENERATIVE_BRAKING_STATE, 0) }.getOrNull()
        } else null
        val level = runCatching { pm.getIntProperty(VehiclePropertyIds.EV_BRAKE_REGENERATION_LEVEL, 0) }.getOrNull()
        onRegen(
            when (state) {
                1 -> RegenState.DISABLED
                2 -> RegenState.PARTIAL
                3 -> RegenState.FULL
                else -> RegenState.UNKNOWN
            },
            level,
        )
    }

    private fun watchRestrictions(c: Car) {
        val ux = runCatching { c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager }.getOrNull() ?: return
        uxManager = ux
        val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { r: CarUxRestrictions ->
            onRestriction(r.isRequiresDistractionOptimization)
        }
        runCatching {
            ux.registerListener(listener)
            onRestriction(ux.currentCarUxRestrictions.isRequiresDistractionOptimization)
        }
    }

    fun stop() {
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CalmRideCarApi"
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
        const val PERMISSION_ENERGY = "android.car.permission.CAR_ENERGY"
        val RUNTIME_PERMISSIONS = arrayOf(PERMISSION_SPEED, PERMISSION_ENERGY, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
