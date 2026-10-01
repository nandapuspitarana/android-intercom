package com.intercom.video.twoway.core.call

import com.intercom.video.twoway.core.FakeClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CallStateMachineTest {
    private lateinit var clock: FakeClock
    private lateinit var m: CallStateMachine

    @BeforeEach
    fun setUp() {
        clock = FakeClock()
        m = CallStateMachine(clock)
    }

    private fun ended(effects: List<Effect>): Effect.CallEnded? = effects.filterIsInstance<Effect.CallEnded>().firstOrNull()

    /** Caller reaches InCall. */
    private fun connectedCaller() {
        m.placeCall()
        m.rxRinging()
        m.rxAccept()
        m.mediaFlowing()
        assertEquals(CallState.InCall, m.state)
    }

    // --- happy path ---------------------------------------------------------------------------------

    @Test
    fun callerHappyPath() {
        val e1 = m.placeCall()
        assertTrue(e1.contains(Effect.SendInvite))
        assertEquals(CallState.Calling(false), m.state)

        m.rxRinging()
        assertEquals(CallState.Calling(true), m.state)

        val e3 = m.rxAccept()
        assertTrue(e3.contains(Effect.StartMedia))
        assertEquals(CallState.Connecting, m.state)

        m.mediaFlowing()
        assertEquals(CallState.InCall, m.state)

        clock.advance(65_000)
        m.rxValid()
        val e5 = m.userHangup()
        assertTrue(e5.any { it is Effect.SendHangup })
        assertTrue(e5.contains(Effect.StopMedia))
        val end = ended(e5)!!
        assertEquals(EndReason.COMPLETED, end.reason)
        assertEquals(CallRole.CALLER, end.role)
        assertEquals(65_000L, end.connectedMs)
    }

    @Test
    fun calleeHappyPath() {
        val e1 = m.incomingInvite()
        assertTrue(e1.contains(Effect.SendRinging))
        assertEquals(CallState.Ringing, m.state)

        val e2 = m.userAccept()
        assertTrue(e2.containsAll(listOf(Effect.SendAccept, Effect.StartMedia)))
        assertEquals(CallState.Connecting, m.state)

        m.mediaFlowing()
        assertEquals(CallState.InCall, m.state)

        val e4 = m.rxHangup()
        assertEquals(EndReason.COMPLETED, ended(e4)!!.reason)
        assertEquals(CallRole.CALLEE, ended(e4)!!.role)
    }

    @Test
    fun resetReturnsToIdleSoNewCallsAreAccepted() {
        connectedCaller()
        m.userHangup()
        assertTrue(m.state is CallState.Ended)
        m.reset()
        assertEquals(CallState.Idle, m.state)
        assertTrue(m.placeCall().contains(Effect.SendInvite))
    }

    // --- INVITE timers ------------------------------------------------------------------------------

    @Test
    fun inviteIsRetransmittedOnceAfter1500ms() {
        m.placeCall()
        clock.advance(1_499)
        assertTrue(m.tick().isEmpty())
        clock.advance(1)
        assertEquals(listOf<Effect>(Effect.ResendInvite), m.tick())
        clock.advance(500)
        assertTrue(m.tick().isEmpty(), "must not retransmit twice")
    }

    @Test
    fun noAnswerAt5sMeansUnavailable() {
        m.placeCall()
        clock.advance(5_000)
        val e = m.tick()
        assertEquals(EndReason.UNAVAILABLE, ended(e)!!.reason)
    }

    @Test
    fun ringingStopsTheUnavailableTimer() {
        m.placeCall()
        clock.advance(2_000)
        m.rxRinging()
        clock.advance(10_000)
        assertTrue(m.tick().isEmpty())
        assertEquals(CallState.Calling(true), m.state)
    }

    // --- connect timeout and heartbeat --------------------------------------------------------------

    @Test
    fun connectTimeoutAfter10sFails() {
        m.incomingInvite()
        m.userAccept()
        clock.advance(9_999)
        assertTrue(m.tick().isEmpty())
        clock.advance(1)
        val e = m.tick()
        assertEquals(EndReason.CONNECTION_FAILED, ended(e)!!.reason)
        assertTrue(e.any { it is Effect.SendHangup })
    }

    @Test
    fun pingEvery2sWhileInCall() {
        connectedCaller()
        clock.advance(1_999)
        m.rxValid()
        assertTrue(m.tick().isEmpty())
        clock.advance(1)
        assertEquals(listOf<Effect>(Effect.SendPing), m.tick())
        clock.advance(2_000)
        m.rxValid()
        assertEquals(listOf<Effect>(Effect.SendPing), m.tick())
    }

    @Test
    fun deadPeerAfter10sWithoutAnyValidMessage() {
        connectedCaller()
        var last: List<Effect> = emptyList()
        repeat(9) {
            clock.advance(1_000)
            last = m.tick() // pings only
        }
        assertEquals(CallState.InCall, m.state)
        clock.advance(1_000)
        last = m.tick()
        assertEquals(EndReason.CONNECTION_LOST, ended(last)!!.reason)
    }

    @Test
    fun validTrafficKeepsTheCallAlive() {
        connectedCaller()
        repeat(30) {
            clock.advance(1_000)
            m.rxValid()
            m.tick()
        }
        assertEquals(CallState.InCall, m.state)
    }

    @Test
    fun pingIsAnsweredWithPong() {
        connectedCaller()
        assertEquals(listOf<Effect>(Effect.SendPong), m.rxPing())
    }

    @Test
    fun pingWhileIdleIsIgnored() = assertTrue(m.rxPing().isEmpty())

    // --- outcomes (also covered end to end by the loopback tests) -----------------------------------

    @Test
    fun rejectBusyAndPairRequiredEndTheCallerSide() {
        m.placeCall()
        assertEquals(EndReason.DECLINED, ended(m.rxReject("declined"))!!.reason)
        m.reset()
        m.placeCall()
        assertEquals(EndReason.UNAVAILABLE, ended(m.rxReject("unavailable"))!!.reason)
        m.reset()
        m.placeCall()
        assertEquals(EndReason.BUSY, ended(m.rxBusy())!!.reason)
        m.reset()
        m.placeCall()
        assertEquals(EndReason.NOT_PAIRED, ended(m.rxPairRequired())!!.reason)
    }

    @Test
    fun userRejectSendsRejectAndEndsDeclined() {
        m.incomingInvite()
        val e = m.userReject()
        assertTrue(e.contains(Effect.SendReject("declined")))
        assertEquals(EndReason.DECLINED, ended(e)!!.reason)
    }

    @Test
    fun cancelWhileRingingIsMissedOnCalleeAndCancelledOnCaller() {
        m.incomingInvite()
        assertEquals(EndReason.MISSED, ended(m.rxCancel())!!.reason)
        m.reset()
        m.placeCall()
        val e = m.userCancel()
        assertTrue(e.contains(Effect.SendCancel))
        assertEquals(EndReason.CANCELLED, ended(e)!!.reason)
    }

    @Test
    fun ringTimeoutIs30sOnBothSides() {
        m.incomingInvite()
        clock.advance(30_000)
        assertEquals(EndReason.MISSED, ended(m.tick())!!.reason)
        m.reset()
        m.placeCall()
        m.rxRinging()
        clock.advance(30_000)
        val e = m.tick()
        assertTrue(e.contains(Effect.SendCancel))
        assertEquals(EndReason.CANCELLED, ended(e)!!.reason)
    }

    @Test
    fun inputsInWrongStateAreIgnored() {
        assertTrue(m.userAccept().isEmpty())
        assertTrue(m.rxAccept().isEmpty())
        assertTrue(m.rxHangup().isEmpty())
        assertTrue(m.mediaFlowing().isEmpty())
        m.placeCall()
        assertTrue(m.placeCall().isEmpty(), "second call while calling is ignored")
        assertTrue(m.incomingInvite().isEmpty(), "engine answers BUSY itself; machine stays put")
        assertEquals(CallState.Calling(false), m.state)
    }
}
