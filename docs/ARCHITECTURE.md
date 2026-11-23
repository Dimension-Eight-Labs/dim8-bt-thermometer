# DIM8 BT Thermometer — Architecture

Status: implemented architecture for the v1.0.0 initial release

License: `GPL-3.0-only`. Copyright © 2026 Dimension Eight Labs LLC.

Target device: Google Pixel XL (`marlin`), Android 10/API 29

Package: `com.dim8labs.btthermometer`

## Goals and release priorities

The application monitors one selected Govee H5182 locally, shows both probes, evaluates independent local high/low alarms, and continues monitoring with the screen off. It has no backend, accounts, telemetry, advertisements, or cloud dependency.

Development priorities, in order:

1. Correct H5182 advertisement identification and temperature decoding.
2. Reliable foreground monitoring, stale/loss detection, and high/low alarms.
3. Persistent settings, timer state, and restart/recovery behavior.
4. Readable minimalist UI.
5. Bounded 30-minute history graphs and elapsed cook time.

Monitoring reliability is the release gate. Cosmetic work must not displace physical screen-off, alarm, and recovery testing.

## Platform and composition

- Kotlin and native Android/AndroidX APIs.
- `minSdk` and `targetSdk` are 29, and `compileSdk` is 37. Behavior is verified on the API 29 Pixel XL.
- One application module unless a demonstrated test/build need justifies more.
- Explicit constructor composition at the application/service boundary. Avoid dependency-injection, BLE, charting, and background-work frameworks.
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
- `NotificationPort`: foreground status and alarm/warning notifications, channels, acknowledgement actions, and the app-owned alarm player. It consumes alarm decisions rather than deriving them.
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

Reading an advertised device alarm is not the same as writing settings. None of the reviewed H5182 decode paths contains a verified H5182 command for changing alarm/target values. `GoveeBTTempLogger` also has connected protocols for history download/authentication on some Govee devices, but that does not establish H5182 configuration-write support. The app must not connect or write GATT settings; local app thresholds remain authoritative.

### Licensing

- `GoveeBTTempLogger` places its decoder source under the [MIT License](https://github.com/wcbonner/GoveeBTTempLogger/blob/master/goveebttemplogger.cpp); retain its copyright and license notice if copying substantial code.
- `GoveeBT-Python` is [MIT-licensed](https://github.com/TechnoSwiss/GoveeBT-Python) and states that its decoder is based on `GoveeBTTempLogger`; it should not be treated as an independently licensed origin for copied upstream code.
- `govee-ble` is [Apache-2.0 licensed](https://github.com/Bluetooth-Devices/govee-ble/blob/main/LICENSE), while `parser.py` also states that its parser was copied from an MIT-licensed source. Exact reuse requires preserving the applicable notices and satisfying Apache NOTICE/attribution obligations where they apply.

`H5182Decoder` is independently written from protocol facts and copies no upstream source. Three public sample advertisements are used as test inputs. Attribution and the dependency inventory are in [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). This section is engineering guidance, not legal advice.

### Required target-device evidence

Before decoder acceptance, capture advertisements from the actual H5182 with both probes at distinguishable temperatures and compare them with a trusted reference. Capture each probe disconnected, several battery levels if practical, and device alarm changes. Record Android's separated manufacturer key/value, service UUIDs, local name, timestamps, and anonymized raw bytes. Convert those captures into unit fixtures and keep all physical-test results distinct from the source-derived examples above. The first anonymized capture is `app/src/test/resources/fixtures/h5182-pixel-xl-2026-11-22.txt`.

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
- raise a repeating alarm-stream warning with a lock-screen page until it is silenced or signal returns, and sound it again every 5 minutes after silencing until signal returns or monitoring stops (owner requirement for overnight use, 2026-11-22);
- continue bounded scan recovery automatically; and
- never evaluate a new temperature alarm from stale or invalid data.

Recovery on a valid packet clears the stale state, records the gap, and resumes evaluation without replaying all missed alarm transitions.

## Alarm policy

Each probe has enabled/disabled low and high thresholds plus a positive hysteresis. Validate `low < high` when both are enabled. The state machine is edge-triggered:

- enter `HIGH` at or above the high threshold; re-arm only after falling below `high − hysteresis`;
- enter `LOW` at or below the low threshold; re-arm only after rising above `low + hysteresis`;
- owner decision (2026-11-22): an alarm sounds only while the reading is beyond its limit, stops on return inside the limit, and resumes if an unsilenced episode crosses the limit again;
- acknowledgement silences the current episode through boundary noise until the re-arm point, while the violation remains visible whenever the reading is beyond the limit;
- continued violating samples do not restart sound; crossing the re-arm boundary permits a later new episode;
- a direct low-to-high or high-to-low transition is a new episode;
- stale/invalid readings produce a monitoring warning, not a fabricated temperature alarm; and
- a packet reporting a probe as unavailable (`0xffff`) immediately raises a separate missing-probe alarm for that probe, including on the first packet of a monitoring session. It uses the probe alarm sound, notification, lock-screen page, and Silence control. While a probe is missing, its temperature alarm is held silent, and Silence during a missing period does not acknowledge that temperature alarm.
- recovery from missing requires at least 3 valid readings spanning 5 seconds (owner-approved period, 2026-11-22). Readings during qualification are not displayed, stored, or evaluated. An unavailable or rejected word, or a packet gap reaching the signal-loss timeout, restarts qualification without starting a new missing episode, so intermittent contact cannot restart a silenced alarm. Once qualified, the missing alarm clears and the temperature alarm is re-evaluated with its Silence state intact. A later disconnect is a new missing episode.
- a silenced missing-probe alarm sounds again 5 minutes after the latest Silence while the probe remains missing, including during recovery qualification (owner requirement). Contact changes do not move this schedule. Recovery or taking the probe out of use clears it. Its timer is independent per probe and separate from the signal-loss repeat.
- each probe has a persistent In Use setting, defaulting to in use. Packets for an unused probe are ignored, so it raises no alarms and adds no history. Changing the setting clears that probe's alarms, reminder, recovery state, and current reading while keeping its target and limits. A probe returned to use is evaluated only from the next packet.

**Hardware observation (2026-11-22, owner decision):** the H5182 can briefly report an unusually low but valid-looking temperature while a probe connector is being removed. One recorded case read 33.8 °F for about 16 s before the probe reported unavailable, which raised a LOW alarm. The connector is physically finicky. This is documented as hardware behavior and deliberately not filtered, because a cold reading can be real. Revisit only if normal cooking shows a recurring problem.

Alarm sound and vibration are owned by one app-level player on the alarm audio stream, not by notifications (2026-11-22). Physical testing found notification-owned sound stopped after a swipe while the alarm stayed active. The player runs while any unsilenced alert exists, so overlapping alerts share one stream, and only Silence, the condition clearing, or stopping monitoring ends it. The monitor tick restarts sound that ends unexpectedly.

Persist thresholds and preferences before applying them. Notifications must use separate monitoring and alarm channels. Alarm channels are high-importance and silent, providing the heads-up, full-screen page, and full probe context. Android alarm volume and Do Not Disturb still apply. A notification action must acknowledge/silence without requiring the activity. Exact default thresholds, hysteresis, sound, vibration, and escalation duration require owner approval and physical audibility testing.

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

Record date, APK/version or commit, phone build, H5182 identity, tester, steps, and observed result. A checked item has recorded physical evidence from November 22, 2026, mostly on development builds. The signed v1.0.0 APK was checked only for install, fresh settings, discovery, both probe readings, and a short monitoring session. Partial results stay unchecked.

- [x] Install the debug/release APK on the Pixel XL through ADB and launch it.
- [ ] Grant required permissions; verify denial and later recovery are understandable. *(Grant passed; denial and recovery not tested.)*
- [x] Select the actual H5182 and receive temperatures matching an independent/trusted reading.
- [x] Change probe 1 and probe 2 independently; verify identity is not swapped.
- [x] Disconnect each probe; verify unavailable data is shown safely and does not create a false temperature.
- [ ] Compare battery reporting with the physical/device reference where supported; otherwise label it unavailable. *(Plausible values seen; percentage accuracy not validated.)*
- [ ] Trigger each probe's high alarm at its boundary; verify sound, notification, and visible state. *(Probe 1 recorded; Probe 2 not recorded.)*
- [ ] Trigger each probe's low alarm at its boundary. *(Probe 2 recorded; Probe 1 not recorded.)*
- [ ] Acknowledge/silence an alarm, remain beyond the threshold, cross the hysteresis re-arm boundary, and trigger it again. *(Silence recorded; full re-arm cycle not recorded.)*
- [ ] Lock the screen for a representative cook interval; verify continued reception, correct alarm timing, and foreground notification. *(Short locked runs passed; a representative cook has not been run.)*
- [x] Move the H5182 out of range or power it off; verify stale/loss timing, warning, bounded recovery, and automatic reconnection.
- [ ] Disable/re-enable Bluetooth and revoke/restore permission; verify safe recovery.
- [ ] Force-stop/process-kill and relaunch as applicable; verify documented service and state behavior. *(One app restart recorded; force-stop not tested.)*
- [ ] Edit targets, high/low limits, units/preferences, and selected thermometer; restart the app and reboot the phone; verify persistence. *(App updates and restart preserved settings; reboot not tested.)*
- [ ] Start, stop, resume, and reset the cook timer; verify useful state across restart and reboot. *(Controls passed; restart and reboot not tested.)*
- [ ] Run longer than 30 minutes with irregular/repeated packets; verify both history graphs remain bounded, show gaps honestly, and do not affect monitoring.
- [ ] Test alarm audibility/readability with the screen locked in the expected outdoor/cooking environment.

## v1.0.0 implementation decisions

These decisions were made during implementation. Items marked provisional need physical evidence or owner approval.

- **SDK and dependencies:** `minSdk` and `targetSdk` are 29, and `compileSdk` is 37. Targeting API 29 keeps permission and service behavior equal to the tested phone. The app has no runtime library beyond the Kotlin standard library.
- **Background access:** the service uses foreground-service type `location` and does not request `ACCESS_BACKGROUND_LOCATION`. Screen-off delivery requires physical verification.
- **CPU sleep:** the service holds a renewed, time-limited partial wake lock while monitoring, because stale-signal detection cannot run during CPU sleep. This trades battery for alarm reliability. Battery impact is unmeasured.
- **Location services:** `Location off` is an explicit monitor state, because API 29 BLE scans can return nothing without an error when location is disabled.
- **Scan filter and mode:** one `0x8251` service-UUID filter with `SCAN_MODE_LOW_LATENCY`. Physical captures showed the UUID in temperature-bearing results. On the Pixel XL with the screen on, packet spacing improved from a 5 s median and 16 s maximum with `SCAN_MODE_BALANCED` to a 1 s median and 7 s maximum. Locked-screen spacing and battery cost still require measurement.
- **Provisional constants:** 60 s stale timeout, 10–120 s scan-restart backoff, 0–300 °C accepted range, 1 °C default hysteresis, and 3 °C color band. Alarm thresholds are disabled until the user sets them.
- **Process restart:** a sticky restart resumes a monitoring session that the user started. Monitoring does not start at boot.
- **Timer across reboot (provisional, question 6):** the timer stops at its last 30-second checkpoint, excludes powered-off time, and waits for Start. Timing uses monotonic time, so no wall-clock anchor is stored.
- **History:** each probe keeps the first accepted sample in each of 180 ten-second buckets.
- **Debug capture:** debug builds log rate-limited `H5182Capture` lines with a salted device hash and raw manufacturer bytes to build target-device fixtures. Release builds do not log them. Remove this after qualification.

## Decisions and unresolved questions

Current decisions: advertisements only; app-local alarms; one selected H5182; foreground service owns scanning; pure decoder/alarm core; Celsius canonical storage; bounded local history; no auto-start monitoring on boot without later approval.

Questions requiring evidence or owner decision:

1. What payload variants and address behavior does the H5182 emit across firmware versions? On the tested unit, the `0x8251` UUID was present in temperature results, the local name was absent, and the manufacturer key and value byte 0 repeated address bytes.
2. Is byte 5 a dependable battery percentage on this unit, and what does its high bit mean?
3. Should the provisional range, stale timeout, retry limits, hysteresis, and color band listed above become final?
4. Does the H5182 expose only a high alarm in advertisements, and do its advertised values change predictably when the Govee app changes settings?
5. Does API 29 screen-off scanning remain reliable over long runs? Short locked runs passed. Doze and battery optimization are untested.
6. Should a cook timer that was running before a reboot include powered-off time, pause at reboot, or prompt the user? The current provisional behavior is described above and is not physically tested.
7. Is monitoring resume after reboot desired? It is not implemented, because background-start behavior and user expectations need explicit approval.

## Release identity and signing

The application ID is `com.dim8labs.btthermometer`. Version 1.0.0 uses version code 1, and every later release must increase the version code.

Release builds read signing details from `~/.config/dim8-bt-thermometer/signing.properties`, or from the file named by `DIM8_BT_THERMOMETER_SIGNING`. The keystore and that file stay outside the repository. Without them, Gradle produces an unsigned release APK. Android accepts an update only when it has the same application ID and signing key, so the key must be preserved for all future releases. `tools/create_release_key.sh` creates the key once and refuses to replace an existing one.

The release certificate SHA-256 fingerprint is `E7:56:C6:43:BF:1F:F2:57:80:02:BF:14:E3:D4:37:B4:63:F1:2D:7F:FD:38:55:5D:76:35:99:86:44:6F:44:04`.

Code shrinking is off for release builds so the shipped code stays the code that was physically tested. The anonymized H5182 capture log runs only when `BuildConfig.DEBUG` is true.
