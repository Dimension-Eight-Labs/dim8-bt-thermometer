package com.dim8labs.btthermometer.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.dim8labs.btthermometer.MonitorRuntime
import com.dim8labs.btthermometer.R
import com.dim8labs.btthermometer.ThermometerApp
import com.dim8labs.btthermometer.core.AlarmLevel
import com.dim8labs.btthermometer.core.AppSnapshot
import com.dim8labs.btthermometer.core.HistoryGraph
import com.dim8labs.btthermometer.core.MonitorState
import com.dim8labs.btthermometer.core.ProbeSettings
import com.dim8labs.btthermometer.core.ProbeStatus
import com.dim8labs.btthermometer.core.ProbeView
import com.dim8labs.btthermometer.core.TemperatureColor
import com.dim8labs.btthermometer.core.Units
import com.dim8labs.btthermometer.service.MonitoringService
import java.util.Locale

/** Renders snapshots and sends user intents. It never scans or evaluates alarms. */
class MainActivity : Activity() {
    private lateinit var runtime: MonitorRuntime
    private lateinit var cards: List<ProbeCard>
    private lateinit var monitorStatus: TextView
    private lateinit var deviceStatus: TextView
    private lateinit var silenceWarning: Button
    private lateinit var selectDevice: Button
    private lateinit var timerValue: TextView
    private lateinit var timerToggle: Button
    private lateinit var timerReset: Button
    private lateinit var monitorToggle: Button
    private var latest: AppSnapshot? = null
    private var monitorButtonStopped: Boolean? = null

    private val listener: (AppSnapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        runtime = (application as ThermometerApp).runtime
        monitorStatus = findViewById(R.id.monitor_status)
        deviceStatus = findViewById(R.id.device_status)
        silenceWarning = findViewById(R.id.silence_warning)
        selectDevice = findViewById(R.id.select_device)
        timerValue = findViewById(R.id.timer_value)
        timerToggle = findViewById(R.id.timer_toggle)
        timerReset = findViewById(R.id.timer_reset)
        monitorToggle = findViewById(R.id.monitor_toggle)
        cards = listOf(
            ProbeCard(findViewById(R.id.probe_1), 0),
            ProbeCard(findViewById(R.id.probe_2), 1),
        )

        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        silenceWarning.setOnClickListener { runtime.post { it.acknowledgeWarning() } }
        selectDevice.setOnClickListener { DevicePicker.show(this, runtime) }
        monitorToggle.setOnClickListener { toggleMonitoring() }
        timerToggle.setOnClickListener {
            val running = latest?.timerRunning == true
            runtime.post { if (running) it.stopTimer() else it.startTimer() }
        }
        timerReset.setOnClickListener { confirmTimerReset() }
    }

    override fun onStart() {
        super.onStart()
        runtime.addListener(listener)
    }

    override fun onStop() {
        runtime.removeListener(listener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQUEST_LOCATION && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(MonitoringService.startIntent(this))
        } else if (requestCode == REQUEST_LOCATION) {
            monitorStatus.text = "Location permission required for Bluetooth scanning"
        }
    }

    private fun toggleMonitoring() {
        // The first snapshot arrives after storage loads on the monitor thread.
        val current = latest ?: return
        if (current.state != MonitorState.STOPPED) {
            startService(MonitoringService.stopIntent(this))
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(MonitoringService.startIntent(this))
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
        }
    }

    private fun confirmTimerReset() {
        if ((latest?.timerElapsedMs ?: 0L) == 0L) {
            runtime.post { it.resetTimer() }
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Reset the cook timer?")
            .setPositiveButton(R.string.reset) { _, _ -> runtime.post { it.resetTimer() } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun render(snapshot: AppSnapshot) {
        latest = snapshot
        monitorStatus.text = Format.monitorStatus(snapshot)
        monitorStatus.setTextColor(
            getColor(
                when (snapshot.state) {
                    MonitorState.MONITORING, MonitorState.STARTING -> R.color.vfd_primary
                    MonitorState.STOPPED -> R.color.text_muted
                    else -> R.color.alarm_accent
                },
            ),
        )
        val device = snapshot.settings.selectedDevice
        deviceStatus.text = if (device == null) {
            "No thermometer"
        } else {
            "H5182 · ${Format.battery(snapshot.batteryPercent)}"
        }
        silenceWarning.visibility = if (snapshot.warningAudible) View.VISIBLE else View.GONE

        val needsSelection = device == null && snapshot.state != MonitorState.STOPPED
        selectDevice.visibility = if (needsSelection) View.VISIBLE else View.GONE
        selectDevice.text = "Select (${snapshot.candidates.size} found)"

        cards.forEachIndexed { index, card ->
            card.render(snapshot.probes[index], snapshot.settings.probes[index], snapshot)
        }

        timerValue.text = Format.duration(snapshot.timerElapsedMs)
        timerToggle.text = if (snapshot.timerRunning) "Stop timer" else "Start timer"
        renderMonitorButton(snapshot.state == MonitorState.STOPPED)
    }

    /** Stopping uses a red outline, so it cannot be mistaken for the neutral timer controls. */
    private fun renderMonitorButton(stopped: Boolean) {
        if (monitorButtonStopped == stopped) return
        monitorButtonStopped = stopped
        monitorToggle.text = if (stopped) "Start monitoring" else "Stop monitoring"
        monitorToggle.setBackgroundResource(if (stopped) R.drawable.btn_primary else R.drawable.btn_stop_monitoring)
        monitorToggle.setTextColor(getColor(if (stopped) R.color.background else R.color.alarm_accent))
    }

    /** View holder for one probe. Color adds to, and never replaces, status text. */
    private inner class ProbeCard(private val root: View, private val index: Int) {
        private val name = root.findViewById<TextView>(R.id.probe_name)
        private val status = root.findViewById<TextView>(R.id.probe_status)
        private val temperature = root.findViewById<TextView>(R.id.probe_temperature)
        private val detail = root.findViewById<TextView>(R.id.probe_detail)
        private val targetValue = limitValue(R.id.limit_target, R.string.target_label)
        private val lowValue = limitValue(R.id.limit_low, R.string.low_short_label)
        private val highValue = limitValue(R.id.limit_high, R.string.high_short_label)
        private val graph = root.findViewById<HistoryGraphView>(R.id.probe_graph)
        private val silence = root.findViewById<Button>(R.id.probe_silence)
        private var displayedColor = TemperatureColor.NEUTRAL
        private var targetColor = TemperatureColor.NEUTRAL
        private var animator: ValueAnimator? = null
        private var backgroundRes = 0
        private var chipColorRes = 0

        init {
            name.text = "PROBE ${index + 1}"
            silence.text = "Silence probe ${index + 1} alarm"
            silence.setOnClickListener { runtime.post { it.acknowledgeProbe(index) } }
        }

        private fun limitValue(rowId: Int, labelRes: Int): TextView {
            val row = root.findViewById<View>(rowId)
            row.findViewById<TextView>(R.id.limit_label).setText(labelRes)
            return row.findViewById(R.id.limit_value)
        }

        fun render(probe: ProbeView, config: ProbeSettings, snapshot: AppSnapshot) {
            val fahrenheit = snapshot.settings.displayFahrenheit
            // The status view is a polite live region, so accessibility services
            // announce state changes without announcing every temperature sample.
            // With no scan running, "waiting" would falsely imply a reading is coming.
            val idle = probe.status == ProbeStatus.WAITING && snapshot.state == MonitorState.STOPPED
            status.text = if (idle) "STOPPED" else Format.probeStatus(probe.status, probe.recovering)
            renderChip(probe, idle)

            val value = probe.temperatureC
            val noReading = value == null || probe.status == ProbeStatus.NO_PROBE ||
                probe.status == ProbeStatus.NOT_IN_USE
            temperature.text = if (noReading) "—" else Format.prominentTemperature(value!!, fahrenheit)
            detail.text = when (probe.status) {
                ProbeStatus.NO_PROBE -> if (probe.recovering) "Probe reconnecting…" else "Probe not detected"
                ProbeStatus.NOT_IN_USE -> "Alarms off · change in Settings"
                ProbeStatus.STALE -> "Last reading ${probe.ageMs?.let(Format::age) ?: "—"} ago"
                ProbeStatus.WAITING -> if (idle) "Tap Start monitoring" else "Waiting for a reading"
                else -> if (probe.alarmLevel != AlarmLevel.NORMAL && !probe.alarmAudible) "Alarm silenced" else " "
            }
            // A target is not an alarm, so an unset target reads "None" rather than "Off".
            targetValue.text = config.targetC?.let { Format.limit(it, fahrenheit) } ?: getString(R.string.none_hint)
            lowValue.text = Format.limit(config.lowC, fahrenheit)
            highValue.text = Format.limit(config.highC, fahrenheit)

            val band = snapshot.settings.colorBandC
            val scaleCenter = TemperatureColor.scaleCenter(config, band)
            val fresh = probe.status != ProbeStatus.STALE && !noReading
            animateColor(
                if (fresh) {
                    TemperatureColor.forTemperature(value!!, scaleCenter, band)
                } else {
                    getColor(R.color.text_muted)
                },
            )

            val missingAlarm = probe.status == ProbeStatus.NO_PROBE && probe.alarmAudible
            val alarmShown = probe.status != ProbeStatus.NOT_IN_USE &&
                (probe.alarmLevel != AlarmLevel.NORMAL || missingAlarm)
            setCardBackground(
                when {
                    alarmShown -> R.drawable.bg_card_alarm
                    probe.status == ProbeStatus.NOT_IN_USE -> R.drawable.bg_card_inactive
                    else -> R.drawable.bg_card
                },
            )
            graph.alpha = if (probe.status == ProbeStatus.NOT_IN_USE) INACTIVE_GRAPH_ALPHA else 1f
            graph.setData(
                HistoryGraph.withLiveReading(probe.history, probe.excursions, HistoryGraph.liveReading(probe)),
                probe.excursions,
                scaleCenter,
                band,
                fahrenheit,
            )
            graph.contentDescription = graphDescription(probe, fahrenheit)
            silence.visibility = if (probe.alarmAudible) View.VISIBLE else View.GONE
        }

        private fun renderChip(probe: ProbeView, idle: Boolean) {
            val colorRes = when {
                idle -> R.color.text_muted
                probe.status == ProbeStatus.NORMAL -> R.color.vfd_primary
                probe.status == ProbeStatus.HIGH || probe.status == ProbeStatus.NO_PROBE -> R.color.text_primary
                probe.status == ProbeStatus.LOW -> R.color.text_primary
                else -> R.color.text_muted
            }
            if (colorRes == chipColorRes) return
            chipColorRes = colorRes
            val color = getColor(colorRes)
            status.setTextColor(color)
            status.backgroundTintList = ColorStateList.valueOf(color)
        }

        private fun setCardBackground(res: Int) {
            if (res == backgroundRes) return
            backgroundRes = res
            // Setting a drawable replaces padding, so the layout's padding is restored.
            val left = root.paddingLeft
            val top = root.paddingTop
            val right = root.paddingRight
            val bottom = root.paddingBottom
            root.setBackgroundResource(res)
            root.setPadding(left, top, right, bottom)
        }

        /** The value updates instantly. Only its color eases. System animation scale applies. */
        private fun animateColor(color: Int) {
            if (color == targetColor) return
            targetColor = color
            animator?.cancel()
            animator = ValueAnimator.ofObject(ArgbEvaluator(), displayedColor, color).apply {
                duration = COLOR_ANIMATION_MS
                addUpdateListener {
                    displayedColor = it.animatedValue as Int
                    temperature.setTextColor(displayedColor)
                }
                start()
            }
        }

        private fun graphDescription(probe: ProbeView, fahrenheit: Boolean): String {
            val valid = probe.history.filterNotNull()
            if (valid.isEmpty()) return "Probe ${index + 1}, no recent history"
            val low = Units.toDisplay(valid.min(), fahrenheit)
            val high = Units.toDisplay(valid.max(), fahrenheit)
            val trend = when {
                valid.last() - valid.first() > TREND_CENTI_C -> "rising"
                valid.first() - valid.last() > TREND_CENTI_C -> "falling"
                else -> "steady"
            }
            return String.format(
                Locale.US,
                "Probe %d, last 30 minutes, %.0f to %.0f degrees, %s",
                index + 1,
                low,
                high,
                trend,
            )
        }
    }

    private companion object {
        const val REQUEST_LOCATION = 1
        // Short enough that alarm and recovery color changes read as immediate.
        const val COLOR_ANIMATION_MS = 120L
        const val TREND_CENTI_C = 50
        const val INACTIVE_GRAPH_ALPHA = 0.45f
    }
}
