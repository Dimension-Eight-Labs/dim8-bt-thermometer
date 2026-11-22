# DIM8 Govee Thermometer — Interface Design

Status: proposed v0.1 design for owner review

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
│ DIM8 Govee Thermometer       ⚙      │
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
- `Monitoring stopped`

Battery displays percent only when the decoder reports a valid, physically verified value; otherwise show `Battery —`, not a guessed value. RSSI may inform diagnostics but should not be presented as precise range.

### Probe cards

Each probe card contains, in order:

1. Probe identity and explicit status (`NORMAL`, `LOW`, `HIGH`, `STALE`, or `NO PROBE`).
2. The current temperature as the dominant element, with unit visible.
3. Clearly separate `Target`, `Low alarm`, and `High alarm` values; never label a target as an alarm.
4. A small 30-minute sparkline with gaps for missing/stale intervals.
5. An acknowledgement control only while that probe has an audible alarm episode.

When stale, retain the last temperature with reduced emphasis and show its age. When the decoder reports an absent probe, show `—` and `Probe not detected`; do not render zero. Alarm state needs an icon/text/banner in addition to color. Probe cards must not shift substantially as status changes.

## Temperature color model

Color is a continuous progress cue relative to the configured target and tolerance, independent of high/low alarm evaluation:

- substantially below target: blue;
- approaching target: transition through yellow/amber;
- at/near target: amber/orange;
- above target: transition toward orange/red.

Implementation should normalize `(temperature − target)` against configurable below/near/above bands and interpolate in a perceptual color space or through a small approved sequence. Clamp outside the bands. The approved palette and exact tolerance boundaries remain owner decisions; all colors must meet contrast requirements with their text/background.

Do not flash or rapidly animate on noisy readings. Smooth color changes using the accepted reading stream without delaying the numeric value or alarm evaluation. Audible high/low alarms derive only from configured alarm thresholds and hysteresis; crossing a display-color boundary never sounds an alarm.

Provide non-color cues and ensure the UI remains understandable with color-vision deficiencies and system high-contrast/font scaling. Respect reduced-motion settings.

## Sparkline behavior

- Show approximately the latest 30 minutes for each probe, oldest left and newest right.
- Keep it visually subordinate to the temperature. No pinch/zoom, cursor, legend, axes, or dedicated chart screen in v0.1.
- Scale sensibly to recent valid data while avoiding exaggerated noise; use a flat centered line for an effectively constant range.
- Break the line across stale/missing intervals. Never fabricate intermediate readings.
- History sampling and rendering are bounded; bursts of BLE advertisements must not create unbounded points or UI work.
- Accessibility text summarizes the visible range, for example `Probe 1, last 30 minutes, 138 to 145 degrees, rising`; do not require a user to interpret the graphic.

## Cook elapsed timer

The bottom bar shows `HH:MM:SS` while useful, expanding hours rather than wrapping at 24. Controls are manual:

- `Start` begins or resumes elapsed time.
- `Stop` freezes the accumulated duration.
- `Reset` requires a lightweight confirmation when elapsed time is nonzero, then returns to zero/stopped.

There is no countdown, journal, named cook, or automatic start based on temperature. Preserve running/stopped state and useful elapsed time across process restarts. Reboot semantics remain an architecture question and must be made explicit before implementation.

## Settings and device selection

Use a compact settings screen or sheet with these groups:

- **Thermometer:** selected H5182, change/rescan action, current reception status.
- **Probe 1 / Probe 2:** target temperature; optional low alarm; optional high alarm; hysteresis; validation summary.
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
- expose `Silence`/`Acknowledge` in both notification and app; and
- continue showing the violation after acknowledgement until it re-arms.

Acknowledgement silences the current episode, not monitoring and not future episodes. Hysteresis prevents repeated boundary alarms. Notification behavior with the screen locked is release-critical. The interface must not imply it can override Android notification volume, Do Not Disturb, or a user-muted channel.

### Stale or connection-loss warning

Use distinct wording and sound policy from a temperature alarm: `Thermometer signal lost` or `Probe 1 reading stale`. Include last-reading age and keep attempting bounded recovery. Acknowledgement quiets the warning episode without pretending reception is restored.

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
