package com.dim8labs.btthermometer.ui

import com.dim8labs.btthermometer.core.AppSnapshot
import com.dim8labs.btthermometer.core.MonitorState
import com.dim8labs.btthermometer.core.ProbeStatus
import com.dim8labs.btthermometer.core.ProbeView
import com.dim8labs.btthermometer.core.Units
import java.util.Locale
import kotlin.math.roundToInt

/** Plain-language presentation strings shared by the UI and notifications. */
object Format {
    fun unit(fahrenheit: Boolean) = if (fahrenheit) "°F" else "°C"

    fun temperature(centiC: Int, fahrenheit: Boolean): String =
        String.format(Locale.US, "%.1f %s", Units.toDisplay(centiC, fahrenheit), unit(fahrenheit))

    /**
     * Prominent on-screen reading. Fahrenheit is shown in whole degrees by owner
     * decision. This is display rounding only: alarms and history use the exact value.
     */
    fun prominentTemperature(centiC: Int, fahrenheit: Boolean): String =
        if (fahrenheit) {
            "${Units.toDisplay(centiC, true).roundToInt()}°F"
        } else {
            String.format(Locale.US, "%.1f°C", Units.toDisplay(centiC, false))
        }

    /** Keeps one decimal when needed, so a shown alarm limit is not misleadingly rounded. */
    fun limit(centiC: Int?, fahrenheit: Boolean): String = centiC?.let {
        String.format(Locale.US, "%.1f", Units.toDisplay(it, fahrenheit)).removeSuffix(".0") + "°"
    } ?: "Off"

    fun duration(ms: Long): String {
        val seconds = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    fun age(ms: Long): String {
        val seconds = ms / 1000
        return if (seconds < 120) "${seconds}s" else "${seconds / 60}m"
    }

    fun monitorStatus(snapshot: AppSnapshot): String {
        val age = snapshot.lastPacketAgeMs?.let(::age)
        return when (snapshot.state) {
            MonitorState.STOPPED -> "Monitoring stopped"
            MonitorState.STARTING ->
                if (snapshot.settings.selectedDevice == null) "Searching for H5182…" else "Starting monitor…"
            MonitorState.MONITORING -> "Monitoring · updated ${age ?: "—"} ago"
            MonitorState.STALE -> age?.let { "Signal lost · last reading $it ago" } ?: "Signal lost · no reading yet"
            MonitorState.BLUETOOTH_OFF -> "Bluetooth off"
            MonitorState.PERMISSION_REQUIRED -> "Location permission required for Bluetooth scanning"
            MonitorState.LOCATION_OFF -> "Location is off · required for Bluetooth scanning"
            MonitorState.SCAN_ERROR -> "Bluetooth scan error · retrying"
        }
    }

    fun battery(percent: Int?) = percent?.let { "Battery $it%" } ?: "Battery —"

    /** A word, plus an arrow for high and low, so state never depends on color alone. */
    fun probeStatus(status: ProbeStatus, recovering: Boolean = false) = when (status) {
        ProbeStatus.WAITING -> "WAITING"
        ProbeStatus.NORMAL -> "NORMAL"
        ProbeStatus.LOW -> "▼ LOW"
        ProbeStatus.HIGH -> "▲ HIGH"
        ProbeStatus.STALE -> "STALE"
        ProbeStatus.NO_PROBE -> if (recovering) "RECONNECTING" else "MISSING"
        ProbeStatus.NOT_IN_USE -> "NOT IN USE"
    }

    fun notificationSummary(snapshot: AppSnapshot): String {
        val fahrenheit = snapshot.settings.displayFahrenheit
        return snapshot.probes.mapIndexed { index, probe ->
            "P${index + 1} ${probeSummary(probe, fahrenheit)}"
        }.joinToString(" · ")
    }

    private fun probeSummary(probe: ProbeView, fahrenheit: Boolean): String {
        val value = probe.temperatureC?.let { temperature(it, fahrenheit) } ?: "—"
        return when (probe.status) {
            ProbeStatus.HIGH -> "$value HIGH"
            ProbeStatus.LOW -> "$value LOW"
            ProbeStatus.STALE -> "$value stale"
            ProbeStatus.NOT_IN_USE -> "not in use"
            else -> value
        }
    }
}
