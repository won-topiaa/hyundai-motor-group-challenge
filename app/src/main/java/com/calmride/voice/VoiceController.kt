package com.calmride.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import com.calmride.core.SpeechTiming
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * Pleos TextToSpeech / SpeechToText in ON_DEVICE mode, so no server registration is needed.
 * Driving is voice-first: the screen is not used while the car moves.
 */
class VoiceController(
    context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val tts = runCatching { TextToSpeech(context, TtsMode.ON_DEVICE) }.getOrNull()
    private val stt = runCatching { SpeechToText(context, SttMode.ON_DEVICE) }.getOrNull()
    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    var listening = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var listenAfterSpeech = false

    /**
     * The sentence [say] waits on: true once said, false when the engine failed. [spokenStarted] keeps a late stop
     * of the sentence before it from ending it early.
     */
    @Volatile private var spoken: CompletableDeferred<Boolean>? = null
    @Volatile private var spokenStarted = false

    private val ttsEvents = object : TtsEvents {
        override fun onStart() {
            spokenStarted = true
        }
        override fun onReady() = Unit
        override fun onDone() {
            if (spokenStarted) spoken?.complete(true)
            if (!listenAfterSpeech) return
            listenAfterSpeech = false
            handler.post(::listen)
        }
        override fun onError(p0: TtsError) {
            listenAfterSpeech = false
            spoken?.complete(false)
        }
        override fun onStop() {
            if (spokenStarted) spoken?.complete(true)
        }
        override fun onUpdatedRms(p0: Double) = Unit
    }

    private val resultListener = object : ResultListener {
        override fun onUpdated(p0: String, p1: Boolean) {
            if (p1 && p0.isNotBlank()) onText(p0)
        }
        override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
        override fun onStartedRecognition() {
            listening = true
        }
        override fun onEndedRecognition() {
            listening = false
        }
        override fun onError(p0: SttError) {
            listening = false
            Log.w(TAG, "stt error $p0")
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(p0: Double) = Unit
    }

    fun start() {
        runCatching {
            tts?.initialize(object : TtsInit {
                override fun onSuccess() {
                    ttsReady = true
                    runCatching {
                        tts.setLocale(Locale.KOREAN)
                        tts.addEventListener(ttsEvents)
                    }
                    onStatus("음성 안내 준비됨")
                }
                override fun onFailure(p0: TtsError) = onStatus("음성 안내 사용 불가")
            })
        }.onFailure { onStatus("음성 안내 사용 불가") }
        runCatching {
            stt?.initialize(object : SttInit {
                override fun onSuccess() {
                    sttReady = true
                    runCatching {
                        stt.setLocale(Locale.KOREAN)
                        stt.addListener(resultListener)
                    }
                }
                override fun onFailure(p0: SttError) = Unit
            })
        }
    }

    fun speak(text: String) {
        listenAfterSpeech = false
        spoken?.complete(true)
        spoken = null
        if (!ttsReady) return
        runCatching { tts?.speak(text, true) }.onFailure { Log.w(TAG, "speak failed", it) }
    }

    /**
     * Speaks and returns once the sentence has been said, so a sound can follow it. When the voice engine is missing
     * or fails, it waits as long as the sentence would take, which keeps the on-screen caption and the timing the same.
     */
    suspend fun say(text: String) {
        val estimate = SpeechTiming.estimateMs(text)
        if (!ttsReady) return delay(estimate)
        val startedAt = SystemClock.elapsedRealtime()
        val done = CompletableDeferred<Boolean>()
        listenAfterSpeech = false
        spoken?.complete(true)
        // Set before speaking: the engine may report the start before speak() returns.
        spokenStarted = false
        spoken = done
        runCatching { tts?.speak(text, true) }.onFailure {
            Log.w(TAG, "speak failed", it)
            done.complete(false)
        }
        // A missing callback must not stall the intro.
        val said = withTimeoutOrNull(estimate + SAY_TIMEOUT_MARGIN_MS) { done.await() }
        if (spoken === done) spoken = null
        if (said == false) delay((estimate - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0))
    }

    /** [say], then opens the microphone once for the answer, like [ask]. */
    suspend fun sayAndListen(text: String) {
        say(text)
        if (ttsReady) listen()
    }

    /**
     * Asks out loud and opens the microphone once for the answer. A passenger cannot reach the screen, so this is
     * how they reply. The microphone never stays open: what is said in a taxi is the passenger's own business.
     */
    fun ask(text: String) {
        speak(text)
        listenAfterSpeech = ttsReady
    }

    fun listen() {
        if (!sttReady) return onStatus("음성 인식 사용 불가")
        runCatching { stt?.request() }.onFailure { Log.w(TAG, "listen failed", it) }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { tts?.removeEventListener(ttsEvents) }
        runCatching { tts?.stop() }
        runCatching { stt?.removeListener(resultListener) }
        runCatching { tts?.release() }
        runCatching { stt?.release() }
    }

    private companion object {
        const val TAG = "CalmRideVoice"
        const val SAY_TIMEOUT_MARGIN_MS = 1500L
    }
}
