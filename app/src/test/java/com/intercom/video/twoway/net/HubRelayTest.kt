package com.intercom.video.twoway.net

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.core.crypto.AesGcm
import com.intercom.video.twoway.core.media.MediaPacket
import com.intercom.video.twoway.core.protocol.HubRegister
import com.intercom.video.twoway.core.protocol.HubRelayClose
import com.intercom.video.twoway.core.protocol.HubRelayOpen
import com.intercom.video.twoway.core.protocol.HubRelayReady
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.RelayFrame
import com.intercom.video.twoway.net.network.HubRegistry
import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.testing.FakeChannel
import com.intercom.video.twoway.testing.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** The hub forwards signaling frames and UDP voice unchanged, only between the two phones of a session. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HubRelayTest {
    private val clock = FakeClock()
    private val hub = HubRegistry(PlainSocketProvider, clock).also { it.enable() }
    private val sockets = mutableListOf<DatagramSocket>()

    private val idA = "%032x".format(0xA)
    private val idB = "%032x".format(0xB)
    private val idC = "%032x".format(0xC)
    private val session = "0102030405060708090a0b0c0d0e0f10"
    private val tag = 0x01020304

    @AfterEach
    fun tearDown() {
        hub.disable()
        sockets.forEach { it.close() }
    }

    private class Phone(val id: String, val ch: FakeChannel)

    private fun join(id: String, ip: String): Phone {
        val ch = FakeChannel(ip)
        hub.onHubMessage(ch, HubRegister(id, "P", 5000))
        return Phone(id, ch)
    }

    private fun relayFrames(p: Phone) = p.ch.sentTexts().mapNotNull { JsonMessageCodec.decode(it) as? RelayFrame }

    private fun readyOf(p: Phone) = p.ch.sentTexts().mapNotNull { JsonMessageCodec.decode(it) as? HubRelayReady }

    private fun open(a: Phone, b: Phone) = hub.onHubMessage(a.ch, HubRelayOpen(session, b.id))

    // --- signaling -----------------------------------------------------------------------------------------------

    @Test
    fun openingARelayTellsBothPhonesWhereToSendVoice() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
        val readyA = readyOf(a).single()
        val readyB = readyOf(b).single()
        assertEquals(session, readyA.sessionId)
        assertEquals(hub.relayPort, readyA.mediaPort)
        assertEquals(readyA, readyB)
        assertEquals(1, hub.relayCount)
    }

    @Test
    fun relayedFramesArriveUnchangedAtTheOtherPartyOnly() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        val c = join(idC, "10.0.0.4")
        open(a, b)

        val payload = ByteArray(300) { (it * 7).toByte() } // stands for an encrypted INVITE/ACCEPT
        hub.onHubMessage(a.ch, RelayFrame(session, payload))
        assertArrayEquals(payload, relayFrames(b).single().frame, "forwarded byte for byte")
        assertTrue(relayFrames(a).isEmpty() && relayFrames(c).isEmpty(), "nobody else sees it")

        hub.onHubMessage(b.ch, RelayFrame(session, byteArrayOf(9, 9)))
        assertArrayEquals(byteArrayOf(9, 9), relayFrames(a).single().frame, "and back")
    }

    @Test
    fun aPhoneThatIsNotPartOfTheSessionCannotInjectOrCloseIt() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        val c = join(idC, "10.0.0.4")
        open(a, b)
        hub.onHubMessage(c.ch, RelayFrame(session, byteArrayOf(1)))
        hub.onHubMessage(c.ch, HubRelayClose(session))
        assertTrue(relayFrames(a).isEmpty() && relayFrames(b).isEmpty())
        assertEquals(1, hub.relayCount, "the session is still up")
    }

    @Test
    fun oversizeAndEmptyRelayFramesAreDropped() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
        hub.onHubMessage(a.ch, RelayFrame(session, ByteArray(HubRegistry.MAX_RELAY_FRAME + 1)))
        hub.onHubMessage(a.ch, RelayFrame(session, ByteArray(0)))
        assertTrue(relayFrames(b).isEmpty())
        hub.onHubMessage(a.ch, RelayFrame(session, ByteArray(HubRegistry.MAX_RELAY_FRAME)))
        assertEquals(1, relayFrames(b).size, "the largest allowed frame passes")
    }

    @Test
    fun aRelayToAnUnknownPhoneOrFromAnUnregisteredOneIsRefused() {
        val a = join(idA, "10.0.0.2")
        hub.onHubMessage(a.ch, HubRelayOpen(session, idB)) // b never registered
        assertTrue(a.ch.sentTexts().any { JsonMessageCodec.decode(it) is HubRelayClose })
        assertEquals(0, hub.relayCount)

        val stranger = FakeChannel("10.0.0.9")
        hub.onHubMessage(stranger, HubRelayOpen(session, idA))
        assertTrue(stranger.isClosed, "only registered phones may open relays")
    }

    @Test
    fun atMostEightCallsAreRelayedAtOnce() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")

        // session ids are random: their first 8 hex chars (the media tag) differ
        fun sid(i: Int) = "%08x".format(0x10000000 + i) + "0".repeat(24)
        for (i in 1..HubRegistry.MAX_RELAYS) hub.onHubMessage(a.ch, HubRelayOpen(sid(i), b.id))
        assertEquals(HubRegistry.MAX_RELAYS, hub.relayCount)
        hub.onHubMessage(a.ch, HubRelayOpen(sid(99), b.id))
        assertEquals(HubRegistry.MAX_RELAYS, hub.relayCount)
    }

    @Test
    fun closingEndsTheSessionAndTellsTheOtherParty() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
        hub.onHubMessage(a.ch, HubRelayClose(session))
        assertEquals(0, hub.relayCount)
        assertTrue(b.ch.sentTexts().any { JsonMessageCodec.decode(it) is HubRelayClose })
        // frames for a closed session go nowhere
        val before = relayFrames(b).size
        hub.onHubMessage(a.ch, RelayFrame(session, byteArrayOf(1)))
        assertEquals(before, relayFrames(b).size)
    }

    @Test
    fun aPhoneLeavingEndsItsRelays() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
        hub.onHubChannelClosed(a.ch)
        assertEquals(0, hub.relayCount)
        assertTrue(b.ch.sentTexts().any { JsonMessageCodec.decode(it) is HubRelayClose })
    }

    @Test
    fun aRelayWithNoTrafficIsClosedAfter15Seconds() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
        clock.advance(14_000)
        hub.expireIdleRelays()
        assertEquals(1, hub.relayCount)

        hub.onHubMessage(a.ch, RelayFrame(session, byteArrayOf(1))) // traffic resets the timer
        clock.advance(14_000)
        hub.expireIdleRelays()
        assertEquals(1, hub.relayCount)

        clock.advance(2_000)
        hub.expireIdleRelays()
        assertEquals(0, hub.relayCount, "15 s without traffic")
        assertTrue(a.ch.sentTexts().any { JsonMessageCodec.decode(it) is HubRelayClose })
    }

    // --- UDP voice --------------------------------------------------------------------------------------------------

    private fun udp(): DatagramSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also {
        it.soTimeout = 1_000
        sockets += it
    }

    private fun packetFor(tag: Int, payload: Byte = 1): ByteArray {
        val key = ByteArray(32) { it.toByte() }
        return MediaPacket.seal(AesGcm(key, byteArrayOf(1, 2, 3, 4)), MediaPacket.TYPE_VOICE, 1, 0, tag, ByteArray(20) { payload })
    }

    private fun send(from: DatagramSocket, data: ByteArray) = from.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), hub.relayPort))

    private fun receive(on: DatagramSocket): ByteArray? = try {
        val buf = ByteArray(2000)
        val p = DatagramPacket(buf, buf.size)
        on.receive(p)
        buf.copyOf(p.length)
    } catch (_: SocketTimeoutException) {
        null
    }

    private fun openUdpSession() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
    }

    @Test
    fun voicePacketsAreForwardedUnchangedBetweenTheTwoPhones() {
        openUdpSession()
        val pa = udp()
        val pb = udp()
        val p1 = packetFor(tag, 1)
        send(pa, p1) // the hub learns A's address (nothing to forward yet)
        val p2 = packetFor(tag, 2)
        send(pb, p2) // learns B and forwards to A
        assertArrayEquals(p2, receive(pa), "A receives B's packet byte for byte")
        send(pa, p1)
        assertArrayEquals(p1, receive(pb), "B receives A's packet byte for byte")
    }

    @Test
    fun packetsForUnknownSessionsOversizeAndShortOnesAreDropped() {
        openUdpSession()
        val pa = udp()
        val pb = udp()
        send(pa, packetFor(tag))
        send(pb, packetFor(tag))
        receive(pa) // B's first packet reaches A
        send(pa, packetFor(0x0BADBEEF)) // unknown tag
        send(
            pa,
            ByteArray(1100) { 5 }.also {
                it[8] = 1
                it[9] = 2
                it[10] = 3
                it[11] = 4
            },
        ) // oversize
        send(pa, ByteArray(5)) // too short
        assertEquals(null, receive(pb), "nothing was forwarded to B")
    }

    @Test
    fun aThirdAddressCannotJoinASession() {
        openUdpSession()
        val pa = udp()
        val pb = udp()
        val intruder = udp()
        send(pa, packetFor(tag, 1))
        send(pb, packetFor(tag, 2))
        receive(pa)
        send(intruder, packetFor(tag, 9))
        assertEquals(null, receive(pa), "A gets nothing from the intruder")
        assertEquals(null, receive(pb), "and neither does B")
    }

    @Test
    fun voiceStopsFlowingOnceTheSessionIsClosed() {
        val a = join(idA, "10.0.0.2")
        val b = join(idB, "10.0.0.3")
        open(a, b)
        val pa = udp()
        val pb = udp()
        send(pa, packetFor(tag))
        send(pb, packetFor(tag))
        assertTrue(receive(pa) != null)
        hub.onHubMessage(a.ch, HubRelayClose(session))
        send(pa, packetFor(tag))
        assertEquals(null, receive(pb))
        assertFalse(await(300) { hub.relayCount > 0 })
    }
}
