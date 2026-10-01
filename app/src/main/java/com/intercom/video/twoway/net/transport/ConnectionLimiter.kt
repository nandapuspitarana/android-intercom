package com.intercom.video.twoway.net.transport

import com.intercom.video.twoway.core.Clock

/**
 * Inbound connection limits: at most [maxConcurrent] open connections and at most [maxNewPerSource]
 * new connections per source address per [windowMs]. Protects the listener from floods (FR-017).
 */
class ConnectionLimiter(private val clock: Clock, maxConcurrent: Int = 8, private val maxNewPerSource: Int = 5, private val windowMs: Long = 10_000) {
    @Volatile
    var maxConcurrent: Int = maxConcurrent
    private var open = 0
    private val recent = HashMap<String, ArrayDeque<Long>>()

    /** Returns true and counts the connection if it may proceed; call [released] when it closes. */
    @Synchronized
    fun tryAcquire(source: String): Boolean {
        val now = clock.nowMs()
        val times = recent.getOrPut(source) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
        if (recent.size > MAX_SOURCES) recent.entries.removeAll { it.value.isEmpty() }
        if (open >= maxConcurrent || times.size >= maxNewPerSource) return false
        times.addLast(now)
        open++
        return true
    }

    @Synchronized
    fun released() {
        if (open > 0) open--
    }

    @Synchronized
    fun openCount(): Int = open

    private companion object {
        const val MAX_SOURCES = 1024
    }
}
