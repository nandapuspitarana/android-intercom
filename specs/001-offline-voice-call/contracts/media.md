# Contract: Media (UDP voice)

Two-way voice during a call (FR-005, FR-008, FR-016, FR-018; SC-003, SC-004, SC-006).

## Codec and timing

- Codec `opus16k20ms`: Opus, 16 kHz, mono, 20 ms frames (320 samples), target ~24 kbps, in-band FEC on.
- Both directions at once; one UDP stream per direction, ports exchanged in INVITE/ACCEPT.
- Muted sender: stops sending voice packets (a 1-byte keep-alive every 1 s keeps NAT/relay paths open and the heartbeat satisfied).

## Packet layout (big-endian)

```text
 0               1               2               3
 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
+---------------+---------------+-------------------------------+
| ver(4)|type(4)|     flags     |        sequence (u16)         |
+---------------+---------------+-------------------------------+
|                       timestamp (u32, samples at 16 kHz)      |
+---------------------------------------------------------------+
|                    session tag (u32) = low 32 bits of callId  |
+---------------------------------------------------------------+
|        payload: AES-256-GCM(Opus frame) + 16-byte tag         |
+---------------------------------------------------------------+
```

- `ver` = 1; `type`: 0 voice, 1 keep-alive; `flags` reserved (0).
- Header (12 bytes) is the GCM associated data; the GCM nonce = 4-byte salt || 8 bytes built from
  `sequence` extended with a 48-bit rollover counter, so the nonce never repeats within a call.
- Max datagram 1024 bytes; larger packets are dropped. Typical size: 12 + ~60 + 16 bytes.
- Packets with a wrong `session tag`, bad tag, or a sequence outside the replay window (64
  packets behind the highest accepted) are dropped silently.

## Receiver behavior

- **Adaptive jitter buffer**: target 40-100 ms based on measured jitter; late packets are dropped;
  missing frames are concealed by Opus PLC (or recovered by FEC from the next frame).
- Playback through a low-latency audio track; speech with echo cancellation, noise suppression and
  gain control enabled on the capture side (research R9).
- If no valid packet arrives for 3 s the UI shows "poor connection"; the call ends only by the
  heartbeat rule in signaling.md (10 s).

## Path selection

1. Direct: each side sends to the peer's `ip:mediaPort`. A 2 s probe (a keep-alive packet each way)
   must succeed or the call falls back.
2. Via hub (hotspot with client isolation): packets are sent to `hub:relayPort` prefixed by the
   4-byte `session tag`; the hub swaps in the other client's address and forwards the datagram
   unchanged (it cannot decrypt). Packets for unknown tags are dropped.
3. If neither path works within the 10 s connect timeout the call fails with a message explaining
   that the network blocks device-to-device traffic (FR-021).

## Network binding

All media sockets are created through the WiFi `Network` (research R3) and never use mobile data
(FR-008, SC-009). A `WifiLock` is held for the duration of the call.

## Indicators and permissions

The microphone indicator (notification and in-call screen) is shown whenever capture is running
(FR-018). Capture starts only after the user accepts (callee) or places the call (caller) and the
microphone permission is granted (FR-020).
