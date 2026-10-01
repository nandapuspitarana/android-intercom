package com.intercom.video.twoway.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.intercom.video.twoway.data.entity.CallHistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface CallHistoryDao {
    @Query("SELECT * FROM call_history ORDER BY startedAt DESC, id DESC")
    fun observeAll(): Flow<List<CallHistoryEntry>>

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC, id DESC")
    suspend fun getAll(): List<CallHistoryEntry>

    @Insert
    suspend fun insert(entry: CallHistoryEntry): Long

    @Query("SELECT COUNT(*) FROM call_history")
    suspend fun count(): Int

    /** Keeps only the [keep] most recent entries. */
    @Query(
        "DELETE FROM call_history WHERE id NOT IN " +
            "(SELECT id FROM call_history ORDER BY startedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun pruneTo(keep: Int)

    @Query("DELETE FROM call_history")
    suspend fun clear()
}
