package com.intercom.video.twoway.core.media

import com.intercom.video.twoway.core.crypto.AesGcm
import com.intercom.video.twoway.core.crypto.SlidingReplayWindow

/**
 * Voice/keep-alive UDP packet (contracts/media.md).
 *
 * ```
 * byte0: ver(4) | type(4)   byte1: flags   bytes2-3: sequence (u16)
 * bytes4-7: timestamp (u32, samples @16 kHz)   bytes8-11: session tag (u32)
 * payload: AES-256-GCM(ciphertext + 16-byte tag), header is the associated data
 * ```
 * The GCM nonce counter is the sequence extended with a rollover counter so it never repeats in a call.
 */
object MediaPacket {
    const val VERSION = 1
    const val TYPE_VOICE = 0
    const val TYPE_KEEPALIVE = 1
    const val HEADER_SIZE = 12
    const val MAX_DATAGRAM = 1024

    class Parsed(val type: Int, val extendedSeq: Long, val timestamp: Long, val payload: ByteArray)

    fun seal(cipher: AesGcm, type: Int, extendedSeq: Long, timestamp: Long, sessionTag: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = ((VERSION shl 4) or (type and 0xF)).toByte()
        header[1] = 0
        header[2] = (extendedSeq ushr 8).toByte()
        header[3] = extendedSeq.toByte()
        writeU32(header, 4, timestamp)
        writeU32(header, 8, sessionTag.toLong() and 0xFFFFFFFFL)
        val sealed = cipher.seal(extendedSeq, header, payload)
        val out = ByteArray(HEADER_SIZE + sealed.size)
        System.arraycopy(header, 0, out, 0, HEADER_SIZE)
        System.arraycopy(sealed, 0, out, HEADER_SIZE, sealed.size)
        require(out.size <= MAX_DATAGRAM) { "datagram too large" }
        return out
    }

    /** Receiver state for one stream: sequence extension plus replay protection. */
    class Receiver(private val cipher: AesGcm, private val sessionTag: Int) {
        private val window = SlidingReplayWindow(64)
        private var highestExt = -1L

        /** Returns null for anything that is not a valid, fresh packet of this call. */
        @Synchronized
        fun open(packet: ByteArray, length: Int = packet.size): Parsed? {
            if (length < HEADER_SIZE + AesGcm.TAG_BYTES || length > MAX_DATAGRAM) return null
            val b0 = packet[0].toInt() and 0xFF
            if ((b0 ushr 4) != VERSION) return null
            val type = b0 and 0xF
            if (type != TYPE_VOICE && type != TYPE_KEEPALIVE) return null
            if (readU32(packet, 8).toInt() != sessionTag) return null
            val seq16 = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            val ext = extend(seq16)
            val header = packet.copyOfRange(0, HEADER_SIZE)
            val plain = cipher.open(ext, header, packet.copyOfRange(HEADER_SIZE, length)) ?: return null
            if (!window.accept(ext)) return null // authentic but replayed or too old
            if (ext > highestExt) highestExt = ext
            return Parsed(type, ext, readU32(packet, 4), plain)
        }

        /** RFC 3711-style estimate of the extended sequence from the 16 low bits. */
        private fun extend(seq16: Int): Long {
            if (highestExt < 0) return seq16.toLong()
            val roc = highestExt ushr 16
            val highSeq = (highestExt and 0xFFFF).toInt()
            val candidate = when {
                highSeq < 0x8000 && seq16 - highSeq > 0x8000 -> roc - 1
                highSeq >= 0x8000 && highSeq - seq16 > 0x8000 -> roc + 1
                else -> roc
            }
            return (candidate shl 16) or seq16.toLong()
        }
    }

    private fun writeU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun readU32(b: ByteArray, off: Int): Long = ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
        ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
}
