package com.intercom.video.twoway.core.protocol

import com.intercom.video.twoway.core.util.B64

/**
 * Converts [Message] to and from strict JSON. Unknown types decode to null and are ignored by callers;
 * malformed JSON or missing/ill-typed required fields throw [JsonException].
 */
object JsonMessageCodec {
    const val MAX_PEERS = 32

    fun encode(m: Message): String = Json.write(toMap(m))

    fun encodeBytes(m: Message): ByteArray = encode(m).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Message? = decode(String(bytes, Charsets.UTF_8))

    fun decode(text: String): Message? {
        // RELAY carries a whole (base64) signaling frame, so it may hold one long string; every other message is strict.
        val o = Json.parseObject(text, Json.MAX_STRING_HARD)
        val type = o["t"] as? String ?: throw JsonException("missing type")
        if (type != "RELAY") Json.requireStringsAtMost(o, Json.MAX_STRING)
        return when (type) {
            "HELLO" -> Hello(o.str("id"), o.str("n"), o.str("r"))
            "PAIR_REQUEST" -> PairRequest(o.str("id"), o.str("n"), o.str("pub"), o.str("nonce"))
            "PAIR_ACCEPT" -> PairAccept(o.str("id"), o.str("n"), o.str("pub"), o.str("nonce"))
            "PAIR_DECLINE" -> PairDecline(o.str("reason"))
            "PAIR_CONFIRM" -> PairConfirm(o.bool("ok"), o.str("mac"), o.str("ipub"), o.str("sig"))
            "INVITE" -> Invite(
                o.str("callId"),
                o.str("from"),
                o.str("fromName"),
                o.str("nonce"),
                o.int("mediaPort"),
                o.long("ts"),
                o.str("mac"),
            )
            "PAIR_REQUIRED" -> PairRequired(o.str("callId"))
            "RINGING" -> Ringing(o.str("callId"), o.str("nonce"), o.str("mac"))
            "ACCEPT" -> Accept(o.str("callId"), o.int("mediaPort"), o.str("codec"))
            "REJECT" -> Reject(o.str("callId"), o.str("reason"))
            "BUSY" -> Busy(o.str("callId"))
            "CANCEL" -> Cancel(o.str("callId"))
            "HANGUP" -> Hangup(o.str("callId"), o.str("reason"))
            "PING" -> Ping(o.str("callId"), o.long("ts"))
            "PONG" -> Pong(o.str("callId"), o.long("ts"))
            "MUTE" -> Mute(o.str("callId"), o.bool("muted"))
            "HUB_REGISTER" -> HubRegister(o.str("id"), o.str("n"), o.int("p"))
            "HUB_DIRECTORY" -> HubDirectory(peers(o["peers"]))
            "HUB_RELAY_OPEN" -> HubRelayOpen(o.str("sessionId"), o.str("toId"))
            "HUB_RELAY_READY" -> HubRelayReady(o.str("sessionId"), o.int("mediaPort"))
            "HUB_RELAY_CLOSE" -> HubRelayClose(o.str("sessionId"))
            "RELAY" -> RelayFrame(o.str("sessionId"), B64.decode(o.str("frame")) ?: throw JsonException("bad frame encoding"))
            else -> null
        }
    }

    private fun peers(v: Any?): List<HubPeer> {
        val list = v as? List<*> ?: throw JsonException("peers must be a list")
        if (list.size > MAX_PEERS) throw JsonException("too many peers")
        return list.map { e ->
            val m = e as? Map<*, *> ?: throw JsonException("peer must be an object")

            @Suppress("UNCHECKED_CAST")
            val o = m as Map<String, Any?>
            HubPeer(o.str("id"), o.str("n"), o.str("ip"), o.int("p"))
        }
    }

    private fun toMap(m: Message): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["t"] = m.type
        out["v"] = PROTOCOL_VERSION
        when (m) {
            is Hello -> {
                out["id"] = m.id
                out["n"] = m.name
                out["r"] = m.role
            }
            is PairRequest -> {
                out["id"] = m.id
                out["n"] = m.name
                out["pub"] = m.pub
                out["nonce"] = m.nonce
            }
            is PairAccept -> {
                out["id"] = m.id
                out["n"] = m.name
                out["pub"] = m.pub
                out["nonce"] = m.nonce
            }
            is PairDecline -> out["reason"] = m.reason
            is PairConfirm -> {
                out["ok"] = m.ok
                out["mac"] = m.mac
                out["ipub"] = m.identityPub
                out["sig"] = m.sig
            }
            is Invite -> {
                out["callId"] = m.callId
                out["from"] = m.from
                out["fromName"] = m.fromName
                out["nonce"] = m.nonce
                out["mediaPort"] = m.mediaPort.toLong()
                out["ts"] = m.ts
                out["mac"] = m.mac
            }
            is PairRequired -> out["callId"] = m.callId
            is Ringing -> {
                out["callId"] = m.callId
                out["nonce"] = m.nonce
                out["mac"] = m.mac
            }
            is Accept -> {
                out["callId"] = m.callId
                out["mediaPort"] = m.mediaPort.toLong()
                out["codec"] = m.codec
            }
            is Reject -> {
                out["callId"] = m.callId
                out["reason"] = m.reason
            }
            is Busy -> out["callId"] = m.callId
            is Cancel -> out["callId"] = m.callId
            is Hangup -> {
                out["callId"] = m.callId
                out["reason"] = m.reason
            }
            is Ping -> {
                out["callId"] = m.callId
                out["ts"] = m.ts
            }
            is Pong -> {
                out["callId"] = m.callId
                out["ts"] = m.ts
            }
            is Mute -> {
                out["callId"] = m.callId
                out["muted"] = m.muted
            }
            is HubRegister -> {
                out["id"] = m.id
                out["n"] = m.name
                out["p"] = m.port.toLong()
            }
            is HubDirectory -> out["peers"] = m.peers.map {
                mapOf("id" to it.id, "n" to it.name, "ip" to it.ip, "p" to it.port.toLong())
            }
            is HubRelayOpen -> {
                out["sessionId"] = m.sessionId
                out["toId"] = m.toId
            }
            is HubRelayReady -> {
                out["sessionId"] = m.sessionId
                out["mediaPort"] = m.mediaPort.toLong()
            }
            is HubRelayClose -> out["sessionId"] = m.sessionId
            is RelayFrame -> {
                out["sessionId"] = m.sessionId
                out["frame"] = B64.encode(m.frame)
            }
        }
        return out
    }

    private fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: throw JsonException("field '$k' must be a string")

    private fun Map<String, Any?>.long(k: String): Long = this[k] as? Long ?: throw JsonException("field '$k' must be an integer")

    private fun Map<String, Any?>.int(k: String): Int {
        val v = long(k)
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw JsonException("field '$k' out of range")
        return v.toInt()
    }

    private fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: throw JsonException("field '$k' must be a boolean")
}
