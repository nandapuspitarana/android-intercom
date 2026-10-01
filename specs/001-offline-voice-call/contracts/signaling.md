# Contract: Signaling

Call setup and control between two devices over TCP (FR-002 to FR-006, FR-011 to FR-014, FR-016,
FR-017). Protocol major version: 1.

## Framing (all TCP traffic)

```text
+----------------+---------------------------+
| length: u32 BE | body (length bytes)       |
+----------------+---------------------------+
```

- `length` MUST be 1..4096; anything else closes the connection.
- At most 8 concurrent inbound connections; at most 5 new connections per source per 10 s; a
  connection that sends nothing for 10 s before the first complete frame is closed.
- Body is UTF-8 JSON, one object per frame, parsed with a strict parser (no object
  deserialization, unknown fields ignored, depth <= 4, strings <= 256 chars).

## Plaintext messages (before a call key exists)

Only these types may be sent unencrypted:

| Type | Fields | Purpose |
|------|--------|---------|
| `HELLO` | `v, id, n, r` | Probe/identify (gateway probe, manual IP). Reply: `HELLO` |
| `PAIR_*` | see pairing.md | Pairing steps |
| `INVITE` | `v, callId, from, fromName, nonce, mediaPort, ts, mac` | Start a call; `mac` = HMAC-SHA256(PS, canonical fields) proves the caller knows `PS` |
| `PAIR_REQUIRED` | `v, callId` | Reply to an INVITE from an unpaired/revoked device |
| `HUB_*` | see Hub below | Hotspot directory/relay |

An INVITE with a bad or missing `mac`, an unknown `from`, or `ts` older than 30 s is answered
with `PAIR_REQUIRED` (or ignored if `autoReject` is on). No ring is shown for it (SC-010).

## Encrypted messages (after INVITE)

Both sides derive per-call keys (research R6):
`K = HKDF-SHA256(PS, salt=nonceCaller||nonceCallee, info="twoway call v1", 2*32 + 2*4)` giving a
signaling key and a media key per direction, plus 4-byte nonce salts. The callee's nonce is
returned in `RINGING`/`ACCEPT` (in the clear, authenticated by `mac`).

Envelope for every later signaling frame body:

```json
{ "c": 1, "n": "<base64 counter>", "d": "<base64 AES-256-GCM(ciphertext+tag)>" }
```

The 12-byte GCM nonce = 4-byte salt || 8-byte strictly increasing counter (the counter `n` is
sent; receivers reject counters that are not greater than the last accepted one). The decrypted
JSON is the message below. Associated data = `callId || direction`.

| Type | Direction | Fields | Meaning |
|------|-----------|--------|---------|
| `RINGING` | callee -> caller | `callId, nonce` | Callee device is alerting the user |
| `ACCEPT` | callee -> caller | `callId, mediaPort, codec` | User accepted; media may start |
| `REJECT` | callee -> caller | `callId, reason` | `declined` or `unavailable` |
| `BUSY` | callee -> caller | `callId` | Already in a call |
| `CANCEL` | caller -> callee | `callId` | Caller gave up while ringing |
| `HANGUP` | either | `callId, reason` | End an established call |
| `PING` / `PONG` | either | `callId, ts` | Heartbeat, every 2 s while in a call |
| `MUTE` | either | `callId, muted` | Informational, shows indicator to peer |

`codec` = `opus16k20ms` in v1. Unknown message types are ignored; unknown protocol major versions
are answered with `REJECT(reason="version")`.

## Sequence (happy path)

```text
Caller                                              Callee
  |--INVITE(callId, nonceA, mediaPortA, mac)------->|  verify mac, paired? not busy?
  |<------RINGING(callId, nonceB) [encrypted]-------|  show incoming call UI
  |                        (user taps Accept)        |
  |<------ACCEPT(callId, mediaPortB) [encrypted]----|
  |==== UDP media both directions (media.md) =======|
  |--PING / PONG every 2 s-------------------------->|
  |--HANGUP-------------------------------------------|  either side may send; both close
```

## Timers

| Timer | Value | On expiry |
|-------|-------|-----------|
| INVITE retransmit | resend once after 1.5 s if no RINGING/BUSY | then fail "unavailable" at 5 s |
| Ring timeout | 30 s | callee: MISSED; caller: send CANCEL, "no answer" |
| Connect timeout | 10 s after ACCEPT | end FAILED |
| Heartbeat | PING every 2 s, dead after 10 s without any valid message | end FAILED, "connection lost" |

## State handling rules

- While not `Idle`, any new INVITE gets `BUSY` and does not affect the current call.
- Glare: if an INVITE arrives while this device is `Calling` the same peer, the lower `deviceId`
  keeps its call; the other cancels and answers as callee (data-model.md).
- Messages for an unknown or ended `callId` are ignored (no response, to avoid reflection).
- On `Ended`, session keys and counters are zeroed.

## Hub (hotspot) messages

Plaintext, only between a client and the hub phone at the gateway; they carry no call secrets.

| Type | Direction | Fields |
|------|-----------|--------|
| `HUB_REGISTER` | client -> hub | `id, n, p` (client's signaling port) |
| `HUB_DIRECTORY` | hub -> client | `peers: [{id, n, ip, p}]` (<= 32 entries), pushed on change |
| `HUB_RELAY_OPEN` | client -> hub | `sessionId, toId` ask the hub to relay a call to `toId` |
| `HUB_RELAY_READY` | hub -> both | `sessionId, mediaPort` |
| `HUB_RELAY_CLOSE` | either | `sessionId` |

When relaying, signaling frames are wrapped `{ "relay": sessionId, "frame": "<base64 frame>" }`; the
hub forwards the inner frame unchanged and cannot decrypt it. The hub applies the same size and
rate limits and closes idle relays after 15 s without traffic.

## Error handling summary

| Condition | Behavior |
|-----------|----------|
| Oversize / malformed frame | Drop the frame, close that connection, keep any active call |
| Bad GCM tag or replayed counter | Drop silently; after 5 in 10 s close the connection |
| Peer unreachable at INVITE | Caller UI "unavailable" within 5 s |
| TCP closes during a call | Heartbeat rules apply; media may continue until the 10 s timeout |
