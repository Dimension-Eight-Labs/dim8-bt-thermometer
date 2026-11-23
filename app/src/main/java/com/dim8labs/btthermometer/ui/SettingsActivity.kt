package com.dim8labs.btthermometer.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.dim8labs.btthermometer.MonitorRuntime
import com.dim8labs.btthermometer.R
import com.dim8labs.btthermometer.ThermometerApp
import com.dim8labs.btthermometer.core.AppSettings
import com.dim8labs.btthermometer.core.AppSnapshot
import com.dim8labs.btthermometer.core.MonitoringPolicy
import com.dim8labs.btthermometer.core.ProbeSettings
import com.dim8labs.btthermometer.core.Units
import java.util.Locale

/**
 * Compact settings form. Values are validated in the display unit, converted
 * once to canonical Celsius, and refused with an inline error when invalid.
 * Leaving with unsaved edits asks before discarding them.
 */
class SettingsActivity : Activity() {
    private lateinit var runtime: MonitorRuntime
    private lateinit var groups: List<ProbeGroup>
    private lateinit var fahrenheit: Switch
    private lateinit var signalWarning: Switch
    private lateinit var colorBand: NumberField
    private lateinit var deviceText: TextView
    private lateinit var errorText: TextView
    private lateinit var scroll: ScrollView
    private var populated = false
    private var formFahrenheit = true
    private var savedFahrenheit = true
    private var savedSignalWarning = true

    private val listener: (AppSnapshot) -> Unit = { snapshot ->
        deviceText.text = snapshot.settings.selectedDevice?.let(DevicePicker::label) ?: "No thermometer selected"
        if (!populated) populate(snapshot.settings)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        runtime = (application as ThermometerApp).runtime
        deviceText = findViewById(R.id.settings_device)
        errorText = findViewById(R.id.settings_error)
        scroll = findViewById(R.id.settings_scroll)
        fahrenheit = findViewById(R.id.settings_fahrenheit)
        signalWarning = findViewById(R.id.settings_signal_warning)
        colorBand = NumberField(
            findViewById(R.id.settings_color_band),
            labelRes = R.string.color_band_label,
            hintRes = R.string.none_hint,
            helpRes = R.string.color_band_help,
            delta = true,
        )
        groups = listOf(
            ProbeGroup(findViewById(R.id.settings_probe_1), "Probe 1"),
            ProbeGroup(findViewById(R.id.settings_probe_2), "Probe 2"),
        )
        findViewById<View>(R.id.settings_back).setOnClickListener { onBackPressed() }
        findViewById<Button>(R.id.settings_change_device).setOnClickListener { DevicePicker.show(this, runtime) }
        findViewById<Button>(R.id.settings_notifications).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
        findViewById<Button>(R.id.settings_save).setOnClickListener { save() }
        fahrenheit.setOnCheckedChangeListener { _, checked -> changeUnit(checked) }
    }

    override fun onStart() {
        super.onStart()
        runtime.addListener(listener)
    }

    override fun onStop() {
        runtime.removeListener(listener)
        super.onStop()
    }

    override fun onBackPressed() {
        if (!hasUnsavedChanges()) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Save changes?")
            .setMessage("You have unsaved settings.")
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Keep editing", null)
            .show()
    }

    private fun hasUnsavedChanges(): Boolean = populated && (
        fahrenheit.isChecked != savedFahrenheit ||
            signalWarning.isChecked != savedSignalWarning ||
            colorBand.isEdited ||
            groups.any { it.isEdited }
        )

    private fun populate(settings: AppSettings) {
        populated = true
        formFahrenheit = settings.displayFahrenheit
        savedFahrenheit = settings.displayFahrenheit
        savedSignalWarning = settings.signalLossWarning
        fahrenheit.isChecked = settings.displayFahrenheit
        signalWarning.isChecked = settings.signalLossWarning
        colorBand.set(settings.colorBandC, formFahrenheit)
        colorBand.markSaved()
        colorBand.showUnit(formFahrenheit)
        groups.forEachIndexed { index, group ->
            group.set(settings.probes[index], formFahrenheit)
            group.markSaved()
        }
    }

    /** Re-renders in the new unit, leaving unparseable text for Save to report. */
    private fun changeUnit(toFahrenheit: Boolean) {
        if (!populated || toFahrenheit == formFahrenheit) return
        val from = formFahrenheit
        (groups.flatMap { it.fields } + colorBand).forEach {
            it.convert(from, toFahrenheit)
            it.showUnit(toFahrenheit)
        }
        formFahrenheit = toFahrenheit
    }

    private fun save() {
        errorText.visibility = View.GONE
        val probes = groups.map { it.read(formFahrenheit) }
        val firstInvalid = probes.indexOfFirst { it == null }
        if (firstInvalid >= 0) {
            // Save stays on screen while the form scrolls, so point to the problem.
            showError("Check ${groups[firstInvalid].label}.")
            scroll.post { scroll.smoothScrollTo(0, groups[firstInvalid].top) }
            return
        }
        val band = colorBand.read(formFahrenheit)
        if (band.invalid || band.value == null || band.value <= 0) {
            showError("Color band must be a positive number.")
            return
        }
        val unit = formFahrenheit
        val warning = signalWarning.isChecked
        val bandC = band.value
        runtime.post { coordinator ->
            probes.forEachIndexed { index, probe -> coordinator.updateProbeSettings(index, probe!!) }
            coordinator.updateDisplay(unit, bandC, warning)
            runOnUiThread { finish() }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }

    private class Parsed(val value: Int?, val invalid: Boolean)

    /**
     * One labeled numeric row. It keeps the stored value when text is unchanged.
     * Without this, rounding through the display unit could alter a threshold
     * and restart its alarm.
     */
    private class NumberField(
        root: View,
        labelRes: Int,
        hintRes: Int,
        helpRes: Int? = null,
        private val delta: Boolean = false,
    ) {
        private val edit = root.findViewById<EditText>(R.id.field_value)
        private val unit = root.findViewById<TextView>(R.id.field_unit)
        private var stored: Int? = null
        private var saved: Int? = null
        private var rendered = ""

        /** True when the visible text or converted value differs from what is saved. */
        val isEdited: Boolean
            get() = edit.text.toString().trim() != rendered || stored != saved

        init {
            val label = root.findViewById<TextView>(R.id.field_label)
            label.setText(labelRes)
            edit.setHint(hintRes)
            edit.contentDescription = label.text
            helpRes?.let {
                root.findViewById<TextView>(R.id.field_help).apply {
                    setText(it)
                    visibility = View.VISIBLE
                }
                // Advanced values stay available with less visual weight.
                label.setTextColor(root.context.getColor(R.color.text_body))
                label.textSize = ADVANCED_LABEL_SP
            }
        }

        fun set(value: Int?, fahrenheit: Boolean) {
            stored = value
            rendered = value?.let { text(it, fahrenheit) } ?: ""
            edit.setText(rendered)
        }

        fun markSaved() {
            saved = stored
        }

        fun showUnit(fahrenheit: Boolean) {
            unit.text = Format.unit(fahrenheit)
        }

        fun read(fahrenheit: Boolean): Parsed {
            val current = edit.text.toString().trim()
            if (current == rendered) return Parsed(stored, false)
            if (current.isEmpty()) return Parsed(null, false)
            val number = current.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return Parsed(null, true)
            val converted = if (delta) {
                Units.deltaFromDisplay(number, fahrenheit)
            } else {
                Units.fromDisplay(number, fahrenheit)
            }
            return Parsed(converted, false)
        }

        fun convert(from: Boolean, to: Boolean) {
            val parsed = read(from)
            if (!parsed.invalid) set(parsed.value, to)
        }

        private fun text(value: Int, fahrenheit: Boolean): String {
            val display = if (delta) Units.deltaToDisplay(value, fahrenheit) else Units.toDisplay(value, fahrenheit)
            return String.format(Locale.US, "%.1f", display)
        }

        private companion object {
            const val ADVANCED_LABEL_SP = 14f
        }
    }

    private class ProbeGroup(private val root: View, val label: String) {
        private val target = NumberField(root.findViewById(R.id.field_target), R.string.target_label, R.string.none_hint)
        private val low = NumberField(root.findViewById(R.id.field_low), R.string.low_label, R.string.off_hint)
        private val high = NumberField(root.findViewById(R.id.field_high), R.string.high_label, R.string.off_hint)
        private val hysteresis = NumberField(
            root.findViewById(R.id.field_hysteresis),
            R.string.hysteresis_label,
            R.string.none_hint,
            R.string.hysteresis_help,
            delta = true,
        )
        private val inUse = root.findViewById<Switch>(R.id.field_in_use)
        private val error = root.findViewById<TextView>(R.id.group_error)
        private var savedInUse = true
        val fields = listOf(target, low, high, hysteresis)
        val top: Int get() = root.top
        val isEdited: Boolean get() = inUse.isChecked != savedInUse || fields.any { it.isEdited }

        init {
            root.findViewById<TextView>(R.id.group_title).text = label
            inUse.contentDescription = "$label in use"
        }

        fun set(settings: ProbeSettings, fahrenheit: Boolean) {
            inUse.isChecked = settings.inUse
            target.set(settings.targetC, fahrenheit)
            low.set(settings.lowC, fahrenheit)
            high.set(settings.highC, fahrenheit)
            hysteresis.set(settings.hysteresisC, fahrenheit)
            fields.forEach { it.showUnit(fahrenheit) }
        }

        fun markSaved() {
            savedInUse = inUse.isChecked
            fields.forEach { it.markSaved() }
        }

        /** Returns validated settings, or null after showing an inline error. */
        fun read(fahrenheit: Boolean): ProbeSettings? {
            error.visibility = View.GONE
            val values = listOf(target, low, high).map { it.read(fahrenheit) }
            val hysteresisValue = hysteresis.read(fahrenheit)
            val message = when {
                values.any { it.invalid } || hysteresisValue.invalid -> "$label: enter valid numbers."
                values.any { it.value != null && it.value !in VALID_RANGE } ->
                    "$label: temperatures must be within the supported probe range."
                hysteresisValue.value == null || hysteresisValue.value <= 0 ->
                    "$label: hysteresis must be greater than zero."
                values[1].value != null && values[2].value != null && values[1].value!! >= values[2].value!! ->
                    "$label: low alarm must be below high alarm."
                else -> null
            }
            if (message != null) {
                error.text = message
                error.visibility = View.VISIBLE
                return null
            }
            return ProbeSettings(
                targetC = values[0].value,
                lowC = values[1].value,
                highC = values[2].value,
                hysteresisC = hysteresisValue.value!!,
                inUse = inUse.isChecked,
            )
        }

        private companion object {
            val VALID_RANGE = MonitoringPolicy.MIN_VALID_CENTI_C..MonitoringPolicy.MAX_VALID_CENTI_C
        }
    }
}
