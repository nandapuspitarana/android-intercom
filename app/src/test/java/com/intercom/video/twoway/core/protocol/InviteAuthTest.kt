package com.intercom.video.twoway.core.protocol

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InviteAuthTest {
    private val ps = ByteArray(32) { (it + 1).toByte() }
    private val now = 1_700_000_000_000L

    private fun invite(ts: Long = now) = InviteAuth.sign(Invite("call1", "peerA", "Kitchen", "bm9uY2U=", 5004, ts, ""), ps)

    @Test
    fun validInviteVerifies() = assertTrue(InviteAuth.verify(invite(), ps, now))

    @Test
    fun wrongSecretIsRejected() = assertFalse(InviteAuth.verify(invite(), ByteArray(32), now))

    @Test
    fun missingOrBadMacIsRejected() {
        assertFalse(InviteAuth.verify(invite().copy(mac = ""), ps, now))
        assertFalse(InviteAuth.verify(invite().copy(mac = "AAAA"), ps, now))
    }

    @Test
    fun everyFieldIsCoveredByTheMac() {
        val i = invite()
        assertFalse(InviteAuth.verify(i.copy(callId = "other"), ps, now))
        assertFalse(InviteAuth.verify(i.copy(from = "other"), ps, now))
        assertFalse(InviteAuth.verify(i.copy(nonce = "b3RoZXI="), ps, now))
        assertFalse(InviteAuth.verify(i.copy(mediaPort = 5005), ps, now))
        assertFalse(InviteAuth.verify(i.copy(ts = i.ts + 1), ps, now))
    }

    @Test
    fun timestampOlderThan30sIsRejected() {
        val old = invite(now - 31_000)
        assertFalse(InviteAuth.verify(old, ps, now))
        assertTrue(InviteAuth.verify(invite(now - 29_000), ps, now))
    }

    @Test
    fun timestampFarInTheFutureIsRejected() = assertFalse(InviteAuth.verify(invite(now + 31_000), ps, now))

    @Test
    fun ringingMacBindsBothNonces() {
        val mac = InviteAuth.ringingMac(ps, "call1", "nA", "nB")
        assertTrue(InviteAuth.verifyRinging(Ringing("call1", "nB", mac), ps, "nA"))
        assertFalse(InviteAuth.verifyRinging(Ringing("call1", "nB", mac), ps, "other"))
        assertFalse(InviteAuth.verifyRinging(Ringing("call1", "nX", mac), ps, "nA"))
        assertFalse(InviteAuth.verifyRinging(Ringing("call1", "nB", mac), ByteArray(32), "nA"))
    }
}
