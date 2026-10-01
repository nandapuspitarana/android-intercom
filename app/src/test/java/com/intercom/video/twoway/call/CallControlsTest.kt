package com.intercom.video.twoway.call

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.core.media.AudioRoute
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.FlakySocketProvider
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** US6: mute, audio route, microphone indicator, poor connection and connection loss, over real sockets. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CallControlsTest {
    private val devices = mutableListOf<TestDevice>()

    private fun device(name: String, clock: FakeClock? = null, flaky: FlakySocketProvider? = null): TestDevice = TestDevice(
        name,
        clock = clock ?: com.intercom.video.twoway.core.SystemClock,
        sockets = flaky ?: com.intercom.video.twoway.net.transport.PlainSocketProvider,
    ).also { devices += it }

    @AfterEach
    fun tearDown() = devices.forEach { it.stop() }

    private fun inCall(d: TestDevice) = d.state as? CallUiState.InCall

    private fun connect(a: TestDevice, b: TestDevice) {
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.accept()
        assertTrue(await { inCall(a) != null && inCall(b) != null }, "a=${a.state} b=${b.state}")
        assertTrue(await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 })
    }

    // --- mute ----------------------------------------------------------------------------------------------------------

    @Test
    fun muteStopsVoiceSendsKeepAlivesAndTheOtherSideSeesTheIndicator() {
        val a = device("Alice")
        val b = device("Bob")
        connect(a, b)
        assertTrue(inCall(a)!!.micLive, "microphone indicator while unmuted")
        assertFalse(inCall(a)!!.muted)

        a.engine.setMuted(true)
        assertTrue(await { inCall(a)?.muted == true }, "a=${a.state}")
        assertTrue(await { inCall(b)?.peerMuted == true }, "the other side is told: ${b.state}")
        assertFalse(inCall(a)!!.micLive, "no microphone indicator while muted")

        // Bob hears nothing new from Alice, but keep-alives arrive about once a second.
        val mediaB = b.engine.currentMedia!!
        Thread.sleep(300) // let in-flight voice drain
        val loudBefore = b.audio.totalLoudFrames
        val voiceBefore = mediaB.voiceReceived.get()
        val aliveBefore = mediaB.keepAlivesReceived.get()
        assertTrue(await(3_000) { mediaB.keepAlivesReceived.get() > aliveBefore }, "keep-alive packets reach the peer")
        assertEquals(voiceBefore, mediaB.voiceReceived.get(), "no voice packets while muted")
        assertTrue(b.audio.totalLoudFrames <= loudBefore + 3, "Bob's speaker stays quiet")

        a.engine.setMuted(false)
        assertTrue(await { inCall(a)?.muted == false && inCall(b)?.peerMuted == false }, "a=${a.state} b=${b.state}")
        assertTrue(await { mediaB.voiceReceived.get() > voiceBefore }, "voice resumes")
        assertTrue(await { inCall(a)?.micLive == true })
    }

    @Test
    fun mutingBeforeTheOtherSideAnswersIsHarmlessAndTheCallStillConnects() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        a.engine.setMuted(true) // before the keys exist: nothing is sent, the flag is simply kept
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.accept()
        assertTrue(await { inCall(a) != null && inCall(b) != null })
        assertTrue(inCall(a)!!.muted)
        assertFalse(inCall(a)!!.micLive)
    }

    // --- audio route -------------------------------------------------------------------------------------------------------

    @Test
    fun theAudioRouteCanBeSwitchedAndOnlyAvailableRoutesAreAccepted() {
        val a = device("Alice")
        val b = device("Bob")
        connect(a, b)
        assertEquals(AudioRoute.EARPIECE, inCall(a)!!.route)
        assertEquals(setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER), inCall(a)!!.availableRoutes)

        a.engine.setRoute(AudioRoute.SPEAKER)
        assertTrue(await { inCall(a)?.route == AudioRoute.SPEAKER })
        assertEquals(AudioRoute.SPEAKER, a.audio.route)

        a.engine.setRoute(AudioRoute.BLUETOOTH) // no headset connected
        Thread.sleep(300)
        assertEquals(AudioRoute.SPEAKER, inCall(a)!!.route, "unavailable route is ignored")

        a.audio.routes = a.audio.routes + AudioRoute.BLUETOOTH // headset connects
        a.engine.setRoute(AudioRoute.BLUETOOTH)
        assertTrue(await { inCall(a)?.route == AudioRoute.BLUETOOTH })
    }

    // --- connection loss ---------------------------------------------------------------------------------------------------------

    @Test
    fun threeSecondsWithoutMediaShowsPoorConnectionButTheCallContinuesAndRecovers() {
        val clock = FakeClock()
        val flaky = FlakySocketProvider()
        val a = device("Alice", clock, flaky)
        val b = device("Bob")
        connect(a, b)
        assertFalse(inCall(a)!!.poorConnection)

        flaky.dropping = true // WiFi stalls
        clock.advance(3_500)
        assertTrue(await { inCall(a)?.poorConnection == true }, "poor connection after 3 s: ${a.state}")
        assertTrue(a.state is CallUiState.InCall, "the call is not ended")

        flaky.dropping = false // WiFi comes back
        assertTrue(await { inCall(a)?.poorConnection == false }, "recovers: ${a.state}")
        assertTrue(a.state is CallUiState.InCall)
    }

    @Test
    fun noValidMessageFor10SecondsEndsTheCallAsConnectionLost() {
        val clock = FakeClock()
        val flaky = FlakySocketProvider()
        val a = device("Alice", clock, flaky)
        val b = device("Bob")
        connect(a, b)

        flaky.dropping = true
        clock.advance(10_500)
        assertTrue(await { a.state is CallUiState.Ended }, "a=${a.state}")
        assertEquals(EndReason.CONNECTION_LOST, (a.state as CallUiState.Ended).reason)
        assertTrue(await { a.records.size == 1 })
        assertEquals(EndReason.CONNECTION_LOST, a.records.single().reason)
        assertTrue(await { !a.audio.inCallMode }, "audio is released")
    }

    @Test
    fun aStallShorterThan10SecondsDoesNotEndTheCall() {
        val clock = FakeClock()
        val flaky = FlakySocketProvider()
        val a = device("Alice", clock, flaky)
        val b = device("Bob")
        connect(a, b)

        flaky.dropping = true
        clock.advance(8_000)
        Thread.sleep(300)
        assertTrue(a.state is CallUiState.InCall)
        flaky.dropping = false
        assertTrue(await { inCall(a)?.poorConnection == false })
        clock.advance(5_000) // traffic is flowing again, so this does not count as silence
        Thread.sleep(500)
        assertTrue(a.state is CallUiState.InCall, "a=${a.state}")
    }

    @Test
    fun theOtherSideNoticesWhenTheCallerLosesTheNetworkAndEndsToo() {
        val flaky = FlakySocketProvider()
        val a = device("Alice", flaky = flaky)
        val bClock = FakeClock()
        val b = device("Bob", bClock)
        connect(a, b)

        flaky.dropping = true // everything Alice sends or receives is lost
        bClock.advance(10_500) // Bob (fake clock) has heard nothing for over 10 s
        assertTrue(await { b.state is CallUiState.Ended }, "b=${b.state}")
        assertEquals(EndReason.CONNECTION_LOST, (b.state as CallUiState.Ended).reason)
    }
}
