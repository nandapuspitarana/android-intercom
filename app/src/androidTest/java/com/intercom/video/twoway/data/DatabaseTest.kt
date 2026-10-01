package com.intercom.video.twoway.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intercom.video.twoway.data.entity.CallDirection
import com.intercom.video.twoway.data.entity.CallHistoryEntry
import com.intercom.video.twoway.data.entity.CallOutcome
import com.intercom.video.twoway.data.entity.PairedDevice
import com.intercom.video.twoway.data.entity.Trust
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = db.close()

    private fun entry(i: Int) = CallHistoryEntry(
        peerDeviceId = "peer",
        peerName = "P",
        direction = CallDirection.INCOMING,
        outcome = CallOutcome.COMPLETED,
        startedAt = i.toLong(),
        durationMs = 1000,
    )

    @Test
    fun pairedDeviceRoundTripAndRevoke() = runBlocking {
        val dao = db.pairedDeviceDao()
        val d = PairedDevice("abc", "Kitchen", ByteArray(32) { 1 }, byteArrayOf(9, 9), Trust.TRUSTED, 5L, null)
        dao.upsert(d)
        val loaded = dao.get("abc")!!
        assertEquals("Kitchen", loaded.displayName)
        assertArrayEquals(byteArrayOf(9, 9), loaded.pairingSecret)
        dao.setTrust("abc", Trust.REVOKED)
        assertEquals(Trust.REVOKED, dao.get("abc")!!.trust)
        dao.delete("abc")
        assertNull(dao.get("abc"))
    }

    @Test
    fun historyIsPrunedToMostRecent500() = runBlocking {
        val dao = db.callHistoryDao()
        for (i in 1..520) dao.insert(entry(i))
        dao.pruneTo(500)
        val all = dao.getAll()
        assertEquals(500, all.size)
        assertEquals(520L, all.first().startedAt)
        assertEquals(21L, all.last().startedAt)
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        val dao = db.callHistoryDao()
        dao.insert(entry(1))
        dao.clear()
        assertEquals(0, dao.count())
    }
}
