# Contract: Pairing

Establishes trust between two devices once (FR-014, FR-015, SC-007, SC-010). Runs over the
signaling TCP port using plaintext framed JSON (see signaling.md framing); all steps are bounded
and time out after 120 s.

## Goals

- Defeat a passive listener and an active man-in-the-middle on the local network.
- No server, no typed secret with low entropy.
- Result: a 32-byte pairing secret `PS` and the peer's public-key fingerprint stored on both devices.

## Handshake (initiator I, responder R)

1. **I -> R `PAIR_REQUEST`**: `{ id, name, pub, nonceI }`
   `pub` = ephemeral P-256 public key (X.509, base64), `nonceI` = 16 random bytes.
   R shows "Pair with <name>?" with Accept / Decline. Unsolicited requests are limited to
   1 pending per source and 3 per minute.
2. **R -> I `PAIR_ACCEPT`**: `{ id, name, pub, nonceR }` (R's ephemeral key and 16-byte nonce)
   or `PAIR_DECLINE`.
3. Both compute `Z = ECDH(own ephemeral private, peer ephemeral public)` and the transcript
   `T = SHA-256("twoway pair v1" || idI || idR || pubI || pubR || nonceI || nonceR)`.
4. **Short Authentication String**: `SAS = (uint32_be(HMAC-SHA256(Z, T)[0..4]) mod 1_000_000)`,
   shown as 6 digits with a leading-zero pad. Both screens show the code; the users compare it
   aloud/visually and each taps "Matches" or "Does not match".
5. **I -> R `PAIR_CONFIRM`** / **R -> I `PAIR_CONFIRM`**: `{ ok: true, mac }` where
   `mac = HMAC-SHA256(Z, "confirm" || role || T)`; each side verifies the peer's MAC before saving.
   If either user taps "Does not match", `ok: false` is sent and nothing is stored.
6. Both derive `PS = HKDF-SHA256(Z, salt=T, info="twoway PS v1", 32)` and store it with the peer's
   long-term identity fingerprint (the long-term public keys are exchanged inside the confirm step,
   signed over `T`, so later handshakes can pin them).

An attacker in the middle ends up with different `Z` values on each side, so the two 6-digit codes
differ with probability 1 - 10^-6 per attempt, and the users reject the pairing.

## QR shortcut

The device that shows a QR encodes:

```text
twoway://pair?v=1&id=<deviceId>&ip=<addr>&p=<port>&fp=<first 16 bytes of SHA-256(long-term pub), hex>&n=<name, url-encoded>
```

The scanner connects directly to `ip:p` and runs the same handshake; the code comparison is still
shown (the QR only removes the manual step of finding the device). The `fp` is checked against
the long-term key the peer presents in step 6. The QR contains no secret.

## Removal

Deleting a paired device (or marking it `REVOKED`) erases `PS`; later INVITEs from it are answered
with `PAIR_REQUIRED` or rejected silently according to settings.

## Failure behavior

| Situation | Behavior |
|-----------|----------|
| Peer declines, timeout, or any malformed message | Pairing aborted, nothing stored, user sees a reason |
| Same `deviceId` already paired with a different fingerprint | Refuse and warn the user (possible impersonation) |
| User says "Does not match" | Abort on both sides, no data stored |
| Network lost mid-pairing | Abort; user can retry |
