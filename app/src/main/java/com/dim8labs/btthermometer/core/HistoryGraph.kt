package com.dim8labs.btthermometer.core

import kotlin.math.roundToInt

/** Three evenly spaced axis labels, in whole display degrees. */
data class GraphAxis(val low: Int, val mid: Int, val high: Int) {
    /** 0 at [low] and 1 at [high]. */
    fun fraction(displayValue: Double): Double = (displayValue - low) / (high - low)
}

/** A plotted threshold excursion at one history slot. */
data class GraphMarker(val index: Int, val excursion: Excursion)

/** Two history slots bounding a real reception gap. */
data class GraphGap(val startIndex: Int, val endIndex: Int)

/**
 * Display-only policy for the 30-minute graph. Nothing here affects alarms.
 *
 * Scaling: the axis always spans at least two coarse steps, so ordinary probe
 * noise (the H5182 reports whole °C, or 1.8 °F steps) stays visually small.
 */
object HistoryGraph {
    private val FAHRENHEIT_STEPS = intArrayOf(5, 10, 20, 25, 50, 100, 200, 500)
    private val CELSIUS_STEPS = intArrayOf(2, 5, 10, 20, 25, 50, 100, 200, 500)
    private const val PADDING_DEGREES = 1.0

    /** Consecutive excursion points closer than this are thinned (6 slots = 1 minute). */
    const val MARKER_SPACING = 6

    /**
     * Newest reading age still drawn at "Now". Measured packet spacing reaches about
     * 16 s, so this bridges normal gaps without presenting an old value as current.
     */
    const val LIVE_MAX_AGE_MS = 20_000L

    /** The current reading when it is fresh enough to draw at "Now", otherwise null. */
    fun liveReading(probe: ProbeView): Int? {
        val current = probe.status == ProbeStatus.NORMAL || probe.status == ProbeStatus.HIGH ||
            probe.status == ProbeStatus.LOW
        val age = probe.ageMs ?: return null
        return probe.temperatureC?.takeIf { current && age <= LIVE_MAX_AGE_MS }
    }

    /**
     * History stores one sample per 10 s, so the graph would otherwise appear to
     * lag. This draws the live reading in the newest slot for display only; nothing
     * is stored. A slot holding an excursion keeps its value so its marker stays
     * on the recorded point.
     */
    fun withLiveReading(values: List<Int?>, excursions: List<Excursion>, live: Int?): List<Int?> {
        if (live == null || values.isEmpty()) return values
        if (excursions.getOrNull(values.lastIndex).let { it != null && it != Excursion.NONE }) return values
        return values.toMutableList().also { it[it.lastIndex] = live }
    }

    /**
     * Chooses the smallest step whose three labels contain the readings with
     * padding, centered on the nearest multiple of that step. Returns null with no data.
     */
    fun axis(values: List<Int?>, fahrenheit: Boolean): GraphAxis? {
        val valid = values.filterNotNull()
        if (valid.isEmpty()) return null
        val min = Units.toDisplay(valid.min(), fahrenheit)
        val max = Units.toDisplay(valid.max(), fahrenheit)
        val center = (min + max) / 2.0
        val steps = if (fahrenheit) FAHRENHEIT_STEPS else CELSIUS_STEPS
        for (step in steps) {
            var mid = (center / step).roundToInt() * step
            // Supported readings are never below zero, so neither is the axis.
            if (mid - step < 0) mid = step
            if (mid - step <= min - PADDING_DEGREES && mid + step >= max + PADDING_DEGREES) {
                return GraphAxis(mid - step, mid, mid + step)
            }
        }
        val largest = steps.last()
        return GraphAxis(0, largest, largest * 2)
    }

    /**
     * Classifies a reading against the limits in force when it was recorded, using
     * the same at-or-beyond boundaries as alarm entry. Independent of alarm state,
     * so a silenced or unsounded excursion is still marked.
     */
    fun classify(settings: ProbeSettings, centiC: Int): Excursion = when {
        settings.highC != null && centiC >= settings.highC -> Excursion.HIGH
        settings.lowC != null && centiC <= settings.lowC -> Excursion.LOW
        else -> Excursion.NONE
    }

    /**
     * Thins long excursions so they do not become a solid bar. Each run keeps its
     * first point, its most extreme point, and one point per [MARKER_SPACING] slots.
     */
    fun markers(values: List<Int?>, excursions: List<Excursion>): List<GraphMarker> {
        val kept = sortedSetOf<Int>()
        var index = 0
        while (index < excursions.size) {
            val kind = excursions[index]
            if (kind == Excursion.NONE || values.getOrNull(index) == null) {
                index += 1
                continue
            }
            val start = index
            var extreme = index
            while (index < excursions.size && excursions[index] == kind && values.getOrNull(index) != null) {
                val value = values[index]!!
                val best = values[extreme]!!
                if ((kind == Excursion.HIGH && value > best) || (kind == Excursion.LOW && value < best)) {
                    extreme = index
                }
                if ((index - start) % MARKER_SPACING == 0) kept += index
                index += 1
            }
            kept += extreme
        }
        return kept.map { GraphMarker(it, excursions[it]) }
    }

    /**
     * Finds reception gaps, including one running up to "now". Time before the
     * first sample is not a gap: monitoring simply had not started.
     */
    fun gaps(values: List<Int?>): List<GraphGap> {
        val result = mutableListOf<GraphGap>()
        var previous = -1
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            if (previous >= 0 && !SparklinePolicy.connects(previous, index)) {
                result += GraphGap(previous, index)
            }
            previous = index
        }
        val now = values.size - 1
        if (previous in 0 until now && !SparklinePolicy.connects(previous, now)) {
            result += GraphGap(previous, now)
        }
        return result
    }
}
