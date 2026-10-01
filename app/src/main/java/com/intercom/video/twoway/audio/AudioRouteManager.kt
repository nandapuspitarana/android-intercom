package com.intercom.video.twoway.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.media.AudioRoute

/**
 * Chooses where call audio comes out: earpiece, speaker or a Bluetooth headset (research R9). Android 12+ uses the
 * communication-device APIs; older versions use the speakerphone flag and Bluetooth SCO.
 */
class AudioRouteManager(context: Context, private val logger: Logger) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Routes the phone can use right now. Earpiece and speaker always; Bluetooth only with a headset connected. */
    fun availableRoutes(): Set<AudioRoute> {
        val routes = linkedSetOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)
        if (bluetoothDevice() != null || legacyBluetoothConnected()) routes += AudioRoute.BLUETOOTH
        return routes
    }

    fun setRoute(route: AudioRoute) {
        if (route !in availableRoutes()) {
            logger.d(TAG, "route $route not available")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = when (route) {
                AudioRoute.EARPIECE -> communicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.SPEAKER -> communicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.BLUETOOTH -> bluetoothDevice()
            }
            if (device != null) audioManager.setCommunicationDevice(device) else logger.d(TAG, "no device for $route")
        } else {
            @Suppress("DEPRECATION")
            when (route) {
                AudioRoute.EARPIECE -> {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.isSpeakerphoneOn = false
                }
                AudioRoute.SPEAKER -> {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    audioManager.isSpeakerphoneOn = true
                }
                AudioRoute.BLUETOOTH -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }
        }
    }

    /** Back to the default (earpiece) when a call ends. */
    fun reset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
        }
    }

    private fun communicationDevice(type: Int): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.availableCommunicationDevices.firstOrNull { it.type == type } else null

    private fun bluetoothDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    private fun legacyBluetoothConnected(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private companion object {
        const val TAG = "AudioRouteManager"
    }
}
