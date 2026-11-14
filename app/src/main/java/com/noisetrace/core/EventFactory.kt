package com.noisetrace.core

/** Turns a captured clip and the vehicle trace around it into one "소음 사건". */
object EventFactory {
    /** Trace points kept with an event: 5 per second is enough for a speed and steering chart. */
    private const val TRACE_EVERY_MS = 200L

    fun build(
        id: String,
        number: Int,
        clip: CapturedClip,
        trace: List<VehicleSnapshot>,
        hazards: List<HazardPass>,
        audioFile: String?,
        demo: Boolean = false,
    ): NoiseEvent {
        val endMs = clip.clipStartMs + clip.durationMs
        var nextAt = Long.MIN_VALUE
        val points = trace.filter { it.timestampMs in clip.clipStartMs..endMs }.mapNotNull { s ->
            if (s.timestampMs < nextAt) return@mapNotNull null
            nextAt = s.timestampMs + TRACE_EVERY_MS
            TracePoint((s.timestampMs - clip.noiseAtMs).toInt(), s.speedKmh, s.steeringDeg, s.brakePressed)
        }
        return NoiseEvent(
            id = id,
            number = number,
            noiseAtMs = clip.noiseAtMs,
            savedAtMs = clip.savedAtMs,
            trigger = clip.kind,
            momentCertain = clip.momentCertain,
            audioFile = audioFile,
            audioBytes = if (audioFile != null) Wav.sizeBytes(clip.pcm.size) else 0,
            clipStartMs = clip.clipStartMs,
            clipDurationMs = clip.durationMs,
            levelDb = clip.levelDb,
            baselineDb = clip.baselineDb,
            spectrum = SpectrumAnalyzer.analyze(clip.pcm, clip.sampleRate, clip.noiseAtMs - clip.clipStartMs),
            context = ContextBuilder.at(trace, hazards, clip.noiseAtMs),
            trace = points,
            excessTimeline = clip.levels.map { it.excessDb ?: 0f },
            demo = demo,
        )
    }
}

/**
 * A week of earlier saves for demos, so the pattern view has something to find: three rattles at the front on
 * slow left-hand ramps (rain or not, AC or not) and one unrelated hum on the motorway.
 */
object SampleHistory {
    private const val DAY = 86_400_000L
    private const val MIN = 60_000L

    class Sample(val event: NoiseEvent, val pcm: ShortArray)

    private class Spec(
        val daysAgo: Int, val hour: Int, val minute: Int, val type: NoiseType, val trigger: TriggerKind,
        val spots: Set<Spot>, val tagged: Boolean, val context: DrivingContext,
    )

    private val specs = listOf(
        Spec(6, 8, 12, NoiseType.RATTLE, TriggerKind.MANUAL, setOf(Spot.FRONT), true,
            DrivingContext(speedKmh = 12f, accelMps2 = -0.7f, steeringDeg = -22f, gear = Gear.DRIVE, wiperOn = false, acOn = true,
                windowOpen = false, outsideTempC = 3f, odometerKm = 23_301f, slopePct = -9f, roadType = RoadType.LOCAL, address = "회사 지하주차장 진입로")),
        Spec(4, 18, 47, NoiseType.RATTLE, TriggerKind.AUTO, setOf(Spot.FRONT, Spot.RIGHT), true,
            DrivingContext(speedKmh = 17f, accelMps2 = -0.6f, steeringDeg = -16f, brake = true, gear = Gear.DRIVE, wiperOn = true, acOn = false,
                windowOpen = false, outsideTempC = 11f, odometerKm = 23_342f, slopePct = -6f, roadType = RoadType.LOCAL, address = "마트 지하주차장 진입로")),
        Spec(2, 19, 5, NoiseType.RATTLE, TriggerKind.MANUAL, setOf(Spot.FRONT), true,
            DrivingContext(speedKmh = 15f, accelMps2 = -0.1f, steeringDeg = -30f, gear = Gear.DRIVE, wiperOn = false, acOn = false,
                windowOpen = false, outsideTempC = 9f, odometerKm = 23_377f, slopePct = -7f, roadType = RoadType.LOCAL, address = "지하주차장 진입구")),
        Spec(1, 7, 58, NoiseType.HUM, TriggerKind.AUTO, emptySet(), false,
            DrivingContext(speedKmh = 96f, accelMps2 = 0.1f, steeringDeg = 1f, gear = Gear.DRIVE, wiperOn = false, acOn = true,
                windowOpen = false, outsideTempC = 6f, odometerKm = 23_395f, slopePct = 0.5f, roadType = RoadType.HIGHWAY, address = "고속도로")),
    )

    fun build(nowMs: Long, firstNumber: Int): List<Sample> = specs.mapIndexed { i, s ->
        val dayStart = nowMs - nowMs % DAY - s.daysAgo * DAY
        // Local time is only for display; build from the device's offset so "8시 12분" reads as morning.
        val offset = java.util.TimeZone.getDefault().getOffset(nowMs)
        val at = dayStart - offset + s.hour * 60 * MIN + s.minute * MIN
        val pcm = DemoCabinAudio.clip(30_000, s.context.speedKmh ?: 0f, s.type, 20_000, seed = 100L + i)
        val clipStart = at - 20_000
        val v = s.context.speedKmh ?: 0f
        val a = (s.context.accelMps2 ?: 0f) * 3.6f
        val steer = s.context.steeringDeg ?: 0f
        val trace = (-20..10).map { sec ->
            // Speed follows the acceleration around the noise; the wheel turns in over the 8 s before it.
            val speed = (v + a * sec.coerceIn(-3, 3)).coerceAtLeast(0f)
            val turn = steer * ((sec + 8) / 8f).coerceIn(0f, 1f)
            TracePoint(sec * 1000, speed, turn, s.context.brake && sec in -1..1)
        }
        val event = NoiseEvent(
            id = "sample-$i-$at",
            number = firstNumber + i,
            noiseAtMs = at,
            savedAtMs = at + if (s.trigger == TriggerKind.MANUAL) 2_500 else 0,
            trigger = s.trigger,
            audioBytes = Wav.sizeBytes(pcm.size),
            clipStartMs = clipStart,
            clipDurationMs = 30_000,
            levelDb = Levels.rmsDb(pcm, 20 * CapturePipeline.SAMPLE_RATE, CapturePipeline.SAMPLE_RATE / 10),
            baselineDb = Levels.rmsDb(pcm, 0, CapturePipeline.SAMPLE_RATE),
            spectrum = SpectrumAnalyzer.analyze(pcm, CapturePipeline.SAMPLE_RATE, 20_000),
            context = s.context,
            trace = trace,
            userType = if (s.tagged) s.type else null,
            spots = s.spots,
            confirmed = s.tagged || s.trigger == TriggerKind.MANUAL,
            demo = true,
        )
        Sample(event, pcm)
    }

    /** Six and a half hours of driving to compare against: how rare a slow left-hand downhill really is. */
    val exposure = DrivingExposure(
        totalSec = 23_400,
        seconds = mapOf(
            Condition.STOPPED to 3_500L, Condition.LOW_SPEED to 4_200L, Condition.MID_SPEED to 10_800L, Condition.HIGH_SPEED to 4_900L,
            Condition.TURN_LEFT to 1_400L, Condition.TURN_RIGHT to 1_500L, Condition.DECEL to 3_300L, Condition.ACCEL to 3_500L,
            Condition.BRAKE to 2_800L, Condition.DOWNHILL to 700L, Condition.UPHILL to 750L, Condition.BUMP to 250L,
            Condition.WIPER to 2_100L, Condition.AC to 10_500L, Condition.COLD to 4_700L, Condition.REVERSE to 300L,
        ),
    )
}
