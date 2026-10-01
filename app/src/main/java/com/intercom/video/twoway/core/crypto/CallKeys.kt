package com.intercom.video.twoway.core.crypto

enum class Direction(val aad: Byte) {
    CALLER_TO_CALLEE(1),
    CALLEE_TO_CALLER(2),
}

/**
 * Per-call key material: HKDF-SHA256(PS, salt = nonceCaller || nonceCallee, info = "twoway call v1")
 * expanded to 4 keys (signaling and media for each direction) plus 4 salts of 4 bytes.
 */
class CallKeys private constructor(private val material: ByteArray) {
    private var wiped = false

    fun signalingKey(d: Direction): ByteArray = slice(if (d == Direction.CALLER_TO_CALLEE) 0 else 1)
    fun mediaKey(d: Direction): ByteArray = slice(if (d == Direction.CALLER_TO_CALLEE) 2 else 3)
    fun signalingSalt(d: Direction): ByteArray = salt(if (d == Direction.CALLER_TO_CALLEE) 0 else 1)
    fun mediaSalt(d: Direction): ByteArray = salt(if (d == Direction.CALLER_TO_CALLEE) 2 else 3)

    fun signalingCipher(d: Direction) = AesGcm(signalingKey(d), signalingSalt(d))
    fun mediaCipher(d: Direction) = AesGcm(mediaKey(d), mediaSalt(d))

    private fun slice(i: Int): ByteArray {
        check(!wiped) { "keys wiped" }
        return material.copyOfRange(i * 32, i * 32 + 32)
    }

    private fun salt(i: Int): ByteArray {
        check(!wiped) { "keys wiped" }
        return material.copyOfRange(KEYS_LEN + i * 4, KEYS_LEN + i * 4 + 4)
    }

    /** Zeroes all key bytes; call when the call ends. */
    fun wipe() {
        material.fill(0)
        wiped = true
    }

    companion object {
        private const val KEYS_LEN = 4 * 32
        private const val TOTAL = KEYS_LEN + 4 * 4
        private val INFO = "twoway call v1".toByteArray(Charsets.UTF_8)

        fun derive(pairingSecret: ByteArray, nonceCaller: ByteArray, nonceCallee: ByteArray): CallKeys =
            CallKeys(Hkdf.derive(pairingSecret, nonceCaller + nonceCallee, INFO, TOTAL))
    }
}
