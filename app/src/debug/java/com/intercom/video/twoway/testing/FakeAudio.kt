package com.intercom.video.twoway.testing

import com.intercom.video.twoway.audio.ConcentusOpusCodec
import com.intercom.video.twoway.core.media.AudioFactory
import com.intercom.video.twoway.core.media.AudioFormat
import com.intercom.video.twoway.core.media.AudioSink
import com.intercom.video.twoway.core.media.AudioSource
import com.intercom.video.twoway.core.media.OpusCodec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A microphone that produces a 440 Hz tone in real time (one 20 ms frame every 20 ms). */
class FakeSource : AudioSource {
    @Volatile
    private var running = false
    private var phase = 0.0
    val framesRead = AtomicInteger()

    override fun start() {
        running = true
    }

    override fun read(frame: ShortArray): Boolean {
        if (!running) return false
        try {
            Thread.sleep(AudioFormat.FRAME_MS.toLong())
        } catch (_: InterruptedException) {
            return false
        }
        for (i in frame.indices) {
            frame[i] = (sin(phase) * 8000).toInt().toShort()
            phase += 2 * PI * 440 / AudioFormat.SAMPLE_RATE
        }
        framesRead.incrementAndGet()
        return running
    }

    override fun stop() {
        running = false
    }
}

/** A speaker that counts what it is asked to play. */
class FakeSink : AudioSink {
    val framesWritten = AtomicInteger()

    /** Frames with audible content (the peer's tone), as opposed to silence. */
    val loudFrames = AtomicInteger()

    @Volatile
    var started = false

    @Volatile
    var stopped = false

    override fun start() {
        started = true
    }

    override fun write(frame: ShortArray) {
        framesWritten.incrementAndGet()
        if (frame.any { abs(it.toInt()) > LOUD }) loudFrames.incrementAndGet()
    }

    override fun stop() {
        stopped = true
    }

    private companion object {
        const val LOUD = 500
    }
}

/** Audio for tests: tone source, counting sink, and the REAL Opus codec so codec round trips are exercised. */
class FakeAudioFactory : AudioFactory {
    val sources = CopyOnWriteArrayList<FakeSource>()
    val sinks = CopyOnWriteArrayList<FakeSink>()

    @Volatile
    var inCallMode = false
        private set

    override fun newSource(): AudioSource = FakeSource().also { sources += it }
    override fun newSink(): AudioSink = FakeSink().also { sinks += it }
    override fun newCodec(): OpusCodec = ConcentusOpusCodec()

    @Volatile
    var route = com.intercom.video.twoway.core.media.AudioRoute.EARPIECE
        private set

    @Volatile
    var routes: Set<com.intercom.video.twoway.core.media.AudioRoute> = setOf(
        com.intercom.video.twoway.core.media.AudioRoute.EARPIECE,
        com.intercom.video.twoway.core.media.AudioRoute.SPEAKER,
    )

    override fun setRoute(route: com.intercom.video.twoway.core.media.AudioRoute) {
        if (route in routes) this.route = route
    }

    override fun availableRoutes() = routes

    override fun enterCallMode() {
        inCallMode = true
    }

    override fun exitCallMode() {
        inCallMode = false
    }

    val totalLoudFrames: Int get() = sinks.sumOf { it.loudFrames.get() }
}
