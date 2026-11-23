package com.dim8labs.btthermometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEvaluatorTest {
    private val config = ProbeSettings(lowC = 5000, highC = 7000, hysteresisC = 100)

    private fun run(vararg temps: Int, start: ProbeAlarmState = ProbeAlarmState()) =
        temps.fold(start) { state, temp -> AlarmEvaluator.evaluate(state, config, temp) }

    @Test
    fun `exact boundaries enter alarms`() {
        assertEquals(AlarmLevel.NORMAL, run(6999).level)
        assertEquals(AlarmLevel.HIGH, run(7000).level)
        assertEquals(AlarmLevel.NORMAL, run(5001).level)
        assertEquals(AlarmLevel.LOW, run(5000).level)
    }

    @Test
    fun `high re-arms only below hysteresis boundary`() {
        assertEquals(AlarmLevel.HIGH, run(7000, 6900).level)
        assertEquals(AlarmLevel.NORMAL, run(7000, 6899).level)
    }

    @Test
    fun `low re-arms only above hysteresis boundary`() {
        assertEquals(AlarmLevel.LOW, run(5000, 5100).level)
        assertEquals(AlarmLevel.NORMAL, run(5000, 5101).level)
    }

    @Test
    fun `acknowledgement persists through violation and clears after re-arm`() {
        val acknowledged = AlarmEvaluator.acknowledge(run(7000))
        assertFalse(acknowledged.audible)
        val continued = AlarmEvaluator.evaluate(acknowledged, config, 7200)
        assertEquals(AlarmLevel.HIGH, continued.level)
        assertTrue(continued.acknowledged)
        val again = run(6800, 7000, start = continued)
        assertTrue(again.audible)
    }

    @Test
    fun `high sound follows the limit and resumes if unsilenced`() {
        assertTrue(run(7000).audible)
        assertFalse(run(7000, 6999).audible)
        assertTrue(run(7000, 6999, 7000).audible)
    }

    @Test
    fun `low sound follows the limit and resumes if unsilenced`() {
        assertTrue(run(5000).audible)
        assertFalse(run(5000, 5001).audible)
        assertTrue(run(5000, 5001, 5000).audible)
    }

    @Test
    fun `silenced alarm stays silent through boundary noise until re-armed`() {
        val silenced = AlarmEvaluator.acknowledge(run(7000))
        assertFalse(run(6999, 7000, start = silenced).audible)
        assertTrue(run(6899, 7000, start = silenced).audible)
    }

    @Test
    fun `falling from high to below low sounds as a new alarm`() {
        val silenced = AlarmEvaluator.acknowledge(run(7000))
        val low = run(6000, 5000, start = silenced)
        assertEquals(AlarmLevel.LOW, low.level)
        assertTrue(low.audible)
    }

    @Test
    fun `direct crossing is a new unacknowledged episode`() {
        val acknowledged = AlarmEvaluator.acknowledge(run(7000))
        val low = AlarmEvaluator.evaluate(acknowledged, config, 4900)
        assertEquals(AlarmLevel.LOW, low.level)
        assertTrue(low.audible)
    }

    @Test
    fun `disabled thresholds never alarm`() {
        val state = AlarmEvaluator.evaluate(ProbeAlarmState(), ProbeSettings(), 30000)
        assertEquals(AlarmLevel.NORMAL, state.level)
    }

    @Test
    fun `changing active threshold evaluates as a new episode`() {
        val acknowledged = AlarmEvaluator.acknowledge(run(7200))
        val next = AlarmEvaluator.onConfigChanged(acknowledged, config, config.copy(highC = 7100), 7200)
        assertEquals(AlarmLevel.HIGH, next.level)
        assertTrue(next.audible)
    }

    @Test
    fun `unrelated edit keeps acknowledgement`() {
        val acknowledged = AlarmEvaluator.acknowledge(run(7200))
        val next = AlarmEvaluator.onConfigChanged(acknowledged, config, config.copy(targetC = 6500), 7200)
        assertTrue(next.acknowledged)
    }

    @Test
    fun `settings validation requires low below high and positive hysteresis`() {
        assertFalse(ProbeSettings(lowC = 7000, highC = 7000).isValid)
        assertFalse(ProbeSettings(hysteresisC = 0).isValid)
        assertTrue(ProbeSettings(lowC = 6999, highC = 7000).isValid)
    }
}
