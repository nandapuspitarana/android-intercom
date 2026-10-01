package com.intercom.video.twoway.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.intercom.video.twoway.core.util.Hex
import com.intercom.video.twoway.core.util.NameSanitizer
import com.intercom.video.twoway.data.dao.LocalIdentityDao
import com.intercom.video.twoway.data.entity.LocalIdentity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Creates and serves this install's identity: random 128-bit deviceId + a P-256 signing key in AndroidKeyStore. */
class IdentityRepository(
    private val dao: LocalIdentityDao,
    private val keyAlias: String = KEY_ALIAS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val defaultName: () -> String = { Build.MODEL ?: "Phone" },
) {
    @Volatile
    private var cached: LocalIdentity? = null

    suspend fun get(): LocalIdentity {
        cached?.let { return it }
        val existing = dao.get()
        if (existing != null && keyExists()) {
            cached = existing
            return existing
        }
        val created = create()
        dao.upsert(created)
        cached = created
        return created
    }

    suspend fun rename(raw: String): Boolean {
        val name = NameSanitizer.sanitize(raw) ?: return false
        get()
        dao.rename(name)
        cached = dao.get()
        return true
    }

    /** SHA-256 of this device's long-term public key; shown in QR codes and pinned by peers. */
    suspend fun fingerprint(): ByteArray = fingerprintOf(get().publicKey)

    /** Signs [data] with the long-term key (used to bind identity to a pairing transcript). */
    fun sign(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = ks.getKey(keyAlias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }

    private fun keyExists(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(keyAlias)

    private fun create(): LocalIdentity {
        val idBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        val pub = gen.generateKeyPair().public.encoded
        return LocalIdentity(
            deviceId = Hex.encode(idBytes),
            displayName = NameSanitizer.sanitizeOr(defaultName(), "Phone"),
            publicKey = pub,
            createdAt = clock(),
        )
    }

    companion object {
        const val KEY_ALIAS = "twoway_identity"

        fun fingerprintOf(publicKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKey)
    }
}
