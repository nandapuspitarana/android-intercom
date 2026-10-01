package com.intercom.video.twoway.net

/** How the devices are connected (spec: Network Mode). Drives UI guidance. */
enum class NetworkMode {
    /** Shared WiFi / LAN. */
    LAN,

    /** This phone runs the hotspot and acts as the hub. */
    HOTSPOT_HOST,

    /** This phone joined a hotspot; the hub is the DHCP gateway. */
    HOTSPOT_CLIENT,

    /** No usable WiFi. */
    NONE,
}

/** Pure decision logic so it can be unit-tested without Android. */
object NetworkModeResolver {
    fun resolve(wifiConnected: Boolean, apInterfaceUp: Boolean, hubReachable: Boolean): NetworkMode = when {
        apInterfaceUp -> NetworkMode.HOTSPOT_HOST
        !wifiConnected -> NetworkMode.NONE
        hubReachable -> NetworkMode.HOTSPOT_CLIENT
        else -> NetworkMode.LAN
    }
}
