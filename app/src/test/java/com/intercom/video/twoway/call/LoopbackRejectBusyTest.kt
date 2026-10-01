package com.intercom.video.twoway.call

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.PeerAddress
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** US3 over real localhost sockets: reject, no answer, cancel, busy and glare (simultaneous calls). */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LoopbackRejectBusyTest {
    private val devices = mutableListOf<TestDevice>()

    private fun device(name: String, id: String? = null, clock: FakeClock? = null): TestDevice = TestDevice(
        name,
        deviceId = id ?: "%032x".format(java.util.Random().nextLong() and Long.MAX_VALUE),
        clock =
        clock ?: com.intercom.video.twoway.core.SystemClock,
    )
        .also { devices += it }

    @AfterEach
    fun tearDown() = devices.forEach { it.stop() }

    private fun ended(d: TestDevice) = d.state as? CallUiState.Ended

    @Test
    fun rejectEndsBothSidesAsDeclinedAndIsRecorded() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.reject()
        assertTrue(await { ended(a) != null && ended(b) != null }, "a=${a.state} b=${b.state}")
        assertEquals(EndReason.DECLINED, ended(a)!!.reason)
        assertEquals(EndReason.DECLINED, ended(b)!!.reason)
        assertTrue(await { a.records.size == 1 && b.records.size == 1 })
        assertEquals(EndReason.DECLINED, a.records.single().reason)
        assertEquals(EndReason.DECLINED, b.records.single().reason)
    }

    @Test
    fun callerCancellingWhileRingingMakesItAMissedCallOnTheCallee() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        assertTrue(await { (a.state as? CallUiState.Calling)?.ringing == true }, "a=${a.state}")
        a.engine.hangup() // cancel
        assertTrue(await { ended(a) != null && ended(b) != null }, "a=${a.state} b=${b.state}")
        assertEquals(EndReason.CANCELLED, ended(a)!!.reason)
        assertEquals(EndReason.MISSED, ended(b)!!.reason)
        assertTrue(await { b.records.size == 1 })
        assertTrue(!b.records.single().outgoing)
    }

    @Test
    fun cancellingBeforeTheCalleeHasRungStillStopsTheRinging() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        a.engine.hangup() // immediately: likely before RINGING, so no keys exist yet
        assertTrue(await { ended(a) != null }, "a=${a.state}")
        assertTrue(await { b.state is CallUiState.Idle || b.state is CallUiState.Ended }, "b=${b.state}")
        assertTrue(!(b.state is CallUiState.Incoming), "Bob must not keep ringing")
    }

    @Test
    fun noAnswerFor30SecondsMakesItMissedOnTheCalleeAndCancelledOnTheCaller() {
        // Callee's clock is fake: advancing it 30 s triggers its ring timeout.
        val a = device("Alice")
        val bClock = FakeClock()
        val b = device("Bob", clock = bClock)
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        bClock.advance(30_000)
        assertTrue(await { ended(b) != null }, "b=${b.state}")
        assertEquals(EndReason.MISSED, ended(b)!!.reason)

        // Caller side: its own fake clock drives its 30 s ring timeout and sends a CANCEL.
        val aClock = FakeClock()
        val a2 = device("Alice2", clock = aClock)
        val b2 = device("Bob2")
        pair(a2, b2)
        a2.start()
        val addrB2 = b2.start()
        a2.engine.call(addrB2)
        assertTrue(await { b2.state is CallUiState.Incoming })
        assertTrue(await { (a2.state as? CallUiState.Calling)?.ringing == true })
        aClock.advance(30_000)
        assertTrue(await { ended(a2) != null && ended(b2) != null }, "a2=${a2.state} b2=${b2.state}")
        assertEquals(EndReason.CANCELLED, ended(a2)!!.reason)
        assertEquals(EndReason.MISSED, ended(b2)!!.reason)
    }

    @Test
    fun aSecondCallerGetsBusyAndTheCallInProgressIsUntouched() {
        val a = device("Alice")
        val b = device("Bob")
        val c = device("Carol")
        pair(a, b)
        pair(c, b)
        a.start()
        val addrB = b.start()
        c.start()

        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
        assertTrue(await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 })

        val started = System.currentTimeMillis()
        c.engine.call(addrB)
        assertTrue(await(5_000) { ended(c) != null }, "c=${c.state}")
        assertEquals(EndReason.BUSY, ended(c)!!.reason)
        assertTrue(System.currentTimeMillis() - started < 5_000, "busy must arrive within 5 s")

        // The first call carries on: still connected, and audio keeps flowing.
        assertTrue(a.state is CallUiState.InCall && b.state is CallUiState.InCall)
        val before = a.audio.totalLoudFrames
        assertTrue(await { a.audio.totalLoudFrames > before + 5 }, "audio still flowing")
    }

    @Test
    fun simultaneousCallsResultInExactlyOneCall_theLowerDeviceIdKeepsItsCall() {
        val low = device("Low", id = "0".repeat(31) + "1")
        val high = device("High", id = "f".repeat(31) + "e")
        pair(low, high)
        val addrLow = low.start()
        val addrHigh = high.start()

        low.engine.call(addrHigh)
        high.engine.call(addrLow)

        // The higher device gives up its own call and rings for the lower device's call.
        assertTrue(await { high.state is CallUiState.Incoming }, "low=${low.state} high=${high.state}")
        high.engine.accept()
        assertTrue(await { low.state is CallUiState.InCall && high.state is CallUiState.InCall }, "low=${low.state} high=${high.state}")
        assertTrue(await { low.audio.totalLoudFrames > 5 && high.audio.totalLoudFrames > 5 })

        // Exactly one call: it is the one the lower id placed. The higher id's own attempt was cancelled.
        assertTrue(await { high.records.size == 1 })
        assertTrue(high.records.single().outgoing && high.records.single().reason == EndReason.CANCELLED)
        assertEquals(0, low.records.size, "the lower device's call is still running")
        low.engine.hangup()
        assertTrue(await { ended(low) != null && ended(high) != null })
    }

    @Test
    fun callingAPeerWhoseSecretWasRemovedAfterwardsFails() {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.trust.revoke(b.deviceId)
        a.engine.call(PeerAddress(b.deviceId, "Bob", addrB.host, addrB.port))
        assertTrue(await { ended(a) != null })
        assertEquals(EndReason.NOT_PAIRED, ended(a)!!.reason)
    }
}
