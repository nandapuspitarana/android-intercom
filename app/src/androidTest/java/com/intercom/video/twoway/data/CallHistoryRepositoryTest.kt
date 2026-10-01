package com.intercom.video.twoway.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.data.entity.CallDirection
import com.intercom.video.twoway.data.entity.CallOutcome
import com.intercom.video.twoway.service.CallRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallHistoryRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CallHistoryRepository

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = CallHistoryRepository(db.callHistoryDao())
    }

    @After
    fun tearDown() = db.close()

    private fun record(reason: EndReason, outgoing: Boolean = true, connectedMs: Long = 0, started: Long = 1L) =
        CallRecord("peer", "Peer", outgoing, reason, started, connectedMs)

    @Test
    fun everyEndReasonMapsToAnOutcome() = runBlocking {
        val expected = mapOf(
            EndReason.COMPLETED to CallOutcome.COMPLETED,
            EndReason.DECLINED to CallOutcome.DECLINED,
            EndReason.BUSY to CallOutcome.BUSY,
            EndReason.CANCELLED to CallOutcome.CANCELLED,
            EndReason.MISSED to CallOutcome.MISSED,
            EndReason.UNAVAILABLE to CallOutcome.FAILED,
            EndReason.CONNECTION_FAILED to CallOutcome.FAILED,
            EndReason.CONNECTION_LOST to CallOutcome.FAILED,
            EndReason.NOT_PAIRED to CallOutcome.FAILED,
            EndReason.NETWORK_BLOCKED to CallOutcome.FAILED,
        )
        expected.forEach { (reason, outcome) -> assertEquals(outcome, CallHistoryRepository.outcomeOf(reason)) }
        assertEquals(EndReason.values().size, expected.size) // no reason is left unmapped
    }

    @Test
    fun durationIsZeroUnlessTheCallCompleted() = runBlocking {
        repo.record(record(EndReason.COMPLETED, connectedMs = 65_000, started = 1))
        repo.record(record(EndReason.MISSED, outgoing = false, connectedMs = 99_000, started = 2))
        repo.record(record(EndReason.CONNECTION_LOST, connectedMs = 5_000, started = 3))
        val all = db.callHistoryDao().getAll().associateBy { it.startedAt }
        assertEquals(65_000L, all.getValue(1).durationMs)
        assertEquals(0L, all.getValue(2).durationMs)
        assertEquals(0L, all.getValue(3).durationMs)
    }

    @Test
    fun directionIsStored() = runBlocking {
        repo.record(record(EndReason.COMPLETED, outgoing = true, started = 1))
        repo.record(record(EndReason.MISSED, outgoing = false, started = 2))
        val all = db.callHistoryDao().getAll().associateBy { it.startedAt }
        assertEquals(CallDirection.OUTGOING, all.getValue(1).direction)
        assertEquals(CallDirection.INCOMING, all.getValue(2).direction)
    }

    @Test
    fun onlyTheMostRecent500AreKept() = runBlocking {
        for (i in 1..520) repo.record(record(EndReason.COMPLETED, connectedMs = 1000, started = i.toLong()))
        val all = db.callHistoryDao().getAll()
        assertEquals(CallHistoryRepository.MAX_ENTRIES, all.size)
        assertEquals(520L, all.first().startedAt)
        assertEquals(21L, all.last().startedAt)
    }

    @Test
    fun clearRemovesAll() = runBlocking {
        repo.record(record(EndReason.COMPLETED))
        repo.clear()
        assertEquals(0, db.callHistoryDao().count())
    }
}
