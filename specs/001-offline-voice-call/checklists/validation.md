# Validation Record: Offline Voice Call

**Created**: 2026-10-01
**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Quickstart**: [quickstart.md](../quickstart.md)

This record separates what was **proven by automated tests** from what still **needs real phones**. Nothing below is
claimed beyond what was run. Automated results come from this repository's own runs on 2026-10-01 (JDK 21 from Android
Studio, Gradle 9.8, AGP 9.4, Android emulator API 36 `Medium_Phone_API_36.0`, one emulator).

## Automated results

| Suite | Command | Result |
|-------|---------|--------|
| JVM unit + loopback tests (real sockets, Opus, AES-GCM, two engines in one JVM) | `./gradlew :app:testDebugUnitTest` | 213 tests, 0 failures |
| Instrumented tests on the emulator (Room, notifications, keystore, Compose UI) | `./gradlew :app:connectedDebugAndroidTest` | 58 tests, 0 failures |
| of which `@FunctionalTest` (real UI + service vs. an in-process fake peer) | `./gradlew :app:connectedFunctionalTest` | 41 tests: CallFlow 3, Background 4, CallOutcomes 4 + RingTimeout 1, Pairing 11, Hotspot 8, InCallControls 7, Security 4, Settings 3 |
| Android lint | `./gradlew :app:lintDebug` | 0 errors |
| ktlint | `./gradlew :app:ktlintCheck` | 0 violations |
| Release build (R8 minify + shrink) | `./gradlew :app:assembleRelease` | OK, 2.0 MB; signed with the debug key and launched on the emulator: starts, shows the device list, no crash; no debug secret or test fake in the APK |

`./gradlew functionalTest` (headless on a Gradle Managed Device) is configured but **was not run**: it needs a system
image download. The same tests were run with `connectedFunctionalTest` against the already installed emulator.

Real defects the tests found and that were fixed during implementation (evidence that the tests are not vacuous):
the 256-character JSON string limit rejected every relayed (RELAY) frame; `Envelope.decode` treated a RELAY frame as malformed
and made the hub close the client; the "connected at" sentinel 0 broke call duration with a clock that starts at 0;
`BootReceiver` crashed when invoked without `goAsync()`; NSD kept stale phones listed after they vanished; the device-list header
wrapped "Settings" letter by letter on a phone-width screen; API 26 calls on minSdk 24 (lint).

## Success criteria

| ID | Criterion | Status | Evidence / what is missing |
|----|-----------|--------|----------------------------|
| SC-001 | Talking within 10 s of accept, 95% of attempts, no internet and no mobile data | **Partly verified** | Loopback (no radio): accept to both sides in call median 27 ms, p95 51 ms, max 52 ms over 15 calls (`MeasurementsTest`). Real WiFi without internet and mobile data off: **manual** (Scenario A) |
| SC-002 | Device appears within 10 s, 95%, WiFi and hotspot | **Partly verified** | NSD registration and discovery run on the emulator's Android stack (the app listed an mDNS-cached entry left by the previous install within 8 s, and removed it after it stopped answering HELLO probes, within 28 s). Only one emulator was available, so two live phones discovering each other was not observed. Timing on a real LAN and on a hotspot: **manual** (Scenarios A, E) |
| SC-003 | Mouth-to-ear delay < 0.5 s (target 0.2 s) | **Partly verified** | Pipeline only: capture to first audible frame at the peer, loopback, median 75 ms, p95 78 ms (jitter buffer included). Audio hardware, WiFi and Bluetooth latency **not measured**: manual |
| SC-004 | No disturbing echo in two-way talk | **Not verified** | Needs two real phones; the app enables the platform echo canceller/noise suppressor/AGC where the device offers them (`AudioCapture`) |
| SC-005 | Incoming call on a locked, screen-off phone alerts within 3 s | **Verified on the emulator** | `BackgroundCallFunctionalTest.aCallRingsAndCanBeAnsweredWhileTheScreenIsOff`: screen off, app in background, call arrives, screen turns on, Accept works. Real phones and Android 14+ full-screen-intent permission per vendor: **manual** (Scenario B) |
| SC-006 | A call stays connected for at least 30 minutes | **Verified on loopback** | `SoakTest`: 31 minutes, 62 checks, still connected with audio flowing at every check, heap flat at 14 MB. Real WiFi/phones (battery, WiFi power saving, vendor limits): **manual** |
| SC-007 | First-time user pairs and calls in under 3 minutes without instructions | **Not verified** | Needs a usability check with a person. The pairing flow itself is fully automated (`PairingFunctionalTest`) |
| SC-008 | Lost connection ends the call on both devices within 15 s with a clear message | **Verified (JVM + UI)** | `CallControlsTest.noValidMessageFor10SecondsEndsTheCallAsConnectionLost`, `theOtherSideNoticesWhenTheCallerLosesTheNetworkAndEndsToo`, `InCallControlsFunctionalTest.aStalledNetworkShowsPoorConnectionThenEndsAsConnectionLost` (10 s heartbeat rule; verified with a fake clock) |
| SC-009 | Zero bytes of call data over mobile data | **Not verified** | The code binds sockets to the WiFi `Network` and never requires internet (`WifiNetworkBinder`); no traffic capture was done. Needs a phone with mobile data on and a traffic monitor (Scenario A) |
| SC-010 | An unpaired device can never ring or listen | **Verified** | `UnpairedInviteTest`, `PairingFunctionalTest.anUnpairedPeerCanNeverMakeThePhoneRing`, `SecurityFunctionalTest` (unknown, revoked, bad MAC, stale and replayed INVITEs; replayed encrypted signaling) |

## Quickstart scenarios

| Scenario | Automated coverage | Still manual |
|----------|--------------------|--------------|
| A: WiFi without internet | `CallFlowFunctionalTest`, `LoopbackCallTest` | Two phones, offline router, mobile data off, traffic check |
| B: locked phone | `BackgroundCallFunctionalTest`, `IncomingCallNotificationTest` | Real phones, vendor battery rules, Android 14+ permission |
| C: reject, miss, busy, cancel | `CallOutcomesFunctionalTest`, `RingTimeoutFunctionalTest`, `LoopbackRejectBusyTest` | Quick run on phones |
| D: pairing | `PairingFunctionalTest`, `PairingLoopbackTest`, `PairingSessionTest` | Camera QR scan (the code text path is tested, the camera scanner is not), two real screens |
| E: hotspot | `HotspotCallTest`, `HotspotFunctionalTest`, `HubRelayTest`, `HubCoordinatorTest` | A real hotspot, `LocalOnlyHotspot` start, a hotspot that really isolates clients, hotspot interface detection |
| F: mute, route, loss | `InCallControlsFunctionalTest`, `CallControlsTest` | Real audio routes (Bluetooth), real WiFi loss |

## Open items (honest list)

- Everything in the "Still manual" column, on at least two phone models/vendors (task T148).
- Real mobile-data check for SC-009; real echo/latency check for SC-003/SC-004; usability check for SC-007.
- Second human reviewer for [security.md](security.md) (constitution rule).
- `./gradlew functionalTest` on a Gradle Managed Device not yet run.
- Known simplification: after a relayed call the media path follows the signaling path (direct or via hub); the 2 s
  UDP probe from contracts/media.md is not implemented (a network that allows TCP but not UDP between phones is not handled).
- Video, group calls, push-to-talk, WiFi Direct/Aware are out of scope for this feature.

## SC-006 soak result

`./gradlew :app:testDebugUnitTest -Psoak -Psoak.minutes=31` on 2026-10-01: two engines over real localhost sockets with Opus and AES-GCM stayed connected for 31 minutes; 62 checks (every 30 s), audio flowing both ways at every check; JVM heap 14 MB at start and 14 MB at the end (no leak).
