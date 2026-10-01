package com.intercom.video.twoway.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallDirection { INCOMING, OUTGOING }

enum class CallOutcome { COMPLETED, MISSED, DECLINED, BUSY, CANCELLED, FAILED }

/** One past call. Kept on the device only; no foreign key so history survives unpairing. */
@Entity(tableName = "call_history")
class CallHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerDeviceId: String,
    /** Name at the time of the call. */
    val peerName: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val startedAt: Long,
    /** 0 unless [outcome] is COMPLETED. */
    val durationMs: Long,
)
