package com.dim8labs.btthermometer.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.dim8labs.btthermometer.core.AlarmSoundOutput

/**
 * Plays the looping alarm tone and vibration independently of any notification.
 *
 * It uses the alarm audio stream, so Android's alarm volume and Do Not Disturb
 * settings still apply. Only one [Ringtone] exists at a time. All calls come from
 * the monitor thread through [com.dim8labs.btthermometer.core.AlarmSoundController].
 */
class AndroidAlarmSound(private val context: Context) : AlarmSoundOutput {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var ringtone: Ringtone? = null
    private var vibrating = false

    override val isPlaying: Boolean
        get() = runCatching { ringtone?.isPlaying == true }.getOrDefault(false)

    override fun start() {
        // An audio failure must not stop the monitor thread. The controller
        // retries on the next tick, and vibration continues meanwhile.
        runCatching {
            ringtone?.stop()
            ringtone = loadRingtone()?.apply {
                audioAttributes = attributes
                isLooping = true
                play()
            }
        }
        if (!vibrating) {
            runCatching {
                vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN_MS, 0), attributes)
                vibrating = true
            }
        }
    }

    override fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrating = false
    }

    /** Falls back so a phone with no alarm tone selected can still make sound. */
    private fun loadRingtone(): Ringtone? =
        FALLBACK_TONE_TYPES.firstNotNullOfOrNull { type ->
            RingtoneManager.getDefaultUri(type)?.let { RingtoneManager.getRingtone(context, it) }
        }

    private companion object {
        val FALLBACK_TONE_TYPES = intArrayOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_RINGTONE,
            RingtoneManager.TYPE_NOTIFICATION,
        ).toList()
        val VIBRATION_PATTERN_MS = longArrayOf(0L, 700L, 500L)
    }
}
