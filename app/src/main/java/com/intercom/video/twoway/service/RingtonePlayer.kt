package com.intercom.video.twoway.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.intercom.video.twoway.core.Logger

/** Rings and vibrates for an incoming call. Replaceable in tests so ringing can be observed. */
interface Ringer {
    val isRinging: Boolean
    fun start(ringtoneUri: String? = null)
    fun stop()
}

/**
 * Plays the chosen (or default) ringtone in a loop and vibrates, honouring the phone's ringer mode:
 * silent = nothing, vibrate = vibration only. Stops on [stop], and on its own after 35 s as a safety net
 * (the call itself times out after 30 s).
 */
class RingtonePlayer(context: Context, private val logger: Logger) : Ringer {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val safetyStop = Runnable { stop() }

    @Volatile
    override var isRinging = false
        private set

    @Synchronized
    override fun start(ringtoneUri: String?) {
        if (isRinging) return
        isRinging = true
        val mode = audioManager.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) playSound(ringtoneUri)
        if (mode != AudioManager.RINGER_MODE_SILENT) vibrate()
        handler.postDelayed(safetyStop, SAFETY_MS)
    }

    @Synchronized
    override fun stop() {
        handler.removeCallbacks(safetyStop)
        if (!isRinging) return
        isRinging = false
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator()?.cancel()
    }

    private fun playSound(ringtoneUri: String?) {
        val uri: Uri = ringtoneUri?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            logger.w(TAG, "cannot play ringtone: ${e.message}")
            player?.release()
            player = null
        }
    }

    private fun vibrate() {
        val v = vibrator() ?: return
        val pattern = longArrayOf(0, 800, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, 0)
        }
    }

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private companion object {
        const val TAG = "RingtonePlayer"
        const val SAFETY_MS = 35_000L
    }
}
