package com.intercom.video.twoway.core.crypto

import com.intercom.video.twoway.core.protocol.Json
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.util.B64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 12-byte nonce = 4-byte per-direction salt || 8-byte strictly increasing counter.
 * The same primitive protects signaling frames and media packets.
 */
class AesGcm(key: ByteArray, private val salt: ByteArray) {
    private val keySpec = SecretKeySpec(key.copyOf(), "AES")

    init {
        require(key.size == 32) { "AES-256 key required" }
        require(salt.size == SALT_LEN) { "salt must be 4 bytes" }
    }

    fun seal(counter: Long, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(salt, counter)))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    /** Returns the plaintext, or null if the tag does not verify (tampering, wrong key or wrong AAD). */
    fun open(counter: Long, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce(salt, counter)))
        c.updateAAD(aad)
        c.doFinal(sealed)
    } catch (e: java.security.GeneralSecurityException) {
        null
    }

    companion object {
        const val SALT_LEN = 4
        const val TAG_BITS = 128
        const val TAG_BYTES = 16

        fun nonce(salt: ByteArray, counter: Long): ByteArray {
            val n = ByteArray(12)
            System.arraycopy(salt, 0, n, 0, SALT_LEN)
            for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
            return n
        }
    }
}

/** JSON envelope for encrypted signaling frames: {"c":1,"n":"<8-byte counter b64>","d":"<ciphertext+tag b64>"}. */
object Envelope {
    class Parsed(val counter: Long, val data: ByteArray)

    fun encode(counter: Long, sealed: ByteArray): String {
        val n = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        return Json.write(mapOf("c" to 1L, "n" to B64.encode(n), "d" to B64.encode(sealed)))
    }

    /** Returns null when the object is not an envelope; throws [JsonException] on malformed JSON. */
    fun decode(text: String): Parsed? {
        // Parsed with the hard bound: a RELAY frame is not an envelope but may hold one long string, and the caller
        // must be able to tell "not an envelope" apart from "malformed". Anything else is validated by the message codec.
        val o = Json.parseObject(text, Json.MAX_STRING_HARD)
        if (o["c"] != 1L) return null
        val nBytes = (o["n"] as? String)?.let { B64.decode(it) } ?: return null
        val data = (o["d"] as? String)?.let { B64.decode(it) } ?: return null
        if (nBytes.size != 8) return null
        var counter = 0L
        for (b in nBytes) counter = (counter shl 8) or (b.toLong() and 0xFF)
        return Parsed(counter, data)
    }
}
