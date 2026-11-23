# DIM8 BT Thermometer — Interface Design

Status: v0.1 design, with the DIM8 visual system implemented for owner review

## DIM8 visual system

### Brand palette

These are the official DIM8 Labs colors, defined in `app/src/main/res/values/colors.xml`. Do not add or approximate brand colors.

| Token | Resource | Hex |
|---|---|---|
| Primary VFD | `vfd_primary` | `#B8DDD7` |
| Bright VFD | `vfd_bright` | `#D6F4EF` |
| Dim VFD | `vfd_dim` | `#5D837E` |
| Secondary VFD | `vfd_secondary` | `#97B5B0` |
| Background | `background` | `#010100` |
| Surface | `surface` | `#0A0C0D` |
| Raised surface | `surface_raised` | `#111517` |
| Primary text | `text_primary` | `#ECEEEC` |
| Body text | `text_body` | `#C9CFCD` |
| Muted text | `text_muted` | `#96A3A0` |
| Border | `border` | `#1C2426` |
| VFD glow | `vfd_glow` | `rgb(140 220 206 / 38%)` (`#618CDCCE`) |

Glow is limited to screen titles and touch feedback. The alarm page and alarm cards keep the functional red background `#4A0F0A`, with the approved Hot color as their accent. These alarm colors are not brand colors.

### Temperature palette

Live readings and history lines use this heat map, defined in `core/Presentation.kt` and independent of the brand palette. VFD colors are never used for a temperature plot.

| State | Hex | Scale position (multiples of the color band from target) |
|---|---|---|
| Cold | `#438ED4` | −4 or lower |
| Below target | `#36B5A8` | −2 |
| At or near target | `#F2BE29` | 0 |
| Above target | `#E78533` | +1 |
| Hot | `#D84A43` | +2 or higher |

Colors interpolate linearly between stops. The target is the center of the scale, and the user's color band sets its width. Without a target, alarm limits anchor the scale: midway between both, or a single low limit at Below or a single high limit at Hot. A probe with neither uses neutral primary text. Color never triggers an alarm.

### Typography

Use the platform sans-serif (Roboto on the Pixel XL). Do not use monospaced, seven-segment, or novelty fonts. The official seven-segment mark appears only as artwork.

### Brand assets

`raw-assets/dim8-vfd-logo.png` is the read-only source of truth and is excluded from Git. `tools/generate_brand_assets.py` crops it to the header mark and builds the adaptive launcher foreground, adding a slim temperature-colored stem above the official dot. The launcher icon is a proposal for owner review.

## Experience principles

The app is a glanceable cooking instrument, not a recipe or lifestyle product. It must remain readable outdoors, with wet or occupied hands, and from farther away than a typical phone interaction. Monitoring state and alarms outrank decoration.

- One primary screen, one compact settings surface, and a device-selection surface.
- Large temperatures, strong contrast, plain status language, and generous touch targets.
- No accounts, onboarding carousel, recipes, journal, social features, advertisements, cloud state, or dedicated graph screen.
- DIM8 branding is restrained to the app name, approved mark, typography, and approved colors. No brand palette is invented; use accessible neutral system colors until assets are supplied.
- Color supplements text/icons and never carries alarm or connection meaning alone.

## Main screen

Portrait is the release-critical layout. Landscape may reflow but must remain usable.

```text
┌─────────────────────────────────────┐
│ DIM8 BT Thermometer       ⚙      │
│ ● Monitoring · H5182 · Battery —    │
├─────────────────────────────────────┤
│ PROBE 1                    NORMAL    │
│             142.3 °F                 │
│ Target 145°   Low 130°   High 150°  │
│  ▁▂▂▃▄▅▆▇  recent 30 min             │
├─────────────────────────────────────┤
│ PROBE 2                     HIGH     │
│             168.1 °F                 │
│ Target 165°   Low 150°   High 168°  │
│  ▁▁▂▃▄▆▇█  recent 30 min             │
│ [Silence probe 2 alarm]              │
├─────────────────────────────────────┤
│ Cook  01:42:17       [Stop] [Reset] │
└─────────────────────────────────────┘
```

The values above illustrate hierarchy only; they are not product defaults.

### Global status row

Show selected device, monitoring state, and battery if verified/supported. Use concise combinations such as:

- `Monitoring · updated 2s ago`
- `Starting monitor…`
- `Signal lost · last reading 48s ago`
- `Bluetooth off`
- `Location permission required` (API 29 wording may mention why BLE scanning needs it)
- `Location is off` (API 29 BLE scans can return nothing while location services are disabled)
- `Monitoring stopped`

Battery displays percent only when the decoder reports a valid, physically verified value; otherwise show `Battery —`, not a guessed value. RSSI may inform diagnostics but should not be presented as precise range.

### Probe cards

Each probe card contains, in order:

1. Probe identity and explicit status (`WAITING`, `NORMAL`, `LOW`, `HIGH`, `STALE`, `MISSING`, `RECONNECTING`, or `NOT IN USE`).
2. The current temperature as the dominant element, with unit visible. Fahrenheit is displayed in whole degrees, while alarms and history keep the exact decoded value.
3. Clearly separate `Target`, `Low alarm`, and `High alarm` values; never label a target as an alarm.
4. A small 30-minute sparkline with gaps for missing/stale intervals.
5. An acknowledgement control only while that probe has an audible alarm episode.

When stale, retain the last temperature with reduced emphasis and show its age. When the decoder reports an absent probe, show `—` and `Probe not detected`; do not render zero. Alarm state needs an icon/text/banner in addition to color. Probe cards must not shift substantially as status changes.

## Temperature color model

Color is a continuous progress cue relative to the configured target and color band, independent of high/low alarm evaluation. It uses the approved temperature palette and scale positions above, normalizing `(temperature − target)` by the band and clamping outside the scale.

Do not flash or rapidly animate on noisy readings. Smooth color changes using the accepted reading stream without delaying the numeric value or alarm evaluation. Audible high/low alarms derive only from configured alarm thresholds and hysteresis; crossing a display-color boundary never sounds an alarm.

Provide non-color cues and ensure the UI remains understandable with color-vision deficiencies and system high-contrast/font scaling. Respect reduced-motion settings.

## History graph behavior

- Show the latest 30 minutes for each probe, oldest left and newest right, with `-30m`, `-15m`, and `Now` labels.
- Keep it visually subordinate to the temperature. No pinch/zoom, cursor, legend, or dedicated chart screen in v0.1.
- Use subtle gridlines and three whole-degree temperature labels. The axis uses the smallest step (at least 5 °F or 2 °C) whose three labels contain the readings with 1 degree of padding, so ordinary 1.8 °F probe steps stay small.
- Color the line from the temperature palette by its height on the graph.
- Mark readings at or beyond the limits in force when they were recorded: a red up-triangle for high and a blue down-triangle for low. Excursions are stored with history, need no audible alarm, are unaffected by later limit edits, and scroll out with their samples. Long runs are thinned to one marker per minute plus the first and most extreme points. History recorded before this feature has no markers because its limits are unknown.
- Break the line across stale/missing intervals and draw a dotted baseline through the gap, including a gap running up to `Now`, without changing the background. The newest point follows the live reading while it is at most 20 s old. This is display-only, and storage remains one sample per 10 s. Empty buckets shorter than the stale timeout are normal advertisement spacing and stay visually connected. Never fabricate stored readings.
- History sampling and rendering are bounded; bursts of BLE advertisements must not create unbounded points or UI work.
- Accessibility text summarizes the visible range, for example `Probe 1, last 30 minutes, 138 to 145 degrees, rising`; do not require a user to interpret the graphic.

## Cook elapsed timer

The bottom bar shows `HH:MM:SS` while useful, expanding hours rather than wrapping at 24. Controls are manual:

- `Start` begins or resumes elapsed time.
- `Stop` freezes the accumulated duration.
- `Reset` requires a lightweight confirmation when elapsed time is nonzero, then returns to zero/stopped.

There is no countdown, journal, named cook, or automatic start based on temperature. Preserve running/stopped state and useful elapsed time across process restarts. Reboot semantics remain an architecture question and must be made explicit before implementation.

## Settings and device selection

Use a compact settings screen with labels that stay beside their values, a Save button fixed above the keyboard, and a prompt before discarding unsaved edits. Hysteresis and color band are shown with less emphasis. Group settings as follows:

- **Thermometer:** selected H5182, change/rescan action, current reception status.
- **Probe 1 / Probe 2:** in-use switch; target temperature; optional low alarm; optional high alarm; hysteresis; validation summary.
- **Display:** °F/°C and target color tolerance/bands.
- **Alerts:** sound/vibration controls allowed by Android notification channels, connection-loss warning preference, and a link to system notification settings.
- **Monitoring:** explanatory status and, only if testing shows it necessary, a link to battery-optimization settings.

Persist edits across app restarts and phone reboots. Validate numeric input in the selected display unit, convert once to canonical Celsius, require low < high when both alarms are enabled, and explain invalid combinations inline. Do not silently rearrange values. Saving a threshold while a current reading is already beyond it must produce deterministic behavior defined by the alarm state-machine tests; the proposed behavior is to evaluate immediately as a new episode.

Device selection lists only plausible H5182 candidates with name and a privacy-conscious short identifier. It must not auto-switch because a different Govee device has stronger RSSI. Clearly state when permissions or Bluetooth prevent discovery.

## Alarms and warnings

### Temperature alarm

On entry into a high or low episode:

- show an unmistakable probe-specific banner/card state;
- post a high-importance audible notification naming probe, reading, and violated threshold;
- expose `Silence`/`Acknowledge` in both notification and app, including a full-screen page over the lock screen because Android 10 hides collapsed notification actions there. While that page is open, a new alarm adds its row without a heads-up banner, which would otherwise cover the top Silence button; and
- continue showing the violation after acknowledgement until it re-arms.

Acknowledgement silences the current episode, not monitoring and not future episodes. Hysteresis prevents repeated boundary alarms. Notification behavior with the screen locked is release-critical. The interface must not imply it can override Android notification volume, Do Not Disturb, or a user-muted channel.

### Missing-probe alarm

When the thermometer reports a probe as unavailable, immediately sound the probe alarm and show `Probe N MISSING` and `Probe not detected` on the notification and lock-screen page. `Silence` quiets that alarm for 5 minutes, including through brief contact changes. If the probe is still missing, the alarm then sounds again, and each Silence restarts the interval. When valid readings return, show `Probe reconnecting…` until recovery qualifies after 5 seconds and at least 3 readings. The alarm and reminder then clear, temperature monitoring resumes, and the other probe continues normally throughout.

### Probe not in use

Each probe has an `In use` switch in Settings, on by default and saved across restarts. A probe that is off shows `NOT IN USE` and `Alarms off`, and raises no missing-probe or temperature alarm. Turning it off clears its active alarm and keeps its target and limits. Turning it back on shows `WAITING` until a fresh reading arrives.

### Stale or connection-loss warning

Use distinct wording from a temperature alarm: `Thermometer signal lost` or `Probe 1 reading stale`. Because the app is used overnight, signal loss uses the same repeating alarm sound and full-screen lock-screen page as a temperature alarm (owner requirement, 2026-11-22). Include last-reading age and keep attempting bounded recovery. The sound continues until the user silences it or signal returns. Acknowledgement quiets the warning for 5 minutes without pretending reception is restored. The alarm then sounds again, repeating this cycle until signal returns or monitoring stops. A later loss sounds again.

## Accessibility and outdoor use

- Meet WCAG AA contrast for normal text and controls; favor stronger contrast for the primary temperatures.
- Support Android font scaling without clipping critical values or controls. Test at default and large font sizes on the Pixel XL.
- Minimum interactive target: 48 dp. Keep acknowledgement and timer controls separated to prevent accidental taps.
- Provide content descriptions/state descriptions for status icons and sparklines; announce new alarm states without announcing every incoming sample.
- Use plain language and stable layout. Avoid thin type, translucent critical text, color-only state, and continuous animation.
- Support light and dark presentation only if both can be verified by the deadline; a single polished, accessible theme is preferable to two incomplete themes.

## Release scope and design acceptance

Required for v0.1: both probe readings, separate target/high/low values, monitoring/battery status, visible and audible alarms with acknowledgement/hysteresis, elapsed timer, settings persistence, and small sparklines.

Defer any brand embellishment, additional screens, chart interaction, presets, or secondary animations that threaten monitoring and physical acceptance. UI acceptance must include glance readability outdoors, locked-screen alarm handling, large-font behavior, absent/stale probe presentation, and confirmation that every alarm/status is understandable without relying on color.
