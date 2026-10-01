package com.intercom.video.twoway.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869) built on javax.crypto.Mac. */
object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, HMAC))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "bad length" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray = expand(extract(salt, ikm), info, length)
}
