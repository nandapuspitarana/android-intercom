package com.intercom.video.twoway.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NetworkModeResolverTest {
    @Test
    fun noWifiMeansNone() = assertEquals(NetworkMode.NONE, NetworkModeResolver.resolve(false, false, false))

    @Test
    fun plainWifiIsLan() = assertEquals(NetworkMode.LAN, NetworkModeResolver.resolve(true, false, false))

    @Test
    fun activeHotspotInterfaceMeansHost() {
        assertEquals(NetworkMode.HOTSPOT_HOST, NetworkModeResolver.resolve(false, true, false))
        assertEquals(NetworkMode.HOTSPOT_HOST, NetworkModeResolver.resolve(true, true, true))
    }

    @Test
    fun reachableHubOnWifiMeansClient() = assertEquals(NetworkMode.HOTSPOT_CLIENT, NetworkModeResolver.resolve(true, false, true))
}
