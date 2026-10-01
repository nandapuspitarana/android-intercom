# Feature Specification: Offline Voice Call over WiFi/Hotspot

**Feature Branch**: `001-offline-voice-call`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description: "Pengguna menemukan perangkat lain yang memakai aplikasi di WiFi yang sama atau di hotspot, memanggilnya, dan bicara dua arah tanpa internet atau kuota data. Penerima bisa menerima atau menolak, dan kedua pihak bisa mengakhiri panggilan."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Call a nearby device and talk (Priority: P1)

A user opens the app, sees the other devices running the app on the same WiFi network or the same phone hotspot, taps one to call it, and once the other person answers, both talk to each other at the same time. Either person can end the call. No internet connection or mobile data is needed at any point.

**Why this priority**: This is the core value of the product: a free phone/intercom for places without a data plan or without internet. Everything else supports this flow.

**Independent Test**: Put two phones on the same WiFi that has no internet (or on one phone's hotspot). Call from phone A to phone B, answer on B, speak from both sides, and hang up. This delivers a working intercom even if no other feature exists.

**Acceptance Scenarios**:

1. **Given** two devices running the app on the same network, **When** the caller opens the device list, **Then** the other device appears with its name within a few seconds.
2. **Given** the caller taps a listed device, **When** the callee's device receives the call, **Then** the callee sees an incoming-call screen with the caller's name, and the caller sees a "calling/ringing" state.
3. **Given** the callee accepts, **When** the call connects, **Then** both users hear each other at the same time without having to press a button.
4. **Given** a call in progress, **When** either user taps "End", **Then** the call ends on both devices and both return to the device list.
5. **Given** the network has no internet and mobile data is turned off, **When** a call is placed, **Then** the call works the same as on a network with internet.

---

### User Story 2 - Receive a call when the app is closed or the screen is off (Priority: P1)

A user whose phone is locked, with the screen off or the app not on screen, still gets an audible and visible incoming-call alert and can answer it, like a normal phone call.

**Why this priority**: An intercom is useless if the other side must keep the app open. Without this, calls will be missed.

**Independent Test**: Lock phone B and close the app, then call it from phone A. Phone B shows and sounds an incoming-call alert, and answering it connects the call.

**Acceptance Scenarios**:

1. **Given** the app is listening in the background and the screen is off, **When** a call arrives, **Then** the screen turns on and an incoming-call alert with ringtone and vibration is shown.
2. **Given** the device is locked, **When** the user accepts, **Then** the call connects without needing to unlock the device first.
3. **Given** the user has turned background listening off in settings, **When** a call arrives, **Then** the caller is told the device is unavailable.

---

### User Story 3 - Reject, miss, or find the other side busy (Priority: P2)

The callee can reject a call. If nobody answers, the call stops ringing on its own and is recorded as missed. If the callee is already in another call, the caller is told right away that the person is busy.

**Why this priority**: These outcomes make the calling experience predictable and avoid callers waiting forever, but the feature is still usable with only P1 flows.

**Independent Test**: From phone A, call phone B and tap "Reject" on B; then call again and let it ring out; then call B while it is in a call with phone C.

**Acceptance Scenarios**:

1. **Given** an incoming call, **When** the callee taps "Reject", **Then** the caller immediately sees "declined" and the ringing stops on both devices.
2. **Given** an incoming call that is not answered, **When** about 30 seconds pass, **Then** ringing stops on both devices and the callee sees a missed-call entry.
3. **Given** the callee is already in a call, **When** a second caller calls, **Then** the second caller sees "busy" within a few seconds and the existing call is not disturbed.
4. **Given** the caller cancels while it is still ringing, **When** the callee's device is ringing, **Then** the ringing stops and a missed call is recorded.

---

### User Story 4 - Pair with a device before it can call me (Priority: P2)

The first time two devices want to talk, the users confirm each other with a short code or QR scan. After that, the devices recognise each other and calls are allowed. Calls from devices that were never paired are refused or require explicit approval.

**Why this priority**: Without this, anyone on the same WiFi could ring or listen in on a device. It is required by the project constitution (Security by Default), but the call flow can be demonstrated first with a trusted test pair.

**Independent Test**: Try to call a device that has not been paired and confirm it is refused. Pair the devices, then confirm the call succeeds.

**Acceptance Scenarios**:

1. **Given** two devices that have never been paired, **When** one calls the other, **Then** the callee is not rung automatically; the callee sees a request to pair, or the call is refused.
2. **Given** both users enter or scan the matching code, **When** pairing finishes, **Then** each device lists the other as trusted and future calls ring normally.
3. **Given** a paired device, **When** the user removes it from trusted devices, **Then** calls from that device are refused again.

---

### User Story 5 - Use a phone hotspot when there is no WiFi (Priority: P2)

When there is no WiFi network, one user turns on their phone hotspot and the other joins it. The two phones can then find each other and call, exactly as on normal WiFi, even though the hotspot has no internet.

**Why this priority**: Many real situations have no router. It widens where the product works, but story 1 already covers hotspot as a "same network".

**Independent Test**: Turn on the hotspot on phone A, join with phone B (mobile data off on both), and complete a call in both directions.

**Acceptance Scenarios**:

1. **Given** phone B joined phone A's hotspot, **When** B opens the device list, **Then** A appears even if the network blocks devices from discovering one another directly.
2. **Given** the hotspot blocks direct traffic between client phones, **When** two client phones both want to call each other, **Then** the call still works through the hotspot phone, or the app clearly tells the users it cannot connect and why.
3. **Given** the hotspot phone is itself a participant, **When** it calls a client or receives a call, **Then** the experience is the same as on normal WiFi.

---

### User Story 6 - Know the state of the call at all times (Priority: P3)

During a call the user can see how long it has lasted, mute their microphone, switch between speaker and earpiece, and sees clear messages when the connection is poor or lost.

**Why this priority**: Convenience and trust features that improve the call but are not required to make one.

**Independent Test**: Make a call, mute and unmute, toggle speaker, then switch off WiFi on one phone and observe messages on both.

**Acceptance Scenarios**:

1. **Given** a call in progress, **When** the user mutes, **Then** the other side hears nothing from that user and the user sees a clear muted indicator.
2. **Given** a call in progress, **When** the connection is lost for more than about 10 seconds, **Then** the call ends on both sides with a clear message instead of staying silent forever.
3. **Given** a call in progress, **When** the microphone is in use, **Then** an indicator is visible so the user always knows the mic is live.

---

### Edge Cases

- Both users call each other at the same moment: only one call is established and neither side ends up with two parallel calls.
- The callee's phone leaves WiFi (or WiFi turns off) while ringing or in a call: the caller is told the call ended.
- A device changes IP address or reconnects to the network: it reappears in the list and can be called again without restarting the app.
- Two devices share the same display name: the list still lets users tell them apart.
- A phone is connected to a WiFi network without internet, and Android wants to switch to mobile data: calls still stay on the local network and do not use mobile data.
- Microphone permission was denied: the user is told how to allow it and the call is not silently one-way.
- Device list has many entries or devices disappear: stale entries are removed within a reasonable time.
- Another app is using the microphone or a normal phone call arrives: the app handles it without crashing, and the user is told what happened.
- A device sends malformed or oversized messages: the receiving device ignores them and stays stable.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST let a user see the other devices running the app on the same WiFi network or hotspot, showing a name for each, and update that list as devices appear and disappear.
- **FR-002**: System MUST let a user start a call to a listed device with a single tap.
- **FR-003**: System MUST show the callee an incoming-call screen with the caller's name and options to accept or reject.
- **FR-004**: System MUST show the caller the call progress (calling, ringing, connected, declined, busy, unavailable, ended).
- **FR-005**: System MUST carry two-way voice at the same time once a call is accepted, without push-to-talk.
- **FR-006**: System MUST allow either party to end a call at any time, and MUST end it on both devices.
- **FR-007**: System MUST work with no internet connection and with mobile data turned off, on WiFi without internet and on a phone hotspot.
- **FR-008**: System MUST NOT send call data over mobile data or to any server outside the local network.
- **FR-009**: System MUST be able to receive a call while the app is in the background, closed to the background service, or the screen is off or locked, and alert the user with sound, vibration, and the screen turning on.
- **FR-010**: System MUST let the user turn background call listening on or off.
- **FR-011**: System MUST reject a call with "busy" when the device is already in a call.
- **FR-012**: System MUST stop ringing and record a missed call if an incoming call is not answered within about 30 seconds, or if the caller cancels.
- **FR-013**: System MUST detect a lost connection during a call within about 10 seconds and end the call with a clear message on both sides.
- **FR-014**: System MUST require devices to be paired before they can ring each other, and MUST refuse or ask the user about calls from unpaired devices.
- **FR-015**: Users MUST be able to pair a device using a short code or QR scan, and remove a paired device.
- **FR-016**: System MUST protect call audio and call setup messages so another device on the same network cannot listen in or place calls.
- **FR-017**: System MUST ignore malformed or oversized messages from other devices without crashing or ending an active call.
- **FR-018**: System MUST show a visible indicator whenever the microphone is in use.
- **FR-019**: System MUST let the user mute the microphone and choose speaker or earpiece during a call.
- **FR-020**: System MUST ask for the permissions it needs (microphone, notifications, nearby devices) at a clear moment and explain what happens if they are denied.
- **FR-021**: System MUST provide a way to start or join a phone hotspot with guidance, and MUST still allow calls when the hotspot prevents client phones from reaching each other directly (by relaying through the hotspot phone or by telling the user it cannot connect).
- **FR-022**: System MUST keep a local history of recent calls (incoming, outgoing, missed) on the device only.
- **FR-023**: System MUST be usable in Indonesian and English.

### Key Entities

- **Device**: A phone running the app on the local network. Has a display name, optional photo, an identifier that stays the same across restarts, a current network address, and an online/offline state.
- **Paired Device (trusted contact)**: A device the user has confirmed through pairing. Has a trust state and the shared secret used to protect calls, kept only on the device.
- **Call**: One attempt to talk between two devices. Has a caller, a callee, a state (calling, ringing, connected, declined, busy, missed, ended), start time, and duration.
- **Call History Entry**: A record of a past call: who, direction (incoming/outgoing), outcome, time, duration. Stored locally.
- **Network Mode**: How the devices are connected: shared WiFi/LAN, or hotspot (one host phone, other phones as clients).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a network with no internet and with mobile data off, a user can place a call and be talking within 10 seconds of the callee accepting, in at least 95% of attempts.
- **SC-002**: A newly started device appears in another device's list within 10 seconds in at least 95% of attempts on both a normal WiFi and a hotspot.
- **SC-003**: Spoken words are heard by the other person with a delay of under 0.5 seconds (target 0.2 seconds) in normal conditions on a healthy local network.
- **SC-004**: Both people can talk and be heard at the same time without echo that disturbs the conversation.
- **SC-005**: An incoming call on a locked, screen-off phone alerts the user within 3 seconds in at least 95% of attempts.
- **SC-006**: A call stays connected for at least 30 minutes without dropping on a healthy network.
- **SC-007**: A first-time user can pair two devices and place a first call in under 3 minutes without instructions.
- **SC-008**: When the connection is lost, 100% of calls end on both devices within 15 seconds and show a clear message.
- **SC-009**: Zero bytes of call data travel over mobile data or to hosts outside the local network during test calls.
- **SC-010**: A device that was never paired cannot make another device ring or hear its audio in any test.

## Assumptions

- Users have Android phones (Android 7.0 or newer) with a working microphone and speaker, and both phones run the app.
- Both devices are on the same local network (WiFi, LAN, or hotspot); crossing networks or reaching devices over the internet is out of scope.
- Voice calls between two devices are the scope of this feature; video, group calls and broadcast/all-call are out of scope and will be separate features.
- Push-to-talk and auto-answer intercom modes are out of scope for this feature (calls are accepted manually).
- Call history is kept locally and is not synced or backed up anywhere.
- Pairing is done once per pair of devices in person, with both users holding their phones.
- Phones may have battery optimizations that stop background apps; the app will guide users to allow background use.
- Existing video streaming and profile-exchange code in the repository is not part of this feature and may be replaced later.
