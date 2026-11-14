package com.noisetrace.vehicle

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
import com.noisetrace.core.Gear

/**
 * AAOS standard Car API, which Pleos recommends for standard data: vehicle speed (the value the cluster shows),
 * gear, outside temperature, and the driving restrictions that decide what the screen may show while moving.
 * Motor rpm is not read: ENGINE_RPM needs CAR_ENGINE_DETAILED, which third-party apps do not get.
 *
 * registerCallback is deprecated from API 35 but is the only option down to minSdk 28.
 */
@Suppress("DEPRECATION")
class CarApiSource(
    private val context: Context,
    private val onSpeed: (Float) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onOutsideTemp: (Float) -> Unit,
    private val onRestriction: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val speedCallback = callback("speed") { v -> (v.value as? Float)?.let(onSpeed) }
    private val tempCallback = callback("outside temp") { v -> (v.value as? Float)?.let(onOutsideTemp) }
    private val gearCallback = callback("gear") { v ->
        onGear(
            when (v.value as? Int) {
                VehicleGear.GEAR_PARK -> Gear.PARK
                VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                else -> Gear.DRIVE
            },
        )
    }

    private fun callback(name: String, onValue: (CarPropertyValue<*>) -> Unit) = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) = onValue(value)
        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "$name error $propId")
        }
    }

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            onStatus("AAOS 아님: 차량 신호 없음")
            return
        }
        // Without a lifecycle listener, Android kills this app whenever the car service restarts.
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
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        propertyManager = pm
        // GEAR_SELECTION needs CAR_POWERTRAIN and the outside temperature CAR_EXTERIOR_ENVIRONMENT, both install-time.
        runCatching { pm?.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
            .onFailure { Log.w(TAG, "gear subscribe failed", it) }
        runCatching { pm?.registerCallback(tempCallback, VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
            .onFailure { Log.w(TAG, "outside temperature unavailable", it) }
        subscribeSpeed()
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
        runCatching { propertyManager?.unregisterCallback(tempCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "NoiseTraceCarApi"
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
        val RUNTIME_PERMISSIONS = arrayOf(PERMISSION_SPEED, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
    }
}
