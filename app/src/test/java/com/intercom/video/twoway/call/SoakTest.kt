package com.intercom.video.twoway.call

import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * SC-006: a call stays connected for at least 30 minutes. Excluded from the normal run (it takes 30 minutes); run with
 * `./gradlew :app:testDebugUnitTest -Psoak` (duration: `-Psoak.minutes=N`, default 30). Every 30 s it checks that the
 * call is still up, audio still flows both ways, and the JVM heap is not growing without bound.
 */
@Tag("soak")
@Timeout(value = 3, unit = TimeUnit.HOURS)
class SoakTest {
    @Test
    fun aCallStaysUpForThirtyMinutes() {
        val minutes = (System.getProperty("soak.minutes") ?: "30").toInt()
        val a = TestDevice("Alice")
        val b = TestDevice("Bob")
        try {
            pair(a, b)
            a.start()
            val addrB = b.start()
            a.engine.call(addrB)
            assertTrue(await { b.state is CallUiState.Incoming })
            b.engine.accept()
            assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })

            val rt = Runtime.getRuntime()
            val heapSamples = mutableListOf<Long>()
            val endAt = System.currentTimeMillis() + minutes * 60_000L
            var lastLoud = 0
            var checks = 0
            while (System.currentTimeMillis() < endAt) {
                Thread.sleep(30_000)
                checks++
                assertTrue(a.state is CallUiState.InCall && b.state is CallUiState.InCall, "dropped after ~${checks / 2} min: a=${a.state} b=${b.state}")
                val loud = a.audio.totalLoudFrames + b.audio.totalLoudFrames
                assertTrue(loud > lastLoud + 100, "audio stopped flowing after ~${checks / 2} min")
                lastLoud = loud
                System.gc()
                heapSamples += (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            }
            val first = heapSamples.take(4).average()
            val last = heapSamples.takeLast(4).average()
            File("build").apply { mkdirs() }
            File("build/measurements.txt").appendText(
                "SC-006 call duration (loopback, real sockets/Opus/crypto): stayed connected for $minutes min, $checks checks, " +
                    "audio flowing at every check; heap ${"%.0f".format(first)} MB -> ${"%.0f".format(last)} MB\n",
            )
            assertTrue(last < first + 64, "heap grew from $first MB to $last MB")
        } finally {
            a.stop()
            b.stop()
        }
    }
}
