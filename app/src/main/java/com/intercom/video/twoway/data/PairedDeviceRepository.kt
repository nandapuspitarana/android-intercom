package com.intercom.video.twoway.data

import com.intercom.video.twoway.core.util.NameSanitizer
import com.intercom.video.twoway.data.dao.PairedDeviceDao
import com.intercom.video.twoway.data.entity.PairedDevice
import com.intercom.video.twoway.data.entity.Trust
import com.intercom.video.twoway.service.PairingSaveResult
import com.intercom.video.twoway.service.PairingStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Paired devices: the pairing secret is encrypted with a keystore key before it is stored, the peer's identity
 * fingerprint is pinned, and revoked devices keep no usable secret. A trusted device that later presents a
 * different identity key is refused (possible impersonation).
 */
class PairedDeviceRepository(private val dao: PairedDeviceDao, private val cipher: SecretCipher, private val clock: () -> Long = System::currentTimeMillis) :
    PairingStore {

    /** Trusted devices only, by name. */
    fun observeTrusted(): Flow<List<PairedDevice>> = dao.observeAll().map { list -> list.filter { it.trust == Trust.TRUSTED } }

    fun observeTrustedIds(): Flow<Set<String>> = observeTrusted().map { list -> list.mapTo(HashSet()) { it.deviceId } }

    override fun savePaired(deviceId: String, name: String, identityPublicKey: ByteArray, secret: ByteArray): PairingSaveResult = runBlocking {
        val fingerprint = IdentityRepository.fingerprintOf(identityPublicKey)
        val existing = dao.get(deviceId)
        if (existing != null && existing.trust == Trust.TRUSTED && !existing.publicKeyFingerprint.contentEquals(fingerprint)) {
            return@runBlocking PairingSaveResult.IDENTITY_CHANGED
        }
        dao.upsert(
            PairedDevice(
                deviceId = deviceId,
                displayName = NameSanitizer.sanitizeOr(name, deviceId.take(8)),
                publicKeyFingerprint = fingerprint,
                pairingSecret = cipher.encrypt(secret),
                trust = Trust.TRUSTED,
                pairedAt = clock(),
                lastSeenAddress = null,
            ),
        )
        PairingSaveResult.SAVED
    }

    /** Removes trust: calls from this device are refused and its secret is erased. */
    suspend fun revoke(deviceId: String) {
        val d = dao.get(deviceId) ?: return
        // keep the row (so its identity stays known) but erase the secret and mark it revoked
        dao.upsert(
            PairedDevice(d.deviceId, d.displayName, d.publicKeyFingerprint, ByteArray(0), Trust.REVOKED, d.pairedAt, d.lastSeenAddress),
        )
    }

    /** Local nickname only; never sent to the peer. */
    suspend fun rename(deviceId: String, rawName: String): Boolean {
        val name = NameSanitizer.sanitize(rawName) ?: return false
        dao.rename(deviceId, name)
        return true
    }

    suspend fun isTrusted(deviceId: String): Boolean = dao.get(deviceId)?.trust == Trust.TRUSTED
}
