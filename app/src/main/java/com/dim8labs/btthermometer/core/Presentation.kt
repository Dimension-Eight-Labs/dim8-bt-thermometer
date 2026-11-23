package com.dim8labs.btthermometer.core

import kotlin.math.roundToInt

/** Unit conversion belongs at presentation and input boundaries. */
object Units {
    fun toDisplay(centiC: Int, fahrenheit: Boolean): Double =
        if (fahrenheit) centiC / 100.0 * 9.0 / 5.0 + 32.0 else centiC / 100.0

    fun fromDisplay(value: Double, fahrenheit: Boolean): Int =
        ((if (fahrenheit) (value - 32.0) * 5.0 / 9.0 else value) * 100.0).roundToInt()

    /** Converts a temperature difference, which has no 32-degree offset. */
    fun deltaToDisplay(centiC: Int, fahrenheit: Boolean): Double =
        if (fahrenheit) centiC / 100.0 * 9.0 / 5.0 else centiC / 100.0

    fun deltaFromDisplay(value: Double, fahrenheit: Boolean): Int =
        ((if (fahrenheit) value * 5.0 / 9.0 else value) * 100.0).roundToInt()
}

/**
 * Sparkline continuity rule. Measured H5182 packets arrive up to 16 s apart, so
 * an empty 10 s bucket is normal. Only a gap reaching the stale timeout is a
 * real reception loss and breaks the line. This affects drawing only.
 */
object SparklinePolicy {
    private val MAX_MISSING_BUCKETS =
        (MonitoringPolicy.STALE_TIMEOUT_MS / MonitoringPolicy.HISTORY_BUCKET_MS).toInt()

    fun connects(previousIndex: Int, index: Int): Boolean =
        index - previousIndex - 1 < MAX_MISSING_BUCKETS
}

/**
 * Display-only heat-map color centered on the probe's target. It never affects
 * alarms. These are the owner-approved temperature colors, deliberately separate
 * from the DIM8 brand palette in res/values/colors.xml.
 *
 * Stops are measured in multiples of the user's color band. The scale reaches
 * "hot" sooner than "cold" because overshooting a target matters more in cooking
 * than being far below it during warm-up.
 */
object TemperatureColor {
    /** Primary text, used when a probe has no target or limit to center the scale. */
    const val NEUTRAL = 0xFFECEEEC.toInt()
    const val COLD = 0xFF438ED4.toInt()
    const val BELOW = 0xFF36B5A8.toInt()
    const val AT_TARGET = 0xFFF2BE29.toInt()
    const val ABOVE = 0xFFE78533.toInt()
    const val HOT = 0xFFD84A43.toInt()

    private const val COLD_POSITION = -4.0
    private const val BELOW_POSITION = -2.0
    private const val ABOVE_POSITION = 1.0
    private const val HOT_POSITION = 2.0

    /**
     * The temperature at the center of the color scale. The target is used when
     * set. Owner testing found a probe with only alarm limits looked uncolored, so
     * limits then anchor the scale: midway between both, or with a single low limit
     * at "below target" or a single high limit at "hot". With neither there is no
     * meaningful reference, so the reading stays neutral.
     */
    fun scaleCenter(settings: ProbeSettings, bandC: Int): Int? {
        val low = settings.lowC
        val high = settings.highC
        return when {
            settings.targetC != null -> settings.targetC
            low != null && high != null -> (low + high) / 2
            low != null -> low - (BELOW_POSITION * bandC).roundToInt()
            high != null -> high - (HOT_POSITION * bandC).roundToInt()
            else -> null
        }
    }

    fun forTemperature(temperatureC: Int, centerC: Int?, bandC: Int): Int {
        if (centerC == null || bandC <= 0) return NEUTRAL
        val position = (temperatureC - centerC).toDouble() / bandC
        return when {
            position <= COLD_POSITION -> COLD
            position <= BELOW_POSITION ->
                blend(COLD, BELOW, (position - COLD_POSITION) / (BELOW_POSITION - COLD_POSITION))
            position <= 0.0 -> blend(BELOW, AT_TARGET, (position - BELOW_POSITION) / -BELOW_POSITION)
            position <= ABOVE_POSITION -> blend(AT_TARGET, ABOVE, position / ABOVE_POSITION)
            position <= HOT_POSITION ->
                blend(ABOVE, HOT, (position - ABOVE_POSITION) / (HOT_POSITION - ABOVE_POSITION))
            else -> HOT
        }
    }

    private fun blend(from: Int, to: Int, fraction: Double): Int {
        fun channel(shift: Int): Int {
            val start = (from ushr shift) and 0xff
            val end = (to ushr shift) and 0xff
            return (start + (end - start) * fraction).roundToInt() shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }
}
