# DIM8 Govee Thermometer — Architecture

Status: proposed architecture for owner review

Initial release: v0.1

Target device: Google Pixel XL (`marlin`), Android 10/API 29

Package: `com.dim8labs.goveethermometer`

## Goals and release priorities

The application monitors one selected Govee H5182 locally, shows both probes, evaluates independent local high/low alarms, and continues monitoring with the screen off. It has no backend, accounts, telemetry, advertisements, or cloud dependency.

Work in this order for the Wednesday, November 25, 2026 deadline:

1. Correct H5182 advertisement identification and temperature decoding.
2. Reliable foreground monitoring, stale/loss detection, and high/low alarms.
3. Persistent settings, timer state, and restart/recovery behavior.
4. Readable minimalist UI.
5. Bounded 30-minute sparklines and elapsed cook time.

Monitoring reliability is the release gate. Cosmetic work must not displace physical screen-off, alarm, and recovery testing.

## Platform and composition

- Kotlin and native Android/AndroidX APIs.
- `minSdk 29` for v0.1. Compile/target SDK selection is an implementation-time toolchain decision; behavior must be verified on the API 29 Pixel XL.
- One application module unless a demonstrated test/build need justifies more.
- Explicit constructor composition at the application/service boundary. Avoid dependency-injection, BLE, charting, and background-work frameworks for v0.1.
- A foreground service owns continuous monitoring. The activity renders state and sends user intents; it does not own the scan lifetime.
- Local storage only. Small preferences/cook state may use `SharedPreferences`; bounded history may use a small `SQLiteOpenHelper` store. Hide either choice behind interfaces so storage can change without affecting alarm or UI logic.

## Component boundaries

```text
Android BLE scan callback
        │ raw advertisement + monotonic receipt time
        ▼
BleScanner ──> H5182Decoder ──> ReadingValidator
                                      │ valid Reading / rejection
                                      ▼
                              MonitoringCoordinator
                              ├─ FreshnessTracker
                              ├─ AlarmEvaluator
                              ├─ HistoryStore
                              └─ CookTimer
                                      │ immutable AppSnapshot
                     ┌────────────────┴────────────────┐
                     ▼                                 ▼
              NotificationPort                    Main UI
```

Responsibilities:

- `BleScanner`: Android scan filters/settings, callback normalization, scan errors, restart/backoff, and selected-device matching. It owns no alarm policy.
- `H5182Decoder`: pure Kotlin transformation of byte arrays into decoded fields or explicit parse errors. It has no Android dependency and never substitutes a plausible value for missing data.
- `ReadingValidator`: rejects malformed lengths, wrong identity, unavailable probe sentinels, and values outside the approved physical range.
- `MonitoringCoordinator`: sole writer of authoritative runtime state. It timestamps accepted readings, updates history, evaluates alarms, and publishes immutable snapshots.
- `AlarmEvaluator`: pure state machine per probe for high, low, normal, acknowledged, and re-arm behavior.
- `SettingsStore`, `CookStateStore`, `HistoryStore`: narrow persistence interfaces. Each owns schema/version handling and bounded storage.
- `NotificationPort`: foreground status and alarm/warning notifications, channels, sound, and acknowledgement actions. It consumes alarm decisions rather than deriving them.
- Activity/view model: maps snapshots and user intents to the main screen. It does not scan, decode, or evaluate thresholds.

Core data types should use Celsius internally and represent absence explicitly. Unit conversion belongs at presentation/input boundaries. Use monotonic elapsed time for freshness and duration while running; also persist wall-clock anchors needed to reconstruct a running cook after process death or reboot, defensively handling clock changes.

## H5182 advertisement protocol

This section distinguishes three evidence levels:

- **Verified from source:** two or more reviewed decoders/captures agree, or one decoder contains an explicit capture and matching decode path. This is still not verification on this project's thermometer.
- **Inferred:** a reasonable interpretation of the reviewed code or captures that is not explicitly established by them.
- **Requires physical-device validation:** behavior that must be checked on the target H5182 and Pixel XL before release.

### Reviewed sources

- `wcbonner/GoveeBTTempLogger`: H5182 recognition and `Govee_Temp::ReadMSG` in [`goveebttemplogger.cpp`](https://github.com/wcbonner/GoveeBTTempLogger/blob/master/goveebttemplogger.cpp), plus broadcast examples in the repository [README](https://github.com/wcbonner/GoveeBTTempLogger). This is the primary capture source.
- `Bluetooth-Devices/govee-ble`: H5182 branch, `PACKED_hhbhh`, and `decode_temps_probes` in [`parser.py`](https://github.com/Bluetooth-Devices/govee-ble/blob/main/src/govee_ble/parser.py); its H5182 fixture and expected output are in [`tests/test_parser.py`](https://github.com/Bluetooth-Devices/govee-ble/blob/main/tests/test_parser.py#L940-L951) and the [H5182 test](https://github.com/Bluetooth-Devices/govee-ble/blob/main/tests/test_parser.py#L5221-L5294).
- `TechnoSwiss/GoveeBT-Python`: the H5182 branch in [`GoveeBT.py`](https://github.com/TechnoSwiss/GoveeBT-Python/blob/main/GoveeBT.py). Its README says it is based on `GoveeBTTempLogger`, so it corroborates the translation into a Bleak callback but is not independent protocol discovery.
- `govee-ble` records a prior H5182 manufacturer-key correction in its [change history](https://github.com/Bluetooth-Devices/govee-ble/blob/main/CHANGELOG.md). This reinforces that a single captured key is not a safe model identifier.

### Discovery identifiers

**Verified from source:**

- Complete/local names have been matched as `GVH5182...`; reviewed code uses either a `GVH5182` prefix or the broader substring `H5182`.
- The advertised 128-bit service UUID used to identify this model is `00008251-0000-1000-8000-00805f9b34fb` (16-bit model value `0x8251` in the Bluetooth base UUID).
- Temperature data is in manufacturer-specific advertising data, normally a 17-byte value after its two-byte manufacturer identifier has been separated by the host Bluetooth API.
- Captured manufacturer-map keys vary. Reviewed examples include `0x1330`, `0x4f36`, and `0x2730`; the corresponding first payload byte also varies. The keys appear device/address-related rather than a stable Bluetooth SIG company identifier.

Therefore, identify/filter the H5182 primarily by the `0x8251` service UUID and, where present, the `GVH5182` name. Treat `0x2730` only as a known captured key and parser fallback, never as the universal H5182 manufacturer ID. A 17-byte manufacturer value is supporting packet-shape evidence, not sufficient identity by itself because H5184 uses the same length and field shape.

**Requires physical-device validation:** capture the actual Android `ScanRecord` and confirm local name, service UUID presence in every temperature-bearing result, address stability, all manufacturer keys, and whether the advertisement and scan response arrive separately.

### Indexing and 17-byte layout

The table indexes the byte array returned for one entry by Android [`ScanRecord.getManufacturerSpecificData()`](https://developer.android.com/reference/android/bluetooth/le/ScanRecord), after Android has removed the two-byte manufacturer identifier. Do not apply these offsets directly to the full raw AD structure: raw data also contains AD length/type and the two manufacturer-ID bytes. BlueZ/Bleak maps use the same logical split, which is why their decoder offsets align with Android's expected value array.

Some non-Android capture/library paths append an `INTELLI_ROCKS...`/Apple-style block to the proprietary bytes. `govee-ble` strips that known 25-byte suffix before dispatch. Native Android normally exposes manufacturer entries separately; ignore unrelated entry `0x004c` and require that the selected proprietary value reduces to exactly 17 bytes rather than applying temperature offsets to an arbitrary combined buffer.

| Value offset | Field | Decode and evidence |
|---|---|---|
| 0 | Variable device/address-related byte | **Verified from source:** varies among captures; not part of a temperature field. |
| 1–4 | Header/state (`01 00 01 01` in reviewed H5182 captures) | **Verified from source:** observed pattern. Meaning is unknown; do not validate more narrowly until the target device is captured. |
| 5 | Battery/status | **Verified from source:** `GoveeBTTempLogger` and `GoveeBT-Python` decode `data[5] & 0x7f`; examples with `0xe4` and `0x64` both yield 100%. `govee-ble` does not currently publish H5182 battery. High-bit meaning and accuracy require physical validation. |
| 6–7 | Unknown state | **Verified from source:** ignored by H5182 decoders. Preserve raw bytes. |
| 8–9 | Probe 1 temperature | **Verified from source:** big-endian 16-bit raw value divided by 100 gives °C. |
| 10–11 | Probe 1 advertised alarm/target | **Verified from source:** same byte order and scale. Sources call it an alarm temperature; they do not establish that it is the same concept as this app's target. |
| 12 | Unknown separator/state | **Verified from source:** ignored by H5182 decoders and varies in captures. Preserve it. |
| 13–14 | Probe 2 temperature | **Verified from source:** big-endian 16-bit raw value divided by 100 gives °C. |
| 15–16 | Probe 2 advertised alarm/target | **Verified from source:** same byte order and scale as probe 1. |

`govee-ble` unpacks offsets 8–16 with Python format `>hhbhh`, so its four temperature-like words are signed big-endian values. `GoveeBTTempLogger` also decodes into C++ `short`; `GoveeBT-Python` performs an unsigned shift/or. The implementations therefore disagree about general negative-word handling even though they agree on byte order and `/100` scaling.

For this app, inspect the unsigned 16-bit raw word first: treat `0xffff` explicitly as unavailable, accept ordinary nonnegative values only within the approved cooking-probe range, and reject other high-bit/out-of-range words pending hardware evidence. Do not copy `govee-ble`'s behavior of converting every negative signed word to `0.0`, or `GoveeBT-Python`'s behavior of turning `0xffff` into `655.35 °C`.

### Advertised probe and alarm/target behavior

**Verified from source:** one H5182 17-byte value carries both probe temperatures and one advertised alarm/target word for each probe. The reviewed sources do not expose distinct advertised low and high limits. `GoveeBTTempLogger` captures show the advertised values changing when device alarms were set, but do not establish full semantics or a write protocol.

**Correction to earlier interpretation:** the alternating 17-byte examples with four configured alarm values are evidence for the four-probe H5184, not for H5182. The current `govee-ble` H5184 decoder uses offset 6 to select probe pair 1–2 versus 3–4. No reviewed source supports alternating probe-pair formats for the dual-probe H5182. Preserve the H5182 assumption of both probes in every valid frame unless physical captures disprove it.

The application target and local low/high thresholds remain separate authoritative settings. If advertised words are ever shown, label them `Device alarm` (not `Target`) until their meaning is physically established.

### Source packet examples for future Kotlin fixtures

These are manufacturer **value** bytes only; the separate map key and unrelated `0x004c` data are omitted.

| Source and manufacturer key | 17-byte value | Expected decode |
|---|---|---|
| `GoveeBTTempLogger`, key `0x1330` | `2701000101e4018008341cdc8008341cdc` | Battery candidate 100%; P1 21.00 °C; device alarm 1 73.88 °C; P2 21.00 °C; device alarm 2 73.88 °C. |
| `GoveeBTTempLogger` README, key `0x3013` | `270100010164018007d0ffff860708ffff` | Battery candidate 100%; P1 20.00 °C; alarm 1 unavailable; P2 18.00 °C; alarm 2 unavailable. |
| `govee-ble` test, key `0x2730` | `6301000101e40101ffff120301ffff0000` | Battery candidate 100%; P1 unavailable; device alarm 1 46.11 °C; P2 unavailable; final raw word `0x0000`. The upstream test maps unavailable probes to 0.0; this app must instead retain `Unavailable`. Meaning of final zero requires physical validation. |

Fixtures derived from public examples should record provenance and use synthetic/anonymized addresses. Add target-device captures rather than relying only on these three inherited examples.

### Invalid values and decoder limits

- **Verified from source:** `0xffff` denotes a disconnected/missing probe or an unset/unavailable advertised alarm in `GoveeBTTempLogger` comments and captures.
- **Requires physical-device validation:** whether `0x0000` in an advertised alarm word means 0 °C, disabled/unset, or another state; whether any other sentinels or status bits exist; the valid measurement range; and whether battery bit 7 is merely a flag.
- Neither reviewed H5182 decoder verifies a checksum, sequence number, or integrity field. No such field is established in the 17 bytes.
- The two general-purpose decoders have unsafe missing-value behavior for this product: one collapses negative/sentinel words to zero and one treats them as large positive temperatures. The Kotlin decoder must return typed field errors/absence and must never feed those values into history or alarms.
- A valid radio packet can contain one unavailable probe and one valid probe. Validate and timestamp fields independently.
- Do not enforce the observed header bytes or unknown offsets until multiple target-device captures show they are invariant.

### Scanning versus GATT

**Verified from source:** temperature, advertised alarm/target words, and the battery candidate are decoded from connectionless manufacturer advertisements. All three reviewed projects receive these fields from scan callbacks/raw advertisements; none connects to the H5182 to obtain the current temperatures. A GATT connection is therefore not required by the documented receive path.

**Inferred:** controller-level passive scanning should be sufficient because H5182 is not marked by `govee-ble` as requiring active scan-response collection. This does not prove Android API 29 delivery under screen-off or low-power conditions. The Pixel XL must validate filtered scanning with the selected Android scan settings, screen locked, and the Govee app absent and present.

Reading an advertised device alarm is not the same as writing settings. None of the reviewed H5182 decode paths contains a verified H5182 command for changing alarm/target values. `GoveeBTTempLogger` also has connected protocols for history download/authentication on some Govee devices, but that does not establish H5182 configuration-write support. v0.1 must not connect or write GATT settings; local app thresholds remain authoritative.

### Licensing

- `GoveeBTTempLogger` places its decoder source under the [MIT License](https://github.com/wcbonner/GoveeBTTempLogger/blob/master/goveebttemplogger.cpp); retain its copyright and license notice if copying substantial code.
- `GoveeBT-Python` is [MIT-licensed](https://github.com/TechnoSwiss/GoveeBT-Python) and states that its decoder is based on `GoveeBTTempLogger`; it should not be treated as an independently licensed origin for copied upstream code.
- `govee-ble` is [Apache-2.0 licensed](https://github.com/Bluetooth-Devices/govee-ble/blob/main/LICENSE), while `parser.py` also states that its parser was copied from an MIT-licensed source. Exact reuse requires preserving the applicable notices and satisfying Apache NOTICE/attribution obligations where they apply.

Protocol facts, offsets, and independently written decoding logic are preferable to copying source. Before implementation, record third-party attribution for any code or fixture copied verbatim and have the owner review the final dependency/license inventory. This section is engineering guidance, not legal advice.

### Required target-device evidence

Before decoder acceptance, capture advertisements from the actual H5182 with both probes at distinguishable temperatures and compare them with a trusted reference. Capture each probe disconnected, several battery levels if practical, and device alarm changes. Record Android's separated manufacturer key/value, service UUIDs, local name, timestamps, and anonymized raw bytes. Convert those captures into unit fixtures and keep all physical-test results distinct from the source-derived examples above.

## BLE and foreground operation on Android 10

- API 29 scanning needs manifest `BLUETOOTH`/`BLUETOOTH_ADMIN` and runtime fine-location permission. Because continuous discovery occurs in a service on Android 10, account for background-location requirements. The implementation plan must reconcile declarations with the chosen target SDK and test them on the actual phone. See Android's [Bluetooth permission guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- Start monitoring only through a visible user action, then immediately promote the service with an ongoing notification. The service must expose stop-monitoring and open-app actions.
- Use at least one non-empty H5182 scan filter. Android documents that unfiltered scans can stop when the screen turns off; this behavior must be physically tested. See [`BluetoothLeScanner`](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner).
- Prefer the lowest scan duty cycle proven reliable. Do not assume `LOW_LATENCY` remains effective in the background. Keep scan callbacks short and serialize processing onto one bounded owner.
- Treat Bluetooth-off, permission revocation, adapter errors, scan failure, and service/process restart as explicit states. Retry recoverable failures with bounded exponential backoff and reset backoff after a valid reading. Do not busy-loop.
- A foreground service improves process priority but does not guarantee uninterrupted scanning. Explain battery-optimization effects and provide a direct settings path only if physical tests show it is needed. Do not promise immunity from OEM/OS process management.
- Android recommends a foreground service when a BLE interaction must remain alive; see [background BLE guidance](https://developer.android.com/develop/connectivity/bluetooth/ble/background).

## Freshness, loss, and recovery

Track last packet and last valid reading separately per probe using monotonic time. A received malformed/unavailable packet proves radio reception but does not make a temperature fresh. State must distinguish `starting`, `monitoring`, `stale`, `Bluetooth off`, `permission required`, `scan error`, and `stopped`.

The stale timeout and scan-restart interval must be constants backed by actual advertisement-cadence captures. Until measured, their numeric values are unresolved; tests should use an injected clock and configurable duration. When stale:

- keep the last value visibly marked with its age rather than presenting it as current;
- raise one connection/stale warning, subject to acknowledgement policy;
- continue bounded scan recovery automatically; and
- never evaluate a new temperature alarm from stale or invalid data.

Recovery on a valid packet clears the stale state, records the gap, and resumes evaluation without replaying all missed alarm transitions.

## Alarm policy

Each probe has enabled/disabled low and high thresholds plus a positive hysteresis. Validate `low < high` when both are enabled. The state machine is edge-triggered:

- enter `HIGH` at or above the high threshold; re-arm only after falling below `high − hysteresis`;
- enter `LOW` at or below the low threshold; re-arm only after rising above `low + hysteresis`;
- acknowledgement silences the current episode but leaves a visible violation until recovery;
- continued violating samples do not restart sound; crossing the re-arm boundary permits a later new episode;
- a direct low-to-high or high-to-low transition is a new episode; and
- stale/invalid readings produce a monitoring warning, not a fabricated temperature alarm.

Persist thresholds and preferences before applying them. Notifications must use separate monitoring and alarm channels. The alarm channel should use a high-importance audible notification and visible full probe context; comply with user-controlled channel sound settings. A notification action must acknowledge/silence without requiring the activity. Exact default thresholds, hysteresis, sound, vibration, and escalation duration require owner approval and physical audibility testing.

## Persistence and bounded history

Persist:

- selected thermometer identity using the most stable identifiers observed, with a user-friendly name;
- per-probe target, enabled high/low thresholds, hysteresis, display units, tolerance/color preferences, and notification preferences;
- cook timer state (`stopped`/`running`, accumulated duration, restart anchors); and
- a bounded recent history sufficient for approximately 30 minutes per probe.

History stores accepted samples only. Down-sample on a fixed time bucket or replace the current bucket so irregular advertisements cannot grow storage or overweight bursts. Prune by age and a hard row/count cap. Gaps remain gaps; do not interpolate missing measurements for alarm decisions. On boot, settings remain available, but monitoring should not silently begin unless an owner-approved boot policy is added; restore a running timer defensively and show that monitoring must be resumed.

## Verification strategy

Automated verification should include:

- pure JVM decoder tests from recorded, anonymized H5182 advertisements: both probes, advertised alarms, battery candidate, `0xFFFF`, malformed/truncated data, wrong identity, and range rejection;
- table-driven alarm tests for exact boundaries, hysteresis, acknowledgement, re-arm, probe independence, threshold edits, and stale transitions;
- injected-clock tests for irregular packets, freshness, history bucketing/pruning, and cook timer restoration/clock changes;
- persistence migration and process-recreation tests; and
- Android instrumentation tests for service commands, notification actions/channels, and basic state-to-UI rendering where practical.

Automated success does not satisfy physical acceptance.

### Physical-device acceptance checklist

Record date, APK/version or commit, phone build, H5182 identity, tester, steps, and observed result. Every item starts **not tested** for this documentation task.

- [ ] Install the debug/release-candidate APK on the Pixel XL through ADB and launch it.
- [ ] Grant required permissions; verify denial and later recovery are understandable.
- [ ] Select the actual H5182 and receive temperatures matching an independent/trusted reading.
- [ ] Change probe 1 and probe 2 independently; verify identity is not swapped.
- [ ] Disconnect each probe; verify unavailable data is shown safely and does not create a false temperature.
- [ ] Compare battery reporting with the physical/device reference where supported; otherwise label it unavailable.
- [ ] Trigger each probe's high alarm at its boundary; verify sound, notification, and visible state.
- [ ] Trigger each probe's low alarm at its boundary.
- [ ] Acknowledge/silence an alarm, remain beyond the threshold, cross the hysteresis re-arm boundary, and trigger it again.
- [ ] Lock the screen for a representative cook interval; verify continued reception, correct alarm timing, and foreground notification.
- [ ] Move the H5182 out of range or power it off; verify stale/loss timing, warning, bounded recovery, and automatic reconnection.
- [ ] Disable/re-enable Bluetooth and revoke/restore permission; verify safe recovery.
- [ ] Force-stop/process-kill and relaunch as applicable; verify documented service and state behavior.
- [ ] Edit targets, high/low limits, units/preferences, and selected thermometer; restart the app and reboot the phone; verify persistence.
- [ ] Start, stop, resume, and reset the cook timer; verify useful state across restart and reboot.
- [ ] Run longer than 30 minutes with irregular/repeated packets; verify both sparklines remain bounded, show gaps honestly, and do not affect monitoring.
- [ ] Test alarm audibility/readability with the screen locked in the expected outdoor/cooking environment.

## Decisions and unresolved questions

Proposed decisions: advertisements only for v0.1; app-local alarms; one selected H5182; foreground service owns scanning; pure decoder/alarm core; Celsius canonical storage; bounded local history; no auto-start monitoring on boot without later approval.

Questions requiring evidence or owner decision:

1. What exact local name, service UUIDs, manufacturer keys, address behavior, packet cadence, and payload variants does the target H5182/firmware emit?
2. Is byte 5 a dependable battery percentage on this unit, and what does its high bit mean?
3. What are the approved physical range, stale timeout, retry limits, default thresholds, hysteresis, target-color tolerance, and alarm sound/vibration policy?
4. Does the H5182 expose only a high alarm in advertisements, and do its advertised values change predictably when the Govee app changes settings?
5. Does API 29 screen-off scanning remain reliable with the chosen filter/mode and current battery-optimization setting?
6. Should a cook timer that was running before a reboot include powered-off time, pause at reboot, or prompt the user?
7. Is monitoring resume after reboot desired? It is excluded from the current proposal because background-start behavior and user expectations need explicit approval.
8. Which established DIM8 brand assets and colors are approved for this app?
