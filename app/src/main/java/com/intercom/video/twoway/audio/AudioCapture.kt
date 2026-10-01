package com.intercom.video.twoway.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.media.AudioFormat
import com.intercom.video.twoway.core.media.AudioSource

/**
 * Microphone capture for calls: VOICE_COMMUNICATION source, 16 kHz mono, with the platform echo
 * canceller, noise suppressor and gain control enabled where the device offers them (research R9).
 * The caller must hold the RECORD_AUDIO permission.
 */
class AudioCapture(private val logger: Logger) : AudioSource {
    private var record: AudioRecord? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @Volatile
    private var stopped = false

    @SuppressLint("MissingPermission")
    override fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufBytes = maxOf(minBuf, AudioFormat.FRAME_SAMPLES * 2 * 4)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioFormat.SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            bufBytes,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "microphone unavailable" }
        enableEffects(r.audioSessionId)
        stopped = false
        r.startRecording()
        record = r
    }

    private fun enableEffects(session: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(session)?.also {
                it.enabled = true
                effects += it
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(session)?.also {
                it.enabled = true
                effects += it
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(session)?.also {
                it.enabled = true
                effects += it
            }
        }
        logger.d(TAG, "voice effects enabled: ${effects.size}")
    }

    override fun read(frame: ShortArray): Boolean {
        val r = record ?: return false
        var off = 0
        while (off < frame.size) {
            if (stopped) return false
            val n = r.read(frame, off, frame.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    override fun stop() {
        stopped = true
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
