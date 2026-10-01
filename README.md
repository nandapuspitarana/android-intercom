# Two Way

Two Way is an Android intercom and voice-call app that works **without internet and without mobile data**. Phones that
run the app find each other on the same WiFi/LAN, or on a phone hotspot when there is no WiFi, and talk to each other
directly. There is no server, no account and no cloud.

Original idea by Pete Parker; original prototype by Team 7Bit (Alex Gusan, Bryan Raymond, Cole Risch, Charles Toll,
Rob Zaslavskiy, Sean Luther and Eric Van Gelder). This version is a rewrite for the offline-call use case
(see `specs/001-offline-voice-call/`); the old video-streaming prototype is in the git history.

## What it does

- **Calls over WiFi, LAN or a phone hotspot** with two-way voice at the same time, no internet and no mobile data.
- **Finds nearby phones** automatically (mDNS, with a multicast fallback and the hotspot phone as a directory), or you
  can add one by typing its address.
- **Rings like a phone**: full-screen incoming call over the lock screen, ringtone and vibration, accept or reject,
  busy and missed-call handling, call history.
- **Pairing**: only phones you paired (6-digit code comparison, or a QR code) can ring you. Calls are encrypted.
- **Hotspot mode**: one phone shares its hotspot and acts as hub; if the hotspot blocks phones from reaching each
  other, calls are relayed through the hotspot phone (it forwards ciphertext and cannot listen).
- **In a call**: duration, mute, earpiece/speaker/Bluetooth, microphone indicator, poor-connection and connection-lost
  messages. English and Indonesian.

## Architecture

Kotlin, Jetpack Compose, one Android module `app`. The call logic is written against small interfaces so that the same
code runs on a phone and in plain JVM tests.

```text
app/src/main/java/com/intercom/video/twoway/
  core/        no android.* imports: call state machine, JSON protocol, crypto (HKDF, AES-GCM), media packet and
               jitter buffer, pairing handshake, helpers
  net/         discovery (NSD, multicast, gateway probe), TCP signaling, UDP media socket, hotspot hub and relay,
               WiFi network binding (keeps traffic off mobile data)
  audio/       microphone, speaker, Opus (Concentus), audio routes
  service/     CallEngine (owns all call state), PairingManager, HubCoordinator, ListenerService (foreground service),
               notifications, ringtone
  data/        Room (paired devices, call history), keystore-backed secrets, settings
  ui/          Compose screens and view models
app/src/debug/ test fixtures shared by unit and instrumented tests (fake audio, test devices, flaky sockets)
specs/         Spec Kit documents: spec, plan, research, data model, contracts, tasks, checklists
```

### Ports and protocol (details in `specs/001-offline-voice-call/contracts/`)

| Port | Use |
|------|-----|
| TCP 45678 | signaling (JSON frames: INVITE, RINGING, ACCEPT, REJECT, BUSY, CANCEL, HANGUP, PING, pairing and hub messages) |
| UDP 239.255.42.99:45679 | multicast announce/query (discovery fallback) |
| mDNS `_twoway._tcp` | discovery |
| UDP, ephemeral | voice: Opus 16 kHz, 20 ms frames, AES-256-GCM per packet |

Security in short: no object deserialization from the network; INVITE is authenticated with the pairing secret;
per-call keys come from HKDF over the pairing secret and both nonces; pairing uses ephemeral ECDH P-256 plus a short
authentication string both users compare. See `specs/001-offline-voice-call/checklists/security.md`.

## Build and run

Requirements: JDK 17 or newer (Android Studio's bundled JDK works), Android SDK with the platform required by
`compileSdk` (Gradle downloads it when the licenses are accepted), Android 7.0+ phones (`minSdk 24`).

```text
./gradlew :app:assembleDebug                 # debug APK
./gradlew :app:assembleRelease               # minified release (sign it yourself)
./gradlew :app:testDebugUnitTest             # JVM unit and loopback tests (no phone needed)
./gradlew :app:connectedDebugAndroidTest     # instrumented + functional tests on a running emulator/phone
./gradlew :app:connectedFunctionalTest       # only the @FunctionalTest classes on a running emulator/phone
./gradlew functionalTest                     # the same, headless on a Gradle Managed Device (CI)
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest -Psoak      # 30-minute call soak test (SC-006); -Psoak.minutes=N to change
```

On a debug build two phones can call each other before pairing (a fixed debug secret is used for devices that were not
paired); release builds never do that.

Manual verification on real phones (WiFi without internet, hotspot, locked screen) follows
`specs/001-offline-voice-call/quickstart.md`.

## Development workflow

The project uses [Spec Kit](https://github.com/github/spec-kit): `.specify/memory/constitution.md` holds the rules
(offline-first, security by default, functional tests for every user story), and each feature has its spec, plan and
task list under `specs/`. Every user story needs automated functional tests that drive the real UI against an
in-process fake peer.
