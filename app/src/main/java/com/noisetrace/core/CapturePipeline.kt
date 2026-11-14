package com.noisetrace.core

import java.util.concurrent.atomic.AtomicLong

/** Audio of one noise, 20 s before and 10 s after, with the levels the detector saw. */
class CapturedClip(
    val kind: TriggerKind,
    /** The button press, or the frame where the detector fired. */
    val savedAtMs: Long,
    /** Best estimate of when the sound itself happened. For a button press, the loudest moment before it. */
    val noiseAtMs: Long,
    /** False when nothing stood out before the button press, so [noiseAtMs] is only a guess. */
    val momentCertain: Boolean,
    val levelDb: Float,
    val baselineDb: Float?,
    val channel: Channel?,
    val pcm: ShortArray,
    val clipStartMs: Long,
    val sampleRate: Int,
    val levels: List<FrameLevel>,
) {
    val durationMs: Long get() = pcm.size * 1000L / sampleRate
}

/**
 * The whole audio path, one 100 ms frame at a time: keeps the last [preMs] in memory, measures each frame, asks the
 * detector, and when a capture starts (button or detector) copies the memory out and keeps [postMs] more.
 *
 * Runs on the audio thread only. The button press arrives from another thread through [requestManual].
 * Nothing reaches storage unless [onFrame] returns a clip.
 */
class CapturePipeline(
    val sampleRate: Int = SAMPLE_RATE,
    val preMs: Long = PRE_MS,
    val postMs: Long = POST_MS,
    val detector: NoiseDetector = NoiseDetector(),
) {
    val frameSamples = sampleRate / 10
    private val preSamples = (sampleRate * preMs / 1000).toInt()
    private val postSamples = (sampleRate * postMs / 1000).toInt()
    private val ring = AudioRing(preSamples)
    private val history = ArrayDeque<FrameLevel>()
    private val historyFrames = ((preMs + postMs) / 100 + 20).toInt()
    private val highPass = HighPass(sampleRate, HIGH_PASS_HZ)
    private val filtered = FloatArray(frameSamples)
    private val manualAt = AtomicLong(0)
    private var pending: Pending? = null

    @Volatile var autoDetect = true

    /** Latest frame, for the level meter on screen. */
    @Volatile var lastFrame: FrameLevel? = null
        private set

    /** Milliseconds of audio still to record after the trigger, or null when idle. */
    @Volatile var captureLeftMs: Long? = null
        private set

    @Volatile var captureKind: TriggerKind? = null
        private set

    private class Pending(
        var kind: TriggerKind,
        var savedAtMs: Long,
        val noiseAtMs: Long,
        val certain: Boolean,
        val levelDb: Float,
        val baselineDb: Float?,
        val channel: Channel?,
        val pre: ShortArray,
        val clipStartMs: Long,
        val post: ShortArray,
        var postWritten: Int = 0,
    )

    /** "방금 소리 저장": callable from any thread; takes effect on the next frame. */
    fun requestManual(atMs: Long) {
        manualAt.set(atMs)
    }

    fun onFrame(pcm: ShortArray, atMs: Long, speedKmh: Float?, suppressed: Boolean): CapturedClip? {
        val n = minOf(pcm.size, frameSamples)
        val fullDb = Levels.rmsDb(pcm, 0, n)
        highPass.process(pcm, filtered, n)
        val highDb = Levels.rmsDb(filtered, n)
        val (frame, trigger) = detector.onFrame(atMs, fullDb, highDb, speedKmh, suppressed, detect = autoDetect && pending == null)
        lastFrame = frame
        history.addLast(frame)
        while (history.size > historyFrames) history.removeFirst()
        ring.write(pcm, 0, n)

        val pressedAt = manualAt.getAndSet(0)
        val p = pending
        if (p != null) {
            // A press during an automatic capture confirms it: someone heard the same noise.
            if (pressedAt != 0L && p.kind == TriggerKind.AUTO) {
                p.kind = TriggerKind.MANUAL
                p.savedAtMs = pressedAt
                captureKind = TriggerKind.MANUAL
            }
            val k = minOf(n, p.post.size - p.postWritten)
            System.arraycopy(pcm, 0, p.post, p.postWritten, k)
            p.postWritten += k
            captureLeftMs = (p.post.size - p.postWritten) * 1000L / sampleRate
            if (p.postWritten >= p.post.size) return finish(p, atMs)
            return null
        }
        when {
            pressedAt != 0L -> startManual(pressedAt, atMs)
            trigger != null -> start(
                TriggerKind.AUTO, trigger.atMs, trigger.atMs, true, trigger.levelDb, trigger.baselineDb, trigger.channel, atMs,
            )
        }
        return null
    }

    /**
     * The noise came before the press. "방금" means the latest one: of the moments clearly louder than usual in the
     * memory, take the last burst, at its loudest frame.
     */
    private fun startManual(pressedAt: Long, frameAtMs: Long) {
        val standouts = history.filter {
            it.atMs in (pressedAt - preMs)..frameAtMs && !it.suppressed && (it.excessDb ?: Float.NEGATIVE_INFINITY) >= CERTAIN_EXCESS_DB
        }
        val latest = standouts.lastOrNull()
        val loudest = latest?.let { l -> standouts.filter { l.atMs - it.atMs <= BURST_SPAN_MS }.maxBy { it.excessDb!! } }
        if (loudest != null) {
            val highWins = (loudest.highBaseDb?.let { loudest.highDb - it } ?: Float.NEGATIVE_INFINITY) >=
                (loudest.fullBaseDb?.let { loudest.fullDb - it } ?: Float.NEGATIVE_INFINITY)
            val channel = if (highWins) Channel.HIGH else Channel.FULL
            val level = if (highWins) loudest.highDb else loudest.fullDb
            val base = if (highWins) loudest.highBaseDb else loudest.fullBaseDb
            start(TriggerKind.MANUAL, pressedAt, loudest.atMs, true, level, base, channel, frameAtMs)
        } else {
            val guess = pressedAt - UNCERTAIN_GUESS_MS
            val level = history.lastOrNull { it.atMs <= guess }?.fullDb ?: lastFrame?.fullDb ?: Levels.SILENCE_DB
            start(TriggerKind.MANUAL, pressedAt, guess, false, level, null, null, frameAtMs)
        }
    }

    private fun start(
        kind: TriggerKind, savedAtMs: Long, noiseAtMs: Long, certain: Boolean,
        levelDb: Float, baselineDb: Float?, channel: Channel?, frameAtMs: Long,
    ) {
        val pre = ring.latest(preSamples)
        pending = Pending(
            kind, savedAtMs, noiseAtMs, certain, levelDb, baselineDb, channel, pre,
            clipStartMs = frameAtMs - pre.size * 1000L / sampleRate,
            post = ShortArray(postSamples),
        )
        captureKind = kind
        captureLeftMs = postMs
    }

    private fun finish(p: Pending, atMs: Long): CapturedClip {
        pending = null
        captureLeftMs = null
        captureKind = null
        detector.holdOffAfterCapture(atMs)
        val pcm = ShortArray(p.pre.size + p.post.size)
        p.pre.copyInto(pcm)
        p.post.copyInto(pcm, p.pre.size)
        val endMs = p.clipStartMs + pcm.size * 1000L / sampleRate
        return CapturedClip(
            p.kind, p.savedAtMs, p.noiseAtMs, p.certain, p.levelDb, p.baselineDb, p.channel, pcm, p.clipStartMs, sampleRate,
            history.filter { it.atMs in p.clipStartMs..endMs },
        )
    }

    /** Drops a capture in progress, e.g. when tracking is switched off. */
    fun cancel() {
        pending = null
        captureLeftMs = null
        captureKind = null
        manualAt.set(0)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val PRE_MS = 20_000L
        const val POST_MS = 10_000L
        const val HIGH_PASS_HZ = 400.0
        /** A press counts as matched to a sound when something was at least this much louder than usual. */
        const val CERTAIN_EXCESS_DB = 6f
        /** With nothing standing out, assume the sound was this long before the press. */
        const val UNCERTAIN_GUESS_MS = 2_000L
        /** Frames this close before the latest loud frame belong to the same noise. */
        const val BURST_SPAN_MS = 1_500L
    }
}
