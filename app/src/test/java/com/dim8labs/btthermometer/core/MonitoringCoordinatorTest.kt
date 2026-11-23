package com.dim8labs.btthermometer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host/JVM tests with an injected clock and in-memory ports. */
class MonitoringCoordinatorTest {
    private class FakeClock : Clock {
        var elapsed = 1_000L
        var wall = 1_800_000_000_000L
        var boot = 7
        override fun elapsedMs() = elapsed
        override fun wallMs() = wall
        override fun bootCount() = boot
        fun advance(ms: Long) { elapsed += ms; wall += ms }
    }

    private class Stores : SettingsStore, HistoryStore, NotificationPort {
        var settings = AppSettings(selectedDevice = SelectedDevice(ADDRESS, "GVH5182_TEST"))
        var cook = CookTimerState()
        val rows = linkedMapOf<Pair<Int, Long>, Int>()
        val rowExcursions = hashMapOf<Pair<Int, Long>, Excursion>()
        val rendered = mutableListOf<AlertState>()
        override fun load() = settings
        override fun save(settings: AppSettings) { this.settings = settings }
        override fun load(oldestBucket: Long, newestBucket: Long) =
            rows.filterKeys { it.second in oldestBucket..newestBucket }
                .map {
                    HistorySample(
                        it.key.first,
                        it.key.second,
                        it.value,
                        rowExcursions[it.key] ?: Excursion.NONE,
                    )
                }
        override fun append(sample: HistorySample, oldestBucket: Long, newestBucket: Long) {
            rows[sample.probe to sample.bucket] = sample.centiC
            rowExcursions[sample.probe to sample.bucket] = sample.excursion
        }
        override fun clear() {
            rows.clear()
            rowExcursions.clear()
        }
        var onRender: (() -> Unit)? = null
        override fun render(alerts: AlertState) {
            rendered += alerts
            onRender?.invoke()
        }
    }

    private class CookStore(val stores: Stores) : CookStateStore {
        override fun load() = stores.cook
        override fun save(state: CookTimerState) { stores.cook = state }
    }

    private val clock = FakeClock()
    private val stores = Stores()
    private var snapshot: AppSnapshot? = null
    private val coordinator = coordinator()

    private fun coordinator() =
        MonitoringCoordinator(stores, CookStore(stores), stores, stores, clock) { snapshot = it }

    private fun packet(p1: Int, p2: Int = p1, address: String = ADDRESS): Advertisement {
        val value = ByteArray(17)
        value[5] = 0x64
        value[8] = (p1 ushr 8).toByte(); value[9] = p1.toByte()
        value[13] = (p2 ushr 8).toByte(); value[14] = p2.toByte()
        return Advertisement(address, "GVH5182_TEST", true, mapOf(0x1330 to value))
    }

    private val snap get() = snapshot!!

    @Test
    fun `valid packet produces independent probe readings`() {
        coordinator.startMonitoring()
        assertEquals(MonitorState.STARTING, snap.state)
        coordinator.onAdvertisement(packet(2100, 1800))
        assertEquals(MonitorState.MONITORING, snap.state)
        assertEquals(2100, snap.probes[0].temperatureC)
        assertEquals(1800, snap.probes[1].temperatureC)
        assertEquals(100, snap.batteryPercent)
    }

    @Test
    fun `other device is a candidate but never feeds readings`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100, address = "AA:BB:CC:00:00:99"))
        assertNull(snap.probes[0].temperatureC)
        assertEquals(1, snap.candidates.size)
    }

    @Test
    fun `absent probe is not zero and does not affect the other probe`() {
        coordinator.updateProbeSettings(0, ProbeSettings(lowC = 1000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(ProbeStatus.NO_PROBE, snap.probes[0].status)
        assertNull(snap.probes[0].temperatureC)
        assertEquals(AlarmLevel.NORMAL, snap.probes[0].alarmLevel)
        assertEquals(2000, snap.probes[1].temperatureC)
    }

    // ---- Missing-probe alarm --------------------------------------------------

    private val missingNow get() = stores.rendered.last().missingProbes.map { it.probe }

    /** Delivers the minimum packets over the recovery period. */
    private fun reconnect(p1: Int, p2: Int) {
        repeat(MonitoringPolicy.PROBE_RECOVERY_MIN_READINGS) { index ->
            if (index > 0) {
                clock.advance(MonitoringPolicy.PROBE_RECOVERY_MS / (MonitoringPolicy.PROBE_RECOVERY_MIN_READINGS - 1))
            }
            coordinator.onAdvertisement(packet(p1, p2))
        }
    }

    @Test
    fun `recovery needs five seconds as well as multiple readings`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(2100, 2000))
        assertTrue(snap.probes[0].recovering)
        repeat(10) {
            clock.advance(499)
            coordinator.onAdvertisement(packet(2100, 2000))
        }
        assertEquals(listOf(0), missingNow) // 4,990 ms: many readings, too little time.
        clock.advance(10)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(emptyList<Int>(), missingNow)
        assertFalse(snap.probes[0].recovering)
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
    }

    @Test
    fun `two readings five seconds apart do not qualify recovery`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(2100, 2000))
        clock.advance(MonitoringPolicy.PROBE_RECOVERY_MS)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(listOf(0), missingNow)
        clock.advance(1_000)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(emptyList<Int>(), missingNow)
    }

    @Test
    fun `readings while qualifying are not displayed stored or evaluated`() {
        coordinator.updateProbeSettings(0, ProbeSettings(lowC = 2000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2500))
        val rowsBefore = stores.rows.count { it.key.first == 0 }
        coordinator.onAdvertisement(packet(1500, 2500))
        clock.advance(2_000)
        coordinator.onAdvertisement(packet(1500, 2500))
        assertNull(snap.probes[0].temperatureC)
        assertEquals(ProbeStatus.NO_PROBE, snap.probes[0].status)
        assertEquals(rowsBefore, stores.rows.count { it.key.first == 0 })
        assertTrue(stores.rendered.last().alarms.isEmpty())
    }

    @Test
    fun `transient reading during settling raises no temperature alarm`() {
        coordinator.updateProbeSettings(1, ProbeSettings(lowC = 1889))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100, 0xffff))
        coordinator.acknowledgeProbe(1)
        coordinator.onAdvertisement(packet(2100, 1800)) // False low while seating the plug.
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(2100, 2100))
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(2100, 2100))
        assertTrue(stores.rendered.none { alert -> alert.alarms.any { it.probe == 1 } })
        assertEquals(2100, snap.probes[1].temperatureC)
    }

    @Test
    fun `temperature beyond limit alarms only after qualification`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(7100, 2000))
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(7200, 2000))
        assertTrue(stores.rendered.last().alarms.isEmpty())
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(7300, 2000))
        val alert = stores.rendered.last().alarms.single()
        assertTrue(alert.audible)
        assertEquals(7300, alert.readingC)
    }

    @Test
    fun `intermittent contact restarts qualification without a new episode`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        val episode = stores.rendered.last().missingProbes.single().episode
        val renders = stores.rendered.size
        repeat(4) {
            coordinator.onAdvertisement(packet(2100, 2000))
            clock.advance(2_000)
            coordinator.onAdvertisement(packet(2100, 2000))
            clock.advance(2_000)
            coordinator.onAdvertisement(packet(0xffff, 2000))
            assertFalse(snap.probes[0].recovering)
            clock.advance(500)
        }
        assertEquals(renders, stores.rendered.size)
        assertEquals(episode, stores.rendered.last().missingProbes.single().episode)
    }

    @Test
    fun `rejected word restarts qualification`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(2100, 2000))
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(2100, 2000))
        clock.advance(2_000)
        coordinator.onAdvertisement(packet(0x8000, 2000))
        assertFalse(snap.probes[0].recovering)
        clock.advance(1_000)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `brief renewed disconnect does not restart a silenced missing alarm`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        repeat(3) {
            coordinator.onAdvertisement(packet(2100, 2000))
            clock.advance(1_000)
            coordinator.onAdvertisement(packet(0xffff, 2000))
            assertEquals(emptyList<Int>(), missingNow)
            assertFalse(snap.probes[0].alarmAudible)
            clock.advance(1_000)
        }
    }

    @Test
    fun `silence during qualification holds and a removal after recovery sounds`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(2100, 2000))
        coordinator.acknowledgeProbe(0)
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(2100, 2000))
        clock.advance(2_500)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `silenced temperature alarm survives intermittent contact and qualification`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100, 2000))
        coordinator.acknowledgeProbe(0)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(7200, 2000))
        clock.advance(1_000)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0) // Silences only the missing alarm.
        reconnect(7200, 2000)
        val alert = stores.rendered.last().alarms.single()
        assertTrue(alert.acknowledged)
        assertFalse(alert.audible)
        assertEquals(ProbeStatus.HIGH, snap.probes[0].status)
    }

    @Test
    fun `other probe temperature alarm is unaffected by intermittent contact`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100, 2000))
        val highAlert = stores.rendered.last().alarms.single()
        coordinator.onAdvertisement(packet(7200, 0xffff))
        coordinator.onAdvertisement(packet(7200, 2000))
        clock.advance(1_000)
        coordinator.onAdvertisement(packet(7300, 0xffff))
        val overlap = stores.rendered.last()
        assertEquals(highAlert, overlap.alarms.single())
        assertEquals(listOf(1), overlap.missingProbes.map { it.probe })
        assertEquals(7300, snap.probes[0].temperatureC)
        coordinator.acknowledgeProbe(1)
        assertTrue(snap.probes[0].alarmAudible)
        assertEquals(emptyList<Int>(), missingNow)
    }

    @Test
    fun `overlapping alarms keep independent silence through reconnect`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.updateProbeSettings(1, ProbeSettings(lowC = 1000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100, 0xffff))
        coordinator.acknowledgeProbe(0)
        assertEquals(listOf(1), missingNow)
        assertFalse(stores.rendered.last().alarms.single().audible)
        reconnect(7200, 900)
        val alerts = stores.rendered.last()
        assertEquals(emptyList<MissingProbeAlert>(), alerts.missingProbes)
        assertFalse(alerts.alarms.single { it.probe == 0 }.audible)
        assertTrue(alerts.alarms.single { it.probe == 1 }.audible)
    }

    @Test
    fun `long packet gap restarts qualification and signal loss still fires at 60 seconds`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(2100, 2000))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS - 1)
        coordinator.tick()
        assertFalse(snap.warningActive)
        clock.advance(1)
        coordinator.tick()
        assertTrue(snap.warningAudible)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertFalse(snap.warningActive) // Signal recovery is immediate.
        assertEquals(listOf(0), missingNow) // Probe recovery restarted.
        assertTrue(snap.probes[0].recovering)
    }

    @Test
    fun `stopping monitoring discards recovery progress`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.onAdvertisement(packet(2100, 2000))
        coordinator.stopMonitoring()
        assertFalse(snap.probes[0].recovering)
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status) // New session: no missing state.
    }

    /**
     * Replays Probe 2 from the physical reconnect at 13:10:37 on 2026-11-22.
     * The first false low is suppressed. The second occurs after five seconds
     * of valid readings, so this qualification rule alone does not prevent it.
     */
    @Test
    fun `recorded reconnect suppresses first false low but not a later one`() {
        coordinator.updateProbeSettings(1, ProbeSettings(lowC = 1889))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2200, 0xffff))
        coordinator.acknowledgeProbe(1)
        val sequence = listOf(
            0L to 1800, 4_000L to 2100, 5_000L to 2100, 6_000L to 2100,
            7_000L to 2100, 8_000L to 2100, 10_000L to 1700, 13_000L to 1900,
        )
        var previous = 0L
        val lowAlertAtMs = mutableListOf<Long>()
        sequence.forEach { (offset, reading) ->
            clock.advance(offset - previous)
            previous = offset
            coordinator.onAdvertisement(packet(2200, reading))
            if (snap.probes[1].alarmAudible && offset !in lowAlertAtMs) lowAlertAtMs += offset
        }
        assertEquals(listOf(10_000L), lowAlertAtMs)
    }

    @Test
    fun `missing probe sounds immediately and identifies only that probe`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(emptyList<Int>(), missingNow)
        coordinator.onAdvertisement(packet(2100, 0xffff))
        assertEquals(listOf(1), missingNow)
        assertTrue(snap.probes[1].alarmAudible)
        assertEquals(ProbeStatus.NO_PROBE, snap.probes[1].status)
        assertFalse(snap.probes[0].alarmAudible)
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
        assertEquals(2100, snap.probes[0].temperatureC)
    }

    @Test
    fun `probe already missing at start alarms on first packet`() {
        coordinator.startMonitoring()
        assertEquals(emptyList<MissingProbeAlert>(), snapAlerts().missingProbes)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `both probes can be missing independently`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        assertEquals(listOf(0, 1), missingNow)
        coordinator.acknowledgeProbe(0)
        assertEquals(listOf(1), missingNow)
    }

    @Test
    fun `repeated missing packets do not create duplicate alerts`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        val renders = stores.rendered.size
        repeat(5) { coordinator.onAdvertisement(packet(0xffff, 2000)) }
        assertEquals(renders, stores.rendered.size)
    }

    @Test
    fun `missing alarm clears automatically when probe returns`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        reconnect(2100, 2000)
        assertEquals(emptyList<Int>(), missingNow)
        assertFalse(snap.probes[0].alarmAudible)
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
    }

    @Test
    fun `silence holds while missing and a later removal sounds again`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        val first = stores.rendered.last().missingProbes.single().episode
        coordinator.acknowledgeProbe(0)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(emptyList<Int>(), missingNow)
        assertFalse(snap.probes[0].alarmAudible)
        reconnect(2100, 2000)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertNotEquals(first, stores.rendered.last().missingProbes.single().episode)
    }

    @Test
    fun `missing probe does not interfere with the other probe temperature alarm`() {
        coordinator.updateProbeSettings(1, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 7100))
        assertEquals(listOf(0), missingNow)
        assertTrue(stores.rendered.last().alarms.single { it.probe == 1 }.audible)
        coordinator.acknowledgeProbe(0)
        assertEquals(emptyList<Int>(), missingNow)
        assertTrue(snap.probes[1].alarmAudible)
    }

    @Test
    fun `missing replaces same probe temperature alarm and resumes it on return`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100, 2000))
        coordinator.onAdvertisement(packet(0xffff, 2000))
        val during = stores.rendered.last()
        assertFalse(during.alarms.single().audible)
        assertEquals(listOf(0), during.missingProbes.map { it.probe })
        assertEquals(AlarmLevel.NORMAL, snap.probes[0].alarmLevel)

        coordinator.acknowledgeProbe(0) // Silences the missing alarm only.
        reconnect(7300, 2000)
        val after = stores.rendered.last().alarms.single()
        assertTrue(after.audible)
        assertEquals(7300, after.readingC)
        assertEquals(ProbeStatus.HIGH, snap.probes[0].status)
    }

    @Test
    fun `silenced temperature alarm stays silenced through a missing period`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100, 2000))
        coordinator.acknowledgeProbe(0)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
        reconnect(7200, 2000)
        assertEquals(emptyList<Int>(), missingNow)
        assertFalse(snap.probes[0].alarmAudible)
    }

    @Test
    fun `probe returning in range clears a held temperature alarm`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100, 2000))
        coordinator.onAdvertisement(packet(0xffff, 2000))
        reconnect(2500, 2000)
        assertTrue(stores.rendered.last().alarms.isEmpty())
        assertEquals(emptyList<Int>(), missingNow)
    }

    @Test
    fun `missing packets keep signal fresh and the 60 second alarm unchanged`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS - 1)
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS - 1)
        coordinator.tick()
        assertFalse(snap.warningActive)
        clock.advance(1)
        coordinator.tick()
        assertTrue(snap.warningAudible)
        assertEquals(listOf(0, 1), missingNow)
    }

    @Test
    fun `silencing signal loss does not silence a missing alarm`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        coordinator.acknowledgeWarning()
        assertFalse(snap.warningAudible)
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `stopping clears missing alarm and restart relies on new packets`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        coordinator.stopMonitoring()
        assertEquals(emptyList<Int>(), missingNow)
        coordinator.startMonitoring()
        assertEquals(emptyList<Int>(), missingNow)
        assertFalse(snap.probes[0].status == ProbeStatus.NO_PROBE)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `invalid word is not reported as a missing probe`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0x8000, 2000))
        assertTrue(stores.rendered.isEmpty() || missingNow.isEmpty())
        assertFalse(snap.probes[0].alarmAudible)
    }

    private fun snapAlerts(): AlertState = stores.rendered.lastOrNull()
        ?: AlertState(emptyList(), emptyList(), false, false, true)

    @Test
    fun `signal loss goes stale warns once and recovers`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS - 1)
        coordinator.tick()
        assertEquals(MonitorState.MONITORING, snap.state)
        clock.advance(1)
        coordinator.tick()
        assertEquals(MonitorState.STALE, snap.state)
        assertEquals(ProbeStatus.STALE, snap.probes[0].status)
        assertEquals(2100, snap.probes[0].temperatureC)
        assertTrue(snap.warningAudible)
        val renders = stores.rendered.size
        coordinator.tick()
        assertEquals(renders, stores.rendered.size)
        coordinator.acknowledgeWarning()
        assertFalse(snap.warningAudible)
        assertTrue(snap.warningActive)
        coordinator.onAdvertisement(packet(2200))
        assertEquals(MonitorState.MONITORING, snap.state)
        assertFalse(snap.warningActive)
    }

    @Test
    fun `silenced signal warning stays quiet and a later loss sounds again`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertTrue(snap.warningAudible)
        coordinator.acknowledgeWarning()
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertFalse(snap.warningAudible)
        coordinator.onAdvertisement(packet(2100))
        assertFalse(snap.warningActive)
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertTrue(snap.warningAudible)
    }

    @Test
    fun `silenced signal warning repeats every five minutes during loss`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        repeat(2) {
            coordinator.acknowledgeWarning()
            clock.advance(MonitoringPolicy.SIGNAL_WARNING_REPEAT_MS - 1)
            coordinator.tick()
            assertFalse(snap.warningAudible)
            clock.advance(1)
            coordinator.tick()
            assertTrue(snap.warningAudible)
            assertFalse(stores.rendered.last().warningAcknowledged)
        }
    }

    @Test
    fun `duplicate silence does not postpone the repeat`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        coordinator.acknowledgeWarning()
        clock.advance(MonitoringPolicy.SIGNAL_WARNING_REPEAT_MS - 1)
        coordinator.acknowledgeWarning()
        clock.advance(1)
        coordinator.tick()
        assertTrue(snap.warningAudible)
    }

    @Test
    fun `signal return or stopping monitoring ends the repeat`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        coordinator.acknowledgeWarning()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS - 1)
        coordinator.tick()
        assertFalse(snap.warningActive)

        clock.advance(1)
        coordinator.tick()
        coordinator.acknowledgeWarning()
        coordinator.stopMonitoring()
        clock.advance(MonitoringPolicy.SIGNAL_WARNING_REPEAT_MS)
        coordinator.tick()
        assertFalse(snap.warningActive)
        assertFalse(snap.warningAudible)
    }

    @Test
    fun `stale period does not evaluate or replay alarms`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(6000))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS * 2)
        coordinator.tick()
        assertEquals(AlarmLevel.NORMAL, snap.probes[0].alarmLevel)
        coordinator.onAdvertisement(packet(6100))
        assertEquals(AlarmLevel.NORMAL, snap.probes[0].alarmLevel)
    }

    @Test
    fun `alarm remains visible through signal loss`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertEquals(AlarmLevel.HIGH, snap.probes[0].alarmLevel)
        assertTrue(snap.probes[0].alarmAudible)
    }

    @Test
    fun `continued violation keeps one alert identity`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100))
        val first = stores.rendered.last().alarms.single()
        coordinator.onAdvertisement(packet(7300))
        assertEquals(first, stores.rendered.last().alarms.single())
    }

    @Test
    fun `new episode after re-arm has a new alert identity`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100))
        val first = stores.rendered.last().alarms.single()
        coordinator.acknowledgeProbe(0)
        coordinator.onAdvertisement(packet(6000))
        coordinator.onAdvertisement(packet(7100))
        assertNotEquals(first.episode, stores.rendered.last().alarms.single().episode)
    }

    @Test
    fun `threshold edit on acknowledged alarm starts a new alert identity`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7200))
        val first = stores.rendered.last().alarms.single()
        coordinator.acknowledgeProbe(0)
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7100))
        val second = stores.rendered.last().alarms.single()
        assertNotEquals(first.episode, second.episode)
        assertFalse(second.acknowledged)
    }

    @Test
    fun `threshold edit on sounding alarm starts a new alert identity`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7200))
        val first = stores.rendered.last().alarms.single()
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7100))
        val second = stores.rendered.last().alarms.single()
        assertNotEquals(first.episode, second.episode)
        assertEquals(7100, second.thresholdC)
    }

    @Test
    fun `packet without manufacturer data proves reception but is not a reading`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100))
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.onAdvertisement(Advertisement(ADDRESS, "GVH5182_TEST", true, emptyMap()))
        assertEquals(MonitorState.MONITORING, snap.state)
        assertEquals(ProbeStatus.STALE, snap.probes[0].status)
    }

    @Test
    fun `return inside limit stops alert and shows normal before re-arm`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000, hysteresisC = 200))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(7100))
        assertTrue(stores.rendered.last().alarms.single().audible)
        coordinator.onAdvertisement(packet(6900))
        assertFalse(stores.rendered.last().alarms.single().audible)
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
        assertEquals(AlarmLevel.NORMAL, snap.probes[0].alarmLevel)
        coordinator.onAdvertisement(packet(7200))
        val resumed = stores.rendered.last().alarms.single()
        assertTrue(resumed.audible)
        assertEquals(7200, resumed.readingC)
        assertEquals(ProbeStatus.HIGH, snap.probes[0].status)
    }

    @Test
    fun `snapshot contains alarm before notification is rendered`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 7000))
        coordinator.startMonitoring()
        var audibleAtRender = false
        stores.onRender = { audibleAtRender = snap.probes[0].alarmAudible }
        coordinator.onAdvertisement(packet(7100))
        assertTrue(audibleAtRender)
    }

    @Test
    fun `searching without a selected device is not signal loss`() {
        stores.settings = AppSettings()
        val unselected = coordinator()
        unselected.startMonitoring()
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS * 2)
        unselected.tick()
        assertEquals(MonitorState.STARTING, snap.state)
        assertFalse(snap.warningActive)
        assertFalse(unselected.isSignalLost)
    }

    @Test
    fun `invalid settings are refused and not persisted`() {
        assertFalse(coordinator.updateProbeSettings(0, ProbeSettings(lowC = 8000, highC = 7000)))
        assertNull(stores.settings.probes[0].lowC)
    }

    @Test
    fun `history is bounded under packet bursts`() {
        coordinator.startMonitoring()
        repeat(5_000) {
            coordinator.onAdvertisement(packet(2000 + it % 50))
            clock.advance(1_000)
        }
        assertEquals(MonitoringPolicy.HISTORY_BUCKETS, snap.probes[0].history.size)
        assertTrue(snap.probes[0].history.filterNotNull().size <= MonitoringPolicy.HISTORY_BUCKETS)
    }

    @Test
    fun `history keeps real gaps`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2000))
        clock.advance(MonitoringPolicy.HISTORY_BUCKET_MS * 3)
        coordinator.onAdvertisement(packet(2100))
        val recent = snap.probes[0].history.takeLast(4)
        assertEquals(listOf(2000, null, null, 2100), recent)
    }

    @Test
    fun `timer survives process restart on same boot`() {
        coordinator.startTimer()
        clock.advance(40_000)
        coordinator.tick()
        clock.advance(5_000)
        val restored = coordinator()
        restored.tick()
        assertTrue(snap.timerRunning)
        assertEquals(45_000, snap.timerElapsedMs)
    }

    @Test
    fun `timer stops at last checkpoint after reboot`() {
        coordinator.startTimer()
        clock.advance(40_000)
        coordinator.tick()
        clock.advance(5_000)
        clock.boot += 1
        clock.elapsed = 500
        val restored = coordinator()
        restored.tick()
        assertFalse(snap.timerRunning)
        assertEquals(40_000, snap.timerElapsedMs)
    }

    @Test
    fun `timer is unaffected by wall clock changes`() {
        coordinator.startTimer()
        clock.elapsed += 10_000
        clock.wall -= 3_600_000
        coordinator.tick()
        assertEquals(10_000, snap.timerElapsedMs)
    }

    // ---- In Use / Not in Use ---------------------------------------------------

    private fun setInUse(index: Int, inUse: Boolean) {
        coordinator.updateProbeSettings(index, stores.settings.probes[index].copy(inUse = inUse))
    }

    private val temperatureAlarmsNow get() =
        stores.rendered.lastOrNull()?.alarms.orEmpty().filter { it.audible }.map { it.probe }

    @Test
    fun `both probes default to in use`() {
        assertTrue(AppSettings().probes.all { it.inUse })
        assertEquals(2, AppSettings().probes.size)
    }

    @Test
    fun `unused probe raises neither missing nor temperature alarms`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000, inUse = false))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(ProbeStatus.NOT_IN_USE, snap.probes[0].status)
        assertFalse(snap.probes[0].alarmAudible)
        coordinator.onAdvertisement(packet(3500, 2000))
        assertEquals(ProbeStatus.NOT_IN_USE, snap.probes[0].status)
        assertNull(snap.probes[0].temperatureC)
        assertFalse(snap.probes[0].alarmAudible)
        assertEquals(emptyList<Int>(), missingNowOrEmpty)
        assertEquals(emptyList<Int>(), temperatureAlarmsNow)
    }

    private val missingNowOrEmpty get() =
        stores.rendered.lastOrNull()?.missingProbes.orEmpty().map { it.probe }

    @Test
    fun `unused probe does not affect the other probe's readings, alarms, or history`() {
        coordinator.updateProbeSettings(1, ProbeSettings(highC = 3000))
        setInUse(0, false)
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 3500))
        assertEquals(3500, snap.probes[1].temperatureC)
        assertEquals(listOf(1), temperatureAlarmsNow)
        assertTrue(stores.rows.keys.all { it.first == 1 })
        assertTrue(stores.rows.isNotEmpty())
    }

    @Test
    fun `disabling clears only that probe's active temperature alarm`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000))
        coordinator.updateProbeSettings(1, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(3500, 3500))
        assertEquals(listOf(0, 1), temperatureAlarmsNow)
        setInUse(0, false)
        assertEquals(listOf(1), temperatureAlarmsNow)
        assertFalse(snap.probes[0].alarmAudible)
        assertTrue(snap.probes[1].alarmAudible)
    }

    @Test
    fun `disabling clears the missing alarm and its reminder`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        setInUse(0, false)
        clock.advance(MonitoringPolicy.MISSING_PROBE_REPEAT_MS + 1)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.tick()
        assertEquals(emptyList<Int>(), missingNowOrEmpty)
    }

    @Test
    fun `disabling preserves target and thresholds`() {
        val config = ProbeSettings(targetC = 2500, lowC = 1000, highC = 3000, hysteresisC = 200)
        coordinator.updateProbeSettings(0, config)
        setInUse(0, false)
        assertEquals(config.copy(inUse = false), stores.settings.probes[0])
        setInUse(0, true)
        assertEquals(config, stores.settings.probes[0])
    }

    @Test
    fun `in-use setting survives a coordinator restart`() {
        setInUse(1, false)
        val restarted = coordinator()
        restarted.startMonitoring()
        restarted.onAdvertisement(packet(2000, 0xffff))
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
        assertEquals(ProbeStatus.NOT_IN_USE, snap.probes[1].status)
        assertFalse(snap.probes[1].alarmAudible)
    }

    @Test
    fun `re-enabling ignores the old reading until a fresh packet arrives`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(3500, 2000))
        setInUse(0, false)
        setInUse(0, true)
        assertEquals(ProbeStatus.WAITING, snap.probes[0].status)
        assertNull(snap.probes[0].temperatureC)
        assertEquals(emptyList<Int>(), temperatureAlarmsNow)
        coordinator.onAdvertisement(packet(2000, 2000))
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
        assertEquals(emptyList<Int>(), temperatureAlarmsNow)
    }

    @Test
    fun `re-enabling alarms on a fresh reading beyond the limit`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        setInUse(0, false)
        coordinator.onAdvertisement(packet(3500, 2000))
        setInUse(0, true)
        assertEquals(emptyList<Int>(), temperatureAlarmsNow)
        coordinator.onAdvertisement(packet(3500, 2000))
        assertEquals(listOf(0), temperatureAlarmsNow)
    }

    @Test
    fun `re-enabling after a long gap does not treat the old reading as current`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2100, 2000))
        setInUse(0, false)
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS * 2)
        setInUse(0, true)
        coordinator.tick()
        assertEquals(ProbeStatus.WAITING, snap.probes[0].status)
        assertNull(snap.probes[0].temperatureC)
    }

    @Test
    fun `re-enabling a probe that is still missing alarms on the next packet`() {
        coordinator.startMonitoring()
        setInUse(0, false)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        setInUse(0, true)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `late Silence for an unused probe has no effect`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        setInUse(0, false)
        coordinator.acknowledgeProbe(0)
        setInUse(0, true)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
    }

    // ---- Five-minute missing-probe reminder --------------------------------------

    private val repeatMs = MonitoringPolicy.MISSING_PROBE_REPEAT_MS

    @Test
    fun `silenced missing alarm sounds again at five minutes and not before`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
        coordinator.acknowledgeProbe(0)
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(repeatMs - 1)
        coordinator.tick()
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(1)
        coordinator.tick()
        assertEquals(listOf(0), missingNow)
        assertTrue(snap.probes[0].alarmAudible)
    }

    @Test
    fun `reminder repeats after each Silence measured from that Silence`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        clock.advance(repeatMs)
        coordinator.tick()
        assertEquals(listOf(0), missingNow)
        clock.advance(30_000) // The reminder sounds for 30 s before it is silenced.
        coordinator.tick()
        coordinator.acknowledgeProbe(0)
        clock.advance(repeatMs - 1)
        coordinator.tick()
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(1)
        coordinator.tick()
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `another Silence during the quiet period restarts the interval`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        clock.advance(120_000)
        coordinator.acknowledgeProbe(0)
        clock.advance(repeatMs - 1)
        coordinator.tick()
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(1)
        coordinator.tick()
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `contact changes do not postpone or restart the reminder`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        clock.advance(100_000)
        coordinator.onAdvertisement(packet(2100, 2000))
        clock.advance(1_000)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(repeatMs - 101_000)
        coordinator.tick()
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `reminder can sound during qualification and recovery then clears it`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        clock.advance(repeatMs - 1_000)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertTrue(snap.probes[0].recovering)
        clock.advance(1_000)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(listOf(0), missingNow)
        clock.advance(MonitoringPolicy.PROBE_RECOVERY_MS)
        coordinator.onAdvertisement(packet(2100, 2000))
        assertEquals(emptyList<Int>(), missingNow)
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
    }

    @Test
    fun `recovery clears the reminder schedule`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        reconnect(2100, 2000)
        assertEquals(ProbeStatus.NORMAL, snap.probes[0].status)
        repeat(3) {
            clock.advance(repeatMs / 2)
            coordinator.onAdvertisement(packet(2100, 2000))
            coordinator.tick()
        }
        assertEquals(emptyList<Int>(), missingNow)
    }

    @Test
    fun `a new disconnect after recovery alarms immediately`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        reconnect(2100, 2000)
        coordinator.onAdvertisement(packet(0xffff, 2000))
        assertEquals(listOf(0), missingNow)
    }

    @Test
    fun `reminders run independently for each probe`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        coordinator.acknowledgeProbe(0)
        clock.advance(60_000)
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        coordinator.acknowledgeProbe(1)
        assertEquals(emptyList<Int>(), missingNow)
        clock.advance(repeatMs - 60_000)
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        assertEquals(listOf(0), missingNow)
        clock.advance(60_000)
        coordinator.onAdvertisement(packet(0xffff, 0xffff))
        assertEquals(listOf(0, 1), missingNow)
        coordinator.acknowledgeProbe(0)
        assertEquals(listOf(1), missingNow)
    }

    @Test
    fun `reminder overlaps another probe's temperature alarm independently`() {
        coordinator.updateProbeSettings(1, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        coordinator.onAdvertisement(packet(0xffff, 3500))
        assertEquals(listOf(1), temperatureAlarmsNow)
        clock.advance(repeatMs)
        coordinator.onAdvertisement(packet(0xffff, 3500))
        assertEquals(listOf(0), missingNow)
        assertEquals(listOf(1), temperatureAlarmsNow)
        coordinator.acknowledgeProbe(1)
        assertEquals(listOf(0), missingNow)
        assertEquals(emptyList<Int>(), temperatureAlarmsNow)
    }

    @Test
    fun `missing reminder does not change the signal-loss repeat`() {
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(0xffff, 2000))
        coordinator.acknowledgeProbe(0)
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertTrue(snap.warningAudible)
        coordinator.acknowledgeWarning()
        clock.advance(repeatMs - MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertEquals(listOf(0), missingNow)
        assertFalse(snap.warningAudible)
        clock.advance(MonitoringPolicy.STALE_TIMEOUT_MS)
        coordinator.tick()
        assertTrue(snap.warningAudible)
    }

    // ---- Recorded threshold excursions -------------------------------------------

    private val latestExcursion get() =
        snap.probes[0].excursions[snap.probes[0].history.indexOfLast { it != null }]

    @Test
    fun `excursion is recorded without requiring an audible alarm`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(3500, 2000))
        coordinator.acknowledgeProbe(0)
        clock.advance(MonitoringPolicy.HISTORY_BUCKET_MS)
        coordinator.onAdvertisement(packet(3600, 2000))
        assertFalse(snap.probes[0].alarmAudible)
        assertEquals(Excursion.HIGH, latestExcursion)
    }

    @Test
    fun `later limit edits do not rewrite recorded excursions`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(3500, 2000))
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 4000))
        assertEquals(Excursion.HIGH, latestExcursion)
        clock.advance(MonitoringPolicy.HISTORY_BUCKET_MS)
        coordinator.onAdvertisement(packet(3500, 2000))
        assertEquals(Excursion.NONE, latestExcursion)
        assertEquals(1, snap.probes[0].excursions.count { it == Excursion.HIGH })
    }

    @Test
    fun `recorded excursion survives a coordinator restart`() {
        coordinator.updateProbeSettings(0, ProbeSettings(lowC = 1000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(500, 2000))
        coordinator().tick()
        assertEquals(1, snap.probes[0].excursions.count { it == Excursion.LOW })
    }

    @Test
    fun `excursion inside an existing normal bucket is not lost`() {
        coordinator.updateProbeSettings(0, ProbeSettings(highC = 3000))
        coordinator.startMonitoring()
        coordinator.onAdvertisement(packet(2000, 2000))
        coordinator.onAdvertisement(packet(3500, 2000))
        assertEquals(Excursion.HIGH, latestExcursion)
        assertEquals(3500, snap.probes[0].history.last { it != null })
        assertEquals(3500, stores.rows.entries.first { it.key.first == 0 }.value)
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:00:00:01"
    }
}
