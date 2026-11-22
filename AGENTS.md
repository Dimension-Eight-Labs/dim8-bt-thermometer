# DIM8 Govee Thermometer — Agent Instructions

These repository instructions supplement the global Dimension Eight Labs rules. They apply to all work in this project.

## Product boundary

- Build a lightweight, entirely offline native Android application for the Govee H5182 and the Google Pixel XL (`marlin`) running Android 10/API 29.
- Respect the approved architecture in `docs/ARCHITECTURE.md` and interaction design in `docs/DESIGN.md`. Raise proposed departures for owner approval before implementing them.
- Prefer small, focused, testable changes. Do not silently expand scope, add speculative features, or introduce dependencies/frameworks without a concrete need and maintenance review.
- Preserve user settings, cook state, history, and existing behavior during changes and migrations.
- Keep core alarm and state logic independent of Android Bluetooth, persistence, notifications, and UI implementations.

## Evidence and hardware claims

- Do not invent BLE behavior, packet fields, device capabilities, measurements, or test results. Retain raw, timestamped advertisement fixtures used by decoder tests, with device identifiers anonymized where practical.
- Treat H5182 configuration writes as unsupported until the protocol and the specific hardware have been verified. In v0.1, local application thresholds are authoritative.
- Clearly distinguish host/JVM tests, Android instrumentation tests, emulator checks, and physical Pixel XL/H5182 tests. Never claim real-device validation without recorded physical-test evidence.
- A build or automated test does not prove screen-off scanning, alarm audibility, radio recovery, or thermometer correctness.
- Record unresolved questions, platform constraints, hardware limitations, and evidence needed to close them.

## Reliability and privacy

- Monitoring and alarms take priority over cosmetic features. Use bounded, nonblocking work; tolerate irregular advertisements; reject invalid readings; and expose stale, permission, Bluetooth, and service failures.
- Keep all data on-device. Do not add accounts, network access, cloud services, telemetry, analytics, advertisements, or logging of sensitive device identifiers.
- Keep dependencies minimal. Prefer Kotlin, Android/AndroidX APIs, and simple explicit composition over a framework-heavy design.

## Validation and delivery

- Add focused regression tests for advertisement decoding, alarm transitions/hysteresis, freshness, timer restoration, and persistence changes.
- Follow the acceptance checklist in `docs/ARCHITECTURE.md`. Do not mark checklist items passed unless the stated test was actually performed.
- Before changes, inspect repository state and relevant source/tests. At handoff, report files changed, commands and tests actually run, warnings, limitations, and remaining physical verification.
- Do not implement features beyond the approved milestone merely because later work is documented.

## Git and Cezar ownership

- The owner controls staging, commits, branches, merges, pushes, tags, releases, and deployment. Do not perform these operations without explicit authorization. Do not remove worktrees or alter unrelated files.
- Normal development uses the Cezar **DIM8 Development** flow: Codex planning → owner approval/revision → Claude implementation → independent Codex review → owner-controlled Git integration.
- This initial documentation creation is an owner-authorized, documentation-only exception. It does not authorize application implementation or any later exception.
- Do not run, modify, or interfere with Cezar state or configuration under `.ai/cezar` unless explicitly directed by the owner.
