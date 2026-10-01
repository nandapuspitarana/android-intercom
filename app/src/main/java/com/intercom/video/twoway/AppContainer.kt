package com.intercom.video.twoway

import android.content.Context
import com.intercom.video.twoway.audio.AndroidAudioFactory
import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.SystemClock
import com.intercom.video.twoway.core.call.EndReason
import com.intercom.video.twoway.data.AppDatabase
import com.intercom.video.twoway.data.CallHistoryRepository
import com.intercom.video.twoway.data.IdentityRepository
import com.intercom.video.twoway.data.PairedDeviceRepository
import com.intercom.video.twoway.data.SecretCipher
import com.intercom.video.twoway.data.SecretStore
import com.intercom.video.twoway.data.SettingsStore
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.discovery.MulticastDiscovery
import com.intercom.video.twoway.net.discovery.NsdDiscovery
import com.intercom.video.twoway.net.network.HotspotManager
import com.intercom.video.twoway.net.network.HubClient
import com.intercom.video.twoway.net.network.HubRegistry
import com.intercom.video.twoway.net.network.WifiNetworkBinder
import com.intercom.video.twoway.service.AndroidLogger
import com.intercom.video.twoway.service.CallEngine
import com.intercom.video.twoway.service.CallEventSink
import com.intercom.video.twoway.service.CallNotificationFactory
import com.intercom.video.twoway.service.CallRuntime
import com.intercom.video.twoway.service.HubCoordinator
import com.intercom.video.twoway.service.LocalDevice
import com.intercom.video.twoway.service.LocalIdentityOps
import com.intercom.video.twoway.service.PairingManager
import com.intercom.video.twoway.service.Ringer
import com.intercom.video.twoway.service.RingtonePlayer
import com.intercom.video.twoway.service.RoomTrustStore
import com.intercom.video.twoway.service.TrustStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Simple manual dependency container (no global mutable statics). Functional tests install a
 * subclass that swaps in an in-memory database, a fake clock, fake audio and a fake runtime.
 */
open class AppContainer(val appContext: Context) {
    open val clock: Clock = SystemClock
    open val logger: Logger = AndroidLogger

    open val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    open val secretCipher: SecretCipher by lazy { SecretStore() }
    open val settings: SettingsStore by lazy { SettingsStore(appContext) }
    open val identity: IdentityRepository by lazy { IdentityRepository(database.identityDao()) }
    open val networkBinder: WifiNetworkBinder by lazy { WifiNetworkBinder(appContext, logger) }
    open val ringer: Ringer by lazy { RingtonePlayer(appContext, logger) }
    open val hotspot: HotspotManager by lazy { HotspotManager(appContext, logger) }
    open val history: CallHistoryRepository by lazy { CallHistoryRepository(database.callHistoryDao()) }
    open val pairedDevices: PairedDeviceRepository by lazy { PairedDeviceRepository(database.pairedDeviceDao(), secretCipher) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Records every finished call in the history and raises a missed-call notification for unanswered ones. */
    protected fun callEventSink() = CallEventSink { record ->
        appScope.launch { history.record(record) }
        if (!record.outgoing && record.reason == EndReason.MISSED) {
            CallNotificationFactory.notify(
                appContext,
                CallNotificationFactory.ID_MISSED,
                CallNotificationFactory.missed(com.intercom.video.twoway.ui.LocaleHelper.wrap(appContext), record.peerName),
            )
        }
    }

    private val _runtime = MutableStateFlow<CallRuntime?>(null)

    /** Non-null while the app is listening; the UI and the service both observe this. */
    val runtime: StateFlow<CallRuntime?> = _runtime
    private val runtimeLock = Mutex()

    /** Builds the call runtime. Overridden in tests. */
    protected open suspend fun buildRuntime(): CallRuntime {
        val me = identity.get()
        val registry = DeviceRegistry(clock, me.deviceId)
        val self = LocalDevice(me.deviceId, me.displayName)
        val trust = TrustStoreFactory.create(RoomTrustStore(database.pairedDeviceDao(), secretCipher))
        val identityOps = object : LocalIdentityOps {
            override val deviceId = me.deviceId
            override val name get() = self.name
            override val publicKey = me.publicKey
            override fun sign(data: ByteArray) = identity.sign(data)
        }
        val engine = CallEngine(
            self = { self },
            trust = trust,
            sockets = networkBinder,
            audio = AndroidAudioFactory(appContext, logger),
            clock = clock,
            logger = logger,
            sink = callEventSink(),
        )
        val nsd = NsdDiscovery(appContext, registry, networkBinder, logger, me.deviceId, { self.name }, { engine.signalingPort })
        val multicast = MulticastDiscovery(
            networkBinder,
            registry,
            clock,
            logger,
            me.deviceId,
            { self.name },
            { engine.signalingPort },
        )
        val pairing = PairingManager(identityOps, pairedDevices, networkBinder, clock, logger)

        // Hotspot support: this phone is the hub when it shares a hotspot, or a hub client when its gateway is one.
        val hubRegistry = HubRegistry(networkBinder, clock, logger, engine.connectionLimiter)
        val hubClient = HubClient(
            networkBinder,
            registry,
            clock,
            { self },
            { engine.signalingPort },
            onInboundChannel = { engine.onRelayedChannel(it) },
            logger = logger,
        )
        val coordinator = HubCoordinator(
            engine = engine, hubRegistry = hubRegistry, hubClient = hubClient, registry = registry, sockets = networkBinder,
            self = { self },
            mode = { networkBinder.mode.value },
            gateway = { networkBinder.gatewayAddress()?.let { it to CallEngine.DEFAULT_PORT } },
            onHubReachable = networkBinder::setHubReachable,
            onTick = networkBinder::refreshMode,
            logger = logger,
        )
        return CallRuntime(
            self = self, engine = engine, registry = registry, pairing = pairing,
            pairedIds = pairedDevices.observeTrustedIds(),
            identityFingerprint = IdentityRepository.fingerprintOf(me.publicKey),
            networkMode = networkBinder.mode,
            sockets = networkBinder,
            hubCoordinator = coordinator,
            onStart = {
                networkBinder.start()
                nsd.start()
                multicast.start()
            },
            onStop = {
                multicast.stop()
                nsd.stop()
                networkBinder.stop()
            },
        )
    }

    /** Starts listening (idempotent). Called by [com.intercom.video.twoway.service.ListenerService]. */
    suspend fun startRuntime() = runtimeLock.withLock {
        val existing = _runtime.value
        if (existing != null && existing.started) return@withLock
        val rt = existing ?: buildRuntime()
        rt.start()
        _runtime.value = rt
    }

    suspend fun stopRuntime() = runtimeLock.withLock {
        _runtime.value?.stop()
        _runtime.value = null
    }
}
