package com.intercom.video.twoway.service

import com.intercom.video.twoway.data.SecretCipher
import com.intercom.video.twoway.data.dao.PairedDeviceDao
import com.intercom.video.twoway.data.entity.Trust
import kotlinx.coroutines.runBlocking

/**
 * [TrustStore] backed by the paired-devices table. Only TRUSTED devices yield a secret; revoked and
 * unknown devices return null so they can neither be called nor ring us. Called from engine/network
 * threads (never the main thread), so blocking on Room is fine.
 */
class RoomTrustStore(private val dao: PairedDeviceDao, private val cipher: SecretCipher) : TrustStore {
    override fun pairingSecret(deviceId: String): ByteArray? {
        val device = runBlocking { dao.get(deviceId) } ?: return null
        if (device.trust != Trust.TRUSTED) return null
        return cipher.decrypt(device.pairingSecret)
    }
}
