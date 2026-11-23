package com.dim8labs.btthermometer.ui

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.dim8labs.btthermometer.MonitorRuntime
import com.dim8labs.btthermometer.R
import com.dim8labs.btthermometer.ThermometerApp
import com.dim8labs.btthermometer.core.AlarmLevel
import com.dim8labs.btthermometer.core.AppSnapshot
import com.dim8labs.btthermometer.core.MonitorState
import com.dim8labs.btthermometer.core.ProbeStatus

/**
 * Full-screen alarm page shown over the lock screen.
 *
 * Android 10 hides notification action buttons on the lock screen until the
 * notification is expanded, so physical testing found no direct way to silence
 * a locked phone. This page does not dismiss the keyguard and exposes only
 * alarm state and Silence. It closes when no probe or signal alarm is audible.
 * Leaving it without pressing Silence does not acknowledge the alarm.
 *
 * Rows share the screen equally, so all three possible alarms keep their Silence
 * buttons visible. A lone alarm uses the extra room for a larger reading and button.
 */
class AlarmActivity : Activity() {
    private lateinit var runtime: MonitorRuntime
    private lateinit var rows: List<Row>
    private lateinit var signalRow: View
    private lateinit var signalTitle: TextView
    private lateinit var signalDetail: TextView
    private lateinit var signalSilence: Button
    private val listener: (AppSnapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        runtime = (application as ThermometerApp).runtime
        rows = listOf(
            Row(findViewById(R.id.alarm_probe_1), 0),
            Row(findViewById(R.id.alarm_probe_2), 1),
        )
        signalRow = findViewById(R.id.alarm_signal)
        signalTitle = signalRow.findViewById(R.id.alarm_title)
        signalDetail = signalRow.findViewById(R.id.alarm_limit)
        signalRow.findViewById<View>(R.id.alarm_temperature).visibility = View.GONE
        signalSilence = signalRow.findViewById<Button>(R.id.alarm_silence).apply {
            text = "Silence signal alarm"
            setOnClickListener { runtime.post { it.acknowledgeWarning() } }
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        runtime.addListener(listener)
    }

    override fun onStop() {
        isVisible = false
        runtime.removeListener(listener)
        super.onStop()
    }

    private fun render(snapshot: AppSnapshot) {
        val probeAlarms = snapshot.probes.count { it.alarmAudible }
        if (probeAlarms == 0 && !snapshot.warningAudible) {
            finish()
            return
        }
        val single = probeAlarms + (if (snapshot.warningAudible) 1 else 0) == 1
        signalRow.visibility = if (snapshot.warningAudible) View.VISIBLE else View.GONE
        signalTitle.text =
            if (snapshot.state == MonitorState.STALE) "Thermometer signal lost" else "Monitoring interrupted"
        signalDetail.text = Format.monitorStatus(snapshot)
        setButtonHeight(signalSilence, single)
        rows.forEach { it.render(snapshot, single) }
    }

    private fun setButtonHeight(button: Button, single: Boolean) {
        val dp = if (single) SINGLE_BUTTON_DP else SHARED_BUTTON_DP
        val height = (dp * resources.displayMetrics.density).toInt()
        val params = button.layoutParams
        if (params.height == height) return
        params.height = height
        button.layoutParams = params
    }

    private inner class Row(private val root: View, private val index: Int) {
        private val title = root.findViewById<TextView>(R.id.alarm_title)
        private val temperature = root.findViewById<TextView>(R.id.alarm_temperature)
        private val limit = root.findViewById<TextView>(R.id.alarm_limit)
        private val silence = root.findViewById<Button>(R.id.alarm_silence)

        init {
            silence.text = "Silence probe ${index + 1}"
            silence.setOnClickListener { runtime.post { it.acknowledgeProbe(index) } }
        }

        fun render(snapshot: AppSnapshot, single: Boolean) {
            val probe = snapshot.probes[index]
            root.visibility = if (probe.alarmAudible) View.VISIBLE else View.GONE
            if (!probe.alarmAudible) return
            setButtonHeight(silence, single)
            temperature.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (single) SINGLE_READING_SP else SHARED_READING_SP)
            if (probe.status == ProbeStatus.NO_PROBE) {
                title.text = "Probe ${index + 1} MISSING"
                temperature.text = "—"
                limit.text = if (probe.recovering) "Probe reconnecting…" else "Probe not detected"
                return
            }
            val fahrenheit = snapshot.settings.displayFahrenheit
            val config = snapshot.settings.probes[index]
            val high = probe.alarmLevel == AlarmLevel.HIGH
            title.text = "Probe ${index + 1} ${if (high) "▲ HIGH" else "▼ LOW"}"
            temperature.text = probe.temperatureC?.let { Format.prominentTemperature(it, fahrenheit) } ?: "—"
            limit.text = if (high) {
                "High alarm ${Format.limit(config.highC, fahrenheit)}"
            } else {
                "Low alarm ${Format.limit(config.lowC, fahrenheit)}"
            }
        }
    }

    companion object {
        /** Read on the monitor thread when posting alarms. */
        @Volatile
        var isVisible = false
            private set

        private const val SINGLE_BUTTON_DP = 104
        private const val SHARED_BUTTON_DP = 64
        private const val SINGLE_READING_SP = 76f
        private const val SHARED_READING_SP = 44f
    }
}
