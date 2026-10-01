package com.intercom.video.twoway.audio

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Build
import com.intercom.video.twoway.core.media.AudioFormat
import com.intercom.video.twoway.core.media.AudioSink

/** Speaker output: low-latency 16 kHz mono stream with voice-communication attributes. */
class AudioPlayback : AudioSink {
    private var track: AudioTrack? = null

    override fun start() {
        val minBuf = AudioTrack.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, AudioFormat.FRAME_SAMPLES * 2 * 4)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(AudioFormat.SAMPLE_RATE)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val t = builder.build()
        t.play()
        track = t
    }

    override fun write(frame: ShortArray) {
        track?.write(frame, 0, frame.size)
    }

    override fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }
}
