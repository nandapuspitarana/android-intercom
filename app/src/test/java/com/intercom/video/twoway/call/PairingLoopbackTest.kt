package com.intercom.video.twoway.call

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PairRateLimiter
import com.intercom.video.twoway.service.PairingFailure
import com.intercom.video.twoway.service.PairingUiState
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Full PAIR_REQUEST -> PAIR_ACCEPT -> PAIR_CONFIRM handshakes between two real engines over localhost. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PairingLoopbackTest {
    private val devices = mutableListOf<TestDevice>()

    private fun device(name: String, clock: FakeClock? = null): TestDevice =
        TestDevice(name, clock = clock ?: com.intercom.video.twoway.core.SystemClock).also { devices += it }

    @AfterEach
    fun tearDown() = devices.forEach { it.stop() }

    private fun verify(d: TestDevice) = d.pairing.state.value as? PairingUiState.Verify

    /** Starts pairing from [a] to [b] and brings both sides to the "compare the code" screen. */
    private fun reachVerify(a: TestDevice, b: TestDevice): Pair<String, String> {
        a.start()
        val addrB = b.start()
        a.pairing.start(addrB)
        assertTrue(await { b.pairing.state.value is PairingUiState.IncomingRequest }, "b=${b.pairing.state.value}")
        val req = b.pairing.state.value as PairingUiState.IncomingRequest
        assertEquals(a.deviceId, req.peerId)
        assertEquals(a.name, req.peerName)
        assertTrue(a.pairing.state.value is PairingUiState.Requesting || a.pairing.state.value is PairingUiState.Verify)
        b.pairing.acceptIncoming()
        assertTrue(await { verify(a) != null && verify(b) != null }, "a=${a.pairing.state.value} b=${b.pairing.state.value}")
        return verify(a)!!.code to verify(b)!!.code
    }

    @Test
    fun matchingCodesPairBothDevicesAndTheNextCallRings() {
        val a = device("Alice")
        val b = device("Bob")
        val (codeA, codeB) = reachVerify(a, b)
        assertEquals(codeA, codeB, "both screens show the same 6 digits")
        assertTrue(Regex("\\d{6}").matches(codeA))

        a.pairing.confirmMatches(true)
        assertTrue(await { (a.pairing.state.value as? PairingUiState.Verify)?.awaitingPeer == true })
        assertFalse(a.pairing.state.value is PairingUiState.Done, "A must wait for B")
        b.pairing.confirmMatches(true)

        assertTrue(
            await { a.pairing.state.value is PairingUiState.Done && b.pairing.state.value is PairingUiState.Done },
            "a=${a.pairing.state.value} b=${b.pairing.state.value}",
        )
        assertEquals("Bob", (a.pairing.state.value as PairingUiState.Done).peerName)

        // Both stored the same secret and pinned each other's identity.
        val secretA = a.trust.pairingSecret(b.deviceId)
        val secretB = b.trust.pairingSecret(a.deviceId)
        assertNotNull(secretA)
        assertArrayEquals(secretA, secretB)
        assertNotNull(a.pairingStore.fingerprints[b.deviceId])
        assertArrayEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(b.identity.publicKey),
            a.pairingStore.fingerprints[b.deviceId],
        )

        // A call between the freshly paired devices now rings, using only the secret they just agreed on.
        a.engine.call(com.intercom.video.twoway.service.PeerAddress(b.deviceId, "Bob", "127.0.0.1", b.engine.signalingPort))
        assertTrue(await { b.state is CallUiState.Incoming }, "b=${b.state}")
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
        assertTrue(await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 })
    }

    @Test
    fun doesNotMatchAbortsBothSidesAndStoresNothing() {
        val a = device("Alice")
        val b = device("Bob")
        reachVerify(a, b)
        a.pairing.confirmMatches(true)
        b.pairing.confirmMatches(false) // Bob sees a different code

        assertTrue(
            await { a.pairing.state.value is PairingUiState.Failed && b.pairing.state.value is PairingUiState.Failed },
            "a=${a.pairing.state.value} b=${b.pairing.state.value}",
        )
        assertEquals(PairingFailure.MISMATCH, (a.pairing.state.value as PairingUiState.Failed).reason)
        assertEquals(PairingFailure.MISMATCH, (b.pairing.state.value as PairingUiState.Failed).reason)
        assertNull(a.trust.pairingSecret(b.deviceId))
        assertNull(b.trust.pairingSecret(a.deviceId))
        assertTrue(a.pairingStore.saved.isEmpty() && b.pairingStore.saved.isEmpty())
    }

    @Test
    fun declinedRequestFailsTheInitiator() {
        val a = device("Alice")
        val b = device("Bob")
        a.start()
        val addrB = b.start()
        a.pairing.start(addrB)
        assertTrue(await { b.pairing.state.value is PairingUiState.IncomingRequest })
        b.pairing.declineIncoming()
        assertTrue(await { a.pairing.state.value is PairingUiState.Failed })
        assertEquals(PairingFailure.DECLINED, (a.pairing.state.value as PairingUiState.Failed).reason)
        assertTrue(await { b.pairing.state.value is PairingUiState.Idle })
        assertNull(a.trust.pairingSecret(b.deviceId))
    }

    @Test
    fun unreachablePeerFailsAsUnreachable() {
        val a = device("Alice")
        a.start()
        a.pairing.start(com.intercom.video.twoway.service.PeerAddress("e".repeat(32), "Nobody", "127.0.0.1", 1))
        assertTrue(await(8_000) { a.pairing.state.value is PairingUiState.Failed })
        assertEquals(PairingFailure.UNREACHABLE, (a.pairing.state.value as PairingUiState.Failed).reason)
    }

    @Test
    fun aSecondRequestWhileBusyIsDeclined() {
        val a = device("Alice")
        val b = device("Bob")
        val c = device("Carol")
        a.start()
        c.start()
        val addrB = b.start()
        a.pairing.start(addrB)
        assertTrue(await { b.pairing.state.value is PairingUiState.IncomingRequest })
        c.pairing.start(addrB)
        assertTrue(await { c.pairing.state.value is PairingUiState.Failed }, "c=${c.pairing.state.value}")
        assertEquals(PairingFailure.DECLINED, (c.pairing.state.value as PairingUiState.Failed).reason)
        assertTrue(b.pairing.state.value is PairingUiState.IncomingRequest, "Bob's pending request from Alice is unaffected")
    }

    @Test
    fun handshakeTimesOutAfter120Seconds() {
        val clock = FakeClock()
        val a = device("Alice", clock)
        val b = device("Bob")
        reachVerify(a, b)
        clock.advance(120_000)
        assertTrue(await(5_000) { a.pairing.state.value is PairingUiState.Failed }, "a=${a.pairing.state.value}")
        assertEquals(PairingFailure.TIMEOUT, (a.pairing.state.value as PairingUiState.Failed).reason)
        assertNull(a.trust.pairingSecret(b.deviceId))
    }

    @Test
    fun aKnownDeviceWithADifferentIdentityKeyIsRefused() {
        val a = device("Alice")
        val b = device("Bob")
        // Alice already trusts "Bob"'s id, but pinned a DIFFERENT identity key for it (e.g. an impostor reused the id).
        a.pairingStore.fingerprints[b.deviceId] = ByteArray(32) { 1 }
        a.trust.trust(b.deviceId, ByteArray(32) { 2 })
        reachVerify(a, b)
        a.pairing.confirmMatches(true)
        b.pairing.confirmMatches(true)
        assertTrue(await { a.pairing.state.value is PairingUiState.Failed }, "a=${a.pairing.state.value}")
        assertEquals(PairingFailure.IDENTITY_CHANGED, (a.pairing.state.value as PairingUiState.Failed).reason)
        assertArrayEquals(ByteArray(32) { 2 }, a.trust.pairingSecret(b.deviceId), "the old secret is untouched")
    }

    @Test
    fun scannedQrFingerprintMustMatchThePeerIdentity() {
        val a = device("Alice")
        val b = device("Bob")
        a.start()
        val addrB = b.start()
        // The QR claims a fingerprint that is NOT Bob's real identity.
        a.pairing.start(addrB, expectedFingerprint = ByteArray(16) { 9 })
        assertTrue(await { b.pairing.state.value is PairingUiState.IncomingRequest })
        b.pairing.acceptIncoming()
        assertTrue(await { verify(a) != null })
        a.pairing.confirmMatches(true)
        b.pairing.confirmMatches(true)
        assertTrue(await { a.pairing.state.value is PairingUiState.Failed }, "a=${a.pairing.state.value}")
        assertEquals(PairingFailure.QR_MISMATCH, (a.pairing.state.value as PairingUiState.Failed).reason)
        assertNull(a.trust.pairingSecret(b.deviceId))
    }

    @Test
    fun scannedQrFingerprintThatMatchesPairsNormally() {
        val a = device("Alice")
        val b = device("Bob")
        a.start()
        val addrB = b.start()
        val realFp = java.security.MessageDigest.getInstance("SHA-256").digest(b.identity.publicKey).copyOf(16)
        a.pairing.start(addrB, expectedFingerprint = realFp)
        assertTrue(await { b.pairing.state.value is PairingUiState.IncomingRequest })
        b.pairing.acceptIncoming()
        assertTrue(await { verify(a) != null })
        a.pairing.confirmMatches(true)
        b.pairing.confirmMatches(true)
        assertTrue(await { a.pairing.state.value is PairingUiState.Done && b.pairing.state.value is PairingUiState.Done })
    }

    @Test
    fun dismissReturnsToIdle() {
        val a = device("Alice")
        a.start()
        a.pairing.start(com.intercom.video.twoway.service.PeerAddress("e".repeat(32), "Nobody", "127.0.0.1", 1))
        assertTrue(await(8_000) { a.pairing.state.value is PairingUiState.Failed })
        a.pairing.dismiss()
        assertTrue(await { a.pairing.state.value is PairingUiState.Idle })
    }

    @Test
    fun rateLimiterAllowsAtMostThreeRequestsPerMinutePerSource() {
        val clock = FakeClock()
        val limiter = PairRateLimiter(clock)
        repeat(3) { assertTrue(limiter.allow("10.0.0.2")) }
        assertFalse(limiter.allow("10.0.0.2"))
        assertTrue(limiter.allow("10.0.0.3"), "other sources are independent")
        clock.advance(60_000)
        assertTrue(limiter.allow("10.0.0.2"), "window slid")
    }
}
