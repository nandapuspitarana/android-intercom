package com.intercom.video.twoway.functional

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.intercom.video.twoway.AppContainer
import com.intercom.video.twoway.TwoWayApp
import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.SystemClock
import com.intercom.video.twoway.data.AppDatabase
import com.intercom.video.twoway.data.IdentityRepository
import com.intercom.video.twoway.net.NetworkMode
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.network.HubClient
import com.intercom.video.twoway.net.network.HubRegistry
import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.net.transport.SocketProvider
import com.intercom.video.twoway.service.CallEngine
import com.intercom.video.twoway.service.CallRuntime
import com.intercom.video.twoway.service.HubCoordinator
import com.intercom.video.twoway.service.LocalDevice
import com.intercom.video.twoway.service.PairingManager
import com.intercom.video.twoway.service.PeerAddress
import com.intercom.video.twoway.service.RoomTrustStore
import com.intercom.video.twoway.service.TrustStore
import com.intercom.video.twoway.testing.FakeAudioFactory
import com.intercom.video.twoway.testing.TestIdentity
import com.intercom.video.twoway.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Container for functional tests: localhost sockets, fake audio and ringer, an in-memory database holding the paired
 * devices (secrets are still encrypted with the real keystore cipher), and scripted discovery.
 */
class TestAppContainer(
    context: Context,
    override val clock: Clock = SystemClock,
    /** Sockets of the app under test; hotspot tests pass an isolated provider that blocks phone-to-phone connections. */
    val sockets: SocketProvider = PlainSocketProvider,
) : AppContainer(context) {
    val audio = FakeAudioFactory()
    val self = LocalDevice("c".repeat(32), "Test Phone")
    val identityOps = TestIdentity(self.deviceId, self.name)
    override val ringer = FakeRinger()
    override val database: AppDatabase by lazy { AppDatabase.inMemory(appContext) }
    val trust: TrustStore by lazy { RoomTrustStore(database.pairedDeviceDao(), secretCipher) }

    /** The network mode the app believes it is in. Tests set LAN / NONE / HOTSPOT_HOST; joining a hub makes it HOTSPOT_CLIENT. */
    val mode = MutableStateFlow(NetworkMode.LAN)

    /** What the "DHCP gateway" is for the hub probe (host, port); null = no gateway. */
    @Volatile
    var gateway: Pair<String, Int>? = null

    /** How often the hub coordinator re-checks its role (fast in tests). */
    @Volatile
    var coordinatorIntervalMs = 300L

    private var hubRegistryRef: HubRegistry? = null
    val hubRegistry: HubRegistry? get() = hubRegistryRef

    override suspend fun buildRuntime(): CallRuntime {
        val registry = DeviceRegistry(clock, self.deviceId)
        val engine = CallEngine(
            self = { self },
            trust = trust,
            sockets = sockets,
            audio = audio,
            clock = clock,
            logger = logger,
            sink = callEventSink(),
        )
        val pairing = PairingManager(identityOps, pairedDevices, sockets, clock, logger)
        val hubRegistry = HubRegistry(sockets, clock, logger, engine.connectionLimiter).also { hubRegistryRef = it }
        val hubClient = HubClient(
            sockets,
            registry,
            clock,
            { self },
            { engine.signalingPort },
            onInboundChannel = { engine.onRelayedChannel(it) },
            logger = logger,
        )
        val coordinator = HubCoordinator(
            engine = engine, hubRegistry = hubRegistry, hubClient = hubClient, registry = registry, sockets = sockets,
            self = { self },
            mode = { mode.value },
            gateway = { gateway },
            onHubReachable = { reachable ->
                if (reachable && mode.value == NetworkMode.LAN) mode.value = NetworkMode.HOTSPOT_CLIENT
                if (!reachable && mode.value == NetworkMode.HOTSPOT_CLIENT) mode.value = NetworkMode.LAN
            },
            logger = logger,
            intervalMs = coordinatorIntervalMs,
        )
        return CallRuntime(
            self = self, engine = engine, registry = registry, pairing = pairing,
            pairedIds = pairedDevices.observeTrustedIds(),
            identityFingerprint = IdentityRepository.fingerprintOf(identityOps.publicKey),
            networkMode = mode,
            sockets = sockets,
            hubCoordinator = coordinator,
        )
    }

    val runtimeOrNull: CallRuntime? get() = runtime.value
}

/**
 * Sets up a functional test: grants permissions, installs a [TestAppContainer] before the app starts, starts a
 * [FakePeerDevice] and (by default) pairs the two so calls work. Everything is torn down afterwards.
 *
 * @param pairPeer false for pairing tests, which start with the two devices unpaired.
 */
class FunctionalTestRule(
    private val clock: Clock = SystemClock,
    private val pairPeer: Boolean = true,
    private val sockets: SocketProvider = PlainSocketProvider,
) : TestWatcher() {
    lateinit var container: TestAppContainer
        private set
    lateinit var peer: FakePeerDevice
        private set
    private var scenario: ActivityScenario<MainActivity>? = null

    /** The shared secret used when the devices are paired by [pairWithPeer]. */
    val secret = ByteArray(32) { (it * 3 + 7).toByte() }

    override fun starting(description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(pkg, it) }
        val app = instrumentation.targetContext.applicationContext as TwoWayApp
        container = TestAppContainer(app, clock, sockets)
        app.container = container
        // Settings live in a real DataStore that survives between tests: start from the defaults.
        runBlocking {
            container.settings.setListenInBackground(true)
            container.settings.setStartOnBoot(false)
            container.settings.setAutoRejectUnknown(false)
            container.settings.setRingtoneUri(null)
        }
        peer = FakePeerDevice().also { it.start() }
        if (pairPeer) pairWithPeer()
    }

    /** Makes the app and the peer trust each other with [secret] (as if they had completed pairing). */
    fun pairWithPeer() {
        container.pairedDevices.savePaired(peer.deviceId, peer.name, peer.device.identity.publicKey, secret)
        peer.device.trust.trust(container.self.deviceId, secret)
    }

    /** Launches the app and waits until its call runtime is up. */
    fun launch(): ActivityScenario<MainActivity> {
        val s = ActivityScenario.launch(MainActivity::class.java)
        scenario = s
        waitUntil { container.runtimeOrNull?.started == true }
        return s
    }

    /** The address of the app under test, as the peer would dial it. */
    fun appAddress(): PeerAddress {
        val rt = container.runtimeOrNull ?: error("runtime not started")
        return PeerAddress(container.self.deviceId, container.self.name, "127.0.0.1", rt.engine.signalingPort)
    }

    /** Makes the app "discover" the peer, as NSD/multicast would on a real network. */
    fun discoverPeer() {
        val rt = container.runtimeOrNull ?: error("runtime not started")
        rt.registry.seen(peer.deviceId, peer.name, "127.0.0.1", peer.address.port, "p", DiscoverySource.MULTICAST)
    }

    override fun finished(description: Description) {
        runCatching { scenario?.close() }
        runCatching { runBlocking { container.stopRuntime() } }
        runCatching { peer.stop() }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        runCatching { ctx.stopService(android.content.Intent(ctx, com.intercom.video.twoway.service.ListenerService::class.java)) }
    }

    companion object {
        fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (condition()) return
                Thread.sleep(25)
            }
            check(condition()) { "condition not met within $timeoutMs ms" }
        }
    }
}
