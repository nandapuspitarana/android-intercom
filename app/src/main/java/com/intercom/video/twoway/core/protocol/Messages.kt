package com.intercom.video.twoway.core.protocol

const val PROTOCOL_VERSION = 1L

/** One signaling/pairing/hub message. Field names follow contracts/signaling.md and contracts/pairing.md. */
sealed interface Message {
    val type: String
}

// --- plaintext handshake / identification -------------------------------------------------------
data class Hello(val id: String, val name: String, val role: String) : Message {
    override val type get() = "HELLO"
}

// --- pairing ------------------------------------------------------------------------------------
data class PairRequest(val id: String, val name: String, val pub: String, val nonce: String) : Message {
    override val type get() = "PAIR_REQUEST"
}

data class PairAccept(val id: String, val name: String, val pub: String, val nonce: String) : Message {
    override val type get() = "PAIR_ACCEPT"
}

data class PairDecline(val reason: String) : Message {
    override val type get() = "PAIR_DECLINE"
}

data class PairConfirm(val ok: Boolean, val mac: String, val identityPub: String, val sig: String) : Message {
    override val type get() = "PAIR_CONFIRM"
}

// --- call control -------------------------------------------------------------------------------
data class Invite(val callId: String, val from: String, val fromName: String, val nonce: String, val mediaPort: Int, val ts: Long, val mac: String) : Message {
    override val type get() = "INVITE"
}

data class PairRequired(val callId: String) : Message {
    override val type get() = "PAIR_REQUIRED"
}

data class Ringing(val callId: String, val nonce: String, val mac: String) : Message {
    override val type get() = "RINGING"
}

data class Accept(val callId: String, val mediaPort: Int, val codec: String) : Message {
    override val type get() = "ACCEPT"
}

data class Reject(val callId: String, val reason: String) : Message {
    override val type get() = "REJECT"
}

data class Busy(val callId: String) : Message {
    override val type get() = "BUSY"
}

data class Cancel(val callId: String) : Message {
    override val type get() = "CANCEL"
}

data class Hangup(val callId: String, val reason: String) : Message {
    override val type get() = "HANGUP"
}

data class Ping(val callId: String, val ts: Long) : Message {
    override val type get() = "PING"
}

data class Pong(val callId: String, val ts: Long) : Message {
    override val type get() = "PONG"
}

data class Mute(val callId: String, val muted: Boolean) : Message {
    override val type get() = "MUTE"
}

// --- hotspot hub --------------------------------------------------------------------------------
data class HubPeer(val id: String, val name: String, val ip: String, val port: Int)

data class HubRegister(val id: String, val name: String, val port: Int) : Message {
    override val type get() = "HUB_REGISTER"
}

data class HubDirectory(val peers: List<HubPeer>) : Message {
    override val type get() = "HUB_DIRECTORY"
}

data class HubRelayOpen(val sessionId: String, val toId: String) : Message {
    override val type get() = "HUB_RELAY_OPEN"
}

data class HubRelayReady(val sessionId: String, val mediaPort: Int) : Message {
    override val type get() = "HUB_RELAY_READY"
}

data class HubRelayClose(val sessionId: String) : Message {
    override val type get() = "HUB_RELAY_CLOSE"
}

/**
 * A signaling frame carried through the hotspot hub: the hub forwards [frame] unchanged to the other end of relay
 * session [sessionId] and cannot read it (it is already encrypted end to end once a call has keys).
 */
class RelayFrame(val sessionId: String, val frame: ByteArray) : Message {
    override val type get() = "RELAY"

    override fun equals(other: Any?) = other is RelayFrame && sessionId == other.sessionId && frame.contentEquals(other.frame)

    override fun hashCode() = sessionId.hashCode() * 31 + frame.contentHashCode()
}
