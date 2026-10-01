package com.intercom.video.twoway.net.network

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds this phone's IPv4 address on the local WiFi/hotspot network (shown in pairing QR codes). */
object LocalAddress {
    /** Prefers wlan/ap/swlan interfaces; falls back to any non-loopback site-local IPv4 address. Null if none. */
    fun find(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.asSequence().filterIsInstance<Inet4Address>().map { ni.name.lowercase() to it } }
            .filter { (_, a) -> !a.isLoopbackAddress && !a.isLinkLocalAddress }
            .toList()
        val preferred = candidates.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") }
        (preferred ?: candidates.firstOrNull { (_, a) -> a.isSiteLocalAddress })?.second?.hostAddress
    } catch (e: java.net.SocketException) {
        null
    }
}
