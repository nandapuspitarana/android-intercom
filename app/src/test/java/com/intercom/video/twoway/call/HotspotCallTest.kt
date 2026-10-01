package com.intercom.video.twoway.call

import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PeerAddress
import com.intercom.video.twoway.testing.IsolatedSocketProvider
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * US5 end to end on localhost: a hotspot phone (hub) and two client phones. "Client isolation" is simulated by making
 * a client's direct connections to the other client fail, so the call must go through the hub.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HotspotCallTest {
    private val devices = mutableListOf<TestDevice>()
    private val isolation = IsolatedSocketProvider()

    private lateinit var hub: TestDevice
    private lateinit var hubAddr: PeerAddress
    private lateinit var a: TestDevice
    private lateinit var b: TestDevice
    private lateinit var addrB: PeerAddress

    private fun device(
        name: String,
        sockets: com.intercom.video.twoway.net.transport.SocketProvider = com.intercom.video.twoway.net.transport.PlainSocketProvider,
    ) = TestDevice(name, sockets = sockets).also { devices += it }

    @AfterEach
    fun tearDown() = devices.forEach { it.stop() }

    /** Hub + two clients that both joined it; A cannot reach B directly. */
    private fun setUp(bJoinsHub: Boolean = true) {
        hub = device("Hotspot Phone")
        hubAddr = hub.start()
        hub.becomeHub()
        a = device("Alice", isolation)
        b = device("Bob")
        pair(a, b)
        a.start()
        addrB = b.start()
        isolation.block("127.0.0.1", addrB.port) // the access point drops phone-to-phone traffic
        assertTrue(a.joinHub(hubAddr))
        if (bJoinsHub) assertTrue(b.joinHub(hubAddr))
        assertTrue(await { hub.hubRegistry.clientCount == (if (bJoinsHub) 2 else 1) })
    }

    @Test
    fun aCallWhoseDirectPathIsBlockedGoesThroughTheHubWithTwoWayAudio() {
        setUp()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming }, "Bob rings via the hub; a=${a.state} b=${b.state}")
        assertTrue(isolation.blockedConnects >= 1, "the direct attempt really was blocked")
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall }, "a=${a.state} b=${b.state}")
        assertEquals(1, hub.hubRegistry.relayCount, "the call is relayed by the hub")
        assertTrue(
            await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 },
            "voice flows both ways through the hub's UDP relay: a=${a.audio.totalLoudFrames} b=${b.audio.totalLoudFrames}",
        )

        a.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended }, "a=${a.state} b=${b.state}")
        assertEquals(EndReason.COMPLETED, (a.state as CallUiState.Ended).reason)
        assertEquals(EndReason.COMPLETED, (b.state as CallUiState.Ended).reason)
        assertTrue(await { hub.hubRegistry.relayCount == 0 }, "the hub drops the relay when the call ends")
    }

    @Test
    fun theCalleeCanEndARelayedCallAndANewCallWorksAfterwards() {
        setUp()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
        b.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended })
        assertEquals(EndReason.COMPLETED, (a.state as CallUiState.Ended).reason)
        a.engine.dismissEnded()
        b.engine.dismissEnded()
        assertTrue(await { hub.hubRegistry.relayCount == 0 })

        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming }, "second relayed call rings: ${b.state}")
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
    }

    @Test
    fun rejectAndCancelWorkOverTheRelay() {
        setUp()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.reject()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended })
        assertEquals(EndReason.DECLINED, (a.state as CallUiState.Ended).reason)
        a.engine.dismissEnded()
        b.engine.dismissEnded()

        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        assertTrue(await { (a.state as? CallUiState.Calling)?.ringing == true })
        a.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended && b.state is CallUiState.Ended })
        assertEquals(EndReason.CANCELLED, (a.state as CallUiState.Ended).reason)
        assertEquals(EndReason.MISSED, (b.state as CallUiState.Ended).reason)
    }

    @Test
    fun whenNeitherADirectPathNorTheRelayWorksTheCallerIsToldTheNetworkBlocksIt() {
        setUp(bJoinsHub = false) // the hub does not know Bob, so it cannot relay to him
        val started = System.currentTimeMillis()
        a.engine.call(addrB)
        assertTrue(await(8_000) { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.NETWORK_BLOCKED, (a.state as CallUiState.Ended).reason)
        assertTrue(System.currentTimeMillis() - started < 6_000, "fails well inside the 10 s connect timeout")
        assertTrue(b.state is CallUiState.Idle)
    }

    @Test
    fun withoutAHubAnUnreachablePhoneIsJustUnavailable() {
        hub = device("Unused")
        a = device("Alice", isolation)
        b = device("Bob")
        pair(a, b)
        a.start()
        addrB = b.start()
        isolation.block("127.0.0.1", addrB.port)
        a.engine.call(addrB) // a never joined a hub: no relay is possible
        assertTrue(await(8_000) { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.UNAVAILABLE, (a.state as CallUiState.Ended).reason)
    }

    @Test
    fun aDirectPathIsPreferredWhenItWorks() {
        setUp()
        isolation.unblockAll() // no isolation on this hotspot
        val before = hub.hubRegistry.relayCount
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
        assertEquals(before, hub.hubRegistry.relayCount, "no relay is used when the phones can talk directly")
        assertTrue(await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 })
    }

    @Test
    fun theHubItselfCanBeCalledDirectlyAndCanCallClients() {
        setUp()
        pair(a, hub)
        pair(hub, b)
        a.engine.call(hubAddr)
        assertTrue(await { hub.state is CallUiState.Incoming }, "hub=${hub.state}")
        hub.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && hub.state is CallUiState.InCall })
        hub.engine.hangup()
        assertTrue(await { a.state is CallUiState.Ended })

        hub.engine.dismissEnded()
        hub.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
    }
}
