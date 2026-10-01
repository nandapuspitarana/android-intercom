package com.intercom.video.twoway.core.media

import java.util.TreeMap

/** What the playout loop should do for the next 20 ms frame. */
sealed interface Playout {
    /** Decode this Opus frame. */
    class Frame(val payload: ByteArray) : Playout

    /** A frame is missing: let the decoder conceal it (PLC). */
    data object Lost : Playout

    /** Nothing to play (still buffering, or underrun): play silence. */
    data object Silence : Playout
}

/**
 * Adaptive jitter buffer for 20 ms voice frames. Reordered packets are put back in order, missing
 * frames are reported as [Playout.Lost], and packets that arrive after their slot has been played
 * are dropped. The target depth adapts between [MIN_FRAMES] (40 ms) and [MAX_FRAMES] (100 ms)
 * from an RFC 3550 style interarrival jitter estimate.
 */
class JitterBuffer(private val frameMs: Int = 20) {
    private val frames = TreeMap<Long, ByteArray>()
    private var playing = false
    private var nextSeq = 0L
    private var underruns = 0

    private var jitterMs = 0.0
    private var lastTransit = Double.NaN

    var lateDropped = 0
        private set

    /** Current target depth in frames. */
    val targetFrames: Int
        @Synchronized get() = computeTarget()

    val bufferedFrames: Int
        @Synchronized get() = frames.size

    /**
     * @param extendedSeq packet sequence (extended, monotonically meaningful)
     * @param timestamp media timestamp in samples @16 kHz
     * @param arrivalMs local arrival time
     */
    @Synchronized
    fun put(extendedSeq: Long, timestamp: Long, arrivalMs: Long, payload: ByteArray) {
        updateJitter(timestamp, arrivalMs)
        if (playing && extendedSeq < nextSeq) {
            lateDropped++
            return
        }
        frames.putIfAbsent(extendedSeq, payload)
    }

    /** Call exactly once every [frameMs]. */
    @Synchronized
    fun poll(): Playout {
        if (!playing) {
            if (frames.size >= computeTarget()) {
                playing = true
                nextSeq = frames.firstKey()
                underruns = 0
            } else {
                return Playout.Silence
            }
        }
        val frame = frames.remove(nextSeq)
        if (frame != null) {
            nextSeq++
            underruns = 0
            return Playout.Frame(frame)
        }
        if (frames.isNotEmpty() && frames.firstKey() > nextSeq) {
            nextSeq++ // later frames exist: this one is lost
            underruns = 0
            return Playout.Lost
        }
        // underrun: nothing newer has arrived yet
        underruns++
        if (underruns > MAX_UNDERRUNS) {
            playing = false
            frames.clear()
            lastTransit = Double.NaN // the next stream may restart at a new sequence; do not count the jump as jitter
        }
        return Playout.Silence
    }

    @Synchronized
    fun reset() {
        frames.clear()
        playing = false
        underruns = 0
        lastTransit = Double.NaN
        jitterMs = 0.0
    }

    private fun updateJitter(timestamp: Long, arrivalMs: Long) {
        val transit = arrivalMs.toDouble() - timestamp / SAMPLES_PER_MS
        if (!lastTransit.isNaN()) {
            val d = kotlin.math.abs(transit - lastTransit)
            jitterMs += (d - jitterMs) / 16.0
        }
        lastTransit = transit
    }

    private fun computeTarget(): Int {
        val wanted = kotlin.math.ceil(2.0 * jitterMs / frameMs).toInt() + MIN_FRAMES
        return wanted.coerceIn(MIN_FRAMES, MAX_FRAMES)
    }

    companion object {
        const val MIN_FRAMES = 2 // 40 ms
        const val MAX_FRAMES = 5 // 100 ms
        private const val MAX_UNDERRUNS = 25 // 0.5 s of silence, then re-buffer
        private const val SAMPLES_PER_MS = 16.0
    }
}
