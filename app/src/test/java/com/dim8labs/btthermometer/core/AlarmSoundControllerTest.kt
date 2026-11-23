package com.dim8labs.btthermometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host/JVM tests for alarm audio ownership. They use a fake output, so they do
 * not prove Android audio behavior or audibility on the phone.
 */
class AlarmSoundControllerTest {
    private class FakeOutput : AlarmSoundOutput {
        override var isPlaying = false
        var starts = 0
        var stops = 0

        /** Mirrors the real output: a restart replaces the stream, never adds one. */
        override fun start() {
            starts += 1
            isPlaying = true
        }

        override fun stop() {
            stops += 1
            isPlaying = false
        }
    }

    private val output = FakeOutput()
    private val sound = AlarmSoundController(output)
    private val reconciler = AlertReconciler()
    private val probeOne = DesiredAlert(AlertKey.Temperature(0), 1)
    private val probeTwoMissing = DesiredAlert(AlertKey.MissingProbe(1), 1)
    private val signal = DesiredAlert(AlertKey.SignalLoss, 0)

    /** Applies alert state the way the Android adapter does. */
    private fun render(vararg desired: DesiredAlert): AlertPlan {
        val plan = reconciler.reconcile(desired.toList())
        sound.update(desired.isNotEmpty())
        return plan
    }

    @Test
    fun `first alert starts one sound and unchanged state does not restart it`() {
        render(probeOne)
        render(probeOne)
        sound.maintain()
        assertEquals(1, output.starts)
        assertTrue(output.isPlaying)
    }

    @Test
    fun `notification presentation has no authority over active sound`() {
        render(probeOne)
        // A swipe or shade event changes no alarm state, so no render occurs.
        repeat(5) { sound.maintain() }
        assertTrue(output.isPlaying)
        assertEquals(0, output.stops)
        assertEquals(1, output.starts)
    }

    @Test
    fun `sound stopped externally restarts while the alarm remains active`() {
        render(probeOne)
        output.isPlaying = false
        sound.maintain()
        assertTrue(output.isPlaying)
        assertEquals(2, output.starts)
    }

    @Test
    fun `overlapping alarms share one sound`() {
        render(probeOne)
        render(probeOne, probeTwoMissing)
        render(probeOne, probeTwoMissing, signal)
        assertEquals(1, output.starts)
    }

    @Test
    fun `silencing one overlapping alarm leaves sound running for the other`() {
        render(probeOne, probeTwoMissing)
        val plan = render(probeOne)
        assertEquals(listOf(probeTwoMissing.key), plan.cancel)
        assertTrue(output.isPlaying)
        assertEquals(0, output.stops)
        assertEquals(1, output.starts)
    }

    @Test
    fun `sound stops only when the last alert ends`() {
        render(probeOne, probeTwoMissing)
        render(probeTwoMissing)
        assertTrue(output.isPlaying)
        render()
        assertFalse(output.isPlaying)
        assertEquals(1, output.stops)
    }

    @Test
    fun `no restart occurs after alarms end`() {
        render(probeOne)
        render()
        sound.maintain()
        assertFalse(output.isPlaying)
        assertEquals(1, output.starts)
    }

    @Test
    fun `a later alarm sounds again`() {
        render(probeOne)
        render()
        render(signal)
        assertTrue(output.isPlaying)
        assertEquals(2, output.starts)
    }
}
