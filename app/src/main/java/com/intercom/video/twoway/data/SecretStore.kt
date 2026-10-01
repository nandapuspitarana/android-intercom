package com.intercom.video.twoway.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (pairing secrets) before they go into the database. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Returns null if the blob cannot be decrypted (key lost, corrupted). */
    fun decrypt(blob: ByteArray): ByteArray?
}

/** [SecretCipher] backed by a non-exportable AES-256-GCM key in AndroidKeyStore. Secrets are never logged. */
class SecretStore(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray? = try {
        val ivLen = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.doFinal(ct)
    } catch (e: java.security.GeneralSecurityException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    companion object {
        const val DEFAULT_ALIAS = "twoway_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
