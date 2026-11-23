package com.dim8labs.btthermometer.core

import com.dim8labs.btthermometer.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host/JVM tests for display logic. They do not prove on-screen rendering. */
class PresentationLogicTest {
    private fun centiFromF(fahrenheit: Double) = Units.fromDisplay(fahrenheit, true)

    // ---- Temperature color ------------------------------------------------------

    @Test
    fun `approved colors appear at their scale positions`() {
        val target = 5000
        val band = 300
        assertEquals(TemperatureColor.COLD, TemperatureColor.forTemperature(target - 4 * band, target, band))
        assertEquals(TemperatureColor.BELOW, TemperatureColor.forTemperature(target - 2 * band, target, band))
        assertEquals(TemperatureColor.AT_TARGET, TemperatureColor.forTemperature(target, target, band))
        assertEquals(TemperatureColor.ABOVE, TemperatureColor.forTemperature(target + band, target, band))
        assertEquals(TemperatureColor.HOT, TemperatureColor.forTemperature(target + 2 * band, target, band))
    }

    @Test
    fun `approved palette values are exact`() {
        assertEquals(0xFF438ED4.toInt(), TemperatureColor.COLD)
        assertEquals(0xFF36B5A8.toInt(), TemperatureColor.BELOW)
        assertEquals(0xFFF2BE29.toInt(), TemperatureColor.AT_TARGET)
        assertEquals(0xFFE78533.toInt(), TemperatureColor.ABOVE)
        assertEquals(0xFFD84A43.toInt(), TemperatureColor.HOT)
    }

    @Test
    fun `color interpolates midway between stops`() {
        val color = TemperatureColor.forTemperature(5150, 5000, 300)
        // Midway from at-target to above: F2BE29 and E78533.
        assertEquals(0xFFEDA22E.toInt(), color)
    }

    @Test
    fun `adjacent degrees never jump in color`() {
        var previous = TemperatureColor.forTemperature(3000, 5000, 300)
        for (temperature in 3001..7000) {
            val color = TemperatureColor.forTemperature(temperature, 5000, 300)
            for (shift in intArrayOf(0, 8, 16)) {
                val change = kotlin.math.abs(((color ushr shift) and 0xff) - ((previous ushr shift) and 0xff))
                assertTrue("Jump at $temperature", change <= 2)
            }
            previous = color
        }
    }

    @Test
    fun `color band widens the scale`() {
        assertNotEquals(
            TemperatureColor.forTemperature(5200, 5000, 200),
            TemperatureColor.forTemperature(5200, 5000, 600),
        )
    }

    @Test
    fun `target centers the scale even when limits exist`() {
        assertEquals(5000, TemperatureColor.scaleCenter(ProbeSettings(targetC = 5000, lowC = 1000, highC = 9000), 300))
    }

    @Test
    fun `limits center the scale when no target is set`() {
        assertEquals(3000, TemperatureColor.scaleCenter(ProbeSettings(lowC = 2000, highC = 4000), 300))
        val lowOnly = TemperatureColor.scaleCenter(ProbeSettings(lowC = 1889), 300)
        assertEquals(TemperatureColor.BELOW, TemperatureColor.forTemperature(1889, lowOnly, 300))
        val highOnly = TemperatureColor.scaleCenter(ProbeSettings(highC = 8000), 300)
        assertEquals(TemperatureColor.HOT, TemperatureColor.forTemperature(8000, highOnly, 300))
    }

    @Test
    fun `no target or limit stays neutral`() {
        val center = TemperatureColor.scaleCenter(ProbeSettings(), 300)
        assertNull(center)
        assertEquals(TemperatureColor.NEUTRAL, TemperatureColor.forTemperature(2000, center, 300))
    }

    // ---- Live graph point --------------------------------------------------------

    private fun view(status: ProbeStatus, temperature: Int?, age: Long?) = ProbeView(
        status = status,
        temperatureC = temperature,
        ageMs = age,
        alarmLevel = AlarmLevel.NORMAL,
        alarmAudible = false,
        history = emptyList(),
    )

    @Test
    fun `only a fresh current reading is drawn live`() {
        assertEquals(2000, HistoryGraph.liveReading(view(ProbeStatus.NORMAL, 2000, 1_000)))
        assertEquals(2000, HistoryGraph.liveReading(view(ProbeStatus.HIGH, 2000, HistoryGraph.LIVE_MAX_AGE_MS)))
        assertNull(HistoryGraph.liveReading(view(ProbeStatus.NORMAL, 2000, HistoryGraph.LIVE_MAX_AGE_MS + 1)))
        assertNull(HistoryGraph.liveReading(view(ProbeStatus.STALE, 2000, 1_000)))
        assertNull(HistoryGraph.liveReading(view(ProbeStatus.NO_PROBE, null, 1_000)))
        assertNull(HistoryGraph.liveReading(view(ProbeStatus.NOT_IN_USE, null, null)))
    }

    @Test
    fun `live reading fills only the newest slot and leaves history unchanged`() {
        val history = listOf(1000, null, 1200)
        val none = List(3) { Excursion.NONE }
        assertEquals(listOf(1000, null, 1300), HistoryGraph.withLiveReading(history, none, 1300))
        assertEquals(listOf(1000, null, 1200), history)
        assertEquals(history, HistoryGraph.withLiveReading(history, none, null))
    }

    @Test
    fun `live reading does not move a recorded excursion marker`() {
        val history = listOf(1000, 3500)
        val excursions = listOf(Excursion.NONE, Excursion.HIGH)
        assertEquals(history, HistoryGraph.withLiveReading(history, excursions, 2000))
    }

    // ---- Graph scale ------------------------------------------------------------

    @Test
    fun `no readings produce no axis`() {
        assertNull(HistoryGraph.axis(listOf(null, null), true))
    }

    @Test
    fun `small fluctuation occupies a small part of the axis`() {
        val axis = HistoryGraph.axis(listOf(centiFromF(71.6), centiFromF(73.4)), true)!!
        assertEquals(10, axis.high - axis.low)
        val visible = axis.fraction(73.4) - axis.fraction(71.6)
        assertTrue("Noise fills $visible of the graph", visible <= 0.2)
    }

    @Test
    fun `flat reading stays inside the axis with padding`() {
        val axis = HistoryGraph.axis(listOf(centiFromF(225.0)), true)!!
        assertTrue(axis.low < 225 && axis.high > 225)
    }

    @Test
    fun `axis labels are evenly spaced multiples of the step`() {
        val axis = HistoryGraph.axis(listOf(centiFromF(90.0), centiFromF(240.0)), true)!!
        assertEquals(axis.mid - axis.low, axis.high - axis.mid)
        assertEquals(0, axis.low % (axis.mid - axis.low))
        assertTrue(axis.low < 90 && axis.high > 240)
    }

    @Test
    fun `axis contains readings at every plausible range`() {
        for (low in 0..30_000 step 1_500) {
            for (high in low..30_000 step 2_500) {
                listOf(true, false).forEach { fahrenheit ->
                    val axis = HistoryGraph.axis(listOf(low, high), fahrenheit)!!
                    assertTrue(axis.fraction(Units.toDisplay(low, fahrenheit)) >= 0.0)
                    assertTrue(axis.fraction(Units.toDisplay(high, fahrenheit)) <= 1.0)
                    assertTrue(axis.low >= 0)
                }
            }
        }
    }

    // ---- Excursion markers ------------------------------------------------------

    @Test
    fun `classification uses alarm boundaries`() {
        val config = ProbeSettings(lowC = 1000, highC = 3000)
        assertEquals(Excursion.HIGH, HistoryGraph.classify(config, 3000))
        assertEquals(Excursion.LOW, HistoryGraph.classify(config, 1000))
        assertEquals(Excursion.NONE, HistoryGraph.classify(config, 2000))
        assertEquals(Excursion.NONE, HistoryGraph.classify(ProbeSettings(), 9000))
    }

    @Test
    fun `long excursion is thinned but keeps its first and extreme points`() {
        val values = List(30) { 3000 + it * (30 - it) }
        val excursions = List(30) { Excursion.HIGH }
        val markers = HistoryGraph.markers(values, excursions).map { it.index }
        assertTrue(0 in markers)
        assertTrue(values.indexOf(values.max()) in markers)
        assertTrue(markers.size <= 7)
    }

    @Test
    fun `high and low runs are marked separately`() {
        val values = listOf(3500, 500, null, 2000)
        val excursions = listOf(Excursion.HIGH, Excursion.LOW, Excursion.NONE, Excursion.NONE)
        assertEquals(
            listOf(GraphMarker(0, Excursion.HIGH), GraphMarker(1, Excursion.LOW)),
            HistoryGraph.markers(values, excursions),
        )
    }

    @Test
    fun `markers leave with their samples`() {
        val history = HistoryBuffer(3)
        history.add(10, 3500, Excursion.HIGH)
        assertEquals(1, history.excursionWindow(12).count { it == Excursion.HIGH })
        assertEquals(0, history.excursionWindow(13).count { it == Excursion.HIGH })
    }

    // ---- Gaps -------------------------------------------------------------------

    @Test
    fun `reception gap is reported but normal spacing is not`() {
        val values = MutableList<Int?>(30) { null }
        values[2] = 2000
        values[6] = 2000 // 3 empty buckets: normal packet spacing.
        values[20] = 2000 // 13 empty buckets: reception gap.
        values[29] = 2000 // 8 empty buckets: reception gap.
        assertEquals(listOf(GraphGap(6, 20), GraphGap(20, 29)), HistoryGraph.gaps(values))
    }

    @Test
    fun `gap running up to now is reported and leading time is not`() {
        val values = MutableList<Int?>(30) { null }
        values[10] = 2000
        assertEquals(listOf(GraphGap(10, 29)), HistoryGraph.gaps(values))
    }

    // ---- Readout ----------------------------------------------------------------

    @Test
    fun `prominent Fahrenheit reading is whole degrees`() {
        assertEquals("72°F", Format.prominentTemperature(2220, true))
        assertEquals("225°F", Format.prominentTemperature(10_722, true))
        assertEquals("22.2°C", Format.prominentTemperature(2220, false))
    }

    @Test
    fun `every probe state has a word`() {
        assertEquals("MISSING", Format.probeStatus(ProbeStatus.NO_PROBE))
        assertEquals("RECONNECTING", Format.probeStatus(ProbeStatus.NO_PROBE, recovering = true))
        assertEquals("NOT IN USE", Format.probeStatus(ProbeStatus.NOT_IN_USE))
        assertEquals("STALE", Format.probeStatus(ProbeStatus.STALE))
        assertEquals("WAITING", Format.probeStatus(ProbeStatus.WAITING))
        assertEquals("NORMAL", Format.probeStatus(ProbeStatus.NORMAL))
        assertEquals("▲ HIGH", Format.probeStatus(ProbeStatus.HIGH))
        assertEquals("▼ LOW", Format.probeStatus(ProbeStatus.LOW))
    }
}
