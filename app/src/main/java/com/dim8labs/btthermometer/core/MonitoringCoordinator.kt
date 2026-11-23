package com.dim8labs.btthermometer.core

/**
 * Sole writer of authoritative runtime state.
 *
 * Threading: every method must run on the single monitor thread owned by the
 * composition root. Outputs are immutable snapshots and alert states.
 *
 * Failure rule: stale, absent, and rejected readings never evaluate a
 * temperature alarm. An existing alarm stays visible until a fresh reading
 * clears it or the user stops monitoring, so losing signal cannot silently end
 * a real violation.
 *
 * Missing-probe rule: a packet reporting a probe as unavailable sounds a
 * separate alarm for that probe at once, including on the first packet of a
 * session. While a probe is missing, its temperature alarm is held silent
 * because its temperature is unknown.
 *
 * Recovery rule: a missing probe stays missing until it reads validly for
 * [MonitoringPolicy.PROBE_RECOVERY_MS] across at least
 * [MonitoringPolicy.PROBE_RECOVERY_MIN_READINGS] packets. Readings during this
 * period are not displayed, stored, or evaluated. An unavailable or rejected
 * word restarts qualification without starting a new missing episode, so
 * intermittent contact cannot restart a silenced alarm. Once qualified, the
 * missing alarm clears and the held temperature alarm is re-evaluated with its
 * Silence state intact.
 *
 * Missing-reminder rule: Silence quiets a missing alarm for
 * [MonitoringPolicy.MISSING_PROBE_REPEAT_MS]. If the probe is still missing,
 * including while it is qualifying recovery, the alarm then sounds again. Each
 * Silence restarts the interval. Contact changes do not, so a loose connector
 * cannot postpone the reminder.
 *
 * In-use rule: packets for a probe that is not in use are ignored, so it raises
 * no alarms and adds no history. Changing the setting in either direction
 * discards the probe's runtime state. A probe returned to use therefore waits
 * for the next packet and is never evaluated from an old reading.
 */
class MonitoringCoordinator(
    private val settingsStore: SettingsStore,
    private val cookStore: CookStateStore,
    private val historyStore: HistoryStore,
    private val alerts: NotificationPort,
    private val clock: Clock,
    private val publish: (AppSnapshot) -> Unit,
) {
    private class ProbeRuntime {
        var temperatureC: Int? = null
        var lastValidElapsedMs: Long? = null
        var absent = false
        var missingEpisode = 0
        var missingAcknowledged = false
        var missingAcknowledgedElapsedMs = 0L
        var recoveryStartElapsedMs: Long? = null
        var recoveryLastElapsedMs = 0L
        var recoveryReadings = 0
        var alarm = ProbeAlarmState()
        var episode = 0
        var entryReadingC = 0
        val history = HistoryBuffer()
    }

    private var settings = settingsStore.load()
    private val timer = CookTimer(cookStore.load(), clock.elapsedMs(), clock.bootCount())
    private val probes = List(2) { ProbeRuntime() }
    private val candidates = LinkedHashMap<String, Pair<SelectedDevice, Long>>()

    private var active = false
    private var fault: RadioFault? = null
    private var monitorStartElapsedMs = 0L
    private var lastPacketElapsedMs: Long? = null
    private var batteryPercent: Int? = null
    private var warningAcknowledged = false
    private var warningAcknowledgedElapsedMs = 0L
    private var lastCheckpointElapsedMs = clock.elapsedMs()
    private var lastAlerts: AlertState? = null

    init {
        cookStore.save(timer.state)
        val newest = currentBucket()
        historyStore.load(newest - MonitoringPolicy.HISTORY_BUCKETS + 1, newest).forEach { sample ->
            probes.getOrNull(sample.probe)?.history?.add(sample.bucket, sample.centiC, sample.excursion)
        }
    }

    val isActive: Boolean get() = active

    /** True when scanning should be recovered because no packet arrived in time. */
    val isSignalLost: Boolean
        get() = active && settings.selectedDevice != null &&
            clock.elapsedMs() - (lastPacketElapsedMs ?: monitorStartElapsedMs) >=
            MonitoringPolicy.STALE_TIMEOUT_MS

    // ---- Monitoring lifecycle ------------------------------------------------

    fun startMonitoring() {
        if (active) return
        active = true
        fault = null
        monitorStartElapsedMs = clock.elapsedMs()
        lastPacketElapsedMs = null
        warningAcknowledged = false
        // Probe presence from an earlier session is not evidence for this one.
        // The first packet decides, so an already missing probe alarms then.
        probes.forEach {
            it.absent = false
            it.missingAcknowledged = false
            clearRecovery(it)
        }
        emit()
    }

    /** Stopping is an explicit user decision, so it also ends active alarms. */
    fun stopMonitoring() {
        active = false
        fault = null
        candidates.clear()
        probes.forEach {
            it.alarm = ProbeAlarmState()
            it.missingAcknowledged = false
            clearRecovery(it)
        }
        emit()
    }

    fun onRadioFault(newFault: RadioFault?) {
        if (fault == newFault) return
        fault = newFault
        emit()
    }

    // ---- Advertisement path --------------------------------------------------

    fun onAdvertisement(ad: Advertisement) {
        if (!active || !ReadingValidator.isH5182(ad.name, ad.hasH5182ServiceUuid)) return
        val now = clock.elapsedMs()
        rememberCandidate(ad, now)

        if (settings.selectedDevice?.address != ad.address) {
            emit()
            return
        }

        // Any packet from the selected device proves radio reception, even if
        // its contents are unusable. It does not make a temperature fresh.
        lastPacketElapsedMs = now
        if (fault == RadioFault.SCAN_ERROR) fault = null

        val selected = H5182Decoder.selectValue(ad.manufacturerData) as? SelectResult.Selected
        val frame = selected?.let { (H5182Decoder.decode(it.value) as? DecodeResult.Success)?.frame }
        if (frame != null) {
            batteryPercent = frame.batteryPercent
            applySample(0, ReadingValidator.validate(frame.probe1.temperature), now)
            applySample(1, ReadingValidator.validate(frame.probe2.temperature), now)
        }
        emit()
    }

    private fun applySample(index: Int, sample: ProbeSample, now: Long) {
        if (!settings.probes[index].inUse) return
        val probe = probes[index]
        when (sample) {
            // An unusable word is not evidence of a stable reconnection.
            ProbeSample.Rejected -> clearRecovery(probe)
            ProbeSample.Absent -> {
                // Only the change to missing starts an episode, so repeated
                // packets and failed recoveries neither restart sound nor undo Silence.
                if (!probe.absent) {
                    probe.missingEpisode += 1
                    probe.missingAcknowledged = false
                }
                probe.absent = true
                probe.temperatureC = null
                clearRecovery(probe)
            }
            is ProbeSample.Valid -> {
                if (probe.absent && !qualifiesRecovery(probe, now)) return
                val returned = probe.absent
                probe.absent = false
                probe.missingAcknowledged = false
                probe.temperatureC = sample.centiC
                probe.lastValidElapsedMs = now
                recordHistory(index, sample.centiC)
                transition(index, AlarmEvaluator.evaluate(probe.alarm, settings.probes[index], sample.centiC), sample.centiC)
                // A held temperature alarm must report the reading that resumed it.
                if (returned && probe.alarm.audible) probe.entryReadingC = sample.centiC
            }
        }
    }

    /**
     * Counts one valid reading toward recovery. A gap reaching the signal-loss
     * timeout restarts the period, because elapsed time without packets is not
     * evidence that the probe stayed connected.
     */
    private fun qualifiesRecovery(probe: ProbeRuntime, now: Long): Boolean {
        val start = probe.recoveryStartElapsedMs
        if (start == null || now - probe.recoveryLastElapsedMs >= MonitoringPolicy.STALE_TIMEOUT_MS) {
            probe.recoveryStartElapsedMs = now
            probe.recoveryLastElapsedMs = now
            probe.recoveryReadings = 1
            return false
        }
        probe.recoveryLastElapsedMs = now
        probe.recoveryReadings += 1
        val qualified = now - start >= MonitoringPolicy.PROBE_RECOVERY_MS &&
            probe.recoveryReadings >= MonitoringPolicy.PROBE_RECOVERY_MIN_READINGS
        if (qualified) clearRecovery(probe)
        return qualified
    }

    private fun clearRecovery(probe: ProbeRuntime) {
        probe.recoveryStartElapsedMs = null
        probe.recoveryLastElapsedMs = 0
        probe.recoveryReadings = 0
    }

    private fun recordHistory(index: Int, centiC: Int) {
        val bucket = currentBucket()
        // Classified now, with the limits in force for this reading.
        val excursion = HistoryGraph.classify(settings.probes[index], centiC)
        if (probes[index].history.add(bucket, centiC, excursion)) {
            historyStore.append(
                HistorySample(index, bucket, centiC, excursion),
                bucket - MonitoringPolicy.HISTORY_BUCKETS + 1,
                bucket,
            )
        }
    }

    /** A change into HIGH or LOW is a new episode with a new notification identity. */
    private fun transition(index: Int, next: ProbeAlarmState, readingC: Int) {
        val probe = probes[index]
        if (next.level != AlarmLevel.NORMAL && next.level != probe.alarm.level) {
            probe.episode += 1
            probe.entryReadingC = readingC
        } else if (next.audible && !probe.alarm.audible) {
            // Sound is resuming, so the notification must show the current reading.
            probe.entryReadingC = readingC
        }
        probe.alarm = next
    }

    private fun rememberCandidate(ad: Advertisement, now: Long) {
        val name = ad.name?.takeIf { it.isNotBlank() } ?: "H5182"
        candidates[ad.address] = SelectedDevice(ad.address, name) to now
        candidates.entries.removeAll { now - it.value.second > MonitoringPolicy.CANDIDATE_EXPIRY_MS }
        while (candidates.size > MonitoringPolicy.MAX_CANDIDATES) {
            candidates.remove(candidates.entries.minByOrNull { it.value.second }!!.key)
        }
    }

    // ---- Periodic work -------------------------------------------------------

    fun tick() {
        val now = clock.elapsedMs()
        if (timer.running && now - lastCheckpointElapsedMs >= MonitoringPolicy.TIMER_CHECKPOINT_MS) {
            timer.checkpoint(now)
            cookStore.save(timer.state)
            lastCheckpointElapsedMs = now
        }
        emit()
    }

    // ---- User intents --------------------------------------------------------

    fun acknowledgeProbe(index: Int) {
        val probe = probes.getOrNull(index) ?: return
        // A notification action can arrive just after the probe was taken out of use.
        if (!settings.probes[index].inUse) return
        // While missing, Silence applies only to the missing alarm. A held
        // temperature alarm was never heard for this condition and stays armed.
        if (probe.absent) {
            probe.missingAcknowledged = true
            probe.missingAcknowledgedElapsedMs = clock.elapsedMs()
        } else {
            probe.alarm = AlarmEvaluator.acknowledge(probe.alarm)
        }
        emit()
    }

    fun acknowledgeWarning() {
        // A duplicate tap must not postpone the next repeat.
        if (!warningAcknowledged) {
            warningAcknowledged = true
            warningAcknowledgedElapsedMs = clock.elapsedMs()
        }
        emit()
    }

    /** Invalid edits are refused. Settings persist before they take effect. */
    fun updateProbeSettings(index: Int, new: ProbeSettings): Boolean {
        if (!new.isValid || index !in settings.probes.indices) return false
        val old = settings.probes[index]
        settings = settings.copy(probes = settings.probes.toMutableList().also { it[index] = new })
        settingsStore.save(settings)
        val probe = probes[index]
        if (old.inUse != new.inUse) {
            resetProbeRuntime(probe)
            emit()
            return true
        }
        // Limits edited while not in use take effect from the first fresh reading.
        if (!new.inUse) {
            emit()
            return true
        }
        val fresh = probe.temperatureC?.takeIf { active && !isProbeStale(probe, clock.elapsedMs()) }
        val next = AlarmEvaluator.onConfigChanged(probe.alarm, old, new, fresh)
        // Editing the active threshold while still beyond it is a new episode.
        // It needs a new identity so the notification shows the new limit and sounds.
        val activeThresholdChanged = when (probe.alarm.level) {
            AlarmLevel.HIGH -> old.highC != new.highC
            AlarmLevel.LOW -> old.lowC != new.lowC
            AlarmLevel.NORMAL -> false
        }
        if (activeThresholdChanged && next.level == probe.alarm.level) {
            probe.episode += 1
            probe.entryReadingC = fresh ?: probe.entryReadingC
        }
        transition(index, next, fresh ?: probe.entryReadingC)
        emit()
        return true
    }

    /** Clears alarms, reminders, and readings. History is kept as a dated record. */
    private fun resetProbeRuntime(probe: ProbeRuntime) {
        probe.temperatureC = null
        probe.lastValidElapsedMs = null
        probe.absent = false
        probe.missingAcknowledged = false
        clearRecovery(probe)
        probe.alarm = ProbeAlarmState()
    }

    fun updateDisplay(displayFahrenheit: Boolean, colorBandC: Int, signalLossWarning: Boolean) {
        settings = settings.copy(
            displayFahrenheit = displayFahrenheit,
            colorBandC = colorBandC,
            signalLossWarning = signalLossWarning,
        )
        settingsStore.save(settings)
        emit()
    }

    /** Readings from different thermometers must never share alarm state or history. */
    fun selectDevice(device: SelectedDevice) {
        if (settings.selectedDevice?.address == device.address) return
        settings = settings.copy(selectedDevice = device)
        settingsStore.save(settings)
        historyStore.clear()
        probes.forEach {
            it.temperatureC = null
            it.lastValidElapsedMs = null
            it.absent = false
            it.missingAcknowledged = false
            clearRecovery(it)
            it.alarm = ProbeAlarmState()
            it.history.clear()
        }
        batteryPercent = null
        lastPacketElapsedMs = null
        monitorStartElapsedMs = clock.elapsedMs()
        warningAcknowledged = false
        emit()
    }

    fun startTimer() = changeTimer { timer.start(clock.elapsedMs()) }

    fun stopTimer() = changeTimer { timer.stop(clock.elapsedMs()) }

    fun resetTimer() = changeTimer { timer.reset() }

    private fun changeTimer(change: () -> Unit) {
        change()
        cookStore.save(timer.state)
        lastCheckpointElapsedMs = clock.elapsedMs()
        emit()
    }

    // ---- Output --------------------------------------------------------------

    private fun isProbeStale(probe: ProbeRuntime, now: Long): Boolean {
        val last = probe.lastValidElapsedMs ?: return true
        return now - last >= MonitoringPolicy.STALE_TIMEOUT_MS
    }

    private fun monitorState(): MonitorState = when {
        !active -> MonitorState.STOPPED
        fault == RadioFault.BLUETOOTH_OFF -> MonitorState.BLUETOOTH_OFF
        fault == RadioFault.PERMISSION_REQUIRED -> MonitorState.PERMISSION_REQUIRED
        fault == RadioFault.LOCATION_OFF -> MonitorState.LOCATION_OFF
        fault == RadioFault.SCAN_ERROR -> MonitorState.SCAN_ERROR
        isSignalLost -> MonitorState.STALE
        lastPacketElapsedMs == null -> MonitorState.STARTING
        else -> MonitorState.MONITORING
    }

    private fun warningCondition(): Boolean {
        if (!active || !settings.signalLossWarning || settings.selectedDevice == null) return false
        // These faults definitely stop reception. A scan error may be transient,
        // so it warns through the signal timeout instead.
        val immediate = fault == RadioFault.BLUETOOTH_OFF ||
            fault == RadioFault.PERMISSION_REQUIRED || fault == RadioFault.LOCATION_OFF
        return immediate || isSignalLost
    }

    private fun emit() {
        val now = clock.elapsedMs()
        val warning = warningCondition()
        // Clearing here makes the next loss a new, audible warning episode. During
        // a continuing loss, silence lasts only for the owner-set repeat interval.
        if (!warning ||
            (warningAcknowledged && now - warningAcknowledgedElapsedMs >= MonitoringPolicy.SIGNAL_WARNING_REPEAT_MS)
        ) {
            warningAcknowledged = false
        }
        // Ending the Silence makes the alert desired again, which reposts its
        // notification and lock-screen page and restarts the shared alarm sound.
        probes.forEach {
            if (it.absent && it.missingAcknowledged &&
                now - it.missingAcknowledgedElapsedMs >= MonitoringPolicy.MISSING_PROBE_REPEAT_MS
            ) {
                it.missingAcknowledged = false
            }
        }

        val newest = currentBucket()
        publish(
            AppSnapshot(
                state = monitorState(),
                settings = settings,
                candidates = candidates.values.map { it.first },
                batteryPercent = batteryPercent,
                lastPacketAgeMs = lastPacketElapsedMs?.let { now - it },
                probes = probes.mapIndexed { index, probe -> probeView(index, probe, now, newest) },
                timerRunning = timer.running,
                timerElapsedMs = timer.elapsedMs(now),
                warningActive = warning,
                warningAudible = warning && !warningAcknowledged,
            ),
        )

        // Render after publishing: a notification can launch the lock-screen
        // alarm page, which must find a snapshot containing this alarm.
        val nextAlerts = AlertState(
            alarms = probes.mapIndexedNotNull { index, probe -> alertFor(index, probe) },
            missingProbes = probes.mapIndexedNotNull { index, probe ->
                if (isMissingAudible(probe)) MissingProbeAlert(index, probe.missingEpisode) else null
            },
            warningActive = warning,
            warningAcknowledged = warningAcknowledged,
            displayFahrenheit = settings.displayFahrenheit,
        )
        if (nextAlerts != lastAlerts) {
            lastAlerts = nextAlerts
            alerts.render(nextAlerts)
        }
    }

    private fun alertFor(index: Int, probe: ProbeRuntime): ProbeAlert? {
        val config = settings.probes[index]
        val threshold = when (probe.alarm.level) {
            AlarmLevel.HIGH -> config.highC
            AlarmLevel.LOW -> config.lowC
            AlarmLevel.NORMAL -> null
        } ?: return null
        return ProbeAlert(
            probe = index,
            episode = probe.episode,
            level = probe.alarm.level,
            readingC = probe.entryReadingC,
            thresholdC = threshold,
            acknowledged = probe.alarm.acknowledged,
            audible = probe.alarm.audible && !probe.absent,
        )
    }

    private fun isMissingAudible(probe: ProbeRuntime): Boolean =
        active && probe.absent && !probe.missingAcknowledged

    private fun probeView(index: Int, probe: ProbeRuntime, now: Long, newestBucket: Long): ProbeView {
        val stale = probe.lastValidElapsedMs != null && (!active || isProbeStale(probe, now))
        // Inside the limit, the card reads normal even before the episode re-arms.
        // A missing probe has no known temperature, so it shows no high or low state.
        val visibleLevel =
            if (probe.alarm.beyondLimit && !probe.absent) probe.alarm.level else AlarmLevel.NORMAL
        val status = when {
            !settings.probes[index].inUse -> ProbeStatus.NOT_IN_USE
            probe.absent -> ProbeStatus.NO_PROBE
            probe.lastValidElapsedMs == null -> ProbeStatus.WAITING
            stale -> ProbeStatus.STALE
            visibleLevel == AlarmLevel.HIGH -> ProbeStatus.HIGH
            visibleLevel == AlarmLevel.LOW -> ProbeStatus.LOW
            else -> ProbeStatus.NORMAL
        }
        return ProbeView(
            status = status,
            temperatureC = probe.temperatureC,
            ageMs = probe.lastValidElapsedMs?.let { now - it },
            alarmLevel = visibleLevel,
            alarmAudible = if (probe.absent) isMissingAudible(probe) else probe.alarm.audible,
            history = probe.history.window(newestBucket),
            excursions = probe.history.excursionWindow(newestBucket),
            recovering = active && probe.absent && probe.recoveryStartElapsedMs != null,
        )
    }

    private fun currentBucket(): Long = Math.floorDiv(clock.wallMs(), MonitoringPolicy.HISTORY_BUCKET_MS)
}
