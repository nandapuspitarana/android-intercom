package com.intercom.video.twoway.core

/** Monotonic time source so timers can be driven by a fake clock in tests. */
interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
