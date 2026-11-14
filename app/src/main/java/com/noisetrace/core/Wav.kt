package com.noisetrace.core

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM WAV, the plainest format a mechanic's laptop or any analysis tool opens. */
object Wav {
    const val HEADER_BYTES = 44

    fun write(out: OutputStream, pcm: ShortArray, sampleRate: Int) {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        out.write(header.array())
        val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(pcm)
        out.write(body.array())
    }

    /** Reads a file written by [write]; returns the samples and the sample rate. */
    fun read(input: InputStream): Pair<ShortArray, Int> {
        val bytes = DataInputStream(input).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= HEADER_BYTES && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        val sampleRate = buf.getInt(24)
        val dataBytes = buf.getInt(40).coerceAtMost(bytes.size - HEADER_BYTES)
        val pcm = ShortArray(dataBytes / 2)
        buf.position(HEADER_BYTES)
        buf.asShortBuffer().get(pcm)
        return pcm to sampleRate
    }

    fun sizeBytes(samples: Int): Long = HEADER_BYTES + samples * 2L
}
