package com.intercom.video.twoway.net.discovery

import com.intercom.video.twoway.core.Logger
import com.intercom.video.twoway.core.NoopLogger
import com.intercom.video.twoway.core.protocol.FrameCodec
import com.intercom.video.twoway.core.protocol.Hello
import com.intercom.video.twoway.core.protocol.JsonException
import com.intercom.video.twoway.core.protocol.JsonMessageCodec
import com.intercom.video.twoway.core.util.NameSanitizer
import com.intercom.video.twoway.net.transport.SocketProvider
import java.io.IOException

/** What a probed phone answered. */
class ProbeResult(val deviceId: String, val name: String, val isHub: Boolean)

/**
 * Sends a plaintext HELLO to `host:port` and reads the reply. Used for the hotspot gateway probe (is the DHCP
 * gateway a phone running Two Way as hub? reply role `h`) and for adding a device by typing its address.
 */
object GatewayProbe {
    const val DEFAULT_TIMEOUT_MS = 2_000

    fun probe(
        sockets: SocketProvider,
        host: String,
        port: Int,
        selfId: String,
        selfName: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        logger: Logger = NoopLogger,
    ): ProbeResult? = try {
        sockets.connect(host, port, timeoutMs).use { s ->
            s.soTimeout = timeoutMs
            FrameCodec.write(s.getOutputStream(), JsonMessageCodec.encodeBytes(Hello(selfId, selfName, "p")))
            val reply = JsonMessageCodec.decode(FrameCodec.read(s.getInputStream()))
            if (reply is Hello && reply.id.length == DeviceRegistry.ID_HEX_LENGTH) {
                ProbeResult(reply.id, NameSanitizer.sanitizeOr(reply.name, reply.id.take(8)), reply.role == "h")
            } else {
                null
            }
        }
    } catch (e: IOException) {
        logger.d("GatewayProbe", "no answer from $host:$port: ${e.message}")
        null
    } catch (e: JsonException) {
        null
    } catch (e: com.intercom.video.twoway.core.protocol.FrameException) {
        null
    }
}
