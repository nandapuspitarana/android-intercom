package com.intercom.video.twoway.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CallKeysTest {
    private val ps = ByteArray(32) { (it * 3).toByte() }
    private val nA = ByteArray(16) { 1 }
    private val nB = ByteArray(16) { 2 }

    @Test
    fun bothSidesDeriveIdenticalKeys() {
        val a = CallKeys.derive(ps, nA, nB)
        val b = CallKeys.derive(ps.copyOf(), nA.copyOf(), nB.copyOf())
        for (d in Direction.values()) {
            assertArrayEquals(a.signalingKey(d), b.signalingKey(d))
            assertArrayEquals(a.mediaKey(d), b.mediaKey(d))
            assertArrayEquals(a.signalingSalt(d), b.signalingSalt(d))
            assertArrayEquals(a.mediaSalt(d), b.mediaSalt(d))
        }
    }

    @Test
    fun signalingAndMediaKeysPerDirectionAreAllDistinct() {
        val k = CallKeys.derive(ps, nA, nB)
        val all = listOf(
            k.signalingKey(Direction.CALLER_TO_CALLEE),
            k.signalingKey(Direction.CALLEE_TO_CALLER),
            k.mediaKey(Direction.CALLER_TO_CALLEE),
            k.mediaKey(Direction.CALLEE_TO_CALLER),
        ).map { it.toList() }
        assertEquals(4, all.toSet().size)
        for (d in Direction.values()) assertEquals(4, k.signalingSalt(d).size)
    }

    @Test
    fun differentNoncesOrSecretGiveDifferentKeys() {
        val base = CallKeys.derive(ps, nA, nB).mediaKey(Direction.CALLER_TO_CALLEE)
        val otherNonce = CallKeys.derive(ps, nA, ByteArray(16) { 3 }).mediaKey(Direction.CALLER_TO_CALLEE)
        val otherSecret = CallKeys.derive(ByteArray(32), nA, nB).mediaKey(Direction.CALLER_TO_CALLEE)
        assertFalse(base.contentEquals(otherNonce))
        assertFalse(base.contentEquals(otherSecret))
    }

    @Test
    fun ciphersInterOperate() {
        val caller = CallKeys.derive(ps, nA, nB)
        val callee = CallKeys.derive(ps, nA, nB)
        val sealed = caller.mediaCipher(Direction.CALLER_TO_CALLEE).seal(1, byteArrayOf(1), "voice".toByteArray())
        assertArrayEquals(
            "voice".toByteArray(),
            callee.mediaCipher(Direction.CALLER_TO_CALLEE).open(1, byteArrayOf(1), sealed),
        )
    }

    @Test
    fun wipeZeroesAndBlocksFurtherUse() {
        val k = CallKeys.derive(ps, nA, nB)
        k.wipe()
        assertThrows(IllegalStateException::class.java) { k.mediaKey(Direction.CALLER_TO_CALLEE) }
    }
}
