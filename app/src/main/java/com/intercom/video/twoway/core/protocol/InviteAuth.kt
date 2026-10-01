package com.intercom.video.twoway.core.protocol

import com.intercom.video.twoway.core.crypto.Hkdf
import com.intercom.video.twoway.core.util.B64
import java.security.MessageDigest

/**
 * Proves the caller knows the pairing secret PS (INVITE mac) and binds the callee's nonce to the
 * same secret (RINGING mac). Messages are otherwise plaintext, so these MACs are what stops a
 * stranger on the network from ringing a device or forging the key exchange (SC-010).
 */
object InviteAuth {
    const val MAX_AGE_MS = 30_000L

    fun inviteMac(ps: ByteArray, callId: String, from: String, nonce: String, mediaPort: Int, ts: Long): String =
        B64.encode(Hkdf.hmac(ps, canonical("invite v1", callId, from, nonce, mediaPort.toString(), ts.toString())))

    fun sign(invite: Invite, ps: ByteArray): Invite = invite.copy(mac = inviteMac(ps, invite.callId, invite.from, invite.nonce, invite.mediaPort, invite.ts))

    /** True if the mac matches and the timestamp is within 30 s of [nowWallMs] (either direction). */
    fun verify(invite: Invite, ps: ByteArray, nowWallMs: Long): Boolean {
        if (kotlin.math.abs(nowWallMs - invite.ts) > MAX_AGE_MS) return false
        val expected = inviteMac(ps, invite.callId, invite.from, invite.nonce, invite.mediaPort, invite.ts)
        return constantTimeEquals(expected, invite.mac)
    }

    fun ringingMac(ps: ByteArray, callId: String, nonceCaller: String, nonceCallee: String): String =
        B64.encode(Hkdf.hmac(ps, canonical("ringing v1", callId, nonceCaller, nonceCallee)))

    fun verifyRinging(r: Ringing, ps: ByteArray, nonceCaller: String): Boolean = constantTimeEquals(ringingMac(ps, r.callId, nonceCaller, r.nonce), r.mac)

    private fun canonical(label: String, vararg fields: String): ByteArray = (label + "\n" + fields.joinToString("\n")).toByteArray(Charsets.UTF_8)

    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
