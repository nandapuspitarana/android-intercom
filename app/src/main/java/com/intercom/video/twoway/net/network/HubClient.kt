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
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.RelayFrame
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.transport.SocketProvider
import com.intercom.video.twoway.net.transport.TcpSignalingClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opens relayed signaling channels through the hotspot hub when a direct connection is impossible. */
interface RelayProvider {
    /** True while this phone is registered with a hub. */
    val available: Boolean

    /** Asks the hub to relay a call to [toId]; blocks up to [timeoutMs]. Null if the hub refused or did not answer. */
    fun openRelay(toId: String, sessionId: String, timeoutMs: Long): RelayChannel?
}

/**
 * The client side of the hotspot hub. Keeps one connection to the hub, registers this phone there, copies the
 * hub's directory into the [DeviceRegistry] (so phones that cannot see each other directly still show up) and
 * hands out [RelayChannel]s for relayed calls, both outgoing and incoming.
 */
class HubClient(
    sockets: SocketProvider,
    private val registry: DeviceRegistry,
    private val clock: Clock,
    private val self: () -> com.intercom.video.twoway.service.LocalDevice,
    private val signalingPort: () -> Int,
    /** An inbound relayed channel (we are the callee) is ready; the call engine starts reading it. */
    private val onInboundChannel: (RelayChannel) -> Unit,
    private val logger: Logger = NoopLogger,
) : RelayProvider {
    private val client = TcpSignalingClient(sockets, logger)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hub-client").apply { isDaemon = true } }
    private val relays = ConcurrentHashMap<String, RelayChannel>()
    private val pending = ConcurrentHashMap<String, Pending>()

    private class Pending {
        val latch = CountDownLatch(1)

        @Volatile
        var channel: RelayChannel? = null
    }

    @Volatile
    private var hub: com.intercom.video.twoway.net.transport.SignalingChannel? = null

    @Volatile
    private var directory: List<HubPeer> = emptyList()

    @Volatile
    private var refresher: java.util.concurrent.ScheduledFuture<*>? = null

    override val available: Boolean get() = hub?.isClosed == false

    @Volatile
    var onDisconnected: () -> Unit = {}

    /** Connects and registers. Returns false if the hub is unreachable. Blocking: call off the main thread. */
    @Synchronized
    fun connect(hubHost: String, hubPort: Int): Boolean {
        disconnect()
        val c = client.connect(hubHost, hubPort) ?: return false
        hub = c
        c.startReading({ body -> onFrame(c, body) }, { onHubClosed(c) })
        val me = self()
        c.send(JsonMessageCodec.encodeBytes(HubRegister(me.deviceId, me.name, signalingPort())))
        refresher = scheduler.scheduleWithFixedDelay({ refresh() }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
        return true
    }

    @Synchronized
    fun disconnect() {
        refresher?.cancel(false)
        refresher = null
        val h = hub
        hub = null
        h?.close()
        closeAllRelays()
    }

    override fun openRelay(toId: String, sessionId: String, timeoutMs: Long): RelayChannel? {
        val h = hub ?: return null
        val p = Pending()
        pending[sessionId] = p
        try {
            if (!h.send(JsonMessageCodec.encodeBytes(HubRelayOpen(sessionId, toId)))) return null
            p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return p.channel
        } finally {
            pending.remove(sessionId)
        }
    }

    // --- frames from the hub ----------------------------------------------------------------------------------

    private fun onFrame(conn: com.intercom.video.twoway.net.transport.SignalingChannel, body: ByteArray) {
        val msg = try {
            JsonMessageCodec.decode(body)
        } catch (e: JsonException) {
            return
        } ?: return
        when (msg) {
            is HubDirectory -> {
                directory = msg.peers
                refresh()
            }
            is HubRelayReady -> onReady(conn, msg)
            is RelayFrame -> relays[msg.sessionId]?.deliver(msg.frame)
            is HubRelayClose -> {
                relays.remove(msg.sessionId)?.closedByHub()
                pending[msg.sessionId]?.latch?.countDown() // refused
            }
            else -> Unit
        }
    }

    private fun onReady(conn: com.intercom.video.twoway.net.transport.SignalingChannel, m: HubRelayReady) {
        val channel = RelayChannel(m.sessionId, conn, m.mediaPort) { relays.remove(it.sessionId) }
        relays[m.sessionId] = channel
        val waiting = pending[m.sessionId]
        if (waiting != null) {
            waiting.channel = channel // we are the caller
            waiting.latch.countDown()
        } else {
            onInboundChannel(channel) // we are the callee
        }
    }

    private fun refresh() {
        val me = self().deviceId
        for (p in directory) {
            if (p.id != me) registry.seen(p.id, p.name, p.ip, p.port, "p", DiscoverySource.HUB_DIRECTORY)
        }
    }

    private fun onHubClosed(conn: com.intercom.video.twoway.net.transport.SignalingChannel) {
        if (hub !== conn) return
        hub = null
        refresher?.cancel(false)
        closeAllRelays()
        onDisconnected()
    }

    private fun closeAllRelays() {
        relays.values.toList().forEach { it.closedByHub() }
        relays.clear()
        pending.values.forEach { it.latch.countDown() }
    }

    private companion object {
        const val REFRESH_MS = 5_000L
    }
}
