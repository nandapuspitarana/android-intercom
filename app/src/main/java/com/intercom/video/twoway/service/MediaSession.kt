package com.intercom.video.twoway.service

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.crypto.AesGcm
import com.intercom.video.twoway.core.media.AudioFactory
import com.intercom.video.twoway.core.media.AudioFormat
import com.intercom.video.twoway.core.media.JitterBuffer
import com.intercom.video.twoway.core.media.MediaPacket
import com.intercom.video.twoway.core.media.Playout
import com.intercom.video.twoway.net.transport.UdpMediaSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-way voice for one call: a capture thread (mic -> Opus -> encrypted UDP packet), a receive thread
 * (UDP -> decrypt -> jitter buffer) and a playout thread (jitter buffer -> Opus -> speaker, every 20 ms).
 * While muted, nothing from the microphone is sent; a 1-byte keep-alive per second keeps the path open
 * (contracts/media.md). Contains no android.* imports so loopback tests can run it on the JVM.
 */
class MediaSession(
    private val socket: UdpMediaSocket,
    private val remoteHost: InetAddress,
    @Volatile var remotePort: Int,
    sendCipher: AesGcm,
    recvCipher: AesGcm,
    private val sessionTag: Int,
    private val audio: AudioFactory,
    private val clock: Clock,
    private val logger: Logger,
    private val onFirstValidPacket: () -> Unit,
) {
    private val sendCipher = sendCipher
    private val receiver = MediaPacket.Receiver(recvCipher, sessionTag)
    private val jitter = JitterBuffer()

    @Volatile
    private var running = false

    @Volatile
    var muted = false

    /** Time (engine clock) of the last valid packet from the peer, or -1. */
    @Volatile
    var lastValidMs = -1L
        private set

    val voiceSent = AtomicInteger()
    val voiceReceived = AtomicInteger()
    val keepAlivesReceived = AtomicInteger()

    @Volatile
    private var firstPacketSeen = false

    @Volatile
    private var micActive = false

    /** True while the microphone is being captured and sent (drives the on-screen mic indicator). */
    val isMicLive: Boolean get() = running && micActive && !muted

    private val threads = mutableListOf<Thread>()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        audio.enterCallMode()
        val source = audio.newSource()
        val sink = audio.newSink()
        val codec = audio.newCodec()
        source.start()
        sink.start()
        micActive = true
        threads += thread("media-capture") { captureLoop(source, codec) }
        threads += thread("media-receive") { receiveLoop() }
        threads += thread("media-playout") { playoutLoop(sink, codec) }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        micActive = false
        socket.close()
        threads.forEach { it.interrupt() }
        threads.forEach { runCatching { it.join(500) } }
        threads.clear()
        audio.exitCallMode()
    }

    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.also { it.start() }

    private fun captureLoop(source: com.intercom.video.twoway.core.media.AudioSource, codec: com.intercom.video.twoway.core.media.OpusCodec) {
        val pcm = ShortArray(AudioFormat.FRAME_SAMPLES)
        var seq = 0L
        var lastKeepAlive = Long.MIN_VALUE
        try {
            while (running) {
                if (!source.read(pcm)) break
                val now = clock.nowMs()
                if (muted) {
                    if (lastKeepAlive == Long.MIN_VALUE || now - lastKeepAlive >= KEEPALIVE_MS) {
                        lastKeepAlive = now
                        sendPacket(MediaPacket.TYPE_KEEPALIVE, seq++, byteArrayOf(0))
                    }
                } else {
                    sendPacket(MediaPacket.TYPE_VOICE, seq++, codec.encode(pcm))
                    voiceSent.incrementAndGet()
                }
            }
        } catch (e: RuntimeException) {
            if (running) logger.e(TAG, "capture failed", e)
        } finally {
            source.stop()
        }
    }

    private fun sendPacket(type: Int, seq: Long, payload: ByteArray) {
        val ts = (seq * AudioFormat.FRAME_SAMPLES) and 0xFFFFFFFFL
        val packet = MediaPacket.seal(sendCipher, type, seq, ts, sessionTag, payload)
        socket.send(remoteHost, remotePort, packet)
    }

    private fun receiveLoop() {
        while (running) {
            val data = socket.receive() ?: continue
            val p = receiver.open(data) ?: continue
            val now = clock.nowMs()
            lastValidMs = now
            if (!firstPacketSeen) {
                firstPacketSeen = true
                onFirstValidPacket()
            }
            if (p.type == MediaPacket.TYPE_VOICE) {
                voiceReceived.incrementAndGet()
                jitter.put(p.extendedSeq, p.timestamp, now, p.payload)
            } else {
                keepAlivesReceived.incrementAndGet()
            }
        }
    }

    private fun playoutLoop(sink: com.intercom.video.twoway.core.media.AudioSink, codec: com.intercom.video.twoway.core.media.OpusCodec) {
        val out = ShortArray(AudioFormat.FRAME_SAMPLES)
        val silence = ShortArray(AudioFormat.FRAME_SAMPLES)
        var next = System.nanoTime()
        try {
            while (running) {
                when (val p = jitter.poll()) {
                    is Playout.Frame -> {
                        codec.decode(p.payload, out)
                        sink.write(out)
                    }
                    Playout.Lost -> {
                        codec.decode(null, out)
                        sink.write(out)
                    }
                    Playout.Silence -> sink.write(silence)
                }
                next += AudioFormat.FRAME_MS * 1_000_000L
                val wait = (next - System.nanoTime()) / 1_000_000L
                if (wait > 0) {
                    Thread.sleep(wait)
                } else if (wait < -200) {
                    next = System.nanoTime()
                }
            }
        } catch (_: InterruptedException) {
            // stopping
        } catch (e: RuntimeException) {
            if (running) logger.e(TAG, "playout failed", e)
        } finally {
            sink.stop()
        }
    }

    private companion object {
        const val TAG = "MediaSession"
        const val KEEPALIVE_MS = 1_000L
    }
}
