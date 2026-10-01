package com.intercom.video.twoway.core.pairing

import com.intercom.video.twoway.core.crypto.Hkdf
import com.intercom.video.twoway.core.protocol.PairAccept
import com.intercom.video.twoway.core.protocol.PairConfirm
import com.intercom.video.twoway.core.protocol.PairRequest
import com.intercom.video.twoway.core.util.B64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class PairingException(message: String) : Exception(message)

enum class PairingRole(val wire: Byte) { INITIATOR(1), RESPONDER(2) }

/** The peer's long-term identity as proven by a valid confirm message. */
class VerifiedPeer(val identityPublicKey: ByteArray) {
    val fingerprint: ByteArray get() = MessageDigest.getInstance("SHA-256").digest(identityPublicKey)
}

/**
 * One pairing handshake (contracts/pairing.md): ephemeral P-256 ECDH, a 6-digit Short Authentication String
 * both users compare, and a key-confirmation step that also carries each side's long-term identity key signed
 * over the transcript. The result is a 32-byte pairing secret PS known only to the two devices.
 *
 * A man in the middle ends up with different shared secrets on each side, so the two 6-digit codes differ and the
 * users reject the pairing. Pure JCA: no android.* imports, so it is unit-tested on the JVM.
 */
class PairingSession(val role: PairingRole, val localId: String, val localName: String, private val rng: SecureRandom = SecureRandom()) {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), rng)
    }.generateKeyPair()
    private val localNonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }

    var peerId: String? = null
        private set
    var peerName: String? = null
        private set
    private var peerPub: ByteArray? = null
    private var peerNonce: ByteArray? = null
    private var shared: ByteArray? = null
    private var transcript: ByteArray? = null

    /** The 6-digit code both users compare. Available once both ephemeral keys have been exchanged. */
    var sas: String? = null
        private set

    private var secret: ByteArray? = null

    // --- message construction ------------------------------------------------------------------------

    /** Initiator: first message. */
    fun createRequest(): PairRequest {
        check(role == PairingRole.INITIATOR)
        return PairRequest(localId, localName, B64.encode(keyPair.public.encoded), B64.encode(localNonce))
    }

    /** Responder: user accepted [req]; returns the reply and derives the code. */
    fun acceptRequest(req: PairRequest): PairAccept {
        check(role == PairingRole.RESPONDER)
        setPeer(req.id, req.name, req.pub, req.nonce)
        derive()
        return PairAccept(localId, localName, B64.encode(keyPair.public.encoded), B64.encode(localNonce))
    }

    /** Initiator: peer accepted; derives the code. */
    fun handleAccept(acc: PairAccept) {
        check(role == PairingRole.INITIATOR)
        setPeer(acc.id, acc.name, acc.pub, acc.nonce)
        derive()
    }

    /** Builds my confirm message after my user compared the codes. [sign] signs with the long-term identity key. */
    fun createConfirm(matches: Boolean, identityPublicKey: ByteArray, sign: (ByteArray) -> ByteArray): PairConfirm {
        if (!matches) return PairConfirm(false, "", "", "")
        val t = transcript ?: throw PairingException("keys not exchanged yet")
        return PairConfirm(
            ok = true,
            mac = B64.encode(confirmMac(role, t)),
            identityPub = B64.encode(identityPublicKey),
            sig = B64.encode(sign(t)),
        )
    }

    /** Verifies the peer's confirm: MAC under the shared secret and identity signature over the transcript. */
    fun verifyConfirm(c: PairConfirm): VerifiedPeer {
        val t = transcript ?: throw PairingException("keys not exchanged yet")
        if (!c.ok) throw PairingException("peer reports that the codes do not match")
        val peerRole = if (role == PairingRole.INITIATOR) PairingRole.RESPONDER else PairingRole.INITIATOR
        val mac = B64.decode(c.mac) ?: throw PairingException("bad mac encoding")
        if (!MessageDigest.isEqual(mac, confirmMac(peerRole, t))) throw PairingException("confirm mac does not verify")
        val idBytes = B64.decode(c.identityPub) ?: throw PairingException("bad identity key encoding")
        val sig = B64.decode(c.sig) ?: throw PairingException("bad signature encoding")
        val identityKey = decodePublicKey(idBytes)
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(identityKey)
                update(t)
                verify(sig)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
        if (!ok) throw PairingException("identity signature does not verify")
        return VerifiedPeer(idBytes)
    }

    /** The 32-byte pairing secret PS. Only valid once the codes were exchanged. */
    fun pairingSecret(): ByteArray {
        secret?.let { return it.copyOf() }
        val z = shared ?: throw PairingException("keys not exchanged yet")
        val t = transcript!!
        return Hkdf.derive(z, t, "twoway PS v1".toByteArray(Charsets.UTF_8), 32).also { secret = it }.copyOf()
    }

    fun wipe() {
        shared?.fill(0)
        secret?.fill(0)
        transcript?.fill(0)
        shared = null
        secret = null
        transcript = null
    }

    // --- internals ----------------------------------------------------------------------------------------

    private fun setPeer(id: String, name: String, pubB64: String, nonceB64: String) {
        if (peerPub != null) throw PairingException("peer already set")
        val pub = B64.decode(pubB64) ?: throw PairingException("bad public key encoding")
        val nonce = B64.decode(nonceB64) ?: throw PairingException("bad nonce encoding")
        if (nonce.size != NONCE_LEN) throw PairingException("bad nonce length")
        if (id.isEmpty() || id == localId) throw PairingException("bad peer id")
        decodePublicKey(pub) // validates the point
        peerId = id
        peerName = name
        peerPub = pub
        peerNonce = nonce
    }

    private fun derive() {
        val pub = peerPub ?: throw PairingException("no peer key")
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(decodePublicKey(pub), true)
        val z = ka.generateSecret()
        shared = z
        val mine = keyPair.public.encoded
        val (idI, idR, pubI, pubR, nI, nR) = if (role == PairingRole.INITIATOR) {
            Sextet(localId, peerId!!, mine, pub, localNonce, peerNonce!!)
        } else {
            Sextet(peerId!!, localId, pub, mine, peerNonce!!, localNonce)
        }
        val t = MessageDigest.getInstance("SHA-256").run {
            update("twoway pair v1".toByteArray(Charsets.UTF_8))
            update(idI.toByteArray(Charsets.UTF_8))
            update(idR.toByteArray(Charsets.UTF_8))
            update(pubI)
            update(pubR)
            update(nI)
            update(nR)
            digest()
        }
        transcript = t
        val h = Hkdf.hmac(z, t)
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        sas = "%06d".format(v % 1_000_000L)
    }

    private fun confirmMac(sender: PairingRole, t: ByteArray): ByteArray {
        val z = shared ?: throw PairingException("keys not exchanged yet")
        return Hkdf.hmac(z, "confirm".toByteArray(Charsets.UTF_8), byteArrayOf(sender.wire), t)
    }

    private data class Sextet(val a: String, val b: String, val c: ByteArray, val d: ByteArray, val e: ByteArray, val f: ByteArray)

    companion object {
        const val NONCE_LEN = 16

        fun decodePublicKey(x509: ByteArray): PublicKey = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))
        } catch (e: java.security.GeneralSecurityException) {
            throw PairingException("invalid public key")
        }
    }
}
