package com.intercom.video.twoway.core.call

import com.intercom.video.twoway.core.Clock

enum class CallRole { CALLER, CALLEE }

/** Why a call ended; mapped to history outcomes and user-facing messages by upper layers. */
enum class EndReason {
    COMPLETED,
    DECLINED,
    BUSY,
    CANCELLED,
    MISSED,
    UNAVAILABLE,
    CONNECTION_FAILED,
    CONNECTION_LOST,
    NOT_PAIRED,

    /** Neither a direct connection nor the hotspot relay could reach the peer (client isolation, FR-021). */
    NETWORK_BLOCKED,
}

sealed interface CallState {
    data object Idle : CallState

    /** Caller side. [remoteRinging] becomes true when the callee's RINGING arrives. */
    data class Calling(val remoteRinging: Boolean) : CallState

    /** Callee side: alerting the user. */
    data object Ringing : CallState

    /** Accepted; waiting for the first valid media packet. */
    data object Connecting : CallState

    data object InCall : CallState

    data class Ended(val reason: EndReason) : CallState
}

/** Things the engine must do as a result of an event or timer. Interpreted by the CallEngine. */
sealed interface Effect {
    data object SendInvite : Effect
    data object ResendInvite : Effect
    data object SendRinging : Effect
    data object SendAccept : Effect
    data class SendReject(val reason: String) : Effect
    data object SendCancel : Effect
    data class SendHangup(val reason: String) : Effect
    data object SendPing : Effect
    data object SendPong : Effect
    data object StartMedia : Effect
    data object StopMedia : Effect
    data class StateChanged(val state: CallState) : Effect
    data class CallEnded(val reason: EndReason, val role: CallRole, val connectedMs: Long) : Effect
}

/**
 * Pure call state machine for ONE call (the engine answers BUSY itself for a second INVITE).
 * It has no threads or sockets: every input returns the [Effect]s to perform, and timers are
 * evaluated by [tick] against an injected [Clock], so every path is testable with a fake clock.
 *
 * Timers (contracts/signaling.md): INVITE retransmit once at 1.5 s and "unavailable" at 5 s when
 * nothing answers; ring timeout 30 s; connect timeout 10 s; PING every 2 s; dead peer after 10 s.
 */
class CallStateMachine(private val clock: Clock) {
    var state: CallState = CallState.Idle
        private set
    var role: CallRole? = null
        private set

    private var startedAt = 0L
    private var connectedAt = NOT_CONNECTED
    private var lastPingAt = 0L
    private var lastValidAt = 0L
    private var inviteResent = false

    val isIdle: Boolean get() = state is CallState.Idle || state is CallState.Ended

    // --- user / engine inputs ---------------------------------------------------------------------

    fun placeCall(): List<Effect> {
        if (!isIdle) return emptyList()
        role = CallRole.CALLER
        startedAt = clock.nowMs()
        inviteResent = false
        return listOf(Effect.SendInvite) + move(CallState.Calling(remoteRinging = false))
    }

    /** A verified INVITE arrived while idle: ring the user. */
    fun incomingInvite(): List<Effect> {
        if (!isIdle) return emptyList()
        role = CallRole.CALLEE
        startedAt = clock.nowMs()
        return listOf(Effect.SendRinging) + move(CallState.Ringing)
    }

    fun userAccept(): List<Effect> {
        if (state != CallState.Ringing) return emptyList()
        startedAt = clock.nowMs()
        return listOf(Effect.SendAccept, Effect.StartMedia) + move(CallState.Connecting)
    }

    fun userReject(): List<Effect> {
        if (state != CallState.Ringing) return emptyList()
        return listOf<Effect>(Effect.SendReject("declined")) + end(EndReason.DECLINED)
    }

    /** Caller gives up while ringing. */
    fun userCancel(): List<Effect> {
        if (state !is CallState.Calling) return emptyList()
        return listOf<Effect>(Effect.SendCancel) + end(EndReason.CANCELLED)
    }

    /** Either side ends an established (or connecting) call. */
    fun userHangup(): List<Effect> = when (state) {
        is CallState.Connecting, is CallState.InCall ->
            listOf<Effect>(Effect.SendHangup("user")) + end(EndReason.COMPLETED)
        is CallState.Calling -> userCancel()
        is CallState.Ringing -> userReject()
        else -> emptyList()
    }

    // --- network inputs -----------------------------------------------------------------------------

    fun rxRinging(): List<Effect> {
        val s = state as? CallState.Calling ?: return emptyList()
        if (s.remoteRinging) return emptyList()
        touch()
        return move(CallState.Calling(remoteRinging = true))
    }

    fun rxAccept(): List<Effect> {
        if (state !is CallState.Calling) return emptyList()
        startedAt = clock.nowMs()
        touch()
        return listOf(Effect.StartMedia) + move(CallState.Connecting)
    }

    fun rxReject(reason: String): List<Effect> {
        if (state !is CallState.Calling) return emptyList()
        val why = if (reason == "declined") EndReason.DECLINED else EndReason.UNAVAILABLE
        return end(why)
    }

    /** The engine could not reach the peer at all while we were calling ([reason] says why). */
    fun failLocally(reason: EndReason): List<Effect> = if (state is CallState.Calling) end(reason) else emptyList()

    fun rxBusy(): List<Effect> = if (state is CallState.Calling) end(EndReason.BUSY) else emptyList()

    fun rxPairRequired(): List<Effect> = if (state is CallState.Calling) end(EndReason.NOT_PAIRED) else emptyList()

    /** Caller cancelled while we were ringing. */
    fun rxCancel(): List<Effect> = if (state == CallState.Ringing) end(EndReason.MISSED) else emptyList()

    fun rxHangup(): List<Effect> = when (state) {
        is CallState.Connecting, is CallState.InCall -> end(EndReason.COMPLETED)
        else -> emptyList()
    }

    fun rxPing(): List<Effect> {
        if (state !is CallState.InCall && state !is CallState.Connecting) return emptyList()
        touch()
        return listOf(Effect.SendPong)
    }

    /** Any valid signaling or media packet from the peer proves it is alive. */
    fun rxValid() {
        if (state is CallState.InCall || state is CallState.Connecting) touch()
    }

    /** First valid media packet received: the path works in at least one direction. */
    fun mediaFlowing(): List<Effect> {
        if (state != CallState.Connecting) return emptyList()
        connectedAt = clock.nowMs()
        touch()
        lastPingAt = clock.nowMs()
        return move(CallState.InCall)
    }

    /** Evaluate timers. Call every ~100 ms. */
    fun tick(): List<Effect> {
        val now = clock.nowMs()
        return when (val s = state) {
            is CallState.Calling -> {
                val age = now - startedAt
                when {
                    s.remoteRinging && age >= RING_TIMEOUT_MS ->
                        listOf<Effect>(Effect.SendCancel) + end(EndReason.CANCELLED)
                    !s.remoteRinging && age >= UNAVAILABLE_MS ->
                        listOf<Effect>(Effect.SendCancel) + end(EndReason.UNAVAILABLE)
                    !s.remoteRinging && !inviteResent && age >= RETRANSMIT_MS -> {
                        inviteResent = true
                        listOf(Effect.ResendInvite)
                    }
                    else -> emptyList()
                }
            }
            CallState.Ringing ->
                if (now - startedAt >= RING_TIMEOUT_MS) end(EndReason.MISSED) else emptyList()
            CallState.Connecting ->
                if (now - startedAt >= CONNECT_TIMEOUT_MS) {
                    listOf<Effect>(Effect.SendHangup("timeout")) + end(EndReason.CONNECTION_FAILED)
                } else {
                    emptyList()
                }
            CallState.InCall -> when {
                now - lastValidAt >= DEAD_PEER_MS ->
                    listOf<Effect>(Effect.SendHangup("lost")) + end(EndReason.CONNECTION_LOST)
                now - lastPingAt >= PING_INTERVAL_MS -> {
                    lastPingAt = now
                    listOf(Effect.SendPing)
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    /** The engine calls this after it has cleaned up an ended call so new calls can be received. */
    fun reset() {
        state = CallState.Idle
        role = null
    }

    // --- internals ----------------------------------------------------------------------------------

    private fun touch() {
        lastValidAt = clock.nowMs()
    }

    private fun move(next: CallState): List<Effect> {
        state = next
        if (next is CallState.Connecting) lastValidAt = clock.nowMs()
        return listOf(Effect.StateChanged(next))
    }

    private fun end(reason: EndReason): List<Effect> {
        val r = role ?: CallRole.CALLER
        val connectedMs =
            if (connectedAt != NOT_CONNECTED && reason == EndReason.COMPLETED) clock.nowMs() - connectedAt else 0L
        connectedAt = NOT_CONNECTED
        val ended = CallState.Ended(reason)
        state = ended
        return listOf(Effect.StopMedia, Effect.StateChanged(ended), Effect.CallEnded(reason, r, connectedMs))
    }

    companion object {
        private const val NOT_CONNECTED = -1L
        const val RETRANSMIT_MS = 1_500L
        const val UNAVAILABLE_MS = 5_000L
        const val RING_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val PING_INTERVAL_MS = 2_000L
        const val DEAD_PEER_MS = 10_000L
    }
}
