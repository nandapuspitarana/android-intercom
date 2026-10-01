package com.intercom.video.twoway.audio

import com.intercom.video.twoway.core.media.AudioFormat
import com.intercom.video.twoway.core.media.OpusCodec
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * Opus 16 kHz mono, 20 ms frames (320 samples), about 24 kbps with in-band FEC, using Concentus
 * (pure Java, so it works on minSdk 24 where MediaCodec has no Opus and needs no NDK).
 * No android.* imports: also used by the JVM loopback tests.
 */
class ConcentusOpusCodec : OpusCodec {
    private val encoder = OpusEncoder(AudioFormat.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(BITRATE)
        setUseInbandFEC(true)
        setPacketLossPercent(5)
        setComplexity(5)
    }
    private val decoder = OpusDecoder(AudioFormat.SAMPLE_RATE, 1)
    private val encodeBuffer = ByteArray(MAX_PACKET)

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioFormat.FRAME_SAMPLES) { "expected one 20 ms frame" }
        val n = encoder.encode(pcm, 0, AudioFormat.FRAME_SAMPLES, encodeBuffer, 0, encodeBuffer.size)
        return encodeBuffer.copyOf(n)
    }

    override fun decode(data: ByteArray?, out: ShortArray) {
        require(out.size >= AudioFormat.FRAME_SAMPLES)
        try {
            if (data == null) {
                decoder.decode(null, 0, 0, out, 0, AudioFormat.FRAME_SAMPLES, false) // packet loss concealment
            } else {
                decoder.decode(data, 0, data.size, out, 0, AudioFormat.FRAME_SAMPLES, false)
            }
        } catch (e: io.github.jaredmdobson.concentus.OpusException) {
            out.fill(0) // corrupt frame: play silence rather than noise
        }
    }

    private companion object {
        const val BITRATE = 24_000
        const val MAX_PACKET = 400
    }
}
