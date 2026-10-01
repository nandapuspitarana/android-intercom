package com.intercom.video.twoway.net.discovery

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.net.transport.SocketProvider
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Fallback discovery for networks where mDNS is filtered: announces ourselves on
 * 239.255.42.99:45679 every 5 s, answers queries immediately, and records announcements from others.
 * Datagrams over 256 bytes, invalid JSON, our own id, other versions and more than 10 per second per
 * source are ignored (contracts/discovery.md).
 */
class MulticastDiscovery(
    private val sockets: SocketProvider,
    private val registry: DeviceRegistry,
    private val clock: Clock,
    private val logger: Logger,
    private val selfId: String,
    private val selfName: () -> String,
    private val signalingPort: () -> Int,
    private val role: () -> String = { "p" },
    private val group: String = Announce.GROUP,
    private val port: Int = Announce.PORT,
) {
    @Volatile
    private var running = false
    private var socket: MulticastSocket? = null
    private val limiter = SourceRateLimiter(clock)

    @Synchronized
    fun start() {
        if (running) return
        val s = try {
            sockets.multicastSocket(port).also {
                it.soTimeout = 1_000
                it.joinGroup(InetAddress.getByName(group))
            }
        } catch (e: IOException) {
            logger.w(TAG, "multicast discovery unavailable: ${e.message}")
            return
        }
        socket = s
        running = true
        Thread({ loop(s) }, "multicast-discovery").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: RuntimeException) {
        }
        socket = null
    }

    private fun loop(s: MulticastSocket) {
        val addr = InetAddress.getByName(group)
        val buf = ByteArray(Announce.MAX_BYTES + 1)
        var lastAnnounce = Long.MIN_VALUE
        try {
            send(s, addr, Announce.encodeQuery())
            while (running) {
                val now = clock.nowMs()
                if (lastAnnounce == Long.MIN_VALUE || now - lastAnnounce >= ANNOUNCE_INTERVAL_MS) {
                    announce(s, addr)
                    lastAnnounce = now
                }
                registry.expire()
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val source = p.address.hostAddress ?: continue
                if (!limiter.allow(source)) continue
                when (val parsed = Announce.parse(buf, p.length)) {
                    is Announce.Parsed.Announcement ->
                        registry.seen(parsed.id, parsed.name, source, parsed.port, parsed.role, DiscoverySource.MULTICAST)
                    Announce.Parsed.Query -> announce(s, addr)
                    null -> Unit
                }
            }
        } catch (e: IOException) {
            if (running) logger.w(TAG, "multicast loop ended: ${e.message}")
        }
    }

    private fun announce(s: MulticastSocket, addr: InetAddress) = send(s, addr, Announce.encodeAnnounce(selfId, selfName(), signalingPort(), role()))

    private fun send(s: MulticastSocket, addr: InetAddress, data: ByteArray) {
        try {
            s.send(DatagramPacket(data, data.size, addr, port))
        } catch (e: IOException) {
            logger.d(TAG, "announce failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "MulticastDiscovery"
        const val ANNOUNCE_INTERVAL_MS = 5_000L
    }
}
