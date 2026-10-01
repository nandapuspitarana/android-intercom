package com.intercom.video.twoway.net

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.net.transport.ConnectionLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionLimiterTest {
    @Test
    fun atMost8ConcurrentConnections() {
        val l = ConnectionLimiter(FakeClock(), maxConcurrent = 8, maxNewPerSource = 100)
        repeat(8) { assertTrue(l.tryAcquire("10.0.0.$it")) }
        assertFalse(l.tryAcquire("10.0.0.99"))
        l.released()
        assertTrue(l.tryAcquire("10.0.0.99"))
        assertEquals(8, l.openCount())
    }

    @Test
    fun atMost5NewConnectionsPerSourcePer10Seconds() {
        val clock = FakeClock()
        val l = ConnectionLimiter(clock)
        repeat(5) {
            assertTrue(l.tryAcquire("10.0.0.2"))
            l.released()
        }
        assertFalse(l.tryAcquire("10.0.0.2"), "6th within 10 s")
        assertTrue(l.tryAcquire("10.0.0.3"), "another source is unaffected")
        clock.advance(10_000)
        assertTrue(l.tryAcquire("10.0.0.2"), "window slid")
    }

    @Test
    fun releaseNeverGoesNegative() {
        val l = ConnectionLimiter(FakeClock())
        l.released()
        l.released()
        assertEquals(0, l.openCount())
    }
}
