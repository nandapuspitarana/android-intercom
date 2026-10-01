package com.intercom.video.twoway.data

import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.data.dao.CallHistoryDao
import com.intercom.video.twoway.data.entity.CallDirection
import com.intercom.video.twoway.data.entity.CallHistoryEntry
import com.intercom.video.twoway.data.entity.CallOutcome
import com.intercom.video.twoway.service.CallRecord
import kotlinx.coroutines.flow.Flow

/** Local call history: one entry per finished call, newest 500 kept, never leaves the device (FR-022). */
class CallHistoryRepository(private val dao: CallHistoryDao) {
    fun observe(): Flow<List<CallHistoryEntry>> = dao.observeAll()

    suspend fun record(record: CallRecord) {
        val outcome = outcomeOf(record.reason)
        dao.insert(
            CallHistoryEntry(
                peerDeviceId = record.peerId,
                peerName = record.peerName,
                direction = if (record.outgoing) CallDirection.OUTGOING else CallDirection.INCOMING,
                outcome = outcome,
                startedAt = record.startedWallMs,
                // data-model: "0 unless COMPLETED"
                durationMs = if (outcome == CallOutcome.COMPLETED) record.connectedMs else 0L,
            ),
        )
        dao.pruneTo(MAX_ENTRIES)
    }

    suspend fun clear() = dao.clear()

    companion object {
        const val MAX_ENTRIES = 500

        fun outcomeOf(reason: EndReason): CallOutcome = when (reason) {
            EndReason.COMPLETED -> CallOutcome.COMPLETED
            EndReason.DECLINED -> CallOutcome.DECLINED
            EndReason.BUSY -> CallOutcome.BUSY
            EndReason.CANCELLED -> CallOutcome.CANCELLED
            EndReason.MISSED -> CallOutcome.MISSED
            EndReason.UNAVAILABLE, EndReason.CONNECTION_FAILED, EndReason.CONNECTION_LOST, EndReason.NOT_PAIRED,
            EndReason.NETWORK_BLOCKED,
            -> CallOutcome.FAILED
        }
    }
}
