package com.dim8labs.btthermometer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dim8labs.btthermometer.R
import com.dim8labs.btthermometer.core.AlarmLevel
import com.dim8labs.btthermometer.core.AlarmSoundController
import com.dim8labs.btthermometer.core.AlertKey
import com.dim8labs.btthermometer.core.AlertReconciler
import com.dim8labs.btthermometer.core.AlertState
import com.dim8labs.btthermometer.core.DesiredAlert
import com.dim8labs.btthermometer.core.NotificationPort
import com.dim8labs.btthermometer.core.ProbeAlert
import com.dim8labs.btthermometer.ui.AlarmActivity
import com.dim8labs.btthermometer.ui.Format
import com.dim8labs.btthermometer.ui.MainActivity

/**
 * Renders alarm decisions as Android notifications.
 *
 * Alarm notifications are visual: they carry the text, Silence action, and
 * full-screen lock-screen page. Sound and vibration belong to [AlarmSoundController],
 * because physical testing showed notification-owned sound could stop while an
 * alarm was still active. Android alarm volume and Do Not Disturb still apply.
 */
class AndroidNotifications(private val context: Context) : NotificationPort {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val reconciler = AlertReconciler()
    private val sound = AlarmSoundController(AndroidAlarmSound(context))

    init {
        createChannels()
    }

    override fun render(alerts: AlertState) {
        val desired = buildList {
            alerts.alarms.filter { it.audible }.forEach {
                add(DesiredAlert(AlertKey.Temperature(it.probe), it.episode))
            }
            alerts.missingProbes.forEach {
                add(DesiredAlert(AlertKey.MissingProbe(it.probe), it.episode))
            }
            if (alerts.warningActive && !alerts.warningAcknowledged) {
                add(DesiredAlert(AlertKey.SignalLoss, 0))
            }
        }
        val plan = reconciler.reconcile(desired)
        plan.cancel.forEach { manager.cancel(notificationId(it)) }
        plan.post.forEach { manager.notify(notificationId(it), buildAlert(it, alerts)) }
        sound.update(desired.isNotEmpty())
    }

    /** Runs on the monitor tick so interrupted alarm sound is restarted. */
    fun maintainSound() = sound.maintain()

    private fun notificationId(key: AlertKey): Int = when (key) {
        is AlertKey.Temperature -> ALARM_ID_BASE + key.probe
        is AlertKey.MissingProbe -> MISSING_ID_BASE + key.probe
        AlertKey.SignalLoss -> WARNING_ID
    }

    private fun buildAlert(key: AlertKey, alerts: AlertState): Notification = when (key) {
        is AlertKey.Temperature ->
            alarmNotification(alerts.alarms.first { it.probe == key.probe }, alerts.displayFahrenheit)
        is AlertKey.MissingProbe -> missingProbeNotification(key.probe)
        AlertKey.SignalLoss -> warningNotification()
    }

    fun statusNotification(title: String, text: String): Notification =
        Notification.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action("Stop monitoring", MonitoringService.stopIntent(context), REQUEST_STOP))
            .build()

    private fun alarmNotification(alert: ProbeAlert, fahrenheit: Boolean): Notification {
        val kind = if (alert.level == AlarmLevel.HIGH) "HIGH" else "LOW"
        val limit = if (alert.level == AlarmLevel.HIGH) "High alarm" else "Low alarm"
        return Notification.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Probe ${alert.probe + 1} $kind")
            .setContentText(
                "${Format.temperature(alert.readingC, fahrenheit)} · " +
                    "$limit ${Format.temperature(alert.thresholdC, fahrenheit)}",
            )
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            // Locked: opens the alarm page. Unlocked: Android shows a heads-up instead.
            .setFullScreenIntent(alarmPageIntent(), true)
            .withoutBannerOverOpenPage()
            .setOngoing(true)
            .addAction(
                action(
                    "Silence",
                    MonitoringService.acknowledgeProbeIntent(context, alert.probe),
                    REQUEST_ACK_BASE + alert.probe,
                ),
            )
            .build()
    }

    /** Uses the probe alarm channel, lock-screen page, and Silence action. */
    private fun missingProbeNotification(probe: Int): Notification =
        Notification.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Probe ${probe + 1} MISSING")
            .setContentText("Probe not detected. Its temperature alarms cannot update.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .setFullScreenIntent(alarmPageIntent(), true)
            .withoutBannerOverOpenPage()
            .setOngoing(true)
            .addAction(
                action(
                    "Silence",
                    MonitoringService.acknowledgeProbeIntent(context, probe),
                    REQUEST_ACK_BASE + probe,
                ),
            )
            .build()

    /**
     * Owner requirement: the app is used overnight, so lost monitoring must wake
     * the user. The shared alarm sound runs until silenced or signal returns.
     */
    private fun warningNotification(): Notification =
        Notification.Builder(context, CHANNEL_SIGNAL_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Thermometer signal lost")
            .setContentText("Temperature alarms cannot update. Trying to recover.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .setFullScreenIntent(alarmPageIntent(), true)
            .withoutBannerOverOpenPage()
            .setOngoing(true)
            .addAction(action("Silence", MonitoringService.acknowledgeWarningIntent(context), REQUEST_ACK_WARNING))
            .build()

    /**
     * Physical testing found a new alarm's heads-up banner covering the top row of
     * the already open alarm page, hiding its Silence button. Android does not show
     * a banner for a grouped child that defers alerting to its summary. Alarm audio
     * is app-owned, so this changes only the banner, and only while the page is open.
     */
    private fun Notification.Builder.withoutBannerOverOpenPage(): Notification.Builder = apply {
        if (AlarmActivity.isVisible) {
            setGroup(GROUP_ALARM_PAGE)
            setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }
    }

    private fun action(label: String, intent: Intent, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_notification),
            label,
            pending,
        ).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarmPageIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_ALARM_PAGE,
        Intent(context, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * High importance keeps the heads-up and full-screen page. The alarm channels
     * are silent because [AndroidAlarmSound] plays the alarm.
     */
    private fun createChannels() {
        val monitoring = NotificationChannel(
            CHANNEL_MONITORING,
            "Monitoring status",
            NotificationManager.IMPORTANCE_LOW,
        )
        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            "Probe alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val signalAlarm = NotificationChannel(
            CHANNEL_SIGNAL_ALARM,
            "Signal-loss alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Android cannot remove sound from an existing channel, so channels that
        // played notification-owned sound are replaced rather than updated.
        LEGACY_CHANNELS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannels(listOf(monitoring, alarm, signalAlarm))
    }

    companion object {
        const val STATUS_ID = 1
        private const val ALARM_ID_BASE = 100
        private const val MISSING_ID_BASE = 110
        private const val WARNING_ID = 200
        private const val GROUP_ALARM_PAGE = "alarm_page_open"
        private const val CHANNEL_MONITORING = "monitoring"
        private const val CHANNEL_ALARM = "probe_alarm_visual"
        private const val CHANNEL_SIGNAL_ALARM = "signal_alarm_visual"
        private val LEGACY_CHANNELS = listOf("signal_warning", "temperature_alarm", "signal_alarm")
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_ACK_WARNING = 3
        private const val REQUEST_ALARM_PAGE = 4
        private const val REQUEST_ACK_BASE = 10
    }
}
