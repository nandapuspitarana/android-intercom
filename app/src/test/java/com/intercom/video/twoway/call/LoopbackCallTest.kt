package com.intercom.video.twoway.call

import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PeerAddress
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Two real CallEngines talking over real localhost TCP/UDP sockets with the real Opus codec. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LoopbackCallTest {
    private lateinit var a: TestDevice
    private lateinit var b: TestDevice
    private lateinit var addrB: PeerAddress

    @BeforeEach
    fun setUp() {
        a = TestDevice("Alice")
        b = TestDevice("Bob")
        pair(a, b)
        a.start()
        addrB = b.start()
    }

    @AfterEach
    fun tearDown() {
        a.stop()
        b.stop()
    }

    private fun connect() {
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming }, "Bob should ring; was ${b.state}")
        b.engine.accept()
        val start = System.currentTimeMillis()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall }, "a=${a.state} b=${b.state}")
        assertTrue(System.currentTimeMillis() - start < 10_000, "call must connect within 10 s of accept")
    }

    @Test
    fun fullCallWithTwoWayAudioAndHangup() {
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming }, "Bob should ring; was ${b.state}")
        val ringing = b.state as CallUiState.Incoming
        assertEquals("Alice", ringing.peerName)
        assertEquals(a.deviceId, ringing.peerId)
        assertTrue(await { (a.state as? CallUiState.Calling)?.ringing == true }, "Alice should see ringing; was ${a.state}")

        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall }, "a=${a.state} b=${b.state}")

        // Both directions carry audible audio at the same time (the 440 Hz tone survives Opus + crypto + UDP).
        assertTrue(await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 }, "no audio: a=${a.audio.totalLoudFrames} b=${b.audio.totalLoudFrames}")
        assertTrue(a.audio.inCallMode && b.audio.inCallMode)

        a.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended }, "a=${a.state} b=${b.state}")
        assertEquals(EndReason.COMPLETED, (a.state as CallUiState.Ended).reason)
        assertEquals(EndReason.COMPLETED, (b.state as CallUiState.Ended).reason)
        assertTrue(await { !a.audio.inCallMode && !b.audio.inCallMode }, "audio mode must be restored")
        assertTrue(await { a.records.size == 1 && b.records.size == 1 })
        assertTrue(a.records.single().outgoing)
        assertTrue(!b.records.single().outgoing)
    }

    @Test
    fun calleeCanEndTheCallToo() {
        connect()
        b.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended }, "a=${a.state} b=${b.state}")
    }

    @Test
    fun endedCallsAllowANewCallRightAway() {
        connect()
        a.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended })
        a.engine.dismissEnded()
        b.engine.dismissEnded()
        assertTrue(await { a.state is CallUiState.Idle && b.state is CallUiState.Idle })
        connect()
        assertTrue(a.audio.sources.size >= 2, "a second call builds a fresh audio pipeline")
    }

    @Test
    fun callToAnUnpairedDeviceNeverRings() {
        val stranger = TestDevice("Mallory")
        a.trust.trust(stranger.deviceId, ByteArray(32) { 9 }) // A thinks it is paired; Mallory does not know A
        val addr = stranger.start()
        try {
            a.engine.call(addr)
            assertTrue(await { a.state is CallUiState.Ended }, "a=${a.state}")
            assertEquals(EndReason.NOT_PAIRED, (a.state as CallUiState.Ended).reason)
            assertTrue(stranger.state is CallUiState.Idle, "the stranger's phone must not ring")
        } finally {
            stranger.stop()
        }
    }

    @Test
    fun callingADeviceWeNeverPairedWithIsRefusedLocally() {
        val unknown = PeerAddress("f".repeat(32), "Nobody", "127.0.0.1", 1)
        a.engine.call(unknown)
        assertTrue(await { a.state is CallUiState.Ended })
        assertEquals(EndReason.NOT_PAIRED, (a.state as CallUiState.Ended).reason)
    }

    @Test
    fun unreachablePeerEndsAsUnavailable() {
        val dead = PeerAddress(b.deviceId, "Bob", "127.0.0.1", 1) // nothing listens on port 1
        a.engine.call(dead)
        assertTrue(await(8_000) { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.UNAVAILABLE, (a.state as CallUiState.Ended).reason)
    }

    @Test
    fun wrongSecretOnTheCalleeSideIsRefused() {
        b.trust.trust(a.deviceId, ByteArray(32) { 77 }) // different secret than Alice has
        a.engine.call(addrB)
        assertTrue(await { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.NOT_PAIRED, (a.state as CallUiState.Ended).reason)
        assertTrue(b.state is CallUiState.Idle, "Bob must not ring for a caller with the wrong secret")
    }

    @Test
    fun aPhoneThatIsNotAcceptingCallsAnswersUnavailableInsteadOfRinging() {
        b.engine.acceptingCalls = false
        a.engine.call(addrB)
        assertTrue(await { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.UNAVAILABLE, (a.state as CallUiState.Ended).reason)
        assertTrue(b.state is CallUiState.Idle, "Bob must not ring")
        // ...and when it accepts calls again, the very next call rings.
        a.engine.dismissEnded()
        b.engine.acceptingCalls = true
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming }, "b=${b.state}")
    }

    @Test
    fun staleInviteTimestampIsRefused() {
        val old = TestDevice("Old", wallClock = { System.currentTimeMillis() - 120_000 })
        pair(old, b)
        old.start()
        try {
            old.engine.call(addrB)
            assertTrue(await { old.state is CallUiState.Ended }, "state=${old.state}")
            assertEquals(EndReason.NOT_PAIRED, (old.state as CallUiState.Ended).reason)
            assertTrue(b.state is CallUiState.Idle)
        } finally {
            old.stop()
        }
    }
}
