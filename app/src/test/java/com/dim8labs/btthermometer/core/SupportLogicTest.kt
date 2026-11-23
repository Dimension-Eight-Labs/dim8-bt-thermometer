package com.dim8labs.btthermometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLogicTest {
    @Test
    fun `backoff is bounded and resets`() {
        val backoff = RecoveryBackoff()
        assertEquals(
            listOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L),
            List(6) { backoff.next() },
        )
        backoff.reset()
        assertEquals(10_000L, backoff.next())
    }

    @Test
    fun `identity uses name first and service UUID when unnamed`() {
        assertTrue(ReadingValidator.isH5182("GVH5182_ABCD", false))
        assertTrue(ReadingValidator.isH5182(null, true))
        assertFalse(ReadingValidator.isH5182("GVH5184_ABCD", true))
        assertFalse(ReadingValidator.isH5182(null, false))
    }

    @Test
    fun `validator enforces range and absence`() {
        assertEquals(ProbeSample.Absent, ReadingValidator.validate(DecodedWord.Unavailable))
        assertEquals(ProbeSample.Rejected, ReadingValidator.validate(DecodedWord.Invalid(0x8000)))
        assertEquals(ProbeSample.Valid(30_000), ReadingValidator.validate(DecodedWord.CentiCelsius(30_000)))
        assertEquals(ProbeSample.Rejected, ReadingValidator.validate(DecodedWord.CentiCelsius(30_001)))
    }

    @Test
    fun `unit conversion handles reference points`() {
        assertEquals(212.0, Units.toDisplay(10_000, true), 0.001)
        assertEquals(10_000, Units.fromDisplay(212.0, true))
        assertEquals(1.8, Units.deltaToDisplay(100, true), 0.001)
        assertEquals(100, Units.deltaFromDisplay(1.8, true))
    }

    @Test
    fun `color clamps and has no target fallback`() {
        assertEquals(TemperatureColor.NEUTRAL, TemperatureColor.forTemperature(5000, null, 300))
        assertEquals(0xFF438ED4.toInt(), TemperatureColor.forTemperature(0, 5000, 300))
        assertEquals(0xFFF2BE29.toInt(), TemperatureColor.forTemperature(5000, 5000, 300))
        assertEquals(0xFFD84A43.toInt(), TemperatureColor.forTemperature(9000, 5000, 300))
    }

    @Test
    fun `sparkline bridges packet jitter but breaks at stale timeout`() {
        assertTrue(SparklinePolicy.connects(10, 11))
        assertTrue(SparklinePolicy.connects(10, 16)) // 5 empty buckets: under 60 s.
        assertFalse(SparklinePolicy.connects(10, 17)) // 6 empty buckets: 60 s.
    }

    @Test
    fun `history ignores repeat bucket and old slot reuse`() {
        val history = HistoryBuffer(3)
        assertTrue(history.add(10, 1))
        assertFalse(history.add(10, 2))
        assertEquals(listOf(null, null, 1), history.window(10))
        history.add(13, 4)
        assertEquals(listOf(null, null, 4), history.window(13))
    }
}
