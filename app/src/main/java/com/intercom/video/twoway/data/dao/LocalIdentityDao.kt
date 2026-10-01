package com.intercom.video.twoway.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.intercom.video.twoway.data.entity.LocalIdentity

@Dao
interface LocalIdentityDao {
    @Query("SELECT * FROM local_identity WHERE id = 1")
    suspend fun get(): LocalIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: LocalIdentity)

    @Query("UPDATE local_identity SET displayName = :name WHERE id = 1")
    suspend fun rename(name: String)
}
