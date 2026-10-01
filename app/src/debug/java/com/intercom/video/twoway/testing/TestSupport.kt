package com.intercom.video.twoway.testing

import com.intercom.video.twoway.core.Clock
import com.intercom.video.twoway.core.SystemClock
import com.intercom.video.twoway.core.util.Hex
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.network.HubClient
import com.intercom.video.twoway.net.network.HubRegistry
import com.intercom.video.twoway.net.transport.PlainSocketProvider
import com.intercom.video.twoway.net.transport.SocketProvider
import com.intercom.video.twoway.service.CallEngine
import com.intercom.video.twoway.service.CallEventSink
import com.intercom.video.twoway.service.CallRecord
import com.intercom.video.twoway.service.CallUiState
import com.intercom.video.twoway.service.LocalDevice
import com.intercom.video.twoway.service.LocalIdentityOps
import com.intercom.video.twoway.service.PairingManager
import com.intercom.video.twoway.service.PairingSaveResult
import com.intercom.video.twoway.service.PairingStore
import com.intercom.video.twoway.service.PeerAddress
import com.intercom.video.twoway.service.TrustStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList

/** Trust store backed by a map. A device missing from the map is "not paired". */
class MapTrustStore(private val secrets: MutableMap<String, ByteArray> = HashMap()) : TrustStore {
    private val _pairedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of trusted devices, for UI "Paired" badges. */
    val pairedIds: StateFlow<Set<String>> = _pairedIds

    @Synchronized
    override fun pairingSecret(deviceId: String): ByteArray? = secrets[deviceId]?.copyOf()

    @Synchronized
    fun trust(deviceId: String, secret: ByteArray) {
        secrets[deviceId] = secret.copyOf()
        _pairedIds.value = secrets.keys.toSet()
    }

    @Synchronized
    fun revoke(deviceId: String) {
        secrets.remove(deviceId)
        _pairedIds.value = secrets.keys.toSet()
    }
}

/** A software identity (long-term P-256 key) for tests; the phone uses AndroidKeyStore instead. */
class TestIdentity(override val deviceId: String, override val name: String) : LocalIdentityOps {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val publicKey: ByteArray get() = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(data)
        sign()
    }
}

/** Pairing store for tests: writes the secret into a [MapTrustStore] and pins the identity fingerprint like the real one. */
class MapPairingStore(private val trust: MapTrustStore) : PairingStore {
    val fingerprints = HashMap<String, ByteArray>()
    val saved = CopyOnWriteArrayList<String>()

    @Synchronized
    override fun savePaired(deviceId: String, name: String, identityPublicKey: ByteArray, secret: ByteArray): PairingSaveResult {
        val fp = MessageDigest.getInstance("SHA-256").digest(identityPublicKey)
        val known = fingerprints[deviceId]
        if (known != null && trust.pairingSecret(deviceId) != null && !known.contentEquals(fp)) return PairingSaveResult.IDENTITY_CHANGED
        fingerprints[deviceId] = fp
        trust.trust(deviceId, secret)
        saved += deviceId
        return PairingSaveResult.SAVED
    }
}

/** One simulated phone: identity + trust + fake audio + a real CallEngine and PairingManager on localhost sockets. */
class TestDevice(
    val name: String,
    val deviceId: String = Hex.encode(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }),
    val trust: MapTrustStore = MapTrustStore(),
    val audio: FakeAudioFactory = FakeAudioFactory(),
    wallClock: () -> Long = System::currentTimeMillis,
    clock: Clock = SystemClock,
    val sockets: SocketProvider = PlainSocketProvider,
) {
    val records = CopyOnWriteArrayList<CallRecord>()

    val engine = CallEngine(
        self = { LocalDevice(deviceId, name) },
        trust = trust,
        sockets = sockets,
        audio = audio,
        clock = clock,
        wallClock = wallClock,
        sink = CallEventSink { records += it },
    )

    val identity = TestIdentity(deviceId, name)
    val pairingStore = MapPairingStore(trust)
    val pairing = PairingManager(identity, pairingStore, sockets, clock)

    /** Phones this device knows about (filled by the hub directory in hotspot tests). */
    val registry = DeviceRegistry(clock, deviceId)

    /** Hub role (this phone shares the hotspot). Off until [becomeHub]. */
    val hubRegistry = HubRegistry(sockets, clock)

    /** Client role: registers with a hub and relays calls through it. */
    val hubClient = HubClient(
        sockets,
        registry,
        clock,
        { LocalDevice(deviceId, name) },
        { engine.signalingPort },
        onInboundChannel = { engine.onRelayedChannel(it) },
    )

    init {
        engine.pairingRouter = pairing
    }

    /** This phone becomes the hotspot hub: answers HELLO as `h`, keeps the directory and relays calls. */
    fun becomeHub() {
        hubRegistry.enable()
        engine.hubRouter = hubRegistry
        engine.roleProvider = { "h" }
    }

    /** This phone joined the hotspot of [hub]: registers there and may relay calls through it. */
    fun joinHub(hub: PeerAddress): Boolean {
        engine.relayProvider = hubClient
        return hubClient.connect(hub.host, hub.port)
    }

    /** Starts on an ephemeral port and returns the address other devices should call. */
    fun start(): PeerAddress {
        val port = engine.start(0)
        return PeerAddress(deviceId, name, "127.0.0.1", port)
    }

    val state: CallUiState get() = engine.state.value

    fun stop() {
        hubClient.disconnect()
        hubRegistry.disable()
        pairing.shutdown()
        engine.stop()
    }
}

/** Pairs two test devices with the same secret (as if they had completed pairing). */
fun pair(a: TestDevice, b: TestDevice, secret: ByteArray = ByteArray(32) { (it * 5 + 1).toByte() }) {
    a.trust.trust(b.deviceId, secret)
    b.trust.trust(a.deviceId, secret)
}

/** Polls until [condition] holds or [timeoutMs] passes. Returns whether it held. */
fun await(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(25)
    }
    return condition()
}
