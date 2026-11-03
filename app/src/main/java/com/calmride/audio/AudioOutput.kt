package com.calmride.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.calmride.core.Cue
import com.calmride.core.ToneSynth
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays the 100Hz priming tone and the cue chimes, following the Pleos Connect audio focus policy:
 * every sound requests focus first, cues duck music instead of stopping it, and focus loss stops playback.
 */
class AudioOutput(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val cueExecutor = Executors.newSingleThreadExecutor()
    private val primingStop = AtomicBoolean(false)
    @Volatile private var primingThread: Thread? = null

    private val cueAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val primingAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(ToneSynth.SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    val isPriming: Boolean get() = primingThread?.isAlive == true

    fun playCue(cue: Cue, volume: Float) {
        cueExecutor.execute {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(cueAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "cue focus rejected: ${cue.kind}")
                return@execute
            }
            try {
                val pcm = ToneSynth.cue(cue.kind, volume.coerceIn(0f, 1f), cue.pan)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(cueAttributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size / 2 * 1000L / ToneSynth.SAMPLE_RATE + 60)
                track.stop()
                track.release()
            } catch (t: Throwable) {
                Log.w(TAG, "cue playback failed", t)
            } finally {
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
    }

    /** Streams the 1-minute 100Hz tone. [onTick] receives remaining seconds; [onDone] gets true if it finished. */
    fun startPriming(volume: Float, onTick: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        if (isPriming) return
        primingStop.set(false)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(primingAttributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    primingStop.set(true)
                }
            }
            .build()
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onDone(false)
            return
        }
        primingThread = Thread({
            val totalFrames = ToneSynth.PRIMING_SECONDS.toLong() * ToneSynth.SAMPLE_RATE
            val chunkFrames = ToneSynth.SAMPLE_RATE / 10
            val buffer = ShortArray(chunkFrames * 2)
            val minBuf = AudioTrack.getMinBufferSize(ToneSynth.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            var completed = false
            val track = AudioTrack.Builder()
                .setAudioAttributes(primingAttributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf, chunkFrames * 4 * 2))
                .build()
            try {
                track.play()
                var frame = 0L
                var lastSecond = -1
                while (frame < totalFrames && !primingStop.get()) {
                    val n = minOf(chunkFrames.toLong(), totalFrames - frame).toInt()
                    ToneSynth.fillPriming(buffer, frame, n, totalFrames, volume.coerceIn(0f, 1f))
                    track.write(buffer, 0, n * 2)
                    frame += n
                    val remaining = ((totalFrames - frame) / ToneSynth.SAMPLE_RATE).toInt()
                    if (remaining != lastSecond) {
                        lastSecond = remaining
                        onTick(remaining)
                    }
                }
                completed = frame >= totalFrames
            } catch (t: Throwable) {
                Log.w(TAG, "priming failed", t)
            } finally {
                runCatching { track.stop() }
                track.release()
                audioManager.abandonAudioFocusRequest(focus)
                primingThread = null
                onDone(completed)
            }
        }, "calmride-priming").also { it.start() }
    }

    fun stopPriming() {
        primingStop.set(true)
    }

    fun release() {
        stopPriming()
        cueExecutor.shutdown()
    }

    private companion object {
        const val TAG = "CalmRideAudio"
    }
}
