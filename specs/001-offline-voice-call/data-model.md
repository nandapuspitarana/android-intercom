# Data Model: Offline Voice Call

Entities from the spec, with fields, validation and state transitions. Persistent entities live in
Room; the rest are in memory in the service.

## Persistent entities

### LocalIdentity (single row, keystore-backed)
| Field | Type | Notes |
|-------|------|-------|
| deviceId | 16 random bytes (hex) | Created once at install; stable across restarts (spec Device) |
| displayName | string, 1-32 chars | Editable; control characters stripped |
| publicKey | P-256 public key (X.509) | Private key stays in AndroidKeyStore |
| createdAt | epoch ms | |

### PairedDevice (spec: Paired Device)
| Field | Type | Notes |
|-------|------|-------|
| deviceId | hex string, PK | Peer's id |
| displayName | string, 1-32 chars | Last name the peer announced; user may rename locally |
| publicKeyFingerprint | 32 bytes SHA-256 | Identity pin |
| pairingSecret | 32 bytes, encrypted blob | `PS`; encrypted with a keystore AES key |
| trust | enum `TRUSTED`, `REVOKED` | Revoked devices are refused (FR-015) |
| pairedAt | epoch ms | |
| lastSeenAddress | string, nullable | Hint only; never trusted for identity |

Rules: `deviceId` unique; fingerprint must match on every handshake; deleting a device removes the
secret. A peer announcing a known `deviceId` with a different fingerprint is rejected and reported.

### CallHistoryEntry (spec: Call History Entry)
| Field | Type | Notes |
|-------|------|-------|
| id | long PK | |
| peerDeviceId | hex string | |
| peerName | string | Name at the time of the call |
| direction | enum `INCOMING`, `OUTGOING` | |
| outcome | enum `COMPLETED`, `MISSED`, `DECLINED`, `BUSY`, `CANCELLED`, `FAILED` | |
| startedAt | epoch ms | |
| durationMs | long, 0 unless COMPLETED | |

Rules: kept on device only; user can clear it; oldest entries beyond 500 are pruned.

### Settings (DataStore)
`listenInBackground` (bool, default true), `startOnBoot` (bool, default false), `autoReject`
unknown devices (bool, default false), `ringtoneUri`, `language`.

## In-memory entities

### Device (spec: Device) - discovered peer
| Field | Type | Notes |
|-------|------|-------|
| deviceId | hex | From announcement |
| displayName | string | Sanitized, max 32 chars |
| address | IP + signaling port | Most recent verified |
| sources | set of `NSD`, `MULTICAST`, `GATEWAY`, `MANUAL`, `HUB_DIRECTORY` | How it was found |
| role | `PEER`, `HUB` | `HUB` = hotspot host |
| lastSeen | monotonic ms | Expires after 15 s without announcement (SC-002, edge case: stale entries) |
| paired | derived bool | True if a TRUSTED PairedDevice exists |
| reachability | `DIRECT`, `VIA_HUB`, `UNKNOWN` | Result of the direct-path probe |

### NetworkMode (spec: Network Mode)
`LAN` (shared WiFi), `HOTSPOT_HOST` (this phone runs the hotspot, acts as hub), `HOTSPOT_CLIENT`
(joined a hotspot, hub = gateway), `NONE` (no WiFi). Derived from `ConnectivityManager` and the
gateway probe; drives UI guidance.

### Call (spec: Call) - one in-memory CallSession
| Field | Type | Notes |
|-------|------|-------|
| callId | 16 random bytes | Chosen by caller |
| role | `CALLER`, `CALLEE` | |
| peer | Device | |
| state | see below | |
| keys | signaling + media keys, nonces | Derived per call (research R6); zeroed on end |
| path | `DIRECT`, `VIA_HUB` | |
| startedAt / connectedAt | monotonic ms | Duration shown in UI (User Story 6) |
| muted, speakerOn | bool | |
| endReason | enum | Maps to history outcome |

## Call state machine

```text
Idle --place call--------------------> Calling       (caller)
Idle --INVITE received (paired)------> Ringing       (callee)
Idle --INVITE received (unpaired)----> Idle          (reply PAIR_REQUIRED / REJECT per settings)
Calling --RINGING------------------->  Calling(ringing remote)   (UI: "ringing")
Calling --ACCEPT-------------------->  Connecting
Calling --REJECT / BUSY-------------> Ended(DECLINED | BUSY)
Calling --user cancel / 30 s--------> Ending -> Ended(CANCELLED | MISSED on callee)
Ringing --user accept---------------> Connecting     (send ACCEPT)
Ringing --user reject---------------> Ended(DECLINED)     (send REJECT)
Ringing --CANCEL / 30 s timeout-----> Ended(MISSED)
Connecting --first media both ways--> InCall
Connecting --10 s timeout-----------> Ended(FAILED)
InCall --user hangup / HANGUP-------> Ending -> Ended(COMPLETED)
InCall --no heartbeat 10 s----------> Ended(FAILED, "connection lost")
any non-Idle --INVITE from other----> reply BUSY, state unchanged
Ended --history written, keys wiped-> Idle
```

Glare (both sides in `Calling` toward each other): the call from the lower `deviceId` wins; the
other device cancels its own invite and treats the winner's INVITE as an incoming call (R10).

## Relationships

`PairedDevice` 1 - 0..n `CallHistoryEntry` (by `peerDeviceId`, no foreign key so history survives
unpairing). `Device` is joined to `PairedDevice` by `deviceId`. `Call` references one `Device`
and produces one `CallHistoryEntry` when it reaches `Ended`.

## Validation rules (from requirements)

- Names: 1-32 chars, trimmed, control characters removed, rendered as plain text only (FR-017).
- All inbound messages: max frame size 4 KiB for signaling, 1 KiB for UDP media packets; larger are dropped (FR-017).
- A pairing secret is never logged or shown; call history contains no audio (FR-022).
