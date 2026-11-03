package com.calmride.vehicle

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/** Pleos FusedLocation SDK: tags where hard decelerations happen so the report can show the spots. */
class LocationSource(
    context: Context,
    private val onLocation: (Double, Double) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val fused = FusedLocation(context)
    private var started = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            onLocation(location.latitude, location.longitude)
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    fun start() {
        try {
            if (!fused.isSupported()) return onStatus("위치 SDK 미지원")
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
            started = true
            onStatus("위치 연결됨")
        } catch (t: Throwable) {
            Log.w(TAG, "FusedLocation unavailable", t)
            onStatus("위치 사용 불가")
        }
    }

    fun stop() {
        if (!started) return
        runCatching { fused.unregisterFusedLocationCallback(listener) }
        runCatching { fused.release() }
        started = false
    }

    private companion object {
        const val TAG = "CalmRideLocation"
    }
}
