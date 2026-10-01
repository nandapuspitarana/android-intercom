package com.intercom.video.twoway.core.crypto

/** Signaling: each counter must be strictly greater than the last accepted one. */
class StrictCounterWindow {
    private var last = -1L

    @Synchronized
    fun accept(counter: Long): Boolean {
        if (counter <= last) return false
        last = counter
        return true
    }
}

/** Media: accepts out-of-order packets within [size] behind the highest accepted, never twice. */
class SlidingReplayWindow(private val size: Int = 64) {
    private var highest = -1L
    private var bitmap = 0L // bit i set => (highest - i) already seen

    init {
        require(size in 1..64)
    }

    @Synchronized
    fun accept(counter: Long): Boolean {
        if (counter < 0) return false
        if (highest < 0) {
            highest = counter
            bitmap = 1L
            return true
        }
        if (counter > highest) {
            val shift = counter - highest
            bitmap = if (shift >= 64) 0L else bitmap shl shift.toInt()
            bitmap = bitmap or 1L
            highest = counter
            return true
        }
        val offset = highest - counter
        if (offset >= size) return false
        val bit = 1L shl offset.toInt()
        if (bitmap and bit != 0L) return false
        bitmap = bitmap or bit
        return true
    }
}
