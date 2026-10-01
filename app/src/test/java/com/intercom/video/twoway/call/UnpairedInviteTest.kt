package com.intercom.video.twoway.call

import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.core.protocol.FrameCodec
import com.intercom.video.twoway.core.protocol.Invite
import com.intercom.video.twoway.core.protocol.InviteAuth
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.PairRequired
import com.intercom.video.twoway.core.util.B64
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Socket
import java.util.concurrent.TimeUnit

/** SC-010: a device that is not paired can never make another device ring. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class UnpairedInviteTest {
    private val devices = mutableListOf<TestDevice>()

    private fun device(name: String): TestDevice = TestDevice(name).also { devices += it }

    @AfterEach
    fun tearDown() = devices.forEach { it.stop() }

    /** Sends a hand-made INVITE straight to [port] and returns whatever message comes back (or null). */
    private fun rawInvite(port: Int, invite: Invite): String? {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 3_000
            FrameCodec.write(s.getOutputStream(), JsonMessageCodec.encodeBytes(invite))
            return try {
                String(FrameCodec.read(s.getInputStream()), Charsets.UTF_8)
            } catch (e: java.io.IOException) {
                null // connection closed without a reply
            }
        }
    }

    private fun invite(from: TestDevice, secret: ByteArray?, ts: Long = System.currentTimeMillis()): Invite {
        val base = Invite("0123456789abcdef0123456789abcdef", from.deviceId, from.name, B64.encode(ByteArray(16) { 3 }), 40000, ts, "")
        return if (secret != null) InviteAuth.sign(base, secret) else base.copy(mac = "AAAA")
    }

    @Test
    fun anUnknownSenderGetsPairRequiredAndNothingRings() {
        val b = device("Bob")
        val port = b.start().port
        val stranger = device("Mallory")
        val reply = rawInvite(port, invite(stranger, ByteArray(32) { 1 })) // validly signed, but with a secret Bob never agreed
        assertTrue(reply != null && JsonMessageCodec.decode(reply) is PairRequired, "reply=$reply")
        Thread.sleep(300)
        assertTrue(b.state is CallUiState.Idle, "Bob must not ring: ${b.state}")
    }

    @Test
    fun aRevokedDeviceIsRefusedLikeAnUnknownOne() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        b.trust.revoke(a.deviceId)
        a.engine.call(addrB)
        assertTrue(await { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.NOT_PAIRED, (a.state as CallUiState.Ended).reason)
        assertTrue(b.state is CallUiState.Idle)
    }

    @Test
    fun aBadOrMissingMacIsRefused() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        val port = b.start().port
        for (bad in listOf(invite(a, secret = null), invite(a, ByteArray(32) { 99 }))) {
            val reply = rawInvite(port, bad)
            assertTrue(reply != null && JsonMessageCodec.decode(reply) is PairRequired, "reply=$reply")
        }
        assertTrue(b.state is CallUiState.Idle)
    }

    @Test
    fun aReplayedOldInviteIsRefused() {
        val a = device("Alice")
        val b = device("Bob")
        val secret = ByteArray(32) { 5 }
        a.trust.trust(b.deviceId, secret)
        b.trust.trust(a.deviceId, secret)
        val port = b.start().port
        val stale = invite(a, secret, ts = System.currentTimeMillis() - 5 * 60_000)
        val reply = rawInvite(port, stale)
        assertTrue(reply != null && JsonMessageCodec.decode(reply) is PairRequired, "reply=$reply")
        assertTrue(b.state is CallUiState.Idle)
    }

    @Test
    fun withAutoRejectUnknownTheSenderGetsNoReplyAtAll() {
        val b = device("Bob")
        val port = b.start().port
        b.engine.autoRejectUnknown = true
        val stranger = device("Mallory")
        val reply = rawInvite(port, invite(stranger, ByteArray(32) { 1 }))
        assertEquals(null, reply, "silent: the connection is just closed")
        assertTrue(b.state is CallUiState.Idle)
    }

    @Test
    fun aCallerIsToldGenericallyUnavailableWhenTheCalleeIsSilent() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        b.trust.revoke(a.deviceId)
        b.engine.autoRejectUnknown = true
        a.engine.call(addrB)
        assertTrue(await { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.UNAVAILABLE, (a.state as CallUiState.Ended).reason, "no hint that pairing is the problem")
    }
}
