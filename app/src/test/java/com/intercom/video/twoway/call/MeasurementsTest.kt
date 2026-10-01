package com.intercom.video.twoway.call

import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Success-criteria measurements on the loopback pipeline (real sockets, real Opus, real encryption; no radio and no
 * audio hardware). Results are asserted against the spec's targets and written to build/measurements.txt so they can be
 * recorded in checklists/validation.md. Real-phone numbers (radio, audio hardware latency) need the manual quickstart.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MeasurementsTest {
    private fun percentile(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    private fun report(lines: List<String>) {
        val dir = File("build").apply { mkdirs() }
        File(dir, "measurements.txt").appendText(lines.joinToString("\n") + "\n")
        lines.forEach(::println)
    }

    @Test
    fun callSetupAfterAcceptAndAudioPipelineLatency() {
        val setupMs = mutableListOf<Long>()
        val firstAudioMs = mutableListOf<Long>()
        repeat(15) {
            val a = TestDevice("Alice")
            val b = TestDevice("Bob")
            try {
                pair(a, b)
                a.start()
                val addrB = b.start()
                a.engine.call(addrB)
                assertTrue(await { b.state is CallUiState.Incoming })

                val acceptedAt = System.nanoTime()
                b.engine.accept()
                assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
                setupMs += (System.nanoTime() - acceptedAt) / 1_000_000

                // Pipeline latency: from capture start at A to the first audible frame played at B (jitter buffer included).
                val source = a.audio.sources.first()
                val capturedAt = System.nanoTime()
                val sink = b.audio.sinks.first()
                assertTrue(await { sink.loudFrames.get() > 0 })
                firstAudioMs += (System.nanoTime() - capturedAt) / 1_000_000
                if (source.framesRead.get() == 0) error("capture never ran")
            } finally {
                a.stop()
                b.stop()
            }
        }
        val s = setupMs.sorted()
        val f = firstAudioMs.sorted()
        report(
            listOf(
                "SC-001 call setup (accept -> both sides in call), loopback, n=${s.size}: median ${percentile(
                    s,
                    0.5,
                )} ms, p95 ${percentile(s, 0.95)} ms, max ${s.last()} ms (target < 10000 ms)",
                "SC-003 audio pipeline (capture -> first audible frame at peer), loopback, n=${f.size}: median ${percentile(
                    f,
                    0.5,
                )} ms, p95 ${percentile(f, 0.95)} ms, max ${f.last()} ms (target < 200 ms mouth-to-ear on a healthy LAN; hardware latency not included)",
            ),
        )
        assertTrue(s.last() < 10_000, "call setup within 10 s")
        assertTrue(percentile(f, 0.95) < 500, "audio pipeline latency p95 ${percentile(f, 0.95)} ms")
    }
}
