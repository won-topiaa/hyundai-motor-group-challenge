package com.calmride.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import com.calmride.core.RouteEvent
import com.calmride.core.RouteEventKind

/**
 * Pleos NaviHelper SDK: the next turn (TBTInfo.distance) and caution points ahead
 * (CautionPoint.remainDistance) while route guidance runs. TBT is also polled once a second
 * in case the navigation app only answers getTBTInfo().
 */
class NaviSource(
    context: Context,
    private val onRoute: (List<RouteEvent>) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val helper = NaviHelper(context)
    private val handler = Handler(Looper.getMainLooper())
    private var tbt: List<RouteEvent> = emptyList()
    private var caution: List<RouteEvent> = emptyList()
    private var camera: List<RouteEvent> = emptyList()
    private var guiding = false
    private var running = false

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            // Only the first entry's distance is from the car; each later one is measured from the
            // maneuver before it (seen on the emulator: turn 82 m, turn 208 m, arrive 58 m), so add them up.
            var fromCar = 0.0
            tbt = tbtInfoList.mapIndexedNotNull { i, info ->
                fromCar += info.distance
                val kind = map(info.type) ?: return@mapIndexedNotNull null
                RouteEvent("tbt:$i:${info.type.name}:${info.description}", kind, fromCar)
            }
            emit()
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            caution = cautionPointList.mapNotNull { p ->
                val kind = map(p.type) ?: return@mapNotNull null
                RouteEvent("caution:${p.id}", kind, p.remainDistance.toDouble())
            }
            emit()
        }

        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            // Drivers often brake hard right before speed cameras.
            camera = cameraAlertList.filter { it.status }.map { RouteEvent("camera:${it.id}", RouteEventKind.SLOW_ZONE, it.remainDistance.toDouble()) }
            emit()
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            guiding = navigationStatus == NavigationStatus.ROUTE_DRIVE
            if (!guiding) {
                tbt = emptyList()
                emit()
            }
            onStatus(if (guiding) "내비 경로 안내 중" else "내비 연결됨 (경로 안내 없음)")
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { helper.getTBTInfo() }
            handler.postDelayed(this, 1000)
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

    private fun emit() = onRoute(tbt + caution + camera)

    private fun map(type: TurnType): RouteEventKind? = when (type) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> RouteEventKind.TURN_LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> RouteEventKind.TURN_RIGHT
        TurnType.TURN_UTURN -> RouteEventKind.U_TURN
        TurnType.TURN_KEEP_L, TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L,
        TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_AVOID_UNDER_L, TurnType.TURN_ENTER_OVER_L, TurnType.TURN_AVOID_OVER_L -> RouteEventKind.KEEP_LEFT
        TurnType.TURN_KEEP_R, TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R,
        TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_AVOID_UNDER_R, TurnType.TURN_ENTER_OVER_R, TurnType.TURN_AVOID_OVER_R -> RouteEventKind.KEEP_RIGHT
        TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
        TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
        TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12 -> RouteEventKind.TURN_RIGHT
        TurnType.TURN_ARRIVE, TurnType.TURN_TOLL -> RouteEventKind.ARRIVE
        else -> null
    }

    private fun map(type: CautionPointType): RouteEventKind? = when (type) {
        CautionPointType.SPEED_BUMP, CautionPointType.RAILROAD_CROSSING -> RouteEventKind.SPEED_BUMP
        CautionPointType.DOWNHILL_CAUTION_AREA -> RouteEventKind.DOWNHILL
        CautionPointType.SCHOOL_ZONE -> RouteEventKind.SLOW_ZONE
        else -> null
    }

    private companion object {
        const val TAG = "CalmRideNavi"
    }
}
