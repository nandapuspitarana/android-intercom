package com.intercom.video.twoway.service

import com.intercom.video.twoway.core.util.HostPort
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.discovery.GatewayProbe
import com.intercom.video.twoway.net.transport.SocketProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything that lives while the app is listening for calls: the call engine, the discovered-device
 * list and the discovery mechanisms. Created by [com.intercom.video.twoway.AppContainer.startRuntime]
 * (hosted by [ListenerService]) so call state never lives in UI classes.
 */
class CallRuntime(
    val self: LocalDevice,
    val engine: CallEngine,
    val registry: DeviceRegistry,
    val pairing: PairingManager,
    /** Ids of devices this phone trusts, for "Paired" badges and deciding whether a tap means call or pair. */
    val pairedIds: Flow<Set<String>>,
    /** SHA-256 of this phone's long-term identity key; the first 16 bytes go into its pairing QR code. */
    val identityFingerprint: ByteArray,
    /** How this phone is connected (shared WiFi, hotspot host or client, none): drives the banner and help. */
    val networkMode: StateFlow<NetworkMode>,
    private val sockets: SocketProvider,
    private val hubCoordinator: HubCoordinator? = null,
    private val onStart: (signalingPort: Int) -> Unit = {},
    private val onStop: () -> Unit = {},
) {
    @Volatile
    var started = false
        private set

    @Synchronized
    fun start() {
        if (started) return
        engine.pairingRouter = pairing
        val port = engine.start()
        onStart(port)
        hubCoordinator?.start()
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        hubCoordinator?.stop()
        onStop()
        engine.stop()
        pairing.shutdown()
        registry.clear()
    }

    /**
     * Adds a phone by a typed `ip` or `ip:port` after it answers a HELLO. Blocking: call off the main thread.
     */
    fun addByAddress(text: String): AddResult {
        val hp = HostPort.parse(text) ?: return AddResult.INVALID
        val r = GatewayProbe.probe(sockets, hp.host, hp.port, self.deviceId, self.name) ?: return AddResult.NO_ANSWER
        if (r.deviceId == self.deviceId) return AddResult.NO_ANSWER
        registry.seen(r.deviceId, r.name, hp.host, hp.port, if (r.isHub) "h" else "p", DiscoverySource.MANUAL)
        return AddResult.ADDED
    }

    enum class AddResult { ADDED, INVALID, NO_ANSWER }
}
