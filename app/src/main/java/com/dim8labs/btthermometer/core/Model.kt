package com.dim8labs.btthermometer.core

/**
 * Domain types and technology-neutral ports. Temperatures are integer
 * centi-Celsius, matching the advertised resolution and avoiding floating-point
 * error at alarm boundaries. Absence is always explicit (`null`).
 */
object MonitoringPolicy {
    /**
     * PROVISIONAL: architecture requires this to be backed by measured H5182
     * advertisement cadence. Replace after physical capture.
     */
    const val STALE_TIMEOUT_MS = 60_000L

    /**
     * Owner requirement for overnight use: a silenced signal-loss alarm sounds
     * again at this interval until signal returns or monitoring stops.
     */
    const val SIGNAL_WARNING_REPEAT_MS = 5L * 60L * 1000L

    /**
     * Owner-approved probe recovery qualification. Physical testing showed
     * unstable readings and contact changes while a probe was being plugged in,
     * so a missing probe must read validly for this long, across this many
     * packets, before its missing alarm clears and temperature alarms resume.
     */
    const val PROBE_RECOVERY_MS = 5_000L
    const val PROBE_RECOVERY_MIN_READINGS = 3

    /**
     * Owner requirement for overnight use: a silenced missing-probe alarm sounds
     * again this long after the latest Silence while the probe remains missing.
     * Deliberately separate from [SIGNAL_WARNING_REPEAT_MS], so neither alarm's
     * schedule can change the other's.
     */
    const val MISSING_PROBE_REPEAT_MS = 5L * 60L * 1000L

    /**
     * PROVISIONAL accepted probe range. High-bit words are already rejected, so
     * sub-zero readings are unsupported until hardware evidence defines them.
     */
    const val MIN_VALID_CENTI_C = 0
    const val MAX_VALID_CENTI_C = 30_000

    /** PROVISIONAL default, pending owner approval. */
    const val DEFAULT_HYSTERESIS_CENTI_C = 100

    /** PROVISIONAL width of the "near target" color band, pending owner approval. */
    const val DEFAULT_COLOR_BAND_CENTI_C = 300

    /** 180 buckets of 10 s cover the 30-minute sparkline with bounded storage. */
    const val HISTORY_BUCKET_MS = 10_000L
    const val HISTORY_BUCKETS = 180

    const val MAX_CANDIDATES = 8
    const val CANDIDATE_EXPIRY_MS = 120_000L
    const val TIMER_CHECKPOINT_MS = 30_000L
}

data class ProbeSettings(
    val targetC: Int? = null,
    // Alarms are disabled until configured: no default threshold is approved.
    val lowC: Int? = null,
    val highC: Int? = null,
    val hysteresisC: Int = MonitoringPolicy.DEFAULT_HYSTERESIS_CENTI_C,
    /** A probe not in use raises no alarms. Its limits are kept for when it returns. */
    val inUse: Boolean = true,
) {
    val isValid: Boolean
        get() = hysteresisC > 0 && (lowC == null || highC == null || lowC < highC)
}

data class SelectedDevice(val address: String, val name: String)

data class AppSettings(
    val probes: List<ProbeSettings> = listOf(ProbeSettings(), ProbeSettings()),
    val displayFahrenheit: Boolean = true,
    val colorBandC: Int = MonitoringPolicy.DEFAULT_COLOR_BAND_CENTI_C,
    val signalLossWarning: Boolean = true,
    val selectedDevice: SelectedDevice? = null,
)

data class Advertisement(
    val address: String,
    val name: String?,
    val hasH5182ServiceUuid: Boolean,
    val manufacturerData: Map<Int, ByteArray>,
)

enum class RadioFault { BLUETOOTH_OFF, PERMISSION_REQUIRED, LOCATION_OFF, SCAN_ERROR }

enum class MonitorState {
    STOPPED, STARTING, MONITORING, STALE, BLUETOOTH_OFF, PERMISSION_REQUIRED, LOCATION_OFF, SCAN_ERROR
}

enum class ProbeStatus { WAITING, NORMAL, LOW, HIGH, STALE, NO_PROBE, NOT_IN_USE }

/** Whether a recorded reading was beyond the limits in force when it was recorded. */
enum class Excursion { NONE, HIGH, LOW }

data class HistorySample(
    val probe: Int,
    val bucket: Long,
    val centiC: Int,
    val excursion: Excursion = Excursion.NONE,
)

data class ProbeView(
    val status: ProbeStatus,
    val temperatureC: Int?,
    val ageMs: Long?,
    val alarmLevel: AlarmLevel,
    val alarmAudible: Boolean,
    /** Oldest to newest, one slot per bucket, `null` for a real gap. */
    val history: List<Int?>,
    /** True while a missing probe is reading validly but has not yet qualified. */
    val recovering: Boolean = false,
    /** Parallel to [history]. Captured at recording time, so later limit edits cannot rewrite it. */
    val excursions: List<Excursion> = emptyList(),
)

data class AppSnapshot(
    val state: MonitorState,
    val settings: AppSettings,
    val candidates: List<SelectedDevice>,
    val batteryPercent: Int?,
    val lastPacketAgeMs: Long?,
    val probes: List<ProbeView>,
    val timerRunning: Boolean,
    val timerElapsedMs: Long,
    val warningActive: Boolean,
    val warningAudible: Boolean,
)

data class ProbeAlert(
    val probe: Int,
    val episode: Int,
    val level: AlarmLevel,
    val readingC: Int,
    val thresholdC: Int,
    val acknowledged: Boolean,
    /** True only while the reading is beyond the limit and not silenced. */
    val audible: Boolean,
)

/** An audible missing-probe alarm. A new [episode] starts each time the probe goes missing. */
data class MissingProbeAlert(val probe: Int, val episode: Int)

data class AlertState(
    val alarms: List<ProbeAlert>,
    val missingProbes: List<MissingProbeAlert>,
    val warningActive: Boolean,
    val warningAcknowledged: Boolean,
    val displayFahrenheit: Boolean,
)

interface Clock {
    /** Monotonic time, including deep sleep. */
    fun elapsedMs(): Long
    fun wallMs(): Long
    /** Identifies the current device boot, used to detect a reboot. */
    fun bootCount(): Int
}

interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

interface CookStateStore {
    fun load(): CookTimerState
    fun save(state: CookTimerState)
}

interface HistoryStore {
    fun load(oldestBucket: Long, newestBucket: Long): List<HistorySample>
    fun append(sample: HistorySample, oldestBucket: Long, newestBucket: Long)
    fun clear()
}

/** Renders alarm decisions. Implementations must not derive alarm policy. */
interface NotificationPort {
    fun render(alerts: AlertState)
}
