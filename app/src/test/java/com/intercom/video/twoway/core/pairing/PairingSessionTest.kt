package com.intercom.video.twoway.core.pairing

import com.intercom.video.twoway.core.protocol.PairAccept
import com.intercom.video.twoway.core.protocol.PairConfirm
import com.intercom.video.twoway.core.protocol.PairRequest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class PairingSessionTest {
    private val idA = "a".repeat(32)
    private val idB = "b".repeat(32)

    /** A device's long-term identity key (software here; AndroidKeyStore on the phone). */
    private class Identity {
        val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val pub: ByteArray get() = pair.public.encoded
        fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }
    }

    private fun handshake(): Pair<PairingSession, PairingSession> {
        val i = PairingSession(PairingRole.INITIATOR, idA, "Alice")
        val r = PairingSession(PairingRole.RESPONDER, idB, "Bob")
        val accept = r.acceptRequest(i.createRequest())
        i.handleAccept(accept)
        return i to r
    }

    @Test
    fun bothSidesDeriveTheSameSixDigitCodeAndSecret() {
        val (i, r) = handshake()
        assertEquals(i.sas, r.sas)
        assertTrue(Regex("\\d{6}").matches(i.sas!!), "code is 6 digits: ${i.sas}")
        assertArrayEquals(i.pairingSecret(), r.pairingSecret())
        assertEquals(32, i.pairingSecret().size)
        assertEquals(idB, i.peerId)
        assertEquals("Bob", i.peerName)
        assertEquals(idA, r.peerId)
    }

    @Test
    fun differentHandshakesGiveDifferentCodesAndSecrets() {
        val (i1, _) = handshake()
        val (i2, _) = handshake()
        assertFalse(i1.pairingSecret().contentEquals(i2.pairingSecret()))
    }

    @Test
    fun codeIsZeroPaddedToSixDigits() {
        // Run enough handshakes to see small numbers; every code must still be exactly 6 characters.
        repeat(60) { assertEquals(6, handshake().first.sas!!.length) }
    }

    @Test
    fun aManInTheMiddleProducesDifferentCodesOnEachSide() {
        // Mallory sits between Alice and Bob and runs a separate handshake with each.
        val alice = PairingSession(PairingRole.INITIATOR, idA, "Alice")
        val bob = PairingSession(PairingRole.RESPONDER, idB, "Bob")
        val malloryToBob = PairingSession(PairingRole.INITIATOR, idA, "Alice") // pretends to be Alice towards Bob
        val malloryToAlice = PairingSession(PairingRole.RESPONDER, idB, "Bob") // pretends to be Bob towards Alice

        val replyToAlice = malloryToAlice.acceptRequest(alice.createRequest())
        alice.handleAccept(replyToAlice)
        val replyToBob = bob.acceptRequest(malloryToBob.createRequest())
        malloryToBob.handleAccept(replyToBob)

        assertNotEquals(alice.sas, bob.sas, "the two users see different codes and reject the pairing")
        assertFalse(alice.pairingSecret().contentEquals(bob.pairingSecret()))
    }

    @Test
    fun confirmMessagesVerifyAndCarryTheIdentityKey() {
        val (i, r) = handshake()
        val idI = Identity()
        val idR = Identity()
        val confirmI = i.createConfirm(true, idI.pub, idI::sign)
        val confirmR = r.createConfirm(true, idR.pub, idR::sign)

        val seenByR = r.verifyConfirm(confirmI)
        val seenByI = i.verifyConfirm(confirmR)
        assertArrayEquals(idI.pub, seenByR.identityPublicKey)
        assertArrayEquals(idR.pub, seenByI.identityPublicKey)
        assertEquals(32, seenByI.fingerprint.size)
    }

    @Test
    fun doesNotMatchAbortsAndTheOtherSideRefusesIt() {
        val (i, r) = handshake()
        val no = i.createConfirm(false, ByteArray(0)) { ByteArray(0) }
        assertFalse(no.ok)
        assertThrows(PairingException::class.java) { r.verifyConfirm(no) }
    }

    @Test
    fun tamperedMacIsRejected() {
        val (i, r) = handshake()
        val id = Identity()
        val c = i.createConfirm(true, id.pub, id::sign)
        val bad = PairConfirm(true, com.intercom.video.twoway.core.util.B64.encode(ByteArray(32)), c.identityPub, c.sig)
        assertThrows(PairingException::class.java) { r.verifyConfirm(bad) }
    }

    @Test
    fun aConfirmCannotBeReflectedBackToItsSender() {
        // The MAC includes the sender's role, so a device cannot be tricked into accepting its own confirm.
        val (i, _) = handshake()
        val id = Identity()
        val own = i.createConfirm(true, id.pub, id::sign)
        assertThrows(PairingException::class.java) { i.verifyConfirm(own) }
    }

    @Test
    fun signatureFromAnotherKeyOrOtherSessionIsRejected() {
        val (i, r) = handshake()
        val claimed = Identity()
        val attacker = Identity()
        val c = i.createConfirm(true, claimed.pub, attacker::sign) // signs with a key that is not the claimed identity
        assertThrows(PairingException::class.java) { r.verifyConfirm(c) }

        // A valid confirm from a different pairing session does not verify here.
        val (i2, _) = handshake()
        val other = i2.createConfirm(true, claimed.pub, claimed::sign)
        assertThrows(PairingException::class.java) { r.verifyConfirm(other) }
    }

    @Test
    fun malformedMessagesAreRejectedWithoutCrashing() {
        val r = PairingSession(PairingRole.RESPONDER, idB, "Bob")
        assertThrows(PairingException::class.java) { r.acceptRequest(PairRequest(idA, "A", "not base64!", "AAAA")) }
        val i = PairingSession(PairingRole.INITIATOR, idA, "Alice")
        val good = i.createRequest()
        assertThrows(PairingException::class.java) { PairingSession(PairingRole.RESPONDER, idB, "B").acceptRequest(good.copy(nonce = "AAAA")) }
        assertThrows(PairingException::class.java) { PairingSession(PairingRole.RESPONDER, idB, "B").acceptRequest(good.copy(pub = "AAAA")) }
        assertThrows(PairingException::class.java) { PairingSession(PairingRole.RESPONDER, idA, "B").acceptRequest(good) } // peer == self
        assertThrows(PairingException::class.java) { PairingSession(PairingRole.INITIATOR, idA, "A").handleAccept(PairAccept("", "x", good.pub, good.nonce)) }
    }

    @Test
    fun confirmBeforeKeysAreExchangedFails() {
        val i = PairingSession(PairingRole.INITIATOR, idA, "Alice")
        i.createRequest()
        val id = Identity()
        assertThrows(PairingException::class.java) { i.createConfirm(true, id.pub, id::sign) }
        assertThrows(PairingException::class.java) { i.pairingSecret() }
    }

    @Test
    fun wipeZeroesTheSecretMaterial() {
        val (i, _) = handshake()
        i.pairingSecret()
        i.wipe()
        assertThrows(PairingException::class.java) { i.pairingSecret() }
    }
}
