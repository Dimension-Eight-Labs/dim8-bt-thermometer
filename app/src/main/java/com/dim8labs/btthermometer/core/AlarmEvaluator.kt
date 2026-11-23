package com.dim8labs.btthermometer.core

enum class AlarmLevel { NORMAL, LOW, HIGH }

data class ProbeAlarmState(
    val level: AlarmLevel = AlarmLevel.NORMAL,
    val acknowledged: Boolean = false,
    /** False while an episode has returned inside its limit but has not re-armed. */
    val beyondLimit: Boolean = true,
) {
    val audible: Boolean get() = level != AlarmLevel.NORMAL && beyondLimit && !acknowledged
}

/**
 * Edge-triggered alarm state machine for one probe.
 *
 * - NORMAL enters HIGH at or above high, or LOW at or below low.
 * - Owner rule: an alarm sounds only while the reading is beyond its limit. It
 *   stops on return inside the limit and resumes if an unsilenced episode
 *   crosses the limit again.
 * - HIGH re-arms only below `high - hysteresis`. LOW re-arms only above
 *   `low + hysteresis`. Until then, a silenced alarm stays silent, so boundary
 *   noise cannot restart a sound the user already acknowledged.
 * - A direct LOW/HIGH crossing is a new, unacknowledged episode.
 * - Callers pass only fresh, valid readings. Stale data never reaches this logic.
 */
object AlarmEvaluator {
    fun evaluate(state: ProbeAlarmState, config: ProbeSettings, temperatureC: Int): ProbeAlarmState {
        val high = config.highC
        val low = config.lowC
        return when (state.level) {
            AlarmLevel.NORMAL -> enter(config, temperatureC)
            AlarmLevel.HIGH -> when {
                high == null -> enter(config, temperatureC)
                low != null && temperatureC <= low -> ProbeAlarmState(AlarmLevel.LOW)
                temperatureC < high - config.hysteresisC -> ProbeAlarmState()
                else -> state.copy(beyondLimit = temperatureC >= high)
            }
            AlarmLevel.LOW -> when {
                low == null -> enter(config, temperatureC)
                high != null && temperatureC >= high -> ProbeAlarmState(AlarmLevel.HIGH)
                temperatureC > low + config.hysteresisC -> ProbeAlarmState()
                else -> state.copy(beyondLimit = temperatureC <= low)
            }
        }
    }

    /**
     * Applies a settings edit. Changing the threshold behind an active episode
     * ends it, and a current reading beyond the new threshold starts a new
     * episode immediately (docs/DESIGN.md). Unrelated edits keep an
     * acknowledgement. With no fresh reading, nothing new is evaluated.
     */
    fun onConfigChanged(
        state: ProbeAlarmState,
        old: ProbeSettings,
        new: ProbeSettings,
        freshTemperatureC: Int?,
    ): ProbeAlarmState {
        val activeThresholdChanged = when (state.level) {
            AlarmLevel.HIGH -> old.highC != new.highC
            AlarmLevel.LOW -> old.lowC != new.lowC
            AlarmLevel.NORMAL -> false
        }
        val base = if (activeThresholdChanged) ProbeAlarmState() else state
        return if (freshTemperatureC == null) base else evaluate(base, new, freshTemperatureC)
    }

    fun acknowledge(state: ProbeAlarmState): ProbeAlarmState =
        if (state.level == AlarmLevel.NORMAL) state else state.copy(acknowledged = true)

    private fun enter(config: ProbeSettings, temperatureC: Int): ProbeAlarmState {
        val high = config.highC
        val low = config.lowC
        return when {
            high != null && temperatureC >= high -> ProbeAlarmState(AlarmLevel.HIGH)
            low != null && temperatureC <= low -> ProbeAlarmState(AlarmLevel.LOW)
            else -> ProbeAlarmState()
        }
    }
}
