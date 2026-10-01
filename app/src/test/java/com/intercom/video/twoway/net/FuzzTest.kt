package com.intercom.video.twoway.net

import com.intercom.video.twoway.core.crypto.Envelope
import com.intercom.video.twoway.core.pairing.PairingLink
import com.intercom.video.twoway.core.protocol.FrameCodec
import com.intercom.video.twoway.core.protocol.FrameException
import com.intercom.video.twoway.core.protocol.Hello
import com.intercom.video.twoway.core.protocol.Invite
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.protocol.RelayFrame
import com.intercom.video.twoway.net.discovery.Announce
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.testing.TestDevice
import com.intercom.video.twoway.testing.await
import com.intercom.video.twoway.testing.pair
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * FR-017: malformed, oversized and random input from the network is ignored without crashing and without disturbing an
 * active call. Deterministic seeds so failures reproduce.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FuzzTest {
    private val devices = mutableListOf<TestDevice>()

    private fun device(name: String) = TestDevice(name).also { devices += it }

    @AfterEach
    fun tearDown() = devices.forEach { it.stop() }

    private fun callInProgress(): Pair<TestDevice, TestDevice> {
        val a = device("Alice")
        val b = device("Bob")
        pair(a, b)
        a.start()
        val addrB = b.start()
        a.engine.call(addrB)
        assertTrue(await { b.state is CallUiState.Incoming })
        b.engine.accept()
        assertTrue(await { a.state is CallUiState.InCall && b.state is CallUiState.InCall })
        assertTrue(await { a.audio.totalLoudFrames > 5 && b.audio.totalLoudFrames > 5 })
        return a to b
    }

    private fun assertCallStillWorks(a: TestDevice, b: TestDevice) {
        assertTrue(a.state is CallUiState.InCall && b.state is CallUiState.InCall, "a=${a.state} b=${b.state}")
        val before = a.audio.totalLoudFrames
        assertTrue(await { a.audio.totalLoudFrames > before + 5 }, "audio must keep flowing")
    }

    // --- parsers: only the documented exceptions, never anything else ---------------------------------------------------

    @Test
    fun theMessageParserOnlyEverThrowsJsonExceptionOnRandomInput() {
        val rnd = Random(42)
        val valid = JsonMessageCodec.encode(Hello("a".repeat(32), "Phone", "p"))
        repeat(3_000) { i ->
            val text = when (i % 4) {
                0 -> String(CharArray(rnd.nextInt(300)) { rnd.nextInt(0x2FF).toChar() })
                1 -> valid.substring(0, rnd.nextInt(valid.length)) // truncated
                2 -> {
                    val start = rnd.nextInt(valid.length)
                    valid.replaceRange(start, minOf(valid.length, start + 1 + rnd.nextInt(3)), rnd.nextInt(256).toChar().toString())
                }
                else -> "{\"t\":\"" + String(CharArray(rnd.nextInt(20)) { ('A' + rnd.nextInt(26)) }) + "\"}"
            }
            try {
                JsonMessageCodec.decode(text)
            } catch (e: JsonException) {
                // expected for malformed input
            }
            try {
                Envelope.decode(text)
            } catch (e: JsonException) {
                // expected
            }
            Announce.parse(text.toByteArray(), minOf(text.length, 256)) // returns null, never throws
            PairingLink.parse(text) // returns null, never throws
        }
    }

    @Test
    fun theFrameReaderRejectsRandomStreamsWithoutHugeAllocations() {
        val rnd = Random(7)
        repeat(2_000) {
            val bytes = ByteArray(rnd.nextInt(5_000)).also { rnd.nextBytes(it) }
            try {
                FrameCodec.read(ByteArrayInputStream(bytes))
            } catch (e: FrameException) {
                // bad length
            } catch (e: java.io.EOFException) {
                // short stream
            }
        }
    }

    @Test
    fun deeplyNestedAndHugeJsonIsRefused() {
        val nested = "{\"t\":\"HELLO\",\"x\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}"
        try {
            JsonMessageCodec.decode(nested)
            org.junit.jupiter.api.Assertions.fail<Unit>("must be refused")
        } catch (e: JsonException) {
            // depth limit
        }
        val bigRelay = RelayFrame("s", ByteArray(2_500))
        JsonMessageCodec.decode(JsonMessageCodec.encode(bigRelay)) // the largest allowed frame still parses
    }

    // --- the signaling port --------------------------------------------------------------------------------------------------

    @Test
    fun junkOnTheSignalingPortDoesNotDisturbAnActiveCall() {
        val (a, b) = callInProgress()
        val port = b.engine.signalingPort
        val rnd = Random(1)

        // 1. random bytes (a "length" that is almost surely invalid)
        repeat(30) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    s.getOutputStream().write(ByteArray(rnd.nextInt(2_000) + 1).also { rnd.nextBytes(it) })
                    s.getOutputStream().flush()
                }
            } catch (_: java.io.IOException) {
                // refused (rate limit) or reset: fine
            }
        }
        // 2. a 10 MB frame announcement followed by data
        try {
            Socket("127.0.0.1", port).use { s ->
                val header = byteArrayOf(0, -96, 0, 0) // 0x00A00000 = 10 485 760
                s.getOutputStream().write(header)
                s.getOutputStream().write(ByteArray(65_536) { 7 })
                s.getOutputStream().flush()
            }
        } catch (_: java.io.IOException) {
            // the server rejected the length and closed the connection, so later writes are reset: exactly what we want
        }
        // 3. well-framed but hostile JSON
        for (junk in listOf(
            "{}",
            "[]",
            "null",
            "{\"t\":\"INVITE\"}",
            "{\"t\":\"INVITE\",\"callId\":5}",
            "{\"c\":1,\"n\":\"x\",\"d\":\"y\"}",
            "{\"t\":\"RELAY\",\"sessionId\":\"s\",\"frame\":\"!!\"}",
            "{\"t\":\"HUB_REGISTER\",\"id\":\"x\",\"n\":\"y\",\"p\":1}",
        )) {
            try {
                Socket("127.0.0.1", port).use { s ->
                    FrameCodec.write(s.getOutputStream(), junk.toByteArray())
                    Thread.sleep(20)
                }
            } catch (_: java.io.IOException) {
                // refused or reset: fine
            }
        }
        // 4. a valid-looking INVITE with a bad MAC while a call is active (must not even produce BUSY for the real call)
        try {
            Socket("127.0.0.1", port).use { s ->
                val bad = Invite("0123456789abcdef0123456789abcdef", a.deviceId, "Alice", "AAAAAAAAAAAAAAAAAAAAAA==", 40000, System.currentTimeMillis(), "AAAA")
                FrameCodec.write(s.getOutputStream(), JsonMessageCodec.encodeBytes(bad))
            }
        } catch (_: java.io.IOException) {
            // refused: fine
        }
        Thread.sleep(500)
        assertCallStillWorks(a, b)
    }

    @Test
    fun aFloodOfConnectionsIsLimitedAndTheCallSurvives() {
        val (a, b) = callInProgress()
        val port = b.engine.signalingPort
        val sockets = mutableListOf<Socket>()
        try {
            repeat(100) {
                try {
                    sockets += Socket("127.0.0.1", port) // opened and left silent
                } catch (_: java.io.IOException) {
                    // refused: fine
                }
            }
            Thread.sleep(500)
            assertTrue(b.engine.connectionLimiter.openCount() <= 8, "open connections stay within the limit: ${b.engine.connectionLimiter.openCount()}")
            assertCallStillWorks(a, b)
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun junkDatagramsOnTheMediaPortDoNotDisturbAnActiveCall() {
        val (a, b) = callInProgress()
        // find B's media port: the UDP port its media socket listens on
        val mediaPort = b.engine.currentMedia?.let { localPortOf(it) } ?: error("no media")
        val rnd = Random(3)
        DatagramSocket().use { s ->
            val dst = InetAddress.getByName("127.0.0.1")
            repeat(2_000) {
                val data = ByteArray(rnd.nextInt(1_300)).also { rnd.nextBytes(it) }
                if (data.isNotEmpty()) s.send(DatagramPacket(data, data.size, dst, mediaPort))
            }
            // packets that look like media (right version byte and length) but are not authentic
            repeat(500) {
                val data = ByteArray(80).also { rnd.nextBytes(it) }
                data[0] = 0x10
                s.send(DatagramPacket(data, data.size, dst, mediaPort))
            }
        }
        Thread.sleep(500)
        assertCallStillWorks(a, b)
    }

    /** The local UDP port of a call's media session, read through reflection (it is not part of the public API). */
    private fun localPortOf(media: com.intercom.video.twoway.service.MediaSession): Int {
        val socketField = media.javaClass.getDeclaredField("socket").apply { isAccessible = true }
        val udp = socketField.get(media) as com.intercom.video.twoway.net.transport.UdpMediaSocket
        return udp.localPort
    }
}
