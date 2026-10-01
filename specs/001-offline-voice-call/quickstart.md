# Quickstart: Validating Offline Voice Call

Runnable scenarios that prove the feature end to end. Details of messages and entities are in
[contracts/](contracts/) and [data-model.md](data-model.md); they are not repeated here.

## Prerequisites

- Two (ideally three) Android phones, Android 7.0+, with microphone and speaker.
- A WiFi router with the WAN cable unplugged (or a router/phone hotspot with no internet), and mobile data **off** on all phones.
- Build tools: JDK 17, Android SDK, the `app` module (after the migration tasks).

## Build and unit tests (no phones needed)

```text
./gradlew :app:testDebugUnitTest      # protocol, crypto, state machine, jitter buffer, loopback call
./gradlew :app:assembleDebug
./gradlew :app:installDebug           # per connected device
```

Expected: all unit tests pass, including the in-JVM two-engine loopback call (accept, reject,
timeout, busy, glare, peer loss).

## Scenario A - WiFi without internet (US1, SC-001, SC-009)

1. Join phones A and B to the offline WiFi. Open the app on both and grant microphone/notification permissions.
2. On A, wait for B to appear in the list (within 10 s).
3. Pair A and B (Scenario D), then tap B on A.
4. On B accept. Talk on both sides at the same time.
5. End the call on A, then repeat and end it on B.

Expected: call connects within 10 s of accept, both sides hear each other with little delay and
no disturbing echo, ending on one side ends both, and a network monitor (or `adb shell dumpsys
netstats`) shows no mobile-data traffic.

## Scenario B - Locked phone (US2, SC-005)

1. Lock B and close the app (keep background listening on).
2. Call B from A.

Expected: B's screen turns on within 3 s with ringtone, vibration and an Accept/Reject screen
over the lock screen; accepting connects the call. Turn background listening off in settings and
call again: A shows "unavailable".

## Scenario C - Reject, miss, busy, cancel (US3)

1. A calls B, B taps Reject: A shows "declined".
2. A calls B, nobody answers for 30 s: ringing stops on both, B shows a missed call.
3. B is in a call with C; A calls B: A shows "busy" within 5 s and the B-C call continues.
4. A calls B and cancels before B answers: B's ringing stops and a missed call appears.

## Scenario D - Pairing (US4, SC-007, SC-010)

1. With A and B unpaired, call B from A: B is **not** rung (it shows a pair request or the call is refused).
2. Start pairing from A (list or QR). B accepts. Compare the 6-digit codes; tap "Matches" on both.
3. Place a call again: it now rings normally.
4. Repeat pairing but tap "Does not match" on one side: nothing is stored and calls are still refused.
5. Remove B from A's trusted devices: B's calls to A are refused again.

## Scenario E - Hotspot (US5)

1. On A turn on the phone hotspot (or use the app's guided start). Join B (and C) to it. Turn mobile data off on B and C.
2. B and C open the app: A appears in their lists (gateway probe) and so do the other clients when the hotspot allows it.
3. Call A from B, then call C from B.
4. If the hotspot blocks client-to-client traffic, the B-to-C call must still connect through A, or B sees a message that the network blocks device-to-device traffic.

Expected: same call quality and flow as Scenario A in every combination that is not blocked.

## Scenario F - Connection loss and robustness (US6, edge cases)

1. During a call turn off WiFi on one phone: both sides show "connection lost" and return to the list within 15 s.
2. Mute/unmute and toggle speaker: the peer hears silence while muted; the muted indicator and mic indicator are correct.
3. Both phones call each other at the same moment: exactly one call is established.
4. Send junk to the signaling port (`nc <ip> 45678` with random bytes or a 10 MB frame): the app ignores it and an active call is unaffected.

## Done when

All scenarios above pass on at least two different phone models/vendors, unit tests are green,
and the constitution checks in [plan.md](plan.md) still hold.
