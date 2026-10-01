# Security Review Checklist: Offline Voice Call

**Purpose**: Review of the security-relevant parts (constitution Principle II and the Development Workflow rule that
network parsing, pairing and crypto changes get a second review).
**Created**: 2026-10-01
**Feature**: [spec.md](../spec.md)

Legend: `[x]` = verified by an automated test named in the evidence column or by reading the code; `[ ]` = open.
**Second reviewer sign-off: NOT YET DONE** (the author cannot sign off their own review; see the last section).

## Pairing (contracts/pairing.md)

- [x] Ephemeral ECDH P-256 per handshake; peer public key is validated as a curve point before use. Evidence: `PairingSessionTest.malformedMessagesAreRejectedWithoutCrashing`
- [x] A man in the middle yields different 6-digit codes on each side. Evidence: `PairingSessionTest.aManInTheMiddleProducesDifferentCodesOnEachSide`
- [x] Nothing is stored until BOTH users pressed "Matches"; "Does not match" aborts and stores nothing. Evidence: `PairingLoopbackTest.doesNotMatchAbortsBothSidesAndStoresNothing`, `PairingFunctionalTest.doesNotMatchAbortsAndStoresNothing`
- [x] Confirm MAC includes the sender's role (no reflection) and the identity signature covers the session transcript. Evidence: `PairingSessionTest.aConfirmCannotBeReflectedBackToItsSender`, `signatureFromAnotherKeyOrOtherSessionIsRejected`
- [x] A known device presenting a different identity key is refused and the old entry kept. Evidence: `PairingLoopbackTest.aKnownDeviceWithADifferentIdentityKeyIsRefused`, `PairingFunctionalTest.aKnownDeviceThatNowPresentsADifferentIdentityIsRefusedWithAWarning`
- [x] QR code carries no secret; a QR for the wrong phone is refused. Evidence: `PairingLinkTest.containsNoSecret`, `PairingLoopbackTest.scannedQrFingerprintMustMatchThePeerIdentity`
- [x] One handshake at a time, 3 requests per minute per source, 120 s overall timeout. Evidence: `PairingLoopbackTest.aSecondRequestWhileBusyIsDeclined`, `rateLimiterAllowsAtMostThreeRequestsPerMinutePerSource`, `handshakeTimesOutAfter120Seconds`
- [x] Pairing secret is encrypted with a non-exportable AndroidKeyStore AES-GCM key before it is stored; never logged. Evidence: code review of `SecretStore`, `PairedDeviceRepository`; `PairingFunctionalTest` runs the real keystore cipher on an emulator.
- [x] Removing a device revokes trust and erases the secret. Evidence: `PairingFunctionalTest.removingAPairedDeviceMakesItsCallsRefusedAgain`
- [ ] Known limit (accepted): no forward secrecy for the long-term pairing secret PS; mitigated by a fresh key per call. Rotation is future work.

## Signaling and call keys (contracts/signaling.md)

- [x] No Java object deserialization from the network anywhere: strict JSON parser (depth <= 4, strings <= 256 chars except the RELAY frame field), 4096-byte frame cap. Evidence: `JsonMessageCodecTest`, `FrameCodecTest`, `FuzzTest`
- [x] INVITE is authenticated with an HMAC over PS and rejected if older than 30 s; RINGING binds the callee nonce. Evidence: `InviteAuthTest`, `UnpairedInviteTest.aReplayedOldInviteIsRefused`
- [x] An unpaired, revoked or wrong-secret sender never makes the phone ring (SC-010). Evidence: `UnpairedInviteTest`, `PairingFunctionalTest.anUnpairedPeerCanNeverMakeThePhoneRing`, `SecurityFunctionalTest.aStrangerWhoKnowsNoSecretCannotRingThePhoneDuringOrAfterTheCall`
- [x] With "ignore unpaired phones" the caller learns nothing (no PAIR_REQUIRED hint). Evidence: `UnpairedInviteTest.withAutoRejectUnknownTheSenderGetsNoReplyAtAll`
- [x] Per-call keys derived with HKDF from PS and both nonces; separate keys per direction and purpose; keys wiped at call end. Evidence: `HkdfTest` (RFC 5869 vectors), `CallKeysTest`
- [x] AES-256-GCM with strictly increasing counters; tampering, wrong AAD, replayed counters are rejected. Evidence: `AesGcmEnvelopeTest`, `ReplayWindowTest`, `SecurityFunctionalTest.replayingTheCallsOwnRecordedSignalingToBothPhonesIsRefused`
- [x] Plaintext BUSY/REJECT/PAIR_REQUIRED are accepted only before RINGING (no keys yet) and cannot end an established call. Evidence: code review of `CallEngine.preRinging`
- [ ] Known limit (accepted, documented in contracts): plaintext pre-RINGING replies can be forged to deny a call attempt (a denial of service, not a confidentiality problem).
- [x] Connection limits (8 concurrent, 5 new per source per 10 s, 10 s to the first frame); oversize or malformed frames close the connection but never touch an active call. Evidence: `ConnectionLimiterTest`, `FuzzTest.aFloodOfConnectionsIsLimitedAndTheCallSurvives`, `junkOnTheSignalingPortDoesNotDisturbAnActiveCall`, `SecurityFunctionalTest`

## Media (contracts/media.md)

- [x] Every voice packet is AES-GCM protected with the header as associated data; wrong session tag, bad tag, replay and out-of-window packets are dropped. Evidence: `MediaPacketTest`
- [x] Junk datagrams on the media port do not disturb a call. Evidence: `FuzzTest.junkDatagramsOnTheMediaPortDoNotDisturbAnActiveCall`, `SecurityFunctionalTest.junkDatagramsOnTheMediaPortLeaveTheCallAlone`
- [x] The microphone indicator is shown whenever capture is live (screen + ongoing-call notification with the microphone foreground type). Evidence: `InCallControlsFunctionalTest.theDurationCounterAdvancesAndTheMicrophoneIndicatorIsVisible`, `theOngoingCallNotificationKeepsTheMicrophoneVisibleInTheShade`

## Hotspot hub and relay

- [x] The hub forwards signaling and voice unchanged and never has call keys. Evidence: `HubRelayTest.relayedFramesArriveUnchangedAtTheOtherPartyOnly`, `voicePacketsAreForwardedUnchangedBetweenTheTwoPhones`
- [x] Only registered phones may open relays; a non-party cannot inject into or close a session; a third UDP address cannot join. Evidence: `HubRelayTest.aPhoneThatIsNotPartOfTheSessionCannotInjectOrCloseIt`, `aThirdAddressCannotJoinASession`, `aRelayToAnUnknownPhoneOrFromAnUnregisteredOneIsRefused`
- [x] Limits: 32 registered phones, 8 relayed calls, relay frames <= 2500 bytes, 15 s idle close. Evidence: `HubDirectoryTest`, `HubRelayTest`
- [x] The hub address comes from the TCP connection, not from what a phone claims. Evidence: `HubDirectoryTest.everyRegisteredPhoneReceivesTheOthersAndUpdatesWhenOneLeaves`
- [ ] Known limit (accepted): a malicious hotspot owner can see who calls whom and when (metadata), and can drop calls. Content stays encrypted.

## Storage, logging, platform

- [x] Backup and device transfer exclude the database, shared preferences and files (`allowBackup=false`, data extraction rules). Evidence: `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `backup_rules.xml`
- [x] The debug-only fallback secret and test fakes are absent from the release APK. Evidence: release APK scanned for `twoway-debug-pairing-secret`, `FakeAudioFactory` (0 matches) at the time of the check; repeat before every release.
- [x] No secret, pairing secret or key material is logged; logging goes through a logger that is silent below ERROR in release builds. Evidence: code review of `AndroidLogger`
- [x] Release build is minified/shrunk and starts on an emulator without crashing. Evidence: signed release APK launched on API 36 emulator.
- [ ] Android 14+ full-screen-intent permission and OEM background restrictions: need device verification (quickstart Scenario B).

## Reviewers

| Role | Name | Date | Result |
|------|------|------|--------|
| Author | (Claude, automated review) | 2026-10-01 | Checklist completed as above |
| Second reviewer (required by the constitution) | _pending_ | | _pending_ |
