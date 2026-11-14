package com.noisetrace.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * One day of this car's usual cabin level per speed band ([Baseline.SPEED_BANDS_KMH]), taken from what the detector
 * has learned. Kept for months, so slow changes show: a tyre wearing, a wheel bearing starting to hum.
 */
data class DayLevels(
    /** Local date, yyyy-MM-dd. */
    val day: String,
    val full: List<Float?>,
    val high: List<Float?>,
    val demo: Boolean = false,
) {
    fun level(channel: Channel, band: Int): Float? = (if (channel == Channel.FULL) full else high).getOrNull(band)
}

/** How the usual level of one speed band and channel moved between the previous 30 days and the last 30. */
data class BandChange(val band: Int, val channel: Channel, val beforeDb: Float, val nowDb: Float, val daysNow: Int, val series: List<Pair<Int, Float>>) {
    val deltaDb: Float get() = nowDb - beforeDb
    val bandLabel: String get() = MonthlyReports.bandLabel(band)
    val channelLabel: String get() = if (channel == Channel.FULL) "낮은 소리" else "높은 소리"

    /** Where a lasting change like this usually comes from. A hint for the owner, not a diagnosis. */
    val hint: String
        get() = when {
            deltaDb < 0 -> "줄었어요 · 수리나 타이어 교체 효과일 수 있어요"
            band == 0 -> "공조·냉각 팬 소리일 수 있어요"
            channel == Channel.FULL && band >= 4 -> "타이어 마모·휠 베어링을 확인해 보세요"
            channel == Channel.FULL -> "구동계·서스펜션 소리일 수 있어요"
            band >= 4 -> "문 고무·창문 틈 바람 소리일 수 있어요"
            else -> "실내 내장재 잡소리일 수 있어요"
        }
}

data class MonthlyReport(
    val events: Int,
    val autoEvents: Int,
    val manualEvents: Int,
    val topPattern: NoisePattern?,
    /** Days since the top pattern's noise was last saved; null when there is no pattern. */
    val daysSinceTop: Int?,
    /** Bands with enough days in both periods, biggest change first. */
    val changes: List<BandChange>,
) {
    val notable: List<BandChange> get() = changes.filter { abs(it.deltaDb) >= MonthlyReports.NOTABLE_DB }
    val hasTrend: Boolean get() = changes.isNotEmpty()
}

/**
 * The monthly report: what was saved in the last 30 days, and how this car's usual sound at each speed moved against
 * the 30 days before. Only steady changes count (enough days in both periods, at least [NOTABLE_DB]), since rain or a
 * rough road changes one day's level. It gives a reason to open the app when nothing rattles, and to see whether a
 * repaired noise stays away.
 */
object MonthlyReports {
    const val PERIOD_DAYS = 30
    const val MIN_DAYS = 5
    const val NOTABLE_DB = 2f
    const val KEEP_DAYS = 120
    private const val DAY_MS = 86_400_000L

    fun bandLabel(band: Int): String = when (band) {
        0 -> "정차"
        1 -> "20 km/h 미만"
        2 -> "20~40 km/h"
        3 -> "40~60 km/h"
        4 -> "60~90 km/h"
        else -> "90 km/h 이상"
    }

    fun dayKey(ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(Date(ms))

    /** Days from [day] to the day of [nowMs]; 0 is today. */
    fun daysAgo(day: String, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }
        val then = f.parse(day)?.time ?: return Int.MAX_VALUE
        val today = f.parse(dayKey(nowMs, zone))!!.time
        return ((today - then + DAY_MS / 2) / DAY_MS).toInt()
    }

    fun build(
        events: List<NoiseEvent>,
        exposure: DrivingExposure,
        history: List<DayLevels>,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): MonthlyReport {
        val recent = events.filter { nowMs - it.noiseAtMs in 0 until PERIOD_DAYS * DAY_MS }
        val top = PatternFinder.find(events, exposure).firstOrNull()
        val sinceTop = top?.events?.maxOfOrNull { it.noiseAtMs }?.let { ((nowMs - it) / DAY_MS).toInt() }
        val aged = history.map { daysAgo(it.day, nowMs, zone) to it }.filter { it.first in 0 until PERIOD_DAYS * 2 }
        val changes = mutableListOf<BandChange>()
        for (channel in Channel.entries) for (band in 0 until Baseline.BAND_COUNT) {
            val series = aged.mapNotNull { (ago, d) -> d.level(channel, band)?.let { ago to it } }.sortedByDescending { it.first }
            val now = series.filter { it.first < PERIOD_DAYS }.map { it.second }
            val before = series.filter { it.first >= PERIOD_DAYS }.map { it.second }
            if (now.size < MIN_DAYS || before.size < MIN_DAYS) continue
            changes += BandChange(band, channel, before.average().toFloat(), now.average().toFloat(), now.size, series)
        }
        return MonthlyReport(
            events = recent.size,
            autoEvents = recent.count { it.trigger == TriggerKind.AUTO },
            manualEvents = recent.count { it.trigger == TriggerKind.MANUAL },
            topPattern = top,
            daysSinceTop = sinceTop,
            changes = changes.sortedByDescending { abs(it.deltaDb) },
        )
    }

    /** Today's usual level per band, only for bands this car has itself driven enough in (no borrowed values). */
    fun today(baseline: Baseline, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): DayLevels? {
        fun levels(c: Channel) = (0 until Baseline.BAND_COUNT).map { b -> if (baseline.mature(c, b)) baseline.stats(c, b)?.medianDb else null }
        val full = levels(Channel.FULL)
        val high = levels(Channel.HIGH)
        if (full.all { it == null } && high.all { it == null }) return null
        return DayLevels(dayKey(nowMs, zone), full, high)
    }

    /** Puts [day] into [history] (replacing the same date, keeping known bands) and drops what is too old. */
    fun merge(history: List<DayLevels>, day: DayLevels, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): List<DayLevels> {
        val old = history.firstOrNull { it.day == day.day }
        val merged = if (old == null) day else DayLevels(
            day.day,
            day.full.mapIndexed { i, v -> v ?: old.full.getOrNull(i) },
            day.high.mapIndexed { i, v -> v ?: old.high.getOrNull(i) },
            demo = false,
        )
        return (history.filter { it.day != day.day } + merged)
            .filter { daysAgo(it.day, nowMs, zone) < KEEP_DAYS }
            .sortedBy { it.day }
    }

    /**
     * Two months of usual levels for demos: steady at every speed, except low-pitched sound above 60 km/h creeping up
     * by about 3 dB over the last month, the way a wearing tyre or a bearing starts to hum.
     */
    fun sampleHistory(nowMs: Long, zone: TimeZone = TimeZone.getDefault(), seed: Long = 11): List<DayLevels> {
        val rng = java.util.Random(seed)
        val fullBase = floatArrayOf(-47f, -44f, -41f, -39f, -36f, -34f)
        val highBase = floatArrayOf(-58f, -55f, -52f, -50f, -47f, -45f)
        return (PERIOD_DAYS * 2 - 1 downTo 0).filter { it % 7 != 3 }.map { ago ->
            val creep = if (ago < PERIOD_DAYS) 3.2f * (PERIOD_DAYS - ago) / PERIOD_DAYS + 1.6f else 0f
            fun jitter() = (rng.nextGaussian() * 0.5).toFloat()
            DayLevels(
                dayKey(nowMs - ago * DAY_MS, zone),
                full = fullBase.mapIndexed { b, v -> v + jitter() + if (b >= 4) creep else 0f },
                high = highBase.mapIndexed { _, v -> v + jitter() },
                demo = true,
            )
        }
    }
}
