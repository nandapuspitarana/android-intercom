# Contract: Discovery

How devices announce themselves and find each other (FR-001, FR-007, FR-021, SC-002).

## Ports

| Purpose | Transport | Port |
|---------|-----------|------|
| Signaling / pairing / hub registration | TCP | 45678 (fallback to an ephemeral port advertised in discovery) |
| Multicast announce | UDP multicast `239.255.42.99` | 45679 |
| Media | UDP | negotiated per call (ephemeral), sent in ACCEPT/INVITE |

## 1. mDNS / NSD

- Service type: `_twoway._tcp`, service name: `tw-<first 8 hex of deviceId>`.
- Port: the signaling TCP port.
- TXT records (all values UTF-8, each <= 64 bytes):

| Key | Value |
|-----|-------|
| `v` | protocol major version, `1` |
| `id` | deviceId hex (32 chars) |
| `n` | display name (<= 32 chars) |
| `r` | `p` peer or `h` hub |

## 2. Multicast announce (fallback)

UDP datagram, <= 256 bytes, JSON, sent every 5 s and on network change; a query datagram makes
peers answer immediately.

```json
{ "t": "announce", "v": 1, "id": "<32 hex>", "n": "Kitchen", "p": 45678, "r": "p" }
{ "t": "query", "v": 1 }
```

Receivers MUST ignore datagrams that are larger than 256 bytes, are not valid JSON, have a
different major version, carry their own `id`, or exceed 10 announcements per second per source.
An entry expires 15 s after the last announce/answer.

## 3. Gateway probe (hotspot)

If the WiFi network has a DHCP gateway and no peer answered within 2 s, the client opens TCP to
`gateway:45678` and sends a plaintext `HELLO` (see signaling.md). A reply with `role = hub` marks
the gateway as the hub; the client then sends `HUB_REGISTER` and receives `HUB_DIRECTORY`.

## 4. Manual entry

User types `ip[:port]`; the app opens TCP and does the same `HELLO` exchange. A device found
this way is shown like any other.

## Trust and privacy

Discovery data is unauthenticated and only a hint: names are sanitized, and identity is proven only
by the pairing handshake and call-time encryption (pairing.md, signaling.md). Unpaired devices
see only `deviceId` and name; no profile picture is exchanged in discovery.
