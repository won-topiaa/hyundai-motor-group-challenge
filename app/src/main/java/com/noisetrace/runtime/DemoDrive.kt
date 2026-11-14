package com.noisetrace.runtime

import android.util.Log
import com.noisetrace.core.DemoCabinAudio
import com.noisetrace.core.DemoScript
import com.noisetrace.core.NoiseType
import com.noisetrace.core.SpeechTiming
import com.noisetrace.core.Spot
import com.noisetrace.core.VoiceCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos

/**
 * Plays [DemoScript] through the real app path with no car: vehicle signals into the controller, synthetic cabin
 * audio into a demo pipeline, the button press, and the driver's spoken answers after each save.
 */
class DemoDrive(private val scope: CoroutineScope, private val controller: NoiseTraceController) {
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch {
            val audio = DemoCabinAudio()
            controller.beginDemo(audio)
            val answers = launch { answerQuestions() }
            var odometer = DemoScript.ODOMETER_KM.toDouble()
            var lat = DemoScript.LATITUDE
            try {
                // Timing marks for lining up a screen recording with the demo's sound.
                Log.i(TAG, "demo start")
                for (step in DemoScript.steps()) {
                    if (!isActive) break
                    odometer += step.speedMps * DemoScript.STEP_MS / 1_000_000.0
                    lat += step.speedMps * DemoScript.STEP_MS / 1000.0 / 111_000.0
                    val now = System.currentTimeMillis()
                    controller.demoVehicle {
                        copy(
                            speedMps = step.speedMps, gear = step.gear, steeringDeg = step.steeringDeg, brakePressed = step.brake,
                            turnSignal = step.turnSignal, wiperOn = DemoScript.WIPER_ON, acOn = DemoScript.AC_ON, windowOpen = false,
                            outsideTempC = DemoScript.OUTSIDE_TEMP_C, odometerKm = odometer.toFloat(),
                            address = step.address, speedLimitKmh = step.speedLimitKmh,
                        ).let {
                            if (step.gpsFix) it.copy(latitude = lat, longitude = DemoScript.LONGITUDE + 0.0001 * cos(lat), altitudeM = step.altitudeM, locationAtMs = now) else it
                        }
                    }
                    step.sound?.let {
                        Log.i(TAG, "demo sound $it at ${step.atSec}")
                        controller.demoSound(it)
                    }
                    if (step.pressSave) {
                        Log.i(TAG, "demo press at ${step.atSec}")
                        controller.saveNow()
                    }
                    delay(DemoScript.STEP_MS)
                }
                // Let the last capture finish and its question be answered.
                withTimeoutOrNull(FINISH_WAIT_MS) {
                    controller.state.first { it.capture == null && it.question == null }
                }
                delay(1500)
            } finally {
                answers.cancel()
                controller.endDemo()
            }
        }
    }

    /** The driver answers each question a moment after it has been said: front, rattle; then "same one". */
    private suspend fun answerQuestions() {
        var last: TagQuestion? = null
        controller.state.collect { s ->
            val q = s.question ?: return@collect
            if (q == last) return@collect
            last = q
            // The driver answers once the question has been said, as they would after hearing it.
            delay(SpeechTiming.estimateMs(q.text) + ANSWER_DELAY_MS)
            if (controller.state.value.question != q) return@collect
            Log.i(TAG, "demo answer ${q.step}")
            controller.handle(
                when (q.step) {
                    TagStep.SAME -> VoiceCommand.Yes
                    TagStep.WHERE -> VoiceCommand.Where(setOf(Spot.FRONT))
                    TagStep.KIND -> VoiceCommand.Kind(NoiseType.RATTLE)
                },
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val ANSWER_DELAY_MS = 1200L
        const val FINISH_WAIT_MS = 30_000L
        const val TAG = "NoiseTrace"
    }
}
