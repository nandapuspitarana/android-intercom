# Tasks: Offline Voice Call over WiFi/Hotspot

**Input**: Design documents from `/specs/001-offline-voice-call/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md), `.specify/memory/constitution.md`

**Tests**: INCLUDED, including mandatory **functional tests per user story** (constitution v1.1.0, Principle VI): automated tests that drive the real UI/service against an in-process `FakePeerDevice`. A story is not done until its functional test passes. Constitution Principle VI requires unit tests for the protocol parser, call state machine and jitter buffer, plus a test for every call flow. Write each story's tests first and confirm they fail before implementing.

**Organization**: Tasks are grouped by user story (US1-US6 from spec.md) so each can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1..US6, only in user-story phases
- Every task names the exact file(s) to create or change

## Path Conventions

Single Android module `app` (see plan.md). Four prefixes are used below; expand them literally:

- `app/src/main/java/com/intercom/video/twoway` = `app/src/main/java/com/intercom/video/twoway`
- `app/src/test/java/com/intercom/video/twoway` = `app/src/test/java/com/intercom/video/twoway` (JVM tests, JUnit 5)
- `app/src/androidTest/java/com/intercom/video/twoway` = `app/src/androidTest/java/com/intercom/video/twoway` (instrumented tests)
- `app/src/main/res` = `app/src/main/res`

All new code is Kotlin. `app/src/main/java/com/intercom/video/twoway/core/**` MUST NOT import `android.*` (so it stays JVM-testable).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Replace the legacy Gradle 1.0 / Java prototype with a modern Kotlin project skeleton.

- [X] T001 Update the Gradle wrapper to the current stable Gradle (JDK 17) in `gradle/wrapper/gradle-wrapper.properties` and refresh `gradlew`, `gradlew.bat`
- [X] T002 Create the version catalog `gradle/libs.versions.toml` (AGP, Kotlin, Compose BOM, Room + KSP, coroutines, DataStore, lifecycle, Concentus Opus, ZXing core + zxing-android-embedded, JUnit 5, kotlinx-coroutines-test, AndroidX test)
- [X] T003 Replace `settings.gradle` and `build.gradle` with `settings.gradle.kts` and `build.gradle.kts` (plugin declarations only, `google()` + `mavenCentral()`, no `jcenter()`)
- [X] T004 Replace `app/build.gradle` with `app/build.gradle.kts`: `applicationId "com.intercom.video.twoway"`, `minSdk 24`, `compileSdk`/`targetSdk` = current stable (at least 35), Kotlin, Compose, Room KSP, JUnit 5 for `testDebugUnitTest`, `debug` source set enabled, release minify on
- [X] T005 Delete the legacy sources with `git rm -r`: `app/src/main/java/com/intercom/video/twoway/Controllers`, `app/src/main/java/com/intercom/video/twoway/Fragments`, `app/src/main/java/com/intercom/video/twoway/Interfaces`, `app/src/main/java/com/intercom/video/twoway/Models`, `app/src/main/java/com/intercom/video/twoway/Network`, `app/src/main/java/com/intercom/video/twoway/Services`, `app/src/main/java/com/intercom/video/twoway/Streaming`, `app/src/main/java/com/intercom/video/twoway/Utilities`, `app/src/main/java/com/intercom/video/twoway/MainActivity.java`, `app/src/androidTest/java/com/intercom/video/twoway/ApplicationTest.java`, legacy layouts/menus under `app/src/main/res/layout` and `app/src/main/res/menu`, `.idea/`, `7bit.iml`, `TwoWay.iml` (history keeps the old code; video returns as a separate feature)
- [X] T006 [P] Add `.gitignore` (build outputs, `.gradle/`, `local.properties`, `.idea/`, `*.iml`; `.specify/` and `.claude/skills/` stay tracked)
- [X] T007 Rewrite `app/src/main/AndroidManifest.xml`: keep package, add permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `NEARBY_WIFI_DEVICES` (`neverForLocation`), `ACCESS_FINE_LOCATION` (`maxSdkVersion="32"`), `CAMERA`; remove `WRITE_EXTERNAL_STORAGE`, `DISABLE_KEYGUARD`, camera `uses-feature` (set `required="false"`); no `screenOrientation` lock; `android:allowBackup="false"`
- [X] T008 [P] Add `.editorconfig` and ktlint/detekt configuration (`config/detekt/detekt.yml`) and wire `./gradlew lint ktlintCheck detekt` in `app/build.gradle.kts`
- [X] T009 [P] Create baseline resources `app/src/main/res/values/strings.xml` (English) and `app/src/main/res/values-in/strings.xml` (Indonesian) with `app_name` and a convention comment: every new user-facing string is added to BOTH files
- [X] T010 [P] Create `app/src/main/res/values/themes.xml` and a Compose Material 3 theme `app/src/main/java/com/intercom/video/twoway/ui/theme/Theme.kt` (light/dark)

**Checkpoint**: `./gradlew :app:assembleDebug` builds an empty app.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Protocol, crypto, storage, network binding and service skeleton that every story needs.

**CRITICAL**: No user story work can begin until this phase is complete.

### Tests first (all [P], must fail before implementation)

- [X] T011 [P] `app/src/test/java/com/intercom/video/twoway/core/util/NameSanitizerTest.kt`: display name 1-32 chars, trimmed, control characters removed, empty after sanitizing is rejected
- [X] T012 [P] `app/src/test/java/com/intercom/video/twoway/core/protocol/FrameCodecTest.kt`: `length` must be 1..4096 (u32 big-endian), partial reads, oversize and zero length close the connection
- [X] T013 [P] `app/src/test/java/com/intercom/video/twoway/core/protocol/JsonMessageCodecTest.kt`: strict parser, depth <= 4, strings <= 256 chars, unknown fields ignored, unknown message types ignored, malformed JSON rejected
- [X] T014 [P] `app/src/test/java/com/intercom/video/twoway/core/crypto/HkdfTest.kt` using RFC 5869 test vectors
- [X] T015 [P] `app/src/test/java/com/intercom/video/twoway/core/crypto/AesGcmEnvelopeTest.kt`: round trip, tampered ciphertext/tag fails, wrong associated data fails, 12-byte nonce = 4-byte salt + 8-byte counter
- [X] T016 [P] `app/src/test/java/com/intercom/video/twoway/core/crypto/ReplayWindowTest.kt`: signaling counters strictly increasing; media window of 64 packets
- [X] T017 [P] `app/src/test/java/com/intercom/video/twoway/core/crypto/CallKeysTest.kt`: `HKDF-SHA256(PS, nonceCaller||nonceCallee, "twoway call v1", 4*32+4*4)` yields separate signaling and media keys per direction plus 4-byte salts; both sides derive identical keys

### Implementation

- [X] T018 [P] `app/src/main/java/com/intercom/video/twoway/core/Clock.kt`: `Clock` interface with `nowMs()` monotonic, `SystemClock` implementation, and a `FakeClock` in `app/src/test/java/com/intercom/video/twoway/core/FakeClock.kt` for timer tests
- [X] T019 [P] `app/src/main/java/com/intercom/video/twoway/core/Logger.kt`: toggleable logger interface (no `System.out`, no `printStackTrace`); Android implementation in `app/src/main/java/com/intercom/video/twoway/service/AndroidLogger.kt`
- [X] T020 [P] `app/src/main/java/com/intercom/video/twoway/core/util/NameSanitizer.kt` (satisfies T011): display name "1-32 chars, trimmed, control characters removed, rendered as plain text only"
- [X] T021 `app/src/main/java/com/intercom/video/twoway/core/protocol/Messages.kt`: sealed message types and fields exactly as in `contracts/signaling.md` and `contracts/pairing.md` (`HELLO`, `PAIR_*`, `INVITE`, `PAIR_REQUIRED`, `RINGING`, `ACCEPT`, `REJECT`, `BUSY`, `CANCEL`, `HANGUP`, `PING`, `PONG`, `MUTE`, `HUB_*`)
- [X] T022 `app/src/main/java/com/intercom/video/twoway/core/protocol/FrameCodec.kt` (satisfies T012): max frame 4096 bytes; the "at most 8 concurrent inbound connections" limit is enforced by the server (T061), the codec only frames
- [X] T023 `app/src/main/java/com/intercom/video/twoway/core/protocol/JsonMessageCodec.kt` (satisfies T013): strict JSON, no object deserialization
- [X] T024 [P] `app/src/main/java/com/intercom/video/twoway/core/crypto/Hkdf.kt` (satisfies T014), HKDF-SHA256 over `javax.crypto.Mac`
- [X] T025 [P] `app/src/main/java/com/intercom/video/twoway/core/crypto/AesGcmEnvelope.kt` (satisfies T015): envelope `{c, n, d}` with AES-256-GCM, associated data `callId || direction`
- [X] T026 [P] `app/src/main/java/com/intercom/video/twoway/core/crypto/ReplayWindow.kt` (satisfies T016)
- [X] T027 `app/src/main/java/com/intercom/video/twoway/core/crypto/CallKeys.kt` (satisfies T017), zeroes key arrays on `wipe()`
- [X] T028 [P] Room entities in `app/src/main/java/com/intercom/video/twoway/data/entity/`: `LocalIdentity.kt` (deviceId 16 random bytes hex, displayName 1-32 chars, publicKey, createdAt), `PairedDevice.kt` (deviceId PK, displayName 1-32 chars, publicKeyFingerprint 32-byte SHA-256, pairingSecret 32-byte encrypted blob, trust enum `TRUSTED`/`REVOKED`, pairedAt, lastSeenAddress nullable), `CallHistoryEntry.kt` (id PK, peerDeviceId, peerName, direction `INCOMING`/`OUTGOING`, outcome `COMPLETED`/`MISSED`/`DECLINED`/`BUSY`/`CANCELLED`/`FAILED`, startedAt, durationMs 0 unless `COMPLETED`)
- [X] T029 `app/src/main/java/com/intercom/video/twoway/data/dao/PairedDeviceDao.kt` and `app/src/main/java/com/intercom/video/twoway/data/dao/CallHistoryDao.kt` (history has no foreign key so it survives unpairing)
- [X] T030 `app/src/main/java/com/intercom/video/twoway/data/AppDatabase.kt` (Room database with the three entities)
- [X] T031 [P] `app/src/main/java/com/intercom/video/twoway/data/SecretStore.kt`: AndroidKeyStore AES key that encrypts/decrypts pairing-secret blobs; secrets are never logged
- [X] T032 `app/src/main/java/com/intercom/video/twoway/data/IdentityRepository.kt`: create once at install a random 128-bit `deviceId` and a P-256 key pair in AndroidKeyStore (software fallback if unsupported), default `displayName` from device model passed through `NameSanitizer`
- [X] T033 [P] `app/src/main/java/com/intercom/video/twoway/data/SettingsStore.kt` (DataStore): `listenInBackground` default true, `startOnBoot` default false, `autoRejectUnknown` default false, `ringtoneUri`, `language`
- [X] T034 [P] `app/src/androidTest/java/com/intercom/video/twoway/data/DatabaseTest.kt`: DAO round trips and history pruning to the most recent 500 entries
- [X] T035 `app/src/main/java/com/intercom/video/twoway/net/network/WifiNetworkBinder.kt`: `ConnectivityManager.requestNetwork` with `TRANSPORT_WIFI` (does NOT require `NET_CAPABILITY_INTERNET` or validated), `bindProcessToNetwork`/`Network.socketFactory`, acquires `WifiLock` (low-latency mode on API 29+) and `MulticastLock`; exposes the bound `Network` for all sockets (FR-008)
- [X] T036 [P] `app/src/main/java/com/intercom/video/twoway/net/NetworkMode.kt`: enum `LAN`, `HOTSPOT_HOST`, `HOTSPOT_CLIENT`, `NONE` and a detector flow derived from `ConnectivityManager`, DHCP gateway and hotspot state
- [X] T037 `app/src/main/java/com/intercom/video/twoway/service/ListenerService.kt`: foreground service (`foregroundServiceType="connectedDevice"`), notification channel "Listening", binder exposing the call engine holder (engine is added in T066); declared in the manifest
- [X] T038 [P] `app/src/main/java/com/intercom/video/twoway/TwoWayApp.kt` (Application) creating an `AppContainer` (database, repositories, settings, binder, clock, logger); no global mutable statics
- [X] T039 [P] `app/src/main/java/com/intercom/video/twoway/ui/PermissionsGate.kt`: requests `RECORD_AUDIO`, `POST_NOTIFICATIONS` (API 33+), `NEARBY_WIFI_DEVICES` (API 33+) at a clear moment with a rationale, and a "how to allow it" screen when denied so a call is never silently one-way (FR-020)
- [X] T040 `app/src/main/java/com/intercom/video/twoway/ui/MainActivity.kt` and `app/src/main/java/com/intercom/video/twoway/ui/AppNavHost.kt`: Compose navigation host with routes `devices`, `call`, `pairing`, `history`, `settings`, `hotspot` (placeholders until each story fills them)
- [ ] T041 `app/build.gradle.kts`: register a `functionalTest` Gradle task that runs only instrumented tests annotated `@FunctionalTest` (`app/src/androidTest/java/com/intercom/video/twoway/functional/FunctionalTest.kt`), plus a Gradle Managed Device (API 34 emulator) so `./gradlew functionalTest` runs headless in CI **Status:** the `functionalTest`, `connectedFunctionalTest` tasks and the managed device are configured and the annotation filter works (run via `connectedFunctionalTest` on the installed emulator); `functionalTest` on the managed device was not run (needs a system image download).
- [X] T042 [P] `app/src/androidTest/java/com/intercom/video/twoway/functional/FakePeerDevice.kt`: an in-process second device (its own `CallEngine`, identity, localhost sockets, fake audio source/sink with frame counters) with scriptable behaviour: auto-accept, reject, busy, no-answer, hang up, send junk; used by every functional test as the "other phone"
- [X] T043 [P] `app/src/androidTest/java/com/intercom/video/twoway/functional/NetworkFaultProxy.kt`: UDP/TCP proxy between the app and `FakePeerDevice` that can delay, drop, black-hole and block direct paths (simulates WiFi loss, poor network, client isolation) so functional tests stay deterministic **Implemented as** in-process socket providers in `app/src/debug/.../testing/`: `IsolatedSocketProvider` (blocks chosen phone-to-phone connections = client isolation), `FlakySocketProvider` (black-holes TCP and UDP = lost WiFi) and `RecordingSocketProvider` (captures signaling for replay tests).
- [X] T044 [P] `app/src/androidTest/java/com/intercom/video/twoway/functional/FunctionalTestRule.kt`: JUnit rule that launches `MainActivity` with Compose test APIs, injects a test `AppContainer` (in-memory database, `FakeClock` with fast-forward for the 30 s / 10 s timers, fake audio), grants permissions and cleans up

**Checkpoint**: unit tests T011-T017 pass; app launches to an empty device list; `ListenerService` starts.

---

## Phase 3: User Story 1 - Call a nearby device and talk (Priority: P1) MVP

**Goal**: Two devices on the same network (no internet) find each other, one calls, the other accepts, both talk at the same time, either ends the call.

**Independent Test**: quickstart Scenario A (devices seeded as trusted by the debug seeder, T068).

### Tests for User Story 1 (write first, confirm they fail)

- [X] T045 [P] [US1] `app/src/test/java/com/intercom/video/twoway/core/call/CallStateMachineTest.kt`: Idle->Calling->Connecting->InCall->Ended with `FakeClock`; INVITE retransmit once after 1.5 s and fail "unavailable" at 5 s; connect timeout 10 s; heartbeat PING every 2 s and dead peer after 10 s; any new INVITE while not Idle is answered `BUSY` without changing state
- [X] T046 [P] [US1] `app/src/test/java/com/intercom/video/twoway/core/media/JitterBufferTest.kt`: reordering, loss, late packets dropped, adaptive target 40-100 ms
- [X] T047 [P] [US1] `app/src/test/java/com/intercom/video/twoway/core/media/MediaPacketTest.kt`: header layout from `contracts/media.md`, datagram <= 1024 bytes, wrong session tag / bad GCM tag / sequence outside the 64-packet window are dropped
- [X] T048 [P] [US1] `app/src/test/java/com/intercom/video/twoway/core/protocol/InviteAuthTest.kt`: INVITE `mac` over canonical fields verifies with the pairing secret, bad/missing mac rejected, `ts` older than 30 s rejected
- [X] T049 [P] [US1] `app/src/test/java/com/intercom/video/twoway/net/DiscoveryParsingTest.kt`: multicast announce <= 256 bytes, invalid JSON/other major version/own id ignored, more than 10 announcements per second per source dropped, entries expire 15 s after last announce
- [X] T050 [P] [US1] `app/src/test/java/com/intercom/video/twoway/call/LoopbackCallTest.kt`: two `CallEngine` instances over real localhost sockets complete INVITE -> RINGING -> ACCEPT -> two-way media -> HANGUP, and a call connects within 10 s of accept
- [X] T051 [P] [US1] `app/src/androidTest/java/com/intercom/video/twoway/functional/CallFlowFunctionalTest.kt` (`@FunctionalTest`): `FakePeerDevice` appears in the device list; tapping it shows calling then ringing; peer auto-accepts and the screen shows connected; audio frames flow in both directions at the same time (fake sink counters > 0 on both sides); pressing End returns both sides to the list; ending from the peer also returns the app to the list; (binding of sockets to the WiFi network is NOT observable in this test and stays a manual check, quickstart Scenario A)

### Implementation for User Story 1

- [X] T052 [P] [US1] `app/src/main/java/com/intercom/video/twoway/core/call/CallSession.kt`: fields from data-model `Call` (callId 16 random bytes, role, peer, state, keys, path, startedAt/connectedAt, muted, speakerOn, endReason); keys wiped on end
- [X] T053 [US1] `app/src/main/java/com/intercom/video/twoway/core/call/CallStateMachine.kt` (satisfies T045): states `Idle, Calling, Ringing, Connecting, InCall, Ending, Ended`, transitions and timers per data-model.md and `contracts/signaling.md`
- [X] T054 [P] [US1] `app/src/main/java/com/intercom/video/twoway/core/protocol/InviteAuth.kt` (satisfies T048)
- [X] T055 [P] [US1] `app/src/main/java/com/intercom/video/twoway/core/media/MediaPacket.kt` (satisfies T047): encrypt/decrypt with the media key, nonce = 4-byte salt + 8 bytes from sequence plus 48-bit rollover
- [X] T056 [P] [US1] `app/src/main/java/com/intercom/video/twoway/core/media/JitterBuffer.kt` (satisfies T046)
- [X] T057 [P] [US1] `app/src/main/java/com/intercom/video/twoway/core/media/OpusCodec.kt` (interface) and `app/src/main/java/com/intercom/video/twoway/audio/ConcentusOpusCodec.kt`: Opus 16 kHz mono 20 ms frames (320 samples), about 24 kbps, in-band FEC on
- [X] T058 [P] [US1] `app/src/main/java/com/intercom/video/twoway/net/discovery/DeviceRegistry.kt`: in-memory `Device` list (deviceId, sanitized displayName, address, sources, role, lastSeen, reachability); entries expire 15 s without an announce; exposes a `StateFlow`
- [X] T059 [P] [US1] `app/src/main/java/com/intercom/video/twoway/net/discovery/NsdDiscovery.kt`: register/discover `_twoway._tcp` with TXT `v`, `id`, `n`, `r` (each value <= 64 bytes) per `contracts/discovery.md`
- [X] T060 [P] [US1] `app/src/main/java/com/intercom/video/twoway/net/discovery/MulticastDiscovery.kt` (satisfies T049): announce every 5 s on `239.255.42.99:45679`, datagram <= 256 bytes, answers queries
- [X] T061 [US1] `app/src/main/java/com/intercom/video/twoway/net/transport/TcpSignalingServer.kt`: port 45678 (fallback ephemeral advertised in discovery), "at most 8 concurrent inbound connections; at most 5 new connections per source per 10 s; a connection that sends nothing for 10 s before the first complete frame is closed", uses `FrameCodec` + `JsonMessageCodec`, sockets bound via `WifiNetworkBinder`
- [X] T062 [US1] `app/src/main/java/com/intercom/video/twoway/net/transport/TcpSignalingClient.kt`: connect, send INVITE and encrypted follow-ups, per `contracts/signaling.md`
- [X] T063 [P] [US1] `app/src/main/java/com/intercom/video/twoway/net/transport/UdpMediaSocket.kt`: UDP socket bound to the WiFi network, "max datagram 1024 bytes", send/receive voice and keep-alive packets
- [X] T064 [P] [US1] `app/src/main/java/com/intercom/video/twoway/audio/AudioCapture.kt`: `AudioRecord` with `VOICE_COMMUNICATION`, `AudioManager.MODE_IN_COMMUNICATION`, platform AEC/NS/AGC enabled where available, 20 ms frames
- [X] T065 [P] [US1] `app/src/main/java/com/intercom/video/twoway/audio/AudioPlayback.kt`: `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`, fed by the jitter buffer, Opus PLC for gaps, audio focus requested for the call
- [X] T066 [US1] `app/src/main/java/com/intercom/video/twoway/service/CallEngine.kt` (satisfies T050): orchestrates state machine, signaling server/client, call key derivation (`CallKeys`), UDP media, Opus, capture/playback; heartbeat PING/PONG every 2 s; exposes `StateFlow<CallUiState>`; depends on T053-T065
- [X] T067 [US1] Wire `CallEngine`, `NsdDiscovery`, `MulticastDiscovery`, `TcpSignalingServer` and `WifiNetworkBinder` into `app/src/main/java/com/intercom/video/twoway/service/ListenerService.kt` so the engine lives in the service, not in UI classes
- [X] T068 [P] [US1] Debug-only `app/src/debug/java/com/intercom/video/twoway/DebugPairingSeeder.kt`: inserts a fixed test `PairedDevice` secret on debug builds so US1 can be tested before pairing exists (MUST NOT exist in release; verify `app/src/release` has no reference) **Implemented as** `app/src/debug/.../service/TrustStoreFactory.kt` (debug: falls back to a fixed secret when a device is not really paired) with a pass-through `app/src/release/.../service/TrustStoreFactory.kt`; verified the release APK contains no debug secret.
- [X] T069 [P] [US1] `app/src/main/java/com/intercom/video/twoway/ui/devices/DeviceListScreen.kt` + `DeviceListViewModel.kt`: list of discovered devices with name, tap to call
- [X] T070 [P] [US1] `app/src/main/java/com/intercom/video/twoway/ui/call/CallingScreen.kt`: caller states calling / ringing / connected / declined / busy / unavailable / ended
- [X] T071 [P] [US1] `app/src/main/java/com/intercom/video/twoway/ui/call/IncomingCallScreen.kt`: caller name with Accept and Reject (foreground version; lock-screen version is US2)
- [X] T072 [P] [US1] `app/src/main/java/com/intercom/video/twoway/ui/call/InCallScreen.kt`: End button (duration, mute and speaker come in US6)
- [X] T073 [US1] Connect `AppNavHost` and ViewModels to the service-hosted `CallEngine` via a bound `ListenerService`; navigate on state changes **Implemented as** `AppViewModel` reading the app-scoped `AppContainer.runtime` (the service starts it), so no binder is needed.
- [X] T074 [US1] Add US1 strings to `app/src/main/res/values/strings.xml` and `app/src/main/res/values-in/strings.xml`
- [ ] T075 [US1] Run quickstart Scenario A on two phones with no internet and mobile data off; confirm no mobile-data traffic (SC-009); fix defects

**Checkpoint**: US1 works alone as a trusted-pair intercom (MVP) and its functional test passes (`./gradlew functionalTest`).

---

## Phase 4: User Story 2 - Receive a call when the app is closed or the screen is off (Priority: P1)

**Goal**: Locked or backgrounded phones ring and can answer like a normal call.

**Independent Test**: quickstart Scenario B.

### Tests for User Story 2

- [X] T076 [P] [US2] `app/src/androidTest/java/com/intercom/video/twoway/service/IncomingCallNotificationTest.kt`: the notification uses category `CALL`, a full-screen intent to `IncomingCallActivity`, and Accept/Reject actions; falls back to heads-up with actions when `canUseFullScreenIntent()` is false
- [X] T077 [P] [US2] `app/src/androidTest/java/com/intercom/video/twoway/functional/BackgroundCallFunctionalTest.kt` (`@FunctionalTest`, UiAutomator): with the app in the background and the screen off (`device.sleep()`), an INVITE from `FakePeerDevice` shows the incoming-call notification/screen, ringtone starts, tapping Accept connects the call without unlocking; with `listenInBackground` off the peer receives `REJECT(unavailable)`; after a boot broadcast with `startOnBoot` on the listener is running

### Implementation for User Story 2

- [X] T078 [P] [US2] `app/src/main/java/com/intercom/video/twoway/service/CallNotificationFactory.kt`: channels "Listening" (low) and "Incoming call" (high importance), full-screen-intent incoming notification, ongoing-call notification
- [X] T079 [P] [US2] `app/src/main/java/com/intercom/video/twoway/ui/call/IncomingCallActivity.kt`: `setShowWhenLocked(true)`, `setTurnScreenOn(true)`, `requestDismissKeyguard`, shows `IncomingCallScreen`; declared in the manifest
- [X] T080 [P] [US2] `app/src/main/java/com/intercom/video/twoway/service/RingtonePlayer.kt`: ringtone (from `ringtoneUri`) and vibration, stops on any state change or timeout
- [X] T081 [US2] Update `app/src/main/java/com/intercom/video/twoway/service/ListenerService.kt`: on `Ringing` post the full-screen notification and start the ringtone; "Accept" starts the microphone foreground service type only from the visible activity (Android 14+ forbids starting it from the background)
- [X] T082 [P] [US2] `app/src/main/java/com/intercom/video/twoway/service/BootReceiver.kt` + manifest entry: restart listening on `BOOT_COMPLETED` only when `startOnBoot` is on
- [X] T083 [P] [US2] `app/src/main/java/com/intercom/video/twoway/ui/settings/SettingsScreen.kt`: toggles `listenInBackground` and `startOnBoot`, ringtone picker
- [X] T084 [US2] When `listenInBackground` is off, the service stops listening and any INVITE gets `REJECT(reason="unavailable")` while the app is not foreground (`app/src/main/java/com/intercom/video/twoway/service/CallEngine.kt`)
- [X] T085 [P] [US2] `app/src/main/java/com/intercom/video/twoway/ui/settings/BackgroundGuidance.kt`: request ignoring battery optimizations and show per-vendor "allow background activity" guidance (FR-010)
- [X] T086 [US2] Add US2 strings to both `strings.xml` files
- [ ] T087 [US2] Run quickstart Scenario B (locked, screen off, app closed); confirm alert within 3 s (SC-005) and accept-from-lock-screen works

**Checkpoint**: US1 and US2 together form a usable phone-style intercom; their functional tests pass.

---

## Phase 5: User Story 3 - Reject, miss, busy, cancel (Priority: P2)

**Goal**: Predictable outcomes when the callee declines, does not answer, is busy, or the caller cancels.

**Independent Test**: quickstart Scenario C.

### Tests for User Story 3

- [X] T088 [P] [US3] Extend `app/src/test/java/com/intercom/video/twoway/core/call/CallStateMachineTest.kt`: REJECT -> Ended(DECLINED); ring timeout 30 s -> MISSED on callee and CANCEL from caller; BUSY reply while in a call leaves the existing call untouched; glare (both Calling each other) resolved so the lower `deviceId` keeps its call
- [X] T089 [P] [US3] `app/src/test/java/com/intercom/video/twoway/call/LoopbackRejectBusyTest.kt`: reject, no-answer timeout, busy, cancel flows over localhost sockets
- [X] T090 [P] [US3] `app/src/androidTest/java/com/intercom/video/twoway/data/CallHistoryRepositoryTest.kt`: an entry per outcome, durationMs 0 unless COMPLETED, pruning beyond 500, clear
- [X] T091 [P] [US3] `app/src/androidTest/java/com/intercom/video/twoway/functional/CallOutcomesFunctionalTest.kt` (`@FunctionalTest`): peer rejects -> UI shows "declined" and a DECLINED history entry exists; no answer for 30 s (fast-forwarded clock) -> ringing stops and a MISSED entry plus missed-call notification appear; callee already in a call -> caller UI shows "busy" within 5 s and the first call is untouched; caller cancels while peer rings -> peer shows a missed call; simultaneous calls -> exactly one call is established

### Implementation for User Story 3

- [X] T092 [US3] Extend `app/src/main/java/com/intercom/video/twoway/core/call/CallStateMachine.kt`: REJECT, CANCEL, BUSY handling, ring timeout 30 s, glare rule by lower `deviceId` (data-model.md)
- [X] T093 [US3] Extend `app/src/main/java/com/intercom/video/twoway/service/CallEngine.kt` to send and receive `REJECT`, `BUSY`, `CANCEL` and map end reasons to history outcomes
- [X] T094 [P] [US3] `app/src/main/java/com/intercom/video/twoway/data/CallHistoryRepository.kt`: write one entry when a call reaches Ended, prune to the most recent 500, clear all
- [X] T095 [P] [US3] `app/src/main/java/com/intercom/video/twoway/ui/history/HistoryScreen.kt` + `HistoryViewModel.kt`: incoming/outgoing/missed list, tap to call back, clear history
- [X] T096 [US3] Missed-call notification in `app/src/main/java/com/intercom/video/twoway/service/CallNotificationFactory.kt` when an incoming call ends as MISSED or CANCELLED
- [X] T097 [US3] Update `app/src/main/java/com/intercom/video/twoway/ui/call/CallingScreen.kt` and `app/src/main/java/com/intercom/video/twoway/ui/call/IncomingCallScreen.kt` for declined / busy / no answer messages and the Reject action
- [X] T098 [US3] Add US3 strings to both `strings.xml` files
- [ ] T099 [US3] Run quickstart Scenario C

**Checkpoint**: all call outcomes behave predictably and are recorded; the US3 functional test passes.

---

## Phase 6: User Story 4 - Pair before a device can call me (Priority: P2)

**Goal**: Only paired devices can ring this phone; pairing uses ECDH with a 6-digit comparison or QR.

**Independent Test**: quickstart Scenario D.

### Tests for User Story 4

- [X] T100 [P] [US4] `app/src/test/java/com/intercom/video/twoway/core/pairing/PairingSessionTest.kt`: both sides derive the same 6-digit SAS and `PS`; a simulated man-in-the-middle yields different SAS values; "Does not match" aborts with nothing stored; confirm MAC verified; 120 s timeout
- [X] T101 [P] [US4] `app/src/test/java/com/intercom/video/twoway/call/PairingLoopbackTest.kt`: full `PAIR_REQUEST` -> `PAIR_ACCEPT` -> `PAIR_CONFIRM` between two engines over localhost
- [X] T102 [P] [US4] `app/src/test/java/com/intercom/video/twoway/call/UnpairedInviteTest.kt`: INVITE from unknown, revoked or bad-mac sender gets `PAIR_REQUIRED` (or is ignored when `autoRejectUnknown` is on) and never rings (SC-010)
- [X] T103 [P] [US4] `app/src/androidTest/java/com/intercom/video/twoway/functional/PairingFunctionalTest.kt` (`@FunctionalTest`): an unpaired `FakePeerDevice` INVITE never rings the app (SC-010); pairing through the UI with matching 6-digit codes marks the peer trusted and the next call rings; "Does not match" aborts and stores nothing; scanning a QR payload starts the same flow; removing the paired device makes its INVITE be refused again; a peer presenting the same `deviceId` with a different key is refused with a warning

### Implementation for User Story 4

- [X] T104 [US4] `app/src/main/java/com/intercom/video/twoway/core/pairing/PairingSession.kt` (satisfies T100): per `contracts/pairing.md` - ephemeral P-256 ECDH, transcript `T`, `SAS = uint32_be(HMAC-SHA256(Z, T)[0..4]) mod 1_000_000` padded to 6 digits, confirm MAC, `PS = HKDF(Z, salt=T, info="twoway PS v1", 32)`
- [X] T105 [US4] `app/src/main/java/com/intercom/video/twoway/data/PairedDeviceRepository.kt`: store `PS` via `SecretStore`, pin the long-term public-key fingerprint, set `REVOKED`, delete removes the secret, refuse a known `deviceId` presenting a different fingerprint
- [X] T106 [US4] `app/src/main/java/com/intercom/video/twoway/net/transport/PairingHandler.kt`: routes `PAIR_*` frames, "1 pending request per source and 3 per minute", 120 s overall timeout, registered in `app/src/main/java/com/intercom/video/twoway/net/transport/TcpSignalingServer.kt` **Implemented as** `service/PairingManager.kt` (+ `PairingRouter` hook in `CallEngine`, `PairRateLimiter`): one handshake at a time, 1 request per source pending and 3 per minute, 120 s timeout.
- [X] T107 [US4] Update `app/src/main/java/com/intercom/video/twoway/service/CallEngine.kt`: INVITE from unknown/revoked/bad-mac sender is answered `PAIR_REQUIRED` and never rings; `autoRejectUnknown` makes it silent
- [X] T108 [P] [US4] `app/src/main/java/com/intercom/video/twoway/ui/pairing/PairingScreen.kt`: incoming pair request, 6-digit code, "Matches" and "Does not match" buttons, abort and error states
- [X] T109 [P] [US4] `app/src/main/java/com/intercom/video/twoway/ui/pairing/QrPairing.kt`: generate and scan the payload `twoway://pair?v=1&id=<deviceId>&ip=<addr>&p=<port>&fp=<16-byte hex>&n=<name>` (no secret inside) using ZXing; permission request for `CAMERA`
- [X] T110 [P] [US4] `app/src/main/java/com/intercom/video/twoway/ui/pairing/PairedDevicesScreen.kt`: list, local rename, remove (revoke)
- [X] T111 [US4] Update `app/src/main/java/com/intercom/video/twoway/ui/devices/DeviceListScreen.kt`: show paired state, "Pair" action, and open pairing when calling an unpaired device
- [X] T112 [US4] Add the `autoRejectUnknown` toggle to `app/src/main/java/com/intercom/video/twoway/ui/settings/SettingsScreen.kt`
- [X] T113 [US4] Add US4 strings to both `strings.xml` files
- [ ] T114 [US4] Run quickstart Scenario D (including "Does not match" and removal)

**Checkpoint**: unpaired devices cannot ring or listen; the debug seeder is no longer needed for normal use; the US4 functional test passes.

---

## Phase 7: User Story 5 - Use a phone hotspot when there is no WiFi (Priority: P2)

**Goal**: Hotspot host and clients find each other and call, including when client isolation blocks direct traffic.

**Independent Test**: quickstart Scenario E.

### Tests for User Story 5

- [X] T115 [P] [US5] `app/src/test/java/com/intercom/video/twoway/net/HubDirectoryTest.kt`: `HUB_REGISTER`, `HUB_DIRECTORY` with at most 32 entries pushed on change, gateway probe `HELLO` reply with `r = h`
- [X] T116 [P] [US5] `app/src/test/java/com/intercom/video/twoway/net/HubRelayTest.kt`: relayed signaling frame forwarded unchanged, UDP media forwarded by session tag, unknown tag dropped, idle relay closed after 15 s, size/rate limits applied
- [X] T117 [P] [US5] `app/src/androidTest/java/com/intercom/video/twoway/functional/HotspotFunctionalTest.kt` (`@FunctionalTest`): `FakePeerDevice` acting as hotspot hub is found by gateway probe and its directory entries appear in the list; with `NetworkFaultProxy` blocking the direct path the call still connects via the hub relay and audio flows; with both paths blocked the UI shows the "network blocks device-to-device traffic" message within the 10 s connect timeout; manual `ip:port` entry adds a device

### Implementation for User Story 5

- [X] T118 [P] [US5] `app/src/main/java/com/intercom/video/twoway/net/discovery/GatewayProbe.kt`: if no peer answered within 2 s, TCP `HELLO` to the DHCP gateway `:45678`
- [X] T119 [US5] `app/src/main/java/com/intercom/video/twoway/net/network/HubRegistry.kt`: host-side directory, handles `HUB_REGISTER`, pushes `HUB_DIRECTORY`; active when this phone's hotspot is on
- [X] T120 [US5] `app/src/main/java/com/intercom/video/twoway/net/network/HubClient.kt`: client-side register and receive directory entries into `app/src/main/java/com/intercom/video/twoway/net/discovery/DeviceRegistry.kt` with source `HUB_DIRECTORY`
- [X] T121 [US5] `app/src/main/java/com/intercom/video/twoway/net/network/HubRelay.kt`: `HUB_RELAY_OPEN/READY/CLOSE`, signaling wrapped as `{relay, frame}`, UDP forwarded by 4-byte session tag; the hub never decrypts
- [X] T122 [US5] Update `app/src/main/java/com/intercom/video/twoway/service/CallEngine.kt` and `app/src/main/java/com/intercom/video/twoway/net/transport/UdpMediaSocket.kt`: try the direct path first with a 2 s keep-alive probe, fall back to `VIA_HUB`, and fail with a clear reason if neither works within the 10 s connect timeout
- [X] T123 [P] [US5] `app/src/main/java/com/intercom/video/twoway/net/network/HotspotManager.kt`: `WifiManager.startLocalOnlyHotspot` on API 26+ (needs `NEARBY_WIFI_DEVICES`/location), instructions-only on API 24-25 and for the system hotspot
- [X] T124 [P] [US5] `app/src/main/java/com/intercom/video/twoway/ui/hotspot/HotspotScreen.kt`: "Start hotspot" and "Join" actions, SSID/password and join QR (`WIFI:T:WPA;S:<ssid>;P:<password>;;`), step-by-step help
- [X] T125 [P] [US5] Manual IP entry (`ip[:port]`) in `app/src/main/java/com/intercom/video/twoway/ui/devices/DeviceListScreen.kt` that performs the `HELLO` exchange
- [X] T126 [US5] Feed `NetworkMode` into the UI (banner on `NONE`/`HOTSPOT_*`) and show a clear message when the network blocks device-to-device traffic (FR-021)
- [X] T127 [US5] Add US5 strings to both `strings.xml` files
- [ ] T128 [US5] Run quickstart Scenario E on a hotspot with and without client isolation

**Checkpoint**: calls work on hotspot networks, direct or relayed; the US5 functional test passes.

---

## Phase 8: User Story 6 - Know the state of the call (Priority: P3)

**Goal**: Duration, mute, speaker/earpiece, mic indicator, and clear connection-loss messaging.

**Independent Test**: quickstart Scenario F.

### Tests for User Story 6

- [X] T129 [P] [US6] `app/src/test/java/com/intercom/video/twoway/call/ConnectionLossTest.kt`: no valid message for 10 s ends the call with "connection lost" within 15 s; 3 s without media shows "poor connection" but does not end the call
- [X] T130 [P] [US6] `app/src/test/java/com/intercom/video/twoway/call/MuteTest.kt`: muted sender sends 1-byte keep-alives once per second and a `MUTE` message; peer sees the muted indicator
- [X] T131 [P] [US6] `app/src/androidTest/java/com/intercom/video/twoway/functional/InCallControlsFunctionalTest.kt` (`@FunctionalTest`): duration counter advances; mute makes the peer's fake sink receive only keep-alives and shows the muted indicator on the peer; speaker/earpiece toggle changes the audio route; the microphone indicator is visible whenever capture runs; black-holing the network for 3 s shows "poor connection" and after 10 s the call ends with "connection lost" and returns to the device list within 15 s

### Implementation for User Story 6

- [X] T132 [P] [US6] `app/src/main/java/com/intercom/video/twoway/audio/AudioRouteManager.kt`: speaker / earpiece / Bluetooth using communication-device APIs on API 31+ with legacy `AudioManager` fallback
- [X] T133 [US6] Update `app/src/main/java/com/intercom/video/twoway/core/media/MediaPacket.kt` and `app/src/main/java/com/intercom/video/twoway/service/CallEngine.kt`: type `keep-alive` once per second while muted, `MUTE` signaling message
- [X] T134 [P] [US6] Update `app/src/main/java/com/intercom/video/twoway/ui/call/InCallScreen.kt`: call duration, mute, speaker/route, peer-muted indicator, "poor connection" banner
- [X] T135 [US6] Always-visible microphone indicator: ongoing-call notification (with `microphone` foreground service type) and an on-screen indicator whenever capture runs (FR-018)
- [X] T136 [US6] On heartbeat loss end the call with a clear message and return to the device list within 15 s (SC-008)
- [X] T137 [US6] Add US6 strings to both `strings.xml` files
- [ ] T138 [US6] Run quickstart Scenario F

**Checkpoint**: all six stories complete and the whole functional suite passes.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T139 [P] `app/src/test/java/com/intercom/video/twoway/net/FuzzTest.kt`: random bytes, 10 MB frames, junk datagrams against `TcpSignalingServer` and `UdpMediaSocket`; an active call must stay unaffected (FR-017)
- [X] T140 [P] Language setting (English/Indonesian) in `app/src/main/java/com/intercom/video/twoway/ui/settings/SettingsScreen.kt` and an audit that every key exists in both `strings.xml` files (FR-023)
- [X] T141 [P] `app/proguard-rules.pro`: keep rules for Room, Concentus and reflection-free serialization; verify a release build
- [X] T142 [P] `app/src/main/res/xml/data_extraction_rules.xml` and `app/src/main/res/xml/backup_rules.xml` excluding keystore-wrapped secrets and the database from backup
- [X] T143 [P] Security review checklist in `specs/001-offline-voice-call/checklists/security.md` covering signaling, pairing, crypto envelope, relay and logging; a second reviewer signs off (constitution Development Workflow) **Status:** checklist written and every item linked to evidence; the required second human reviewer has NOT signed off yet (see the Reviewers table).
- [X] T144 [P] `app/src/androidTest/java/com/intercom/video/twoway/functional/SecurityFunctionalTest.kt` (`@FunctionalTest`) and `app/src/androidTest/java/com/intercom/video/twoway/functional/FunctionalSuite.kt`: junk bytes, a 10 MB frame and replayed signaling from `FakePeerDevice` during an active call do not end or disturb the call; the suite runs every `@FunctionalTest` class together **Implemented as** `SecurityFunctionalTest.kt` (+ `SettingsFunctionalTest.kt`); there is no separate `FunctionalSuite.kt`: `./gradlew functionalTest` / `connectedFunctionalTest` already run every `@FunctionalTest` class together, and a second suite class would run them twice.
- [X] T145 Amend `.specify/memory/constitution.md` to v1.1.1: Technical Constraints wording "authenticated encryption (TLS or an equivalent pairing-keyed scheme)", update the Sync Impact Report
- [X] T146 Measure and record latency (SC-003), a 30-minute call (SC-006), discovery time (SC-002), call setup time (SC-001) in `specs/001-offline-voice-call/checklists/validation.md`
- [X] T147 Update `README.md` (architecture, ports, protocol summary, build/run) and mark the items of `TODO.md` that this feature supersedes
- [ ] T148 Run the full `quickstart.md` on at least two phone models/vendors and record results in `specs/001-offline-voice-call/checklists/validation.md`; confirm SC-009 (no mobile-data traffic) and SC-010 (unpaired cannot ring)

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies; T005 after T003-T004 compile, T007 after T005.
- **Foundational (Phase 2)**: needs Phase 1; BLOCKS all stories.
- **US1 (Phase 3)**: needs Phase 2. **MVP.**
- **US2**: needs US1 (the engine hosted in the service).
- **US3**: needs US1 (touches the same state machine/engine; sequence after US2 if one developer).
- **US4**: needs US1; independent of US2/US3 apart from shared files (`CallEngine.kt`, `DeviceListScreen.kt`, `SettingsScreen.kt`).
- **US5**: needs US1 (and US4 for realistic pairing between clients).
- **US6**: needs US1.
- **Polish (Phase 9)**: after the stories you intend to ship.

### Within a story

Tests first and failing -> core (`core/**`) -> adapters (`net/`, `audio/`) -> `CallEngine` -> UI -> strings -> quickstart run.

### Story completion order (graph)

```text
Phase 1 -> Phase 2 -> US1 -+-> US2 -> US3
                           +-> US4 -> US5
                           +-> US6
                                   \-> Polish
```

### Parallel opportunities

- Phase 1: T006, T008, T009, T010 together.
- Phase 2 tests T011-T017 together; then T020, T024, T025, T026, T028, T031, T033 and T038-T039 together.
- US1: tests T045-T050 together; then T052, T054-T060, T063-T065, T068-T072 are different files and can run in parallel.
- US4: T108-T110 (three UI files) together after T104-T105.
- US6: T132 and T134 together.

### Parallel example: User Story 1

```text
Task: "app/src/test/java/com/intercom/video/twoway/core/call/CallStateMachineTest.kt"      (T045)
Task: "app/src/test/java/com/intercom/video/twoway/core/media/JitterBufferTest.kt"          (T046)
Task: "app/src/test/java/com/intercom/video/twoway/core/media/MediaPacketTest.kt"           (T047)
Task: "app/src/main/java/com/intercom/video/twoway/core/media/JitterBuffer.kt"              (T056)
Task: "app/src/main/java/com/intercom/video/twoway/audio/AudioCapture.kt"                   (T064)
Task: "app/src/main/java/com/intercom/video/twoway/audio/AudioPlayback.kt"                  (T065)
```

---

## Implementation Strategy

1. **MVP first**: Phase 1, Phase 2, US1 (T001-T075). Stop and validate Scenario A. This is already a working offline voice intercom for a trusted test pair.
2. **Make it a real phone**: US2 (background and locked-screen ringing) - the product is not usable without it.
3. **Make it safe**: US4 (pairing) before using on any shared network; do not ship without it.
4. **Round out**: US3 (outcomes and history), US5 (hotspot), US6 (call UX), then Polish.
5. **Ship gate**: US1 + US2 + US4 with their functional tests green (`./gradlew functionalTest`) + Polish items T139, T143, T148.

## Notes

- Total tasks: 148. Tests are written before the code they cover.
- `[P]` tasks touch different files; tasks editing `CallEngine.kt`, `ListenerService.kt`, `DeviceListScreen.kt`, `SettingsScreen.kt` or `strings.xml` are NOT parallel with each other.
- Every user story has a `@FunctionalTest` class under `app/src/androidTest/java/com/intercom/video/twoway/functional/`; run them with `./gradlew functionalTest`. Never mark a story complete with a failing or missing functional test.
- Commit after each task or logical group; never commit secrets or the debug seeder into the release variant.
- Re-run `/speckit-analyze` after generating this file for a consistency pass.
