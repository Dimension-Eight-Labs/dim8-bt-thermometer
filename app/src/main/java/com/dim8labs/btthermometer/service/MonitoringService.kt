package com.dim8labs.btthermometer.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.dim8labs.btthermometer.MonitorRuntime
import com.dim8labs.btthermometer.R
import com.dim8labs.btthermometer.ThermometerApp
import com.dim8labs.btthermometer.ble.BleScanner
import com.dim8labs.btthermometer.core.AppSnapshot
import com.dim8labs.btthermometer.core.MonitoringCoordinator
import com.dim8labs.btthermometer.ui.Format

/**
 * Foreground service that owns the scan lifetime, so monitoring continues while
 * the phone is locked or another app is in use.
 *
 * A partial wake lock is held while monitoring. Without it, the one-second tick
 * stops during CPU sleep, so signal loss would raise no warning because no
 * callback arrives to wake the app. This trades battery for alarm reliability.
 * Actual screen-off behavior still requires physical verification.
 */
class MonitoringService : Service() {
    private lateinit var runtime: MonitorRuntime
    private lateinit var scanner: BleScanner
    private lateinit var notifications: AndroidNotifications
    private lateinit var wakeLock: PowerManager.WakeLock
    private var lastWakeRenewalMs = 0L
    private var destroyed = false // Guarded by wakeLock.
    private var lastStatusText = ""
    private var lastStatusState: Any? = null
    private var lastStatusPostMs = 0L

    private val statusListener: (AppSnapshot) -> Unit = ::updateStatus

    override fun onCreate() {
        super.onCreate()
        runtime = (application as ThermometerApp).runtime
        notifications = AndroidNotifications(this)
        // Promote immediately: Android requires this soon after service start.
        startForeground(
            AndroidNotifications.STATUS_ID,
            notifications.statusNotification(getString(R.string.app_name), "Starting monitor…"),
        )
        scanner = BleScanner(this, runtime::submitAdvertisement, runtime::postRaw)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dim8thermometer:monitoring")
            .apply { setReferenceCounted(false) }
        runtime.addListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutDown()
            ACTION_ACK_PROBE -> {
                val probe = intent.getIntExtra(EXTRA_PROBE, -1)
                runtime.post {
                    it.acknowledgeProbe(probe)
                    stopIfIdle(it)
                }
            }
            ACTION_ACK_WARNING -> runtime.post {
                it.acknowledgeWarning()
                stopIfIdle(it)
            }
            // A null intent is a sticky restart after the process was killed
            // during a user-started monitoring session.
            else -> begin()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runtime.removeListener(statusListener)
        runtime.tickHook = null
        val stoppedScanner = scanner
        runtime.postRaw { stoppedScanner.stop() }
        synchronized(wakeLock) {
            destroyed = true
            if (wakeLock.isHeld) wakeLock.release()
        }
        super.onDestroy()
    }

    private fun begin() {
        renewWakeLock()
        runtime.tickHook = ::serviceTick
        runtime.post { coordinator ->
            coordinator.startMonitoring()
            coordinator.onRadioFault(scanner.service(false))
        }
    }

    private fun shutDown() {
        runtime.tickHook = null
        runtime.post { coordinator ->
            scanner.stop()
            coordinator.stopMonitoring()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** An action from a stale notification must not leave an idle foreground service. */
    private fun stopIfIdle(coordinator: MonitoringCoordinator) {
        if (coordinator.isActive) return
        getSystemService(NotificationManager::class.java).cancel(AndroidNotifications.STATUS_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun serviceTick(coordinator: MonitoringCoordinator) {
        if (!coordinator.isActive) return
        coordinator.onRadioFault(scanner.service(coordinator.isSignalLost))
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeRenewalMs >= WAKE_RENEW_MS) renewWakeLock()
    }

    /** A timeout guarantees release even if service teardown is ever missed. */
    private fun renewWakeLock() = synchronized(wakeLock) {
        // The monitor thread may renew while the main thread destroys the
        // service. The flag prevents reacquiring a lock that was just released.
        if (destroyed) return@synchronized
        lastWakeRenewalMs = SystemClock.elapsedRealtime()
        wakeLock.acquire(WAKE_TIMEOUT_MS)
    }

    /** Throttled, except for state changes, to limit notification updates. */
    private fun updateStatus(snapshot: AppSnapshot) {
        val text = Format.notificationSummary(snapshot)
        val now = SystemClock.elapsedRealtime()
        val stateChanged = snapshot.state != lastStatusState
        if (text == lastStatusText && !stateChanged) return
        if (!stateChanged && now - lastStatusPostMs < STATUS_MIN_INTERVAL_MS) return
        lastStatusText = text
        lastStatusState = snapshot.state
        lastStatusPostMs = now
        getSystemService(NotificationManager::class.java).notify(
            AndroidNotifications.STATUS_ID,
            notifications.statusNotification(Format.monitorStatus(snapshot), text),
        )
    }

    companion object {
        private const val ACTION_START = "com.dim8labs.btthermometer.START"
        private const val ACTION_STOP = "com.dim8labs.btthermometer.STOP"
        private const val ACTION_ACK_PROBE = "com.dim8labs.btthermometer.ACK_PROBE"
        private const val ACTION_ACK_WARNING = "com.dim8labs.btthermometer.ACK_WARNING"
        private const val EXTRA_PROBE = "probe"
        private const val WAKE_TIMEOUT_MS = 15L * 60L * 1000L
        private const val WAKE_RENEW_MS = 5L * 60L * 1000L
        private const val STATUS_MIN_INTERVAL_MS = 5_000L

        fun startIntent(context: Context) =
            Intent(context, MonitoringService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context) =
            Intent(context, MonitoringService::class.java).setAction(ACTION_STOP)

        fun acknowledgeProbeIntent(context: Context, probe: Int) =
            Intent(context, MonitoringService::class.java)
                .setAction(ACTION_ACK_PROBE)
                .putExtra(EXTRA_PROBE, probe)

        fun acknowledgeWarningIntent(context: Context) =
            Intent(context, MonitoringService::class.java).setAction(ACTION_ACK_WARNING)
    }
}
