package com.noisetrace.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.noisetrace.core.Wav
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays a saved noise through the car speakers for the mechanic, following the Pleos audio focus policy:
 * request focus first, stop on focus loss.
 */
class ClipPlayer(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val stopFlag = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .build()

    val playing: Boolean get() = thread?.isAlive == true

    /** Plays [file] from [fromMs]; [onPosition] gets the position in ms about ten times a second. */
    fun play(file: File, fromMs: Long, onPosition: (Long) -> Unit, onDone: () -> Unit) {
        stop()
        thread?.join(300)
        stopFlag.set(false)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stopFlag.set(true)
            }
            .build()
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onDone()
            return
        }
        thread = Thread({
            var track: AudioTrack? = null
            try {
                val (pcm, rate) = file.inputStream().use { Wav.read(it) }
                val chunk = rate / 10
                track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4))
                    .build()
                track.play()
                var pos = (fromMs * rate / 1000).toInt().coerceIn(0, pcm.size)
                while (pos < pcm.size && !stopFlag.get()) {
                    val n = minOf(chunk, pcm.size - pos)
                    track.write(pcm, pos, n)
                    pos += n
                    onPosition(pos * 1000L / rate)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "playback failed", t)
            } finally {
                runCatching { track?.stop() }
                track?.release()
                audioManager.abandonAudioFocusRequest(focus)
                onDone()
            }
        }, "noisetrace-playback").also { it.start() }
    }

    fun stop() {
        stopFlag.set(true)
    }

    private companion object {
        const val TAG = "NoiseTracePlayer"
    }
}
