package com.intercom.video.twoway.net

import com.intercom.video.twoway.core.FakeClock
import com.intercom.video.twoway.net.discovery.Announce
import com.intercom.video.twoway.net.discovery.DeviceRegistry
import com.intercom.video.twoway.net.discovery.DiscoverySource
import com.intercom.video.twoway.net.discovery.SourceRateLimiter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoveryParsingTest {
    private val id = "a".repeat(32)
    private val other = "b".repeat(32)

    @Test
    fun announceRoundTrips() {
        val bytes = Announce.encodeAnnounce(id, "Kitchen", 45678, "p")
        assertTrue(bytes.size <= Announce.MAX_BYTES)
        assertEquals(Announce.Parsed.Announcement(id, "Kitchen", 45678, "p"), Announce.parse(bytes))
        assertEquals(Announce.Parsed.Query, Announce.parse(Announce.encodeQuery()))
    }

    @Test
    fun longNamesStillFitIn256Bytes() {
        val bytes = Announce.encodeAnnounce(id, "é".repeat(200), 45678, "p")
        assertTrue(bytes.size <= Announce.MAX_BYTES, "size ${bytes.size}")
        assertTrue(Announce.parse(bytes) is Announce.Parsed.Announcement)
    }

    @Test
    fun oversizeInvalidAndOtherVersionAreIgnored() {
        assertNull(Announce.parse(ByteArray(Announce.MAX_BYTES + 1) { '{'.code.toByte() }))
        assertNull(Announce.parse("not json".toByteArray()))
        assertNull(Announce.parse(ByteArray(0)))
        assertNull(Announce.parse("""{"t":"announce","v":2,"id":"$id","n":"x","p":1}""".toByteArray()))
        assertNull(Announce.parse("""{"t":"announce","v":1,"id":"$id","n":"x"}""".toByteArray()))
        assertNull(Announce.parse("""{"t":"other","v":1}""".toByteArray()))
    }

    @Test
    fun registryIgnoresOwnIdAndInvalidEntries() {
        val reg = DeviceRegistry(FakeClock(), selfId = id)
        reg.seen(id, "me", "10.0.0.1", 1000, "p", DiscoverySource.MULTICAST) // own id
        reg.seen("short", "x", "10.0.0.2", 1000, "p", DiscoverySource.MULTICAST) // bad id
        reg.seen(other, "x", "10.0.0.2", 0, "p", DiscoverySource.MULTICAST) // bad port
        assertTrue(reg.devices.value.isEmpty())
    }

    @Test
    fun registrySanitizesNamesAndMergesSources() {
        val reg = DeviceRegistry(FakeClock(), selfId = id)
        reg.seen(other, "  Dapur\u0000 ", "10.0.0.2", 45678, "p", DiscoverySource.NSD)
        reg.seen(other, "Dapur", "10.0.0.2", 45678, "p", DiscoverySource.MULTICAST)
        val d = reg.devices.value.single()
        assertEquals("Dapur", d.displayName)
        assertEquals(setOf(DiscoverySource.NSD, DiscoverySource.MULTICAST), d.sources)
    }

    @Test
    fun entriesExpireAfter15sWithoutAnAnnounce() {
        val clock = FakeClock()
        val reg = DeviceRegistry(clock, selfId = id)
        reg.seen(other, "B", "10.0.0.2", 45678, "p", DiscoverySource.MULTICAST)
        clock.advance(15_000)
        reg.expire()
        assertEquals(1, reg.devices.value.size, "exactly 15 s is still alive")
        clock.advance(1)
        reg.expire()
        assertTrue(reg.devices.value.isEmpty())
    }

    @Test
    fun seeingADeviceAgainRefreshesItsTimer() {
        val clock = FakeClock()
        val reg = DeviceRegistry(clock, selfId = id)
        reg.seen(other, "B", "10.0.0.2", 45678, "p", DiscoverySource.MULTICAST)
        clock.advance(10_000)
        reg.seen(other, "B", "10.0.0.2", 45678, "p", DiscoverySource.MULTICAST)
        clock.advance(10_000)
        reg.expire()
        assertEquals(1, reg.devices.value.size)
    }

    @Test
    fun moreThan10AnnouncementsPerSecondPerSourceAreDropped() {
        val clock = FakeClock()
        val limiter = SourceRateLimiter(clock, 10)
        repeat(10) { assertTrue(limiter.allow("10.0.0.2")) }
        assertFalse(limiter.allow("10.0.0.2"))
        assertTrue(limiter.allow("10.0.0.3"), "other sources are independent")
        clock.advance(1_000)
        assertTrue(limiter.allow("10.0.0.2"), "window resets after a second")
    }
}
