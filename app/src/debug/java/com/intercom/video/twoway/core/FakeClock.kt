package com.intercom.video.twoway.core

/** Manually advanced clock for deterministic timer tests (ring timeout, heartbeat, connect timeout). */
class FakeClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long) {
        require(ms >= 0)
        now += ms
    }
}
