# Phase 0 Research: Offline Voice Call

All technical unknowns from the plan are resolved below. Items marked **verify on device** depend
on vendor/Android behaviour and are covered by quickstart scenarios, not by assumption.

## R1. Language, UI toolkit and build

- **Decision**: Kotlin + coroutines/Flow; Jetpack Compose (Material 3) with ViewModels; Gradle
  Kotlin DSL; current stable AGP/Gradle/Kotlin on JDK 17.
- **Rationale**: the existing build (AGP 1.0.0, compileSdk 21, support-v7) cannot build modern
  targets; the existing UI is fragment/`setContentView` navigation with global statics, which
  conflicts with Principle VII. Compose + ViewModel gives state that survives rotation.
- **Alternatives**: keep Java + XML (rejected: more boilerplate, hard to test, deprecated
  patterns already present); Flutter/React Native (rejected: need low-level audio, service and
  network control).

## R2. Voice codec

- **Decision**: Opus, 16 kHz mono, 20 ms frames, ~24 kbps, in-band FEC on, DTX off for v1, using
  **Concentus** (pure-Java Opus) behind an `OpusCodec` interface.
- **Rationale**: `MediaCodec` Opus is only available on API 29+, and minSdk is 24. A pure-Java
  codec avoids NDK builds. 16 kHz mono is wideband speech, a large improvement over the legacy
  8 kHz raw PCM, and the bitrate is tiny for WiFi.
- **Alternatives**: libopus via JNI (better CPU, but adds NDK/ABI maintenance; keep as a drop-in
  behind the interface if Concentus CPU use is too high on low-end phones — **verify on device**);
  raw PCM (rejected: 256 kbps, no loss concealment); AMR/Speex (rejected: poorer, less supported).

## R3. Network binding with no internet

- **Decision**: request the WiFi `Network` through `ConnectivityManager.requestNetwork` with
  `TRANSPORT_WIFI`, bind with `bindProcessToNetwork` (or create sockets via
  `Network.socketFactory`/`bindSocket`), and never require `NET_CAPABILITY_INTERNET` or
  `VALIDATED`. Hold `WifiManager.WifiLock` (low-latency mode on API 29+) and a `MulticastLock`.
- **Rationale**: Android may prefer mobile data or drop an unvalidated WiFi; explicit binding
  keeps call traffic on WiFi and satisfies FR-008 / SC-009.
- **Alternatives**: do nothing (rejected: leaks to mobile data and fails on "no internet" WiFi
  on some devices). **Verify on device**: behaviour of captive-portal detection prompts on
  hotspot networks across vendors.

## R4. Discovery

- **Decision**: three layers, merged into one device list:
  1. `NsdManager` service `_twoway._tcp` with TXT (deviceId, name, proto version, role).
  2. UDP multicast announce/query on `239.255.42.99:45679` every 5 s with 15 s expiry (fallback
     for networks where mDNS is filtered).
  3. **Gateway probe**: on a hotspot, always TCP-HELLO the DHCP gateway; if it answers it is the
     hub and supplies the peer directory (R7).
  Manual IP entry and QR (pairing) are additional ways in.
- **Rationale**: mDNS is reliable on most WiFi but unreliable on some hotspots/APs; multicast
  covers APs that pass multicast but block mDNS; the gateway is the only address that is always known.
- **Alternatives**: UDP subnet broadcast only (legacy approach, rejected: blocked by many APs
  and by Android power saving, and noisy); Bluetooth/WiFi Aware (rejected for v1: narrower device support).

## R5. Pairing and keys

- **Decision**: each install creates a long-term P-256 key pair (AndroidKeyStore, with software
  fallback where unsupported) and a random 128-bit `deviceId`. Pairing = ephemeral ECDH over a
  TCP channel; both devices derive a 6-digit **Short Authentication String** from the handshake
  transcript and the users confirm the numbers match ("numeric comparison"). A QR on one device
  carries the address and public-key fingerprint so the scanner skips the manual step, and the
  comparison code is still shown. Result: a 256-bit pairing secret `PS` stored per paired device.
- **Rationale**: stops a passive and active network attacker without any PIN-guessing problem,
  needs no server, and fits the "short code or QR" requirement. The SAS is the same idea as
  Bluetooth numeric comparison.
- **Alternatives**: typed shared PIN (rejected: low entropy, offline brute force unless a PAKE
  is added); SPAKE2/OPAQUE (rejected: no mature Android library, more code); QR-only secret
  (kept as the QR option's payload binding, but not the sole method since both phones need a camera).
- **Known limit**: no forward secrecy for long-term `PS`; mitigated by a fresh per-call key
  (R6). Rekeying/rotation is a later enhancement.

## R6. Encryption of signaling and media

- **Decision**: per-call key material from HKDF-SHA256(`PS`, nonceA || nonceB, "twoway call v1"),
  yielding separate signaling and media keys per direction. AES-256-GCM everywhere (hardware
  accelerated on all supported phones), with 96-bit nonces built from a per-direction counter and
  a 32-bit salt; a sliding replay window (64 packets for media, strictly increasing for signaling).
- **Rationale**: same scheme for both channels (one review), no TLS certificates (see plan
  Complexity Tracking), and a fresh key per call limits the damage of a leaked past call.
- **Alternatives**: TLS (rejected, see Complexity Tracking); SRTP (rejected: needs a library and
  DTLS or key management of its own); ChaCha20-Poly1305 (possible on API 28+ only through JCA,
  AES-GCM works on API 24).

## R7. Hotspot behaviour and relay

- **Decision**: a phone with its hotspot active is the **hub**. Clients register with the hub
  over TCP at the gateway address; the hub keeps the directory and, per call, tells both sides
  whether a direct path works. A direct path is tried first (2 s probe). If it fails (client
  isolation), signaling frames and UDP media packets are forwarded by the hub using an opaque
  session id; payloads stay end-to-end encrypted so the hub cannot read them. If neither works
  the UI explains why (FR-021).
- **Rationale**: client isolation is common on public/phone hotspots and the gateway is the one
  address that every client can reach.
- **Alternatives**: always relay (rejected: extra latency and hub battery when not needed);
  never relay (rejected: fails the hotspot story on isolated hotspots).
- **App-managed hotspot**: `WifiManager.startLocalOnlyHotspot` (API 26+) for guided start and QR
  join; on API 24-25 and for the system hotspot the app only gives step-by-step instructions
  (programmatic hotspot control is not allowed there). **Verify on device**: LocalOnlyHotspot
  needs location/`NEARBY_WIFI_DEVICES` and the location switch on some versions.

## R8. Receiving calls in the background

- **Decision**: a `ListenerService` foreground service (type `connectedDevice`, notification
  channel "Listening") keeps the TCP server, mDNS registration and multicast listener alive. An
  incoming INVITE posts a high-importance `CATEGORY_CALL` notification with a **full-screen
  intent** to `IncomingCallActivity` (`showWhenLocked`, `turnScreenOn`), plus ringtone and vibration
  from the service. Accepting starts the microphone only when the user is in the visible activity
  (Android 14+ forbids starting a microphone foreground service from the background).
- **Rationale**: removes the legacy `startActivity()`-from-service pattern that Android 10+ blocks,
  and works without any push service (FCM is internet).
- **Alternatives**: `ConnectionService` self-managed Telecom (best system integration, but API 26+;
  can be added later behind the same call interface); keeping the legacy `startActivity()`
  (rejected: blocked).
- **Android 14+ note**: `USE_FULL_SCREEN_INTENT` may need a user grant for apps not declared as
  calling apps; the app checks `canUseFullScreenIntent()` and falls back to a heads-up
  notification with the Accept/Reject actions. **Verify on device**.
- **Battery**: show the vendor-specific "allow background activity" and "ignore battery
  optimizations" guidance in settings (FR-010); listening can be turned off.

## R9. Audio pipeline

- **Decision**: `AudioRecord` with `VOICE_COMMUNICATION`, `AudioManager.MODE_IN_COMMUNICATION`,
  platform AEC/NS/AGC effects enabled where available; `AudioTrack` low-latency (performance mode
  `LOW_LATENCY`), 20 ms frames; adaptive jitter buffer targeting 40-100 ms; packet-loss
  concealment via Opus; route selection (speaker/earpiece/Bluetooth) through `AudioManager`
  communication device APIs (with legacy fallbacks); audio focus requested for the call.
- **Rationale**: the platform voice mode gives echo cancellation on most devices, which full-duplex
  needs (SC-004). The legacy `Thread.sleep(100)` capture loop and shared `ByteArrayOutputStream`
  are replaced.
- **Alternatives**: custom AEC (rejected: large effort); Oboe/AAudio native (rejected for v1,
  consider if latency target is missed — **verify on device**).

## R10. Call state, timers and reliability

- **Decision**: one `CallStateMachine` (Idle, Calling, Ringing, Connecting, InCall, Ending, Ended)
  owned by the service. Timers: ring timeout 30 s, connect timeout 10 s, heartbeat every 2 s with
  10 s dead-peer timeout, 3 s INVITE retransmit window. Glare (simultaneous calls) resolved by
  comparing `deviceId`s: the lower id's invite wins and the other side answers BUSY/auto-accepts
  as callee.
- **Rationale**: Principle V; deterministic, testable on the JVM with a virtual clock.

## R11. Data storage

- **Decision**: Room for `PairedDevice` and `CallHistoryEntry`; AndroidKeyStore wraps the stored
  pairing secrets (AES key in keystore encrypts secret blobs); DataStore for settings. `allowBackup`
  disabled for key material (`android:allowBackup="false"` or data extraction rules excluding it).
- **Alternatives**: SharedPreferences with Base64 (legacy approach, rejected: no encryption, no queries).

## R12. QR and camera for pairing

- **Decision**: ZXing-embedded `ScanContract` for scanning and ZXing core for generating the QR
  (works offline, no Google Play Services requirement).
- **Alternatives**: ML Kit barcode scanning (rejected: needs Play Services model on some setups,
  heavier); CameraX analyzer with custom decoding (rejected: more code for the same outcome).

## R13. Testing strategy

- **Decision**: all of `core/` is Android-free. Tests: protocol codec round-trip and malformed/
  oversized inputs; crypto envelope and replay window; SAS derivation vectors; state machine with
  a fake clock for every spec scenario (accept, reject, timeout, cancel, busy, glare, peer loss);
  jitter buffer with simulated loss/reorder; loopback two-engine test over localhost sockets for a
  full call; relay forwarding test. Instrumented tests cover the service, notifications and UI.
  Manual two-device scenarios live in quickstart.md.
- **Rationale**: Principle VI; keeps the risky logic fast to test without phones.

## Resolved unknowns summary

| Unknown from plan | Resolution |
|-------------------|------------|
| Language/UI | R1 |
| Opus on minSdk 24 | R2 (Concentus) |
| QR library | R12 |
| TLS vs app-layer encryption | R6 + plan Complexity Tracking |
| Keeping traffic off mobile data | R3 |
| Hotspot relay and client isolation | R7 |
| Background incoming call | R8 |
