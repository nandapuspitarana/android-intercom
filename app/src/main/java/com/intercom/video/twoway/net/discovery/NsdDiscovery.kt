package com.intercom.video.twoway.net.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.protocol.PROTOCOL_VERSION
import com.intercom.video.twoway.net.transport.SocketProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * mDNS/NSD discovery: registers `_twoway._tcp` with TXT (v, id, n, r) and resolves other devices
 * (contracts/discovery.md). NSD reports a service once, so resolved services are refreshed in the
 * [DeviceRegistry] every 5 s until they are reported lost.
 */
class NsdDiscovery(
    context: Context,
    private val registry: DeviceRegistry,
    private val sockets: SocketProvider,
    private val logger: Logger,
    private val selfId: String,
    private val selfName: () -> String,
    private val signalingPort: () -> Int,
    private val role: () -> String = { "p" },
) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val known = ConcurrentHashMap<String, Resolved>() // serviceName -> resolved info
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var scheduler: ScheduledExecutorService? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    private class Resolved(val id: String, val name: String, val host: String, val port: Int, val role: String) {
        /** Consecutive failed liveness probes. */
        @Volatile
        var failures = 0
    }

    @Synchronized
    fun start() {
        if (scheduler != null) return
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nsd-refresh").apply { isDaemon = true } }
            .also { s ->
                s.scheduleWithFixedDelay({ refresh() }, REFRESH_MS, REFRESH_MS, TimeUnit.MILLISECONDS)
            }
        register()
        discover()
    }

    @Synchronized
    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
        known.clear()
        resolveQueue.clear()
        resolving = false
    }

    private fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_PREFIX + selfId.take(8)
            serviceType = SERVICE_TYPE
            port = signalingPort()
            setAttribute("v", PROTOCOL_VERSION.toString())
            setAttribute("id", selfId)
            setAttribute("n", selfName().take(32))
            setAttribute("r", role())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = logger.d(TAG, "registered ${i.serviceName}")
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) = logger.w(TAG, "register failed $errorCode")
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { logger.w(TAG, "registerService failed: ${it.message}") }
    }

    private fun discover() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = logger.w(TAG, "discovery failed $errorCode")
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith(SERVICE_PREFIX)) return
                if (info.serviceName == SERVICE_PREFIX + selfId.take(8)) return
                enqueueResolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                known.remove(info.serviceName)?.let { registry.remove(it.id) }
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { logger.w(TAG, "discoverServices failed: ${it.message}") }
    }

    @Synchronized
    private fun enqueueResolve(info: NsdServiceInfo) {
        resolveQueue.add(info)
        resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        finishResolve()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        handleResolved(info)
                        finishResolve()
                    }
                },
            )
        }.onFailure { finishResolve() }
    }

    @Synchronized
    private fun finishResolve() {
        resolving = false
        resolveNext()
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val attrs = info.attributes
        fun attr(k: String) = attrs[k]?.toString(Charsets.UTF_8)
        val id = attr("id") ?: return
        if (id == selfId) return
        if (attr("v") != PROTOCOL_VERSION.toString()) return
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return
        val r = Resolved(id, attr("n").orEmpty(), host, info.port, attr("r") ?: "p")
        known[info.serviceName] = r
        registry.seen(r.id, r.name, r.host, r.port, r.role, DiscoverySource.NSD)
    }

    /**
     * NSD reports a service once and the mDNS cache can keep it for minutes after a phone vanished (WiFi off, app killed,
     * uninstalled), so each known phone is probed with a HELLO before it is listed again. Two failed probes in a row remove it.
     */
    private fun refresh() {
        for ((serviceName, r) in known.entries.toList()) {
            val alive = GatewayProbe.probe(sockets, r.host, r.port, selfId, selfName(), PROBE_TIMEOUT_MS, logger)?.deviceId == r.id
            if (alive) {
                r.failures = 0
                registry.seen(r.id, r.name, r.host, r.port, r.role, DiscoverySource.NSD)
            } else if (++r.failures >= MAX_FAILURES) {
                known.remove(serviceName)
                registry.remove(r.id)
                logger.d(TAG, "dropping unresponsive ${r.name}")
            }
        }
        registry.expire()
    }

    private companion object {
        const val TAG = "NsdDiscovery"
        const val SERVICE_TYPE = "_twoway._tcp."
        const val SERVICE_PREFIX = "tw-"
        const val REFRESH_MS = 5_000L
        const val PROBE_TIMEOUT_MS = 800
        const val MAX_FAILURES = 2
    }
}
