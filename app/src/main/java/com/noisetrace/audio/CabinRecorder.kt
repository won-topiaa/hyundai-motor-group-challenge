package com.noisetrace.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.noisetrace.core.CapturePipeline
import com.noisetrace.core.CapturedClip
import com.noisetrace.core.DemoCabinAudio

/**
 * Feeds the [CapturePipeline] one 100 ms frame at a time on its own thread: from the cabin microphone, or from
 * synthetic cabin audio during the demo drive. The microphone audio only ever lives in the pipeline's 20-second
 * memory unless a capture copies it out.
 *
 * Uses the UNPROCESSED source where the car offers it, else VOICE_RECOGNITION: both skip the automatic gain control
 * that would flatten the very level changes the detector looks for.
 */
class CabinRecorder(
    private val context: Context,
    private val onClip: (CapturedClip, demo: Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    /** Read every frame on the audio thread. */
    @Volatile var speedKmh: Float? = null
    @Volatile var suppressed: () -> Boolean = { false }

    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var pipeline: CapturePipeline? = null
        private set

    val active: Boolean get() = running

    fun startMic(pipeline: CapturePipeline) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStatus("마이크 권한 필요")
            return
        }
        start(pipeline, null)
    }

    fun startDemo(pipeline: CapturePipeline, audio: DemoCabinAudio) = start(pipeline, audio)

    private fun start(pipeline: CapturePipeline, demo: DemoCabinAudio?) {
        stop()
        this.pipeline = pipeline
        running = true
        thread = Thread({ loop(pipeline, demo) }, "noisetrace-audio").also { it.start() }
    }

    private fun loop(pipeline: CapturePipeline, demo: DemoCabinAudio?) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frame = ShortArray(pipeline.frameSamples)
        val record = if (demo == null) openMic(pipeline.sampleRate, frame.size) ?: run {
            running = false
            return
        } else null
        var nextAt = System.currentTimeMillis()
        try {
            record?.startRecording()
            if (record != null) onStatus("마이크 켜짐 · 최근 20초만 메모리에 보관")
            while (running) {
                if (demo != null) {
                    demo.next(frame)
                    // Keep real time: the demo drive runs on the clock, and so must its audio.
                    nextAt += 100
                    val wait = nextAt - System.currentTimeMillis()
                    if (wait > 0) Thread.sleep(wait)
                } else {
                    var read = 0
                    while (read < frame.size && running) {
                        val n = record!!.read(frame, read, frame.size - read)
                        if (n <= 0) {
                            Log.w(TAG, "read error $n")
                            Thread.sleep(100)
                            break
                        }
                        read += n
                    }
                    if (read < frame.size) continue
                }
                val clip = pipeline.onFrame(frame, System.currentTimeMillis(), speedKmh, suppressed())
                if (clip != null) onClip(clip, demo != null)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "audio loop failed", t)
            onStatus("마이크 오류: ${t.javaClass.simpleName}")
        } finally {
            runCatching { record?.stop() }
            record?.release()
            running = false
        }
    }

    private fun openMic(sampleRate: Int, frameSamples: Int): AudioRecord? {
        val audio = context.getSystemService(AudioManager::class.java)
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return try {
            @Suppress("MissingPermission")
            val record = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, frameSamples * 2 * 4))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onStatus("마이크를 열 수 없음")
                null
            } else record
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord failed", t)
            onStatus("마이크를 열 수 없음: ${t.javaClass.simpleName}")
            null
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(500) }
        thread = null
        pipeline?.cancel()
    }

    private companion object {
        const val TAG = "NoiseTraceAudio"
    }
}
