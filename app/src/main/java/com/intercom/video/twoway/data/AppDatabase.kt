package com.intercom.video.twoway.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.intercom.video.twoway.data.dao.CallHistoryDao
import com.intercom.video.twoway.data.dao.LocalIdentityDao
import com.intercom.video.twoway.data.dao.PairedDeviceDao
import com.intercom.video.twoway.data.entity.CallHistoryEntry
import com.intercom.video.twoway.data.entity.LocalIdentity
import com.intercom.video.twoway.data.entity.PairedDevice

@Database(
    entities = [LocalIdentity::class, PairedDevice::class, CallHistoryEntry::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun identityDao(): LocalIdentityDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun callHistoryDao(): CallHistoryDao

    companion object {
        const val NAME = "twoway.db"

        fun create(context: Context): AppDatabase = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()

        fun inMemory(context: Context): AppDatabase = Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
    }
}
