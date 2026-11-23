package com.dim8labs.btthermometer.core

/** One continuous alarm output. Implementations own the platform audio and vibration. */
interface AlarmSoundOutput {
    /** True while audio is actually playing. */
    val isPlaying: Boolean

    /** Starts or restarts output. Must not throw, and must not create a second stream. */
    fun start()

    fun stop()
}

/**
 * Owns the single alarm sound shared by all active alerts.
 *
 * Physical testing showed Android can stop notification-owned sound while the
 * alarm is still active. Sound therefore follows alarm state alone: it runs while
 * any unsilenced alert exists, so showing, hiding, or swiping a notification has
 * no authority over it. Overlapping alerts share one stream, and silencing one
 * leaves the stream running for the others.
 *
 * All calls must come from the monitor thread.
 */
class AlarmSoundController(private val output: AlarmSoundOutput) {
    private var wanted = false

    /** Applies the current alarm state. Repeated calls do not restart the sound. */
    fun update(anyAlertAudible: Boolean) {
        if (anyAlertAudible == wanted) return
        wanted = anyAlertAudible
        if (wanted) output.start() else output.stop()
    }

    /**
     * Called once per monitor tick. Restarts sound that ended unexpectedly, such
     * as after an audio-system interruption, for as long as an alarm is active.
     */
    fun maintain() {
        if (wanted && !output.isPlaying) output.start()
    }
}
