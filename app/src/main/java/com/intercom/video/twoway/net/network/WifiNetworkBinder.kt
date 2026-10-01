package com.intercom.video.twoway.net.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.NetworkModeResolver
import com.intercom.video.twoway.net.transport.SocketProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Keeps call traffic on the WiFi network (research R3). Observes a WiFi `Network` WITHOUT requiring
 * internet capability or validation, binds the process to it, and holds a WifiLock + MulticastLock
 * while started. Also acts as the [SocketProvider] for the engine.
 */
class WifiNetworkBinder(context: Context, private val logger: Logger) : SocketProvider {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _network = MutableStateFlow<Network?>(null)
    val network: StateFlow<Network?> = _network

    private val _mode = MutableStateFlow(NetworkMode.NONE)
    val mode: StateFlow<NetworkMode> = _mode

    @Volatile
    private var hubReachable = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    fun start() {
        if (callback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // local WiFi may have no internet
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logger.d(TAG, "WiFi network available")
                cm.bindProcessToNetwork(network)
                _network.value = network
                refreshMode()
            }

            override fun onLost(network: Network) {
                logger.d(TAG, "WiFi network lost")
                if (_network.value == network) {
                    cm.bindProcessToNetwork(null)
                    _network.value = null
                }
                refreshMode()
            }
        }
        callback = cb
        cm.registerNetworkCallback(request, cb)
        acquireLocks()
        refreshMode()
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        runCatching { cm.bindProcessToNetwork(null) }
        _network.value = null
        wifiLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        multicastLock = null
        refreshMode()
    }

    /** Called by the hub client (hotspot story) when the gateway answered as a hub. */
    fun setHubReachable(reachable: Boolean) {
        hubReachable = reachable
        refreshMode()
    }

    private fun acquireLocks() {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi.createWifiLock(mode, "twoway:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = wifi.createMulticastLock("twoway:multicast").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Re-evaluates the network mode (the hotspot interface can come and go without a network callback). */
    fun refreshMode() {
        _mode.value = NetworkModeResolver.resolve(
            wifiConnected = _network.value != null,
            apInterfaceUp = hotspotInterfaceUp(),
            hubReachable = hubReachable,
        )
    }

    /** True if a typical Android soft-AP interface is up with an IPv4 address (this phone shares its hotspot). */
    private fun hotspotInterfaceUp(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().asSequence().any { ni ->
            val n = ni.name.lowercase()
            (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wlan1")) &&
                ni.isUp &&
                ni.inetAddresses.asSequence().any { it is java.net.Inet4Address }
        }
    } catch (e: java.net.SocketException) {
        false
    }

    /** The DHCP gateway of the current WiFi network (the hotspot phone when on a hotspot), or null. */
    @Suppress("DEPRECATION")
    fun gatewayAddress(): String? {
        val gw = wifi.dhcpInfo?.gateway ?: return null
        if (gw == 0) return null
        return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
    }

    // --- SocketProvider: every outgoing socket is bound to the WiFi network -------------------------
    override fun connect(host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        _network.value?.bindSocket(s)
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        return s
    }

    override fun serverSocket(port: Int): ServerSocket = ServerSocket().also {
        it.reuseAddress = true
        it.bind(InetSocketAddress(port))
    }

    override fun datagramSocket(port: Int): DatagramSocket {
        val s = DatagramSocket(null)
        s.reuseAddress = true
        _network.value?.bindSocket(s)
        s.bind(InetSocketAddress(port))
        return s
    }

    override fun multicastSocket(port: Int): MulticastSocket {
        val s = MulticastSocket(null)
        s.reuseAddress = true
        _network.value?.bindSocket(s)
        s.bind(InetSocketAddress(port))
        return s
    }

    private companion object {
        const val TAG = "WifiNetworkBinder"
    }
}
