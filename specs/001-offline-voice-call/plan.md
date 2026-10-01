# Implementation Plan: Offline Voice Call over WiFi/Hotspot

**Branch**: `001-offline-voice-call` | **Date**: 2026-10-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-offline-voice-call/spec.md`

## Summary

Turn the legacy video-intercom prototype into a phone-style voice call app that works with no
internet and no mobile data. Devices find each other on the local network (mDNS, with multicast and
gateway-probe fallbacks), pair once with an ECDH key exchange confirmed by a 6-digit comparison
code or QR, then place calls using a small signaling protocol (invite/ringing/accept/reject/busy/
cancel/hangup) over TCP and Opus voice over UDP. All signaling and media are authenticated and
encrypted with keys derived from the pairing secret. Incoming calls are received by a foreground
listener service and shown with a full-screen-intent notification. On a phone hotspot, the hotspot
phone acts as a directory and blind relay so client phones can still call each other when client
isolation blocks direct traffic. The new code is written in Kotlin; the legacy Java video/profile
code is removed from the build (git history keeps it).

## Technical Context

**Language/Version**: Kotlin 2.x on JDK 17 (new code); legacy Java removed from build

**Primary Dependencies**: AndroidX (core, lifecycle, activity), Jetpack Compose + Material 3, Kotlin
coroutines/Flow, Room, Concentus (pure-Java Opus encoder/decoder), ZXing-embedded or ML Kit
Barcode scanning (QR; chosen in research), Android `NsdManager`, JCA/AndroidKeyStore (ECDH P-256,
AES-GCM, HKDF implemented over HMAC)

**Storage**: Room (SQLite) for paired devices and call history; AndroidKeyStore for the device
identity key; DataStore for settings. Nothing is synced off the device.

**Testing**: JUnit 5 + kotlinx-coroutines-test for pure-JVM unit tests (protocol, state machine,
crypto envelope, jitter buffer, pairing); in-JVM two-engine loopback tests over real localhost
sockets; Android instrumented/Compose UI tests; manual two-device quickstart scenarios

**Target Platform**: Android 7.0+ (minSdk 24), targetSdk = current stable at implementation time (at least 35)

**Project Type**: mobile-app (single Android application module `app`)

**Performance Goals**: mouth-to-ear latency under 200 ms on a healthy LAN; call setup under 10 s
after accept; discovery under 10 s; stable 30-minute call; incoming-call alert under 3 s on a locked device

**Constraints**: no internet/server/mobile data (traffic bound to the WiFi network); full-duplex
voice; battery-friendly standby listening; no Java object deserialization from the network; all
network messages size-limited; works across Android 7 to current

**Scale/Scope**: two-party calls; tens of devices on one LAN; about 8 screens (device list, calling,
incoming call, in-call, pairing, settings, history, hotspot help)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| # | Principle | Status | How the plan satisfies it |
|---|-----------|--------|---------------------------|
| I | Offline-first | PASS | No servers or accounts; sockets bound to the WiFi `Network`; hotspot directory + blind relay (research R3, R7) |
| II | Security by default | PASS | JSON with hard size limits, no object streams; pairing via ECDH + numeric comparison; AES-GCM on signaling and media; mic indicator; connection and rate limits (contracts/) |
| III | Voice first, low latency | PASS | Opus 20 ms frames, UDP, adaptive jitter buffer, voice-communication audio mode with AEC/NS/AGC; no video in this feature |
| IV | Modern Android | PASS | AndroidX, minSdk 24, foreground service with correct types, full-screen-intent notification, no background `startActivity()` |
| V | Explicit call state | PASS | Single `CallStateMachine` in the service process; heartbeat and timeouts defined in contracts/ |
| VI | Testability and Functional Tests | PASS | Pure-Kotlin core enables JVM unit tests; loopback two-engine tests; **a `@FunctionalTest` per user story** (real UI + service vs. in-process `FakePeerDevice` with `NetworkFaultProxy`) run by `./gradlew functionalTest`; quickstart covers manual two-device WiFi-no-internet and hotspot |
| VII | Simplicity | PASS (see tracking) | Layered packages, one responsibility each; no global statics; toggleable logger |
| Tech constraints | Signaling "TLS over TCP" | PASS (resolved) | Constitution v1.1.1 now reads "authenticated encryption (TLS or an equivalent pairing-keyed scheme)"; signaling uses AES-GCM with pairing-derived keys |

**Post-design re-check**: the deviation above is the only one; all other gates still pass after
Phase 1 design (data-model.md and contracts/ were checked against Principles II, III and V).

## Project Structure

### Documentation (this feature)

```text
specs/001-offline-voice-call/
|-- plan.md              # This file
|-- research.md          # Phase 0 output
|-- data-model.md        # Phase 1 output
|-- quickstart.md        # Phase 1 output
|-- contracts/
|   |-- discovery.md     # mDNS / multicast / gateway-probe announcements
|   |-- pairing.md       # ECDH + numeric comparison, QR payload
|   |-- signaling.md     # framing, encryption envelope, message types, timers
|   `-- media.md         # UDP packet format, encryption, relay forwarding
`-- tasks.md             # Phase 2 output (/speckit-tasks - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/
|-- build.gradle.kts
`-- src/
    |-- main/
    |   |-- AndroidManifest.xml
    |   `-- java/com/intercom/video/twoway/
    |       |-- core/                 # pure Kotlin, no android.* imports (JVM-testable)
    |       |   |-- call/             # CallStateMachine, CallSession, timers
    |       |   |-- protocol/         # message types, JSON codec, framing, size limits
    |       |   |-- crypto/           # HKDF, AES-GCM envelope, SAS derivation, replay window
    |       |   |-- media/            # RTP-like packet, jitter buffer, Opus wrapper interface
    |       |   `-- pairing/          # PairingSession (ECDH, SAS)
    |       |-- net/                  # android networking adapters
    |       |   |-- discovery/        # NsdDiscovery, MulticastDiscovery, GatewayProbe
    |       |   |-- transport/        # TcpSignalingServer/Client, UdpMediaSocket
    |       |   |-- network/          # WifiNetworkBinder, HotspotManager, HubRelay
    |       |   `-- NetworkMode.kt
    |       |-- audio/                # AudioCapture, AudioPlayback, AudioRouteManager, OpusCodec
    |       |-- service/              # ListenerService, CallNotification, BootReceiver
    |       |-- data/                 # Room db, PairedDeviceDao, CallHistoryDao, SettingsStore, KeyStore
    |       `-- ui/                   # Compose screens + ViewModels (devices, call, pairing, settings, history)
    |-- test/                         # JVM unit and loopback tests
    `-- androidTest/                  # instrumented + Compose UI tests
```

**Structure Decision**: single Android application module, with a Kotlin `core` package that has
no Android dependencies so the protocol, state machine, crypto envelope and jitter buffer can be
tested on the JVM, and thin Android adapters (`net`, `audio`, `service`) around it. The legacy
`Streaming/`, `Network/`, `Controllers/`, `Fragments/`, `Models/`, `Utilities/` Java packages are
deleted as part of the migration task; video returns as a separate feature.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Signaling secured by AES-GCM with pairing-derived keys instead of TLS (constitution Technical Constraints) | Peers are phones on an ad-hoc LAN with no certificate authority; TLS would need a self-signed certificate per device plus pinning at pairing, and Android's TLS stack has no PSK suites. The same pairing key already protects media, so one scheme covers both. | TLS with self-signed certs adds certificate generation, trust-store and pinning code and a second key system next to the media keys, with no extra security. **Recommended follow-up**: amend constitution to v1.0.1 wording "authenticated encryption (TLS or an equivalent pairing-keyed scheme)". |
| Hotspot hub relay (extra component) | Many hotspots block phone-to-phone traffic (client isolation); without a relay FR-021 cannot be met | Telling users "cannot connect" only is allowed by FR-021 as a fallback, but fails the hotspot story for the common case; the relay is blind (forwards ciphertext) so it adds no security risk |
