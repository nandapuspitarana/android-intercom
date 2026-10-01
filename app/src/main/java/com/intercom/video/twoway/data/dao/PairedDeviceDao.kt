package com.intercom.video.twoway.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.intercom.video.twoway.data.entity.PairedDevice
import com.intercom.video.twoway.data.entity.Trust
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_device ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<PairedDevice>>

    @Query("SELECT * FROM paired_device WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): PairedDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDevice)

    @Query("UPDATE paired_device SET trust = :trust WHERE deviceId = :deviceId")
    suspend fun setTrust(deviceId: String, trust: Trust)

    @Query("UPDATE paired_device SET displayName = :name WHERE deviceId = :deviceId")
    suspend fun rename(deviceId: String, name: String)

    @Query("UPDATE paired_device SET lastSeenAddress = :address WHERE deviceId = :deviceId")
    suspend fun setLastSeenAddress(deviceId: String, address: String?)

    @Query("DELETE FROM paired_device WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}
