package com.intercom.video.twoway.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JitterBufferTest {
    private fun frame(n: Int) = byteArrayOf(n.toByte())

    /** Feeds packets 20 ms apart with timestamps in samples (320 per frame). */
    private fun JitterBuffer.feed(seq: Long, arrivalMs: Long) = put(seq, seq * 320, arrivalMs, frame(seq.toInt()))

    private fun Playout.id(): Int = when (this) {
        is Playout.Frame -> payload[0].toInt()
        Playout.Lost -> -1
        Playout.Silence -> -2
    }

    @Test
    fun staysSilentUntilTargetDepthIsBuffered() {
        val jb = JitterBuffer()
        assertEquals(-2, jb.poll().id())
        jb.feed(1, 0)
        assertEquals(-2, jb.poll().id(), "1 frame < target of 2")
        jb.feed(2, 20)
        assertEquals(1, jb.poll().id())
        assertEquals(2, jb.poll().id())
    }

    @Test
    fun reorderedPacketsPlayInOrder() {
        val jb = JitterBuffer()
        jb.feed(3, 0)
        jb.feed(1, 5)
        jb.feed(2, 10)
        val played = List(3) { jb.poll().id() }
        assertEquals(listOf(1, 2, 3), played)
    }

    @Test
    fun missingFrameIsReportedAsLostWhenLaterOnesExist() {
        val jb = JitterBuffer()
        jb.feed(1, 0)
        jb.feed(3, 40)
        jb.feed(4, 60)
        assertEquals(1, jb.poll().id())
        assertEquals(-1, jb.poll().id(), "frame 2 never arrived")
        assertEquals(3, jb.poll().id())
        assertEquals(4, jb.poll().id())
    }

    @Test
    fun lateArrivalAfterItsSlotIsDropped() {
        val jb = JitterBuffer()
        jb.feed(1, 0)
        jb.feed(2, 20)
        jb.feed(4, 60)
        jb.poll() // 1
        jb.poll() // 2
        jb.poll() // Lost(3)
        jb.feed(3, 100) // too late
        assertEquals(1, jb.lateDropped)
        assertEquals(4, jb.poll().id())
    }

    @Test
    fun duplicatePacketsAreIgnored() {
        val jb = JitterBuffer()
        jb.feed(1, 0)
        jb.feed(1, 1)
        jb.feed(2, 20)
        assertEquals(2, jb.bufferedFrames)
    }

    @Test
    fun underrunPlaysSilenceThenRebuffers() {
        val jb = JitterBuffer()
        jb.feed(1, 0)
        jb.feed(2, 20)
        jb.poll()
        jb.poll()
        repeat(30) { assertEquals(-2, jb.poll().id()) } // nothing arrives
        // stream resumes at a later sequence: it must start again once buffered, not wait for old seq
        jb.feed(500, 1000)
        jb.feed(501, 1020)
        assertTrue(jb.poll() is Playout.Frame, "playback restarts from the new sequence")
    }

    @Test
    fun lowJitterKeepsTheMinimumDepth() {
        val jb = JitterBuffer()
        for (i in 0L until 100) jb.feed(i, i * 20)
        assertEquals(JitterBuffer.MIN_FRAMES, jb.targetFrames)
    }

    @Test
    fun highJitterIncreasesDepthUpToTheMaximum() {
        val jb = JitterBuffer()
        for (i in 0L until 100) {
            val noise = if (i % 2 == 0L) 0L else 60L // +-60 ms swings
            jb.feed(i, i * 20 + noise)
        }
        assertTrue(jb.targetFrames > JitterBuffer.MIN_FRAMES)
        assertEquals(JitterBuffer.MAX_FRAMES, jb.targetFrames)
    }

    @Test
    fun targetStaysWithin40And100ms() {
        val jb = JitterBuffer()
        for (i in 0L until 50) jb.feed(i, i * 20 + (i * 37 % 200))
        assertTrue(jb.targetFrames in JitterBuffer.MIN_FRAMES..JitterBuffer.MAX_FRAMES)
        assertTrue(jb.targetFrames * 20 in 40..100)
    }
}
