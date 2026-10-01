package com.intercom.video.twoway.net.network

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.NoopLogger
import com.intercom.video.twoway.core.protocol.HubDirectory
import com.intercom.video.twoway.core.protocol.HubPeer
import com.intercom.video.twoway.core.protocol.HubRegister
import com.intercom.video.twoway.core.protocol.HubRelayClose
import com.intercom.video.twoway.core.protocol.HubRelayOpen
import com.intercom.video.twoway.core.protocol.HubRelayReady
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.Message
import com.intercom.video.twoway.core.protocol.RelayFrame
import com.intercom.video.twoway.core.util.Hex
import com.intercom.video.twoway.core.util.NameSanitizer
import com.intercom.video.twoway.net.transport.ConnectionLimiter
import com.intercom.video.twoway.net.transport.SignalingChannel
import com.intercom.video.twoway.net.transport.SocketProvider
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Receives HUB_* and RELAY frames that arrive on the signaling port (the call engine hands them over). */
interface HubRouter {
    fun onHubMessage(channel: SignalingChannel, msg: Message)
    fun onHubChannelClosed(channel: SignalingChannel)
}

/**
 * The hotspot phone's role (contracts/signaling.md "Hub"): keeps a directory of the phones that registered, so clients
 * that cannot see each other directly (client isolation) still find each other, and relays calls between them. The
 * hub forwards signaling frames and UDP voice packets unchanged: it never has the call keys, so it cannot read them.
 *
 * Limits: at most [MAX_CLIENTS] registered phones, [MAX_RELAYS] concurrent relayed calls, relay frames up to
 * [MAX_RELAY_FRAME] bytes, and relays with no traffic for [IDLE_MS] are closed.
 */
class HubRegistry(
    private val sockets: SocketProvider,
    private val clock: Clock,
    private val logger: Logger = NoopLogger,
    private val limiter: ConnectionLimiter? = null,
) : HubRouter {
    private class Client(val id: String, val name: String, val ip: String, val port: Int, val channel: SignalingChannel)

    private class Relay(val sessionId: String, val tag: Int, val a: SignalingChannel, val b: SignalingChannel) {
        @Volatile
        var lastFrameMs = 0L
    }

    private val udp = UdpRelay(sockets, clock, logger)
    private val clients = LinkedHashMap<String, Client>()
    private val relays = HashMap<String, Relay>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hub-registry").apply { isDaemon = true } }

    @Volatile
    var enabled = false
        private set

    /** UDP port phones send relayed voice to, or -1 while the hub is off. */
    val relayPort: Int get() = udp.port

    val clientCount: Int @Synchronized get() = clients.size

    /** Number of calls currently relayed through this hub. */
    val relayCount: Int @Synchronized get() = relays.size

    @Synchronized
    fun enable(): Int {
        if (enabled) return udp.port
        val port = udp.start()
        limiter?.maxConcurrent = HUB_MAX_CONNECTIONS
        enabled = true
        scheduler.scheduleWithFixedDelay({ expireIdleRelays() }, 1, 1, TimeUnit.SECONDS)
        return port
    }

    @Synchronized
    fun disable() {
        if (!enabled) return
        enabled = false
        udp.stop()
        val all = clients.values.map { it.channel }
        clients.clear()
        relays.clear()
        all.forEach { it.close() }
        limiter?.maxConcurrent = 8
    }

    // --- HubRouter -----------------------------------------------------------------------------------------

    @Synchronized
    override fun onHubMessage(channel: SignalingChannel, msg: Message) {
        if (!enabled) {
            channel.close()
            return
        }
        when (msg) {
            is HubRegister -> register(channel, msg)
            is HubRelayOpen -> openRelay(channel, msg)
            is RelayFrame -> forward(channel, msg)
            is HubRelayClose -> relays[msg.sessionId]?.let { if (it.a === channel || it.b === channel) closeRelay(it, notify = true) }
            else -> Unit
        }
    }

    @Synchronized
    override fun onHubChannelClosed(channel: SignalingChannel) {
        val gone = clients.values.filter { it.channel === channel }
        if (gone.isEmpty()) return
        gone.forEach { clients.remove(it.id) }
        relays.values.filter { it.a === channel || it.b === channel }.forEach { closeRelay(it, notify = true) }
        pushDirectories()
    }

    // --- handlers --------------------------------------------------------------------------------------------

    private fun register(channel: SignalingChannel, m: HubRegister) {
        if (m.id.length != 32 || Hex.decode(m.id) == null || m.port !in 1..65535) {
            channel.close()
            return
        }
        if (clients.size >= MAX_CLIENTS && !clients.containsKey(m.id)) {
            channel.close()
            return
        }
        // a phone that registers again replaces its earlier connection
        clients[m.id]?.let { old -> if (old.channel !== channel) old.channel.close() }
        clients[m.id] = Client(m.id, NameSanitizer.sanitizeOr(m.name, m.id.take(8)), channel.remoteHost, m.port, channel)
        pushDirectories()
    }

    private fun openRelay(channel: SignalingChannel, m: HubRelayOpen) {
        val requester = clients.values.firstOrNull { it.channel === channel }
        if (requester == null) {
            channel.close() // only registered phones may ask for a relay
            return
        }
        val target = clients[m.toId]
        val tag = tagOf(m.sessionId)
        if (target == null || target === requester || tag == null || relays.size >= MAX_RELAYS || m.sessionId in relays) {
            send(channel, HubRelayClose(m.sessionId))
            return
        }
        if (udp.addSession(tag) == null) {
            send(channel, HubRelayClose(m.sessionId))
            return
        }
        val relay = Relay(m.sessionId, tag, requester.channel, target.channel).also { it.lastFrameMs = clock.nowMs() }
        relays[m.sessionId] = relay
        val ready = HubRelayReady(m.sessionId, udp.port)
        send(requester.channel, ready)
        send(target.channel, ready)
    }

    private fun forward(from: SignalingChannel, m: RelayFrame) {
        val relay = relays[m.sessionId] ?: return
        if (m.frame.isEmpty() || m.frame.size > MAX_RELAY_FRAME) return
        val to = when {
            from === relay.a -> relay.b
            from === relay.b -> relay.a
            else -> return // not a party of this session
        }
        relay.lastFrameMs = clock.nowMs()
        send(to, m)
    }

    private fun closeRelay(r: Relay, notify: Boolean) {
        relays.remove(r.sessionId)
        udp.removeSession(r.tag)
        if (notify) {
            send(r.a, HubRelayClose(r.sessionId))
            send(r.b, HubRelayClose(r.sessionId))
        }
    }

    /** Closes relays with no traffic for [IDLE_MS]. Runs every second; public so tests can drive it. */
    @Synchronized
    fun expireIdleRelays() {
        if (!enabled) return
        val now = clock.nowMs()
        relays.values.toList().forEach { r ->
            val last = maxOf(r.lastFrameMs, udp.lastActivity(r.tag))
            if (now - last >= IDLE_MS) closeRelay(r, notify = true)
        }
    }

    private fun pushDirectories() {
        for (c in clients.values) {
            val peers = clients.values.filter { it.id != c.id }.take(JsonMessageCodec.MAX_PEERS)
                .map { HubPeer(it.id, it.name, it.ip, it.port) }
            send(c.channel, HubDirectory(peers))
        }
    }

    private fun send(channel: SignalingChannel, msg: Message) {
        if (!channel.send(JsonMessageCodec.encodeBytes(msg))) logger.d(TAG, "could not send ${msg.type}")
    }

    companion object {
        const val MAX_CLIENTS = 32
        const val MAX_RELAYS = 8
        const val MAX_RELAY_FRAME = 2_500
        const val IDLE_MS = 15_000L
        private const val HUB_MAX_CONNECTIONS = 48
        private const val TAG = "HubRegistry"

        /** The media session tag for a call id: its first 8 hex chars as a 32-bit integer (same as CallSession.sessionTag). */
        fun tagOf(sessionId: String): Int? = if (sessionId.length == 32 && Hex.decode(sessionId) != null) sessionId.take(8).toLong(16).toInt() else null
    }
}
