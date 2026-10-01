package com.intercom.video.twoway.core.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonMessageCodecTest {
    private fun roundTrip(m: Message) = assertEquals(m, JsonMessageCodec.decode(JsonMessageCodec.encode(m)))

    @Test
    fun everyMessageTypeRoundTrips() {
        roundTrip(Hello("aa", "Phone", "p"))
        roundTrip(PairRequest("id", "n", "pub", "nonce"))
        roundTrip(PairAccept("id", "n", "pub", "nonce"))
        roundTrip(PairDecline("no"))
        roundTrip(PairConfirm(true, "mac", "ipub", "sig"))
        roundTrip(Invite("c1", "from", "Name", "nonce", 5000, 1234567890123L, "mac"))
        roundTrip(PairRequired("c1"))
        roundTrip(Ringing("c1", "nonce", "mac"))
        roundTrip(Accept("c1", 6000, "opus16k20ms"))
        roundTrip(Reject("c1", "declined"))
        roundTrip(Busy("c1"))
        roundTrip(Cancel("c1"))
        roundTrip(Hangup("c1", "user"))
        roundTrip(Ping("c1", 5L))
        roundTrip(Pong("c1", 5L))
        roundTrip(Mute("c1", true))
        roundTrip(HubRegister("id", "n", 45678))
        roundTrip(HubDirectory(listOf(HubPeer("id", "n", "192.168.43.2", 45678))))
        roundTrip(HubRelayOpen("s", "to"))
        roundTrip(HubRelayReady("s", 7000))
        roundTrip(HubRelayClose("s"))
        roundTrip(RelayFrame("s", byteArrayOf(1, 2, 3, -1)))
    }

    @Test
    fun unknownTypeIsIgnored() = assertNull(JsonMessageCodec.decode("""{"t":"SOMETHING_NEW","x":1}"""))

    @Test
    fun unknownFieldsAreIgnored() {
        val m = JsonMessageCodec.decode("""{"t":"BUSY","callId":"c","extra":{"a":[1,2]}}""")
        assertEquals(Busy("c"), m)
    }

    @Test
    fun missingOrWrongTypedFieldThrows() {
        assertThrows(JsonException::class.java) { JsonMessageCodec.decode("""{"t":"BUSY"}""") }
        assertThrows(JsonException::class.java) { JsonMessageCodec.decode("""{"t":"BUSY","callId":5}""") }
        assertThrows(JsonException::class.java) { JsonMessageCodec.decode("""{"callId":"x"}""") }
    }

    @Test
    fun malformedJsonThrows() {
        for (bad in listOf("", "{", "[]", "null", """{"t":"BUSY",}""", """{"t":"BUSY"} x""", "\"str\"", "{'t':1}")) {
            assertThrows(JsonException::class.java, { JsonMessageCodec.decode(bad) }, "should reject: $bad")
        }
    }

    @Test
    fun depthAboveFourIsRejected() {
        val ok = """{"a":{"b":{"c":1}}}""" // depth 3
        Json.parseObject(ok)
        val deep = """{"a":{"b":{"c":{"d":{"e":1}}}}}""" // depth 5
        assertThrows(JsonException::class.java) { Json.parseObject(deep) }
    }

    @Test
    fun stringsOverLimitAreRejected() {
        Json.parseObject("""{"a":"${"x".repeat(Json.MAX_STRING)}"}""")
        assertThrows(JsonException::class.java) {
            Json.parseObject("""{"a":"${"x".repeat(Json.MAX_STRING + 1)}"}""")
        }
    }

    @Test
    fun everyMessageExceptRelayKeepsTheStrict256CharStringLimit() {
        val long = "x".repeat(Json.MAX_STRING + 1)
        assertThrows(JsonException::class.java) { JsonMessageCodec.decode("""{"t":"HELLO","id":"a","n":"$long","r":"p"}""") }
        assertThrows(JsonException::class.java) { JsonMessageCodec.decode("""{"t":"BUSY","callId":"$long"}""") }
        // the limit also applies inside nested hub peer entries
        assertThrows(JsonException::class.java) {
            JsonMessageCodec.decode("""{"t":"HUB_DIRECTORY","peers":[{"id":"a","n":"$long","ip":"1.1.1.1","p":1}]}""")
        }
    }

    @Test
    fun aRelayFrameMayCarryALongBase64Payload() {
        val big = ByteArray(2_500) { it.toByte() } // the largest frame a hub relays
        val decoded = JsonMessageCodec.decode(JsonMessageCodec.encode(RelayFrame("s", big))) as RelayFrame
        assertEquals(RelayFrame("s", big), decoded)
        // ...but nothing may exceed the hard bound
        assertThrows(JsonException::class.java) {
            JsonMessageCodec.decode("""{"t":"RELAY","sessionId":"s","frame":"${"A".repeat(Json.MAX_STRING_HARD + 1)}"}""")
        }
    }

    @Test
    fun tooManyHubPeersRejected() {
        val peer = """{"id":"a","n":"b","ip":"1.1.1.1","p":1}"""
        val many = (1..JsonMessageCodec.MAX_PEERS + 1).joinToString(",") { peer }
        assertThrows(JsonException::class.java) { JsonMessageCodec.decode("""{"t":"HUB_DIRECTORY","peers":[$many]}""") }
    }

    @Test
    fun escapesAreHandled() {
        val m = JsonMessageCodec.decode("""{"t":"HANGUP","callId":"a\"b\\cA","reason":"x"}""")
        assertEquals(Hangup("a\"b\\cA", "x"), m)
        roundTrip(Hangup("quote\" back\\ ctl\u0001", "r"))
    }
}
