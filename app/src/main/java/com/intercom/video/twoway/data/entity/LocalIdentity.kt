package com.intercom.video.twoway.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * This install's identity (single row, id = 1). The private key never leaves AndroidKeyStore;
 * only the public key (X.509) is stored here.
 */
@Entity(tableName = "local_identity")
class LocalIdentity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** 16 random bytes, hex (32 chars). Stable across restarts. */
    val deviceId: String,
    /** 1-32 chars, sanitized. */
    val displayName: String,
    val publicKey: ByteArray,
    val createdAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
