package com.dim8labs.btthermometer.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Host/JVM tests for notification operation planning. */
class AlertReconcilerTest {
    private val temperature = AlertKey.Temperature(0)
    private val missing = AlertKey.MissingProbe(1)
    private val reconciler = AlertReconciler()

    @Test
    fun `new alert posts once and an unchanged alert is not reposted`() {
        val desired = listOf(DesiredAlert(missing, 1))
        assertEquals(AlertPlan(emptyList(), listOf(missing)), reconciler.reconcile(desired))
        assertEquals(AlertPlan(emptyList(), emptyList()), reconciler.reconcile(desired))
    }

    @Test
    fun `new identity under the same key posts again`() {
        reconciler.reconcile(listOf(DesiredAlert(temperature, 1)))
        assertEquals(
            AlertPlan(emptyList(), listOf(temperature)),
            reconciler.reconcile(listOf(DesiredAlert(temperature, 2))),
        )
    }

    @Test
    fun `ending one alert does not repost a remaining alert`() {
        reconciler.reconcile(listOf(DesiredAlert(temperature, 1), DesiredAlert(missing, 1)))
        assertEquals(
            AlertPlan(listOf(missing), emptyList()),
            reconciler.reconcile(listOf(DesiredAlert(temperature, 1))),
        )
    }

    @Test
    fun `ending the last alert only cancels`() {
        reconciler.reconcile(listOf(DesiredAlert(missing, 1)))
        assertEquals(AlertPlan(listOf(missing), emptyList()), reconciler.reconcile(emptyList()))
    }

    @Test
    fun `replacement in one update does not post twice`() {
        reconciler.reconcile(listOf(DesiredAlert(temperature, 1)))
        assertEquals(
            AlertPlan(listOf(temperature), listOf(missing)),
            reconciler.reconcile(listOf(DesiredAlert(missing, 1))),
        )
    }
}
