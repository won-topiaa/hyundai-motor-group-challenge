package com.calmride.vehicle

import com.calmride.core.Gear
import com.calmride.core.RouteEvent
import com.calmride.core.TurnSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays a [DemoScript] drive for demos when no car or Virtual Console scenario is available. */
class SimulatedDrive(
    private val scope: CoroutineScope,
    private val onSpeed: (Float) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onBrake: (Boolean) -> Unit,
    private val onSignal: (TurnSignal) -> Unit,
    private val onSteering: (Float) -> Unit,
    private val onRoute: (List<RouteEvent>) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(style: DemoStyle = DemoStyle.GENTLE) {
        if (running) return
        job = scope.launch {
            onGear(Gear.PARK); onSpeed(0f); delay(1500)
            onGear(Gear.DRIVE); delay(800)

            var signal = TurnSignal.OFF
            for (step in DemoScript.steps(style)) {
                if (!isActive) break
                onSpeed(step.speedMps)
                onBrake(step.brake)
                if (step.signal != signal) {
                    signal = step.signal
                    onSignal(signal)
                }
                onSteering(step.steeringDeg)
                onRoute(step.route)
                delay(DemoScript.STEP_MS)
            }
            onBrake(false); onSpeed(0f); onRoute(emptyList())
            delay(1000)
            onGear(Gear.PARK)
            onFinished()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
