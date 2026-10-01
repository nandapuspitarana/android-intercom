package com.intercom.video.twoway.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.media.AudioFactory
import com.intercom.video.twoway.core.media.AudioRoute
import com.intercom.video.twoway.core.media.AudioSink
import com.intercom.video.twoway.core.media.AudioSource
import com.intercom.video.twoway.core.media.OpusCodec

/** Real audio for a phone: mic, speaker, Concentus Opus, communication mode and audio focus during calls. */
class AndroidAudioFactory(context: Context, private val logger: Logger) : AudioFactory {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null
    private val routes = AudioRouteManager(context, logger)

    override fun setRoute(route: AudioRoute) = routes.setRoute(route)

    override fun availableRoutes(): Set<AudioRoute> = routes.availableRoutes()

    override fun newSource(): AudioSource = AudioCapture(logger)

    override fun newSink(): AudioSink = AudioPlayback()

    override fun newCodec(): OpusCodec = ConcentusOpusCodec()

    @Synchronized
    override fun enterCallMode() {
        previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
    }

    @Synchronized
    override fun exitCallMode() {
        routes.reset()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        audioManager.mode = previousMode
    }
}
