package com.intercom.video.twoway.core.media

/** Audio constants shared by capture, codec and playback: 16 kHz mono, 20 ms frames. */
object AudioFormat {
    const val SAMPLE_RATE = 16_000
    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS // 320
    const val CODEC_NAME = "opus16k20ms"
}

/** Where the call audio comes out (the user can switch during a call). */
enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH }

/** Microphone side. [read] blocks until one 20 ms frame is available. */
interface AudioSource {
    fun start()

    /** Fills [frame] with [AudioFormat.FRAME_SAMPLES] samples. Returns false when stopped. */
    fun read(frame: ShortArray): Boolean

    fun stop()
}

/** Speaker side. */
interface AudioSink {
    fun start()
    fun write(frame: ShortArray)
    fun stop()
}

/** Speech codec. Android builds use Concentus (pure-Java Opus). */
interface OpusCodec {
    /** Encodes one 20 ms frame. */
    fun encode(pcm: ShortArray): ByteArray

    /** Decodes a frame; [data] == null asks the decoder to conceal a lost frame. */
    fun decode(data: ByteArray?, out: ShortArray)
}

/** Creates audio endpoints per call so the engine does not depend on Android. */
interface AudioFactory {
    fun newSource(): AudioSource
    fun newSink(): AudioSink
    fun newCodec(): OpusCodec

    /** Android: communication audio mode + focus. Called when media starts / stops. */
    fun enterCallMode() = Unit
    fun exitCallMode() = Unit

    /** Routes the call audio to [route]; ignored if it is not available. */
    fun setRoute(route: AudioRoute) = Unit

    /** Routes the phone can currently use (Bluetooth only while a headset is connected). */
    fun availableRoutes(): Set<AudioRoute> = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
}
