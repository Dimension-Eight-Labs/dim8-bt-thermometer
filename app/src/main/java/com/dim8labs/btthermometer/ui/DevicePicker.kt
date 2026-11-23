package com.dim8labs.btthermometer.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.dim8labs.btthermometer.MonitorRuntime
import com.dim8labs.btthermometer.core.SelectedDevice

/** Explicit selection only: the app never auto-switches to a stronger signal. */
object DevicePicker {
    fun show(activity: Activity, runtime: MonitorRuntime) {
        val snapshot = runtime.latest ?: return
        val candidates = snapshot.candidates
        if (candidates.isEmpty()) {
            val hint = if (snapshot.state == com.dim8labs.btthermometer.core.MonitorState.STOPPED) {
                "Start monitoring to search for thermometers."
            } else {
                "No H5182 found yet. Make sure it is powered on and nearby."
            }
            Toast.makeText(activity, hint, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Select thermometer")
            .setItems(candidates.map(::label).toTypedArray()) { _, which ->
                val device = candidates[which]
                runtime.post { it.selectDevice(device) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows only the end of the address, enough to distinguish nearby devices. */
    fun label(device: SelectedDevice): String = "${device.name} · …${device.address.takeLast(5)}"
}
