package com.calmride.vehicle

import com.calmride.core.Gear
import com.calmride.core.RideTracker
import com.calmride.core.RouteEvent
import com.calmride.core.TurnSignal
import com.calmride.core.VoiceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Plays one whole taxi ride around a [DemoScript] drive for demos when no car or Virtual Console scenario is available. */
class SimulatedDrive(
    private val scope: CoroutineScope,
    private val onSpeed: (Float) -> Unit,
    private val onGear: (Gear) -> Unit,
    private val onBrake: (Boolean) -> Unit,
    private val onSignal: (TurnSignal) -> Unit,
    private val onSteering: (Float) -> Unit,
    private val onRoute: (List<RouteEvent>) -> Unit,
    private val onPassenger: (Boolean) -> Unit,
    /** The passenger's spoken answers. */
    private val onAnswer: (VoiceCommand) -> Unit,
    private val onFinished: () -> Unit,
) {
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun start(style: DemoStyle = DemoStyle.GENTLE) {
        if (running) return
        job = scope.launch {
            onGear(Gear.PARK); onSpeed(0f); delay(1500)
            onPassenger(true); delay(ANSWER_DELAY_MS)
            onAnswer(VoiceCommand.Yes); delay(ANSWER_DELAY_MS)
            onAnswer(VoiceCommand.CuesOnly); delay(1000)
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
            onGear(Gear.PARK); delay(ANSWER_DELAY_MS)
            onAnswer(VoiceCommand.Score(if (style == DemoStyle.GENTLE) 5 else 2)); delay(1500)
            onPassenger(false); delay(RideTracker.ALIGHT_HOLD_MS + 1500)
            onFinished()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val ANSWER_DELAY_MS = 6000L
    }
}
