package com.intercom.video.twoway.functional

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.RecordingSocketProvider
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Random

/**
 * FR-016 / FR-017 / SC-010 against the real app during a real call: junk bytes, a 10 MB frame, junk datagrams and a REPLAY
 * of the call's own recorded (encrypted) signaling frames, sent from "the network", must neither end nor disturb the call.
 */
@FunctionalTest
@RunWith(AndroidJUnit4::class)
class SecurityFunctionalTest {
    private val recorder = RecordingSocketProvider()
    private val setup = FunctionalTestRule(sockets = recorder)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    @Before
    fun callInProgress() {
        setup.launch()
        compose.waitForTag("devices_empty")
        setup.discoverPeer()
        compose.waitForTag("call_${setup.peer.deviceId}")
        compose.onNodeWithTag("call_${setup.peer.deviceId}").performClick()
        compose.waitForTag("end_call")
        assertTrue(await { setup.container.audio.totalLoudFrames > 5 && setup.peer.audio.totalLoudFrames > 5 })
    }

    private fun assertCallUndisturbed() {
        Thread.sleep(600)
        assertTrue(
            "the call is still up: ${setup.container.runtimeOrNull!!.engine.state.value}",
            setup.container.runtimeOrNull!!.engine.state.value is CallUiState.InCall,
        )
        assertTrue("the peer's call too: ${setup.peer.state}", setup.peer.state is CallUiState.InCall)
        compose.onNodeWithTag("end_call").assertExists() // still on the call screen
        val before = setup.container.audio.totalLoudFrames
        assertTrue("audio keeps flowing", await { setup.container.audio.totalLoudFrames > before + 5 })
    }

    private fun send(port: Int, bytes: ByteArray) {
        try {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write(bytes)
                s.getOutputStream().flush()
                Thread.sleep(30)
            }
        } catch (_: java.io.IOException) {
            // refused or reset: the app closed the connection, which is the right answer
        }
    }

    @Test
    fun junkAndAHugeFrameOnTheSignalingPortLeaveTheCallAlone() {
        val appPort = setup.container.runtimeOrNull!!.engine.signalingPort
        val rnd = Random(11)
        repeat(20) { send(appPort, ByteArray(rnd.nextInt(1500) + 1).also { rnd.nextBytes(it) }) }
        send(appPort, byteArrayOf(0, -96, 0, 0) + ByteArray(70_000) { 5 }) // announces a 10 MB frame
        send(appPort, byteArrayOf(0, 0, 0, 2, '{'.code.toByte(), '}'.code.toByte())) // well framed, hostile JSON
        assertCallUndisturbed()
    }

    @Test
    fun replayingTheCallsOwnRecordedSignalingToBothPhonesIsRefused() {
        // Everything the app sent to the peer so far: the plaintext INVITE and the encrypted follow-ups (PING/ACCEPT...).
        val recorded = recorder.capturedStreams().filter { it.isNotEmpty() }
        assertTrue("some signaling was recorded", recorded.isNotEmpty())
        val peerPort = setup.peer.address.port
        val appPort = setup.container.runtimeOrNull!!.engine.signalingPort
        repeat(3) {
            recorded.forEach { bytes ->
                send(peerPort, bytes) // a different connection than the call's: the peer must not act on it
                send(appPort, bytes)
            }
        }
        assertCallUndisturbed()
        assertTrue("no second ring: the replayed INVITE is not accepted", !setup.container.ringer.isRinging)
    }

    @Test
    fun junkDatagramsOnTheMediaPortLeaveTheCallAlone() {
        val media = setup.container.runtimeOrNull!!.engine.currentMedia ?: error("no media session")
        val field = media.javaClass.getDeclaredField("socket").apply { isAccessible = true }
        val udp = field.get(media) as com.intercom.video.twoway.net.transport.UdpMediaSocket
        val rnd = Random(5)
        DatagramSocket().use { s ->
            val dst = InetAddress.getByName("127.0.0.1")
            repeat(1_500) {
                val data = ByteArray(rnd.nextInt(1_300) + 1).also { rnd.nextBytes(it) }
                s.send(DatagramPacket(data, data.size, dst, udp.localPort))
            }
        }
        assertCallUndisturbed()
    }

    @Test
    fun aStrangerWhoKnowsNoSecretCannotRingThePhoneDuringOrAfterTheCall() {
        compose.onNodeWithTag("end_call").performClick()
        compose.waitForTag("ended_message")
        compose.onNodeWithTag("ended_ok").performClick()
        compose.waitForTag("call_${setup.peer.deviceId}")

        val stranger = FakePeerDevice("Stranger").also { it.start() }
        try {
            stranger.device.trust.trust(setup.container.self.deviceId, ByteArray(32) { 99 }) // believes in a secret the phone never agreed to
            stranger.callApp(setup.appAddress())
            assertTrue(await { stranger.device.records.isNotEmpty() })
            assertTrue("never rang", !setup.container.ringer.isRinging)
            assertTrue(setup.container.runtimeOrNull!!.engine.state.value is CallUiState.Idle)
        } finally {
            stranger.stop()
        }
    }
}
