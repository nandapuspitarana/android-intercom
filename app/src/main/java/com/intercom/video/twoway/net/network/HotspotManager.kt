package com.intercom.video.twoway.net.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.intercom.video.twoway.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** State of the hotspot this app started. */
sealed interface HotspotState {
    data object Off : HotspotState
    data object Starting : HotspotState

    /** Running: other phones join with [ssid] and [password]. */
    data class On(val ssid: String, val password: String) : HotspotState

    data class Failed(val reason: String) : HotspotState
}

/**
 * Starts a local-only hotspot from inside the app (Android 8+) and exposes the network name and password so the
 * other phone can join (also as a WiFi QR code). On older Android versions, and for the system hotspot, the user
 * turns the hotspot on in Settings (guided by the hotspot screen). Needs NEARBY_WIFI_DEVICES (Android 13+) or
 * location access (Android 8-12) to be granted first.
 */
open class HotspotManager(context: Context, private val logger: Logger) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Off)
    open val state: StateFlow<HotspotState> = _state

    /** False below Android 8: the user must start the hotspot in system settings. */
    open val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @SuppressLint("MissingPermission", "NewApi") // only reached when isSupported (Android 8+)
    open fun start() {
        if (!isSupported || _state.value is HotspotState.On || _state.value is HotspotState.Starting) return
        _state.value = HotspotState.Starting
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = r
                        _state.value = describe(r)
                    }

                    override fun onStopped() {
                        reservation = null
                        _state.value = HotspotState.Off
                    }

                    override fun onFailed(reason: Int) {
                        logger.w(TAG, "local-only hotspot failed: $reason")
                        _state.value = HotspotState.Failed(
                            when (reason) {
                                ERROR_NO_CHANNEL -> "no channel"
                                ERROR_INCOMPATIBLE_MODE -> "incompatible mode"
                                ERROR_TETHERING_DISALLOWED -> "tethering not allowed"
                                else -> "error $reason"
                            },
                        )
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: SecurityException) {
            _state.value = HotspotState.Failed("permission denied")
        } catch (e: IllegalStateException) {
            _state.value = HotspotState.Failed(e.message ?: "not available")
        }
    }

    open fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) reservation?.close()
        reservation = null
        _state.value = HotspotState.Off
    }

    @SuppressLint("NewApi") // called only from the Android 8+ hotspot callback
    @Suppress("DEPRECATION")
    private fun describe(r: WifiManager.LocalOnlyHotspotReservation): HotspotState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val c = r.softApConfiguration
        HotspotState.On(c.ssid.orEmpty(), c.passphrase.orEmpty())
    } else {
        val c = r.wifiConfiguration
        HotspotState.On(c?.SSID.orEmpty().trim('"'), c?.preSharedKey.orEmpty().trim('"'))
    }

    companion object {
        private const val TAG = "HotspotManager"

        /** The text of a WiFi join QR code (understood by Android's camera and Google Lens): `WIFI:T:WPA;S:<ssid>;P:<password>;;`. */
        fun wifiQrText(ssid: String, password: String): String {
            fun esc(s: String) = buildString {
                for (c in s) {
                    if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
                    append(c)
                }
            }
            return "WIFI:T:WPA;S:${esc(ssid)};P:${esc(password)};;"
        }
    }
}
