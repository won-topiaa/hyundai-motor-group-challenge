package com.noisetrace.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.noisetrace.core.HazardAhead
import com.noisetrace.core.RoadHazard

/**
 * Pleos NaviHelper SDK: the road around the car. Caution points ahead (speed bumps, damaged road, level crossings,
 * downhill stretches) tell whether the car was going over something when the noise came; the speed limit tells the
 * road type; the current address names the place in the report.
 */
class NaviSource(
    context: Context,
    private val onHazards: (List<HazardAhead>) -> Unit,
    private val onSpeedLimit: (Int) -> Unit,
    private val onAddress: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val helper = NaviHelper(context)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val listener = object : NaviHelperEventListener {
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            onHazards(cautionPointList.mapNotNull { p -> map(p.type)?.let { HazardAhead("caution:${p.id}", it, p.remainDistance.toDouble()) } })
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = onSpeedLimit(speedLimit)

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            currentLocationInfo.address.takeIf { it.isNotBlank() }?.let(onAddress)
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            val guiding = navigationStatus == NavigationStatus.ROUTE_DRIVE
            if (!guiding) onHazards(emptyList())
            onStatus(if (guiding) "내비 경로 안내 중 (방지턱·노면 정보 수신)" else "내비 연결됨 (경로 안내 없음)")
        }
    }

    /** The address only changes as the car moves, so asking every few seconds is plenty. */
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { helper.getCurrentLocationInfo() }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        running = true
        try {
            helper.initialize({
                helper.addListener(listener)
                onStatus("내비 연결됨")
                handler.post(poll)
            }, { t ->
                Log.w(TAG, "NaviHelper init failed", t)
                onStatus("내비 연결 실패")
            })
        } catch (t: Throwable) {
            Log.w(TAG, "NaviHelper unavailable", t)
            onStatus("내비 사용 불가")
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
    }

    private fun map(type: CautionPointType): RoadHazard? = when (type) {
        CautionPointType.SPEED_BUMP -> RoadHazard.SPEED_BUMP
        CautionPointType.ROAD_DAMAGE_ALERT -> RoadHazard.ROAD_DAMAGE
        CautionPointType.RAILROAD_CROSSING -> RoadHazard.RAILROAD
        CautionPointType.DOWNHILL_CAUTION_AREA -> RoadHazard.DOWNHILL
        CautionPointType.NARROW_ROAD -> RoadHazard.NARROW_ROAD
        else -> null
    }

    private companion object {
        const val TAG = "NoiseTraceNavi"
        const val POLL_MS = 5_000L
    }
}
