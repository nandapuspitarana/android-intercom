<!--
Sync Impact Report
- Version change: 1.1.0 -> 1.1.1 (PATCH: Technical Constraints wording; signaling is secured by authenticated encryption
  keyed from the pairing secret instead of TLS, see specs/001-offline-voice-call/plan.md Complexity Tracking)
- Earlier: 1.0.0 -> 1.1.0 (MINOR: Principle VI materially expanded, mandatory functional tests per story)
- Modified principles: VI "Testability" -> "Testability and Functional Tests (NON-NEGOTIABLE)"
- Earlier: (template) -> 1.0.0 initial ratification; all placeholders replaced
- Added sections: Core Principles I-VII, Platform & Technical Constraints, Development Workflow & Quality Gates, Governance
- Removed sections: none
- Deferred TODOs: none
-->
# Two Way Constitution

## Core Principles

### I. Offline-First, No Internet or Mobile Data (NON-NEGOTIABLE)
The app MUST place and receive calls with no internet connection and no mobile data. It MUST work
on any WiFi/LAN, including WiFi that has no internet, and on a phone hotspot. There MUST be no
cloud server, account, or third-party relay. A hotspot host MUST be reachable by clients at a
known gateway address, and when client isolation blocks peer-to-peer traffic, the host MUST relay
signaling and media, or the UI MUST tell the user. Sockets MUST be bound to the WiFi network so
Android does not route them over mobile data.
Rationale: the product exists to give free calling where there is no data plan or no router.

### II. Security by Default
No data from the network MAY be deserialized as a Java object; messages MUST be explicit,
size-limited, validated formats (JSON or protobuf). Devices MUST be paired (PIN or QR) before they
can call, and calls from unpaired devices MUST be rejected or require explicit user approval.
Signaling and media MUST be encrypted with keys derived from pairing. The UI MUST show a visible
indicator whenever the microphone or camera is active. Servers MUST bound concurrent connections
and request rate.
Rationale: a LAN is a shared, untrusted network; an open intercom is an eavesdropping device.

### III. Voice First, Low Latency
Audio MUST be full-duplex by default, use a speech codec (Opus), and use UDP-based transport with a
jitter buffer. Mouth-to-ear latency MUST target under 200 ms on a healthy LAN. Echo cancellation,
noise suppression, and gain control MUST be enabled (voice-communication audio mode). Video is
optional, MUST be a separate stream from audio, and MUST NOT degrade audio when the network is poor.
Push-to-talk is an optional intercom mode, not the default.

### IV. Modern Android Platform
The project MUST use AndroidX, a minSdk of 24, and a current targetSdk. Runtime permissions MUST
be requested in context. Listening for calls MUST run in a foreground service with correct
foreground service types. Incoming calls MUST use a full-screen-intent notification; background
`startActivity()` calls are prohibited. Deprecated APIs (`android.hardware.Camera`,
`ActionBarActivity`, device-ID telephony calls) MUST NOT be used in new code.

### V. Explicit Call State and Reliability
Call state (Idle, Calling, Ringing, InCall, Ended) MUST live in one state machine owned by the
service, not in UI classes. Signaling MUST define invite, ringing, accept, reject, busy, cancel,
and hangup. Heartbeats and timeouts MUST detect lost peers and WiFi loss, and the app MUST end or
recover the call cleanly. A WifiLock MUST be held during calls and while listening, if enabled.

### VI. Testability and Functional Tests (NON-NEGOTIABLE)
The signaling protocol parser, call state machine, and jitter buffer MUST have unit tests.
Every user story MUST also have automated **functional tests** that drive the real UI and
service end to end against an in-process fake peer device (with controllable accept, reject,
busy, and injected network faults), written before the implementation. A story MUST NOT be
marked done until its functional tests pass. Every user-facing call flow (call, answer, reject,
busy, hangup, peer loss) MUST additionally have a documented two-device manual test, including a
no-internet WiFi and a hotspot scenario. Bug fixes MUST include a regression test where one is
feasible.

### VII. Simplicity and Clear Boundaries
No global mutable static state for UI or call data. Classes MUST have one responsibility
(discovery, signaling, audio, video, UI). Logging MUST use a toggleable logger, not
`System.out` or `printStackTrace`. Complexity MUST be justified in the plan.

## Platform & Technical Constraints

- Transport: mDNS/NSD discovery (with UDP multicast/broadcast and manual IP/QR fallback), TCP
  signaling protected by authenticated encryption (TLS, or an equivalent scheme keyed from the
  pairing secret), RTP-style UDP for audio and video.
- Network roles: peer on shared WiFi/LAN, or host/client on a phone hotspot.
- Data: profile and contacts stored locally only; nothing leaves the local network.
- Languages: Indonesian and English UI.

## Development Workflow & Quality Gates

- Work follows Spec Kit: specify, plan, tasks, implement, then converge. Each feature has its own
  spec before code is written.
- A change MUST pass build, lint, and unit tests before merge.
- Each plan MUST include a Constitution Check against Principles I-VII; violations MUST be
  justified in the plan's complexity section.
- Security-relevant changes (network parsing, pairing, crypto) MUST get a second review.

## Governance

This constitution supersedes other practices in this repository. Amendments require a written
change to this file, a version bump, and an updated Sync Impact Report. Versioning follows
semantic rules: MAJOR for removing or redefining a principle, MINOR for adding a principle or
materially expanding guidance, PATCH for clarifications. All specs, plans, and reviews MUST verify
compliance; non-compliance MUST be documented and justified. `TODO.md` is the working roadmap and
MUST stay consistent with this constitution.

**Version**: 1.1.1 | **Ratified**: 2026-10-01 | **Last Amended**: 2026-10-01
