package com.noisetrace.core

/**
 * The last few seconds of cabin audio, kept only in memory. Every new sample overwrites the oldest one, so nothing
 * older than [capacity] samples exists anywhere unless a capture copies it out.
 */
class AudioRing(val capacity: Int) {
    private val data = ShortArray(capacity)
    private var next = 0

    /** Samples written since creation (or the last [clear]); the ring holds min(written, capacity) of them. */
    var written = 0L
        private set

    val size: Int get() = minOf(written, capacity.toLong()).toInt()

    fun write(src: ShortArray, offset: Int = 0, length: Int = src.size - offset) {
        var from = offset
        var left = length
        // Only the newest [capacity] samples can survive, so skip the rest of an oversized write.
        if (left > capacity) {
            from += left - capacity
            written += left - capacity
            left = capacity
        }
        while (left > 0) {
            val n = minOf(left, capacity - next)
            System.arraycopy(src, from, data, next, n)
            next = (next + n) % capacity
            from += n
            left -= n
            written += n
        }
    }

    /** Copies the newest [count] samples in time order (fewer when the ring has not filled up yet). */
    fun latest(count: Int): ShortArray {
        val n = minOf(count, size)
        val out = ShortArray(n)
        val start = (next - n + capacity) % capacity
        val firstPart = minOf(n, capacity - start)
        System.arraycopy(data, start, out, 0, firstPart)
        if (firstPart < n) System.arraycopy(data, 0, out, firstPart, n - firstPart)
        return out
    }

    fun clear() {
        data.fill(0)
        next = 0
        written = 0
    }
}
