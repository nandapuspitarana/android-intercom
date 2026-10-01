package com.intercom.video.twoway.core.media

import com.intercom.video.twoway.core.crypto.AesGcm
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MediaPacketTest {
    private val key = ByteArray(32) { it.toByte() }
    private val salt = byteArrayOf(1, 2, 3, 4)
    private val tag = 0x1234ABCD
    private val tx = AesGcm(key, salt)

    private fun receiver(sessionTag: Int = tag) = MediaPacket.Receiver(AesGcm(key, salt), sessionTag)

    private fun voice(seq: Long, payload: ByteArray = ByteArray(40) { 7 }) = MediaPacket.seal(tx, MediaPacket.TYPE_VOICE, seq, seq * 320, tag, payload)

    @Test
    fun headerLayoutMatchesContract() {
        val p = MediaPacket.seal(tx, MediaPacket.TYPE_VOICE, 0x0102L, 0x0A0B0C0DL, 0x11223344, ByteArray(10))
        assertEquals(0x10, p[0].toInt() and 0xFF) // ver 1, type 0
        assertEquals(0, p[1].toInt())
        assertArrayEquals(byteArrayOf(1, 2), p.copyOfRange(2, 4))
        assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), p.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), p.copyOfRange(8, 12))
        assertEquals(12 + 10 + AesGcm.TAG_BYTES, p.size)
    }

    @Test
    fun roundTripVoiceAndKeepAlive() {
        val rx = receiver()
        val a = rx.open(voice(1))!!
        assertEquals(MediaPacket.TYPE_VOICE, a.type)
        assertEquals(1L, a.extendedSeq)
        assertArrayEquals(ByteArray(40) { 7 }, a.payload)
        val k = MediaPacket.seal(tx, MediaPacket.TYPE_KEEPALIVE, 2, 640, tag, byteArrayOf(0))
        assertEquals(MediaPacket.TYPE_KEEPALIVE, rx.open(k)!!.type)
        assertEquals(12 + 1 + 16, k.size)
    }

    @Test
    fun typicalPacketIsSmall() = assert(voice(1).size <= MediaPacket.MAX_DATAGRAM)

    @Test
    fun oversizedDatagramIsRejectedOnSealAndOpen() {
        assertNull(receiver().open(ByteArray(MediaPacket.MAX_DATAGRAM + 1)))
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MediaPacket.seal(tx, MediaPacket.TYPE_VOICE, 1, 0, tag, ByteArray(MediaPacket.MAX_DATAGRAM))
        }
    }

    @Test
    fun wrongSessionTagIsDropped() = assertNull(receiver(sessionTag = 0x55).open(voice(1)))

    @Test
    fun badGcmTagOrTamperedHeaderIsDropped() {
        val rx = receiver()
        val p = voice(1)
        val flipTag = p.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(rx.open(flipTag))
        val flipHeader = p.copyOf().also { it[1] = 1 } // flags are authenticated as AAD
        assertNull(rx.open(flipHeader))
        assertNotNull(rx.open(p)) // the untouched packet still works
    }

    @Test
    fun wrongKeyIsDropped() {
        val other = MediaPacket.Receiver(AesGcm(ByteArray(32) { 9 }, salt), tag)
        assertNull(other.open(voice(1)))
    }

    @Test
    fun replayAndTooOldPacketsAreDropped() {
        val rx = receiver()
        assertNotNull(rx.open(voice(100)))
        assertNull(rx.open(voice(100)), "replay")
        assertNotNull(rx.open(voice(99)), "reordering within the window is fine")
        assertNull(rx.open(voice(99)), "but only once")
        assertNull(rx.open(voice(100 - 64)), "outside the 64-packet window")
    }

    @Test
    fun sequenceRolloverIsHandled() {
        val rx = receiver()
        for (seq in listOf(65530L, 65534L, 65535L, 65536L, 65537L, 65540L)) {
            val p = rx.open(voice(seq))
            assertNotNull(p, "seq $seq")
            assertEquals(seq, p!!.extendedSeq)
        }
    }

    @Test
    fun truncatedPacketIsDropped() {
        assertNull(receiver().open(voice(1).copyOf(20)))
        assertNull(receiver().open(ByteArray(3)))
    }
}
