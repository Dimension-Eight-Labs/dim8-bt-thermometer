package com.dim8labs.btthermometer.core

/** Identifies one independently silenced audible alert. */
sealed interface AlertKey {
    data class Temperature(val probe: Int) : AlertKey
    data class MissingProbe(val probe: Int) : AlertKey
    data object SignalLoss : AlertKey
}

/** [identity] changes when an alert must sound again under the same key. */
data class DesiredAlert(val key: AlertKey, val identity: Int)

data class AlertPlan(val cancel: List<AlertKey>, val post: List<AlertKey>)

/**
 * Converts the desired audible alerts into the smallest set of notification
 * operations.
 *
 * - An unchanged alert is never reposted, so it does not interrupt the user again.
 * - Notifications are visual only. [AlarmSoundController] owns the sound, so an
 *   alert ending needs no repost to keep another alarm audible.
 */
class AlertReconciler {
    private var posted: Map<AlertKey, Int> = emptyMap()

    fun reconcile(desired: List<DesiredAlert>): AlertPlan {
        val next = desired.associate { it.key to it.identity }
        val cancel = posted.keys.filter { it !in next }
        val changed = desired.filter { posted[it.key] != it.identity }.map { it.key }
        posted = next
        return AlertPlan(cancel, changed)
    }
}
