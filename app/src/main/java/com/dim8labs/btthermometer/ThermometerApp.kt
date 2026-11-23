package com.dim8labs.btthermometer

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import com.dim8labs.btthermometer.core.Advertisement
import com.dim8labs.btthermometer.core.AppSnapshot
import com.dim8labs.btthermometer.core.Clock
import com.dim8labs.btthermometer.core.MonitoringCoordinator
import com.dim8labs.btthermometer.service.AndroidNotifications
import com.dim8labs.btthermometer.store.PrefsCookStateStore
import com.dim8labs.btthermometer.store.PrefsSettingsStore
import com.dim8labs.btthermometer.store.SqliteHistoryStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Composition root. It wires concrete adapters into the core explicitly. */
class ThermometerApp : Application() {
    lateinit var runtime: MonitorRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = MonitorRuntime(this)
    }
}

/**
 * Owns the single monitor thread on which all coordinator work runs. Storage
 * and alarm evaluation therefore stay off the UI thread, and the UI receives
 * only the newest immutable snapshot.
 */
class MonitorRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("monitor").apply { start() }
    private val monitor = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val pendingAdvertisements = AtomicInteger(0)
    private val newestSnapshot = AtomicReference<AppSnapshot?>(null)
    private val deliveryPosted = AtomicBoolean(false)
    private val listeners = LinkedHashSet<(AppSnapshot) -> Unit>()
    private lateinit var coordinator: MonitoringCoordinator
    private lateinit var notifications: AndroidNotifications

    /** Runs on the monitor thread after each one-second tick. */
    @Volatile
    var tickHook: ((MonitoringCoordinator) -> Unit)? = null

    val latest: AppSnapshot? get() = newestSnapshot.get()

    init {
        monitor.post {
            notifications = AndroidNotifications(appContext)
            coordinator = MonitoringCoordinator(
                settingsStore = PrefsSettingsStore(appContext),
                cookStore = PrefsCookStateStore(appContext),
                historyStore = SqliteHistoryStore(appContext),
                alerts = notifications,
                clock = AndroidClock(appContext),
                publish = ::deliver,
            )
            coordinator.tick()
            monitor.postDelayed(::tick, TICK_MS)
        }
    }

    fun post(action: (MonitoringCoordinator) -> Unit) {
        monitor.post { action(coordinator) }
    }

    /** Drops packets when the queue is full. Newer advertisements will follow. */
    fun submitAdvertisement(ad: Advertisement) {
        if (pendingAdvertisements.incrementAndGet() > MAX_PENDING_ADVERTISEMENTS) {
            pendingAdvertisements.decrementAndGet()
            return
        }
        monitor.post {
            pendingAdvertisements.decrementAndGet()
            coordinator.onAdvertisement(ad)
        }
    }

    fun postRaw(action: () -> Unit) {
        monitor.post(action)
    }

    /** Must be called on the main thread. */
    fun addListener(listener: (AppSnapshot) -> Unit) {
        listeners += listener
        latest?.let(listener)
    }

    /** Must be called on the main thread. */
    fun removeListener(listener: (AppSnapshot) -> Unit) {
        listeners -= listener
    }

    private fun tick() {
        coordinator.tick()
        notifications.maintainSound()
        tickHook?.invoke(coordinator)
        monitor.postDelayed(::tick, TICK_MS)
    }

    /** Coalesces delivery so a slow UI cannot build a backlog of snapshots. */
    private fun deliver(snapshot: AppSnapshot) {
        newestSnapshot.set(snapshot)
        if (!deliveryPosted.compareAndSet(false, true)) return
        main.post {
            deliveryPosted.set(false)
            val current = newestSnapshot.get() ?: return@post
            listeners.toList().forEach { it(current) }
        }
    }

    private class AndroidClock(private val context: Context) : Clock {
        override fun elapsedMs() = SystemClock.elapsedRealtime()
        override fun wallMs() = System.currentTimeMillis()
        override fun bootCount() =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MAX_PENDING_ADVERTISEMENTS = 32
    }
}
