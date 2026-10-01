package com.intercom.video.twoway.core.crypto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplayWindowTest {
    @Test
    fun signalingCountersMustStrictlyIncrease() {
        val w = StrictCounterWindow()
        assertTrue(w.accept(0))
        assertTrue(w.accept(1))
        assertFalse(w.accept(1))
        assertFalse(w.accept(0))
        assertTrue(w.accept(10))
        assertFalse(w.accept(9))
    }

    @Test
    fun mediaWindowAcceptsReorderingOnce() {
        val w = SlidingReplayWindow(64)
        assertTrue(w.accept(100))
        assertTrue(w.accept(99)) // reordered, first time
        assertFalse(w.accept(99)) // replay
        assertFalse(w.accept(100)) // replay
        assertTrue(w.accept(101))
    }

    @Test
    fun mediaWindowRejectsOlderThan64() {
        val w = SlidingReplayWindow(64)
        assertTrue(w.accept(1000))
        assertTrue(w.accept(1000 - 63))
        assertFalse(w.accept(1000 - 64))
        assertFalse(w.accept(1))
    }

    @Test
    fun mediaWindowHandlesLargeJumps() {
        val w = SlidingReplayWindow(64)
        assertTrue(w.accept(5))
        assertTrue(w.accept(5000))
        assertFalse(w.accept(5)) // now far outside the window
        assertTrue(w.accept(4990))
    }

    @Test
    fun negativeCountersAreRejected() {
        assertFalse(SlidingReplayWindow().accept(-1))
    }
}
