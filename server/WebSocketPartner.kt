package foo.starred.blackbird.websocket.partner.impl

import foo.starred.blackbird.core.config.impl.PartnerConfig
import foo.starred.blackbird.core.util.CompressionUtil
import foo.starred.blackbird.database.impl.hypixel.HypixelRawPlayerDatabase
import foo.starred.blackbird.websocket.partner.data.WebSocketPartnerPacket
import foo.starred.blackbird.websocket.partner.enums.WebSocketPartnerRegistry
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

object WebSocketPartner {
    private val logger = LoggerFactory.getLogger(WebSocketPartner::class.java)

    val partners: ConcurrentHashMap.KeySetView<WebSocketServerSession, Boolean> = ConcurrentHashMap.newKeySet()
    val pending = ConcurrentHashMap<String, CompletableDeferred<Map<String, HypixelRawPlayerDatabase.RawEntry>>>()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun handle(session: WebSocketServerSession) {
        try {
            if (!auth(session)) return

            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val packet = decode(frame) ?: continue
                route(session, packet)
            }
        } catch (_: ClosedReceiveChannelException) {
        } catch (e: Exception) {
            logger.warn("Partner WebSocket error: {}", e.message)
        } finally {
            partners.remove(session)
        }
    }

    suspend fun query(uuids: List<String>): Map<String, HypixelRawPlayerDatabase.RawEntry> {
        if (partners.isEmpty()) return emptyMap()
        if (uuids.isEmpty()) return emptyMap()

        val id = "req_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<Map<String, HypixelRawPlayerDatabase.RawEntry>>()
        pending[id] = deferred

        val frame = Frame.Text(json.encodeToString(WebSocketPartnerPacket.Out(WebSocketPartnerRegistry.ServerBound.Query.id, id, uuids = uuids)))
        for (session in partners) {
            runCatching { session.send(frame) }
        }

        return try {
            withTimeoutOrNull(6.seconds) { deferred.await() } ?: emptyMap()
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun auth(session: WebSocketServerSession): Boolean {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val packet = decode(frame) ?: continue

            if (packet.type == WebSocketPartnerRegistry.ServerBound.Auth.id && packet.token == PartnerConfig.secret) {
                partners.add(session)
                out(session, WebSocketPartnerPacket.Out(WebSocketPartnerRegistry.ClientBound.Auth.id, packet.id, success = true))
                return true
            }

            out(session, WebSocketPartnerPacket.Out(WebSocketPartnerRegistry.ClientBound.Auth.id, packet.id, success = false, error = "Unauthorized"))
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return false
        }

        return false
    }

    private suspend fun route(session: WebSocketServerSession, packet: WebSocketPartnerPacket.In) {
        when (packet.type) {
            WebSocketPartnerRegistry.ServerBound.Query.id -> {
                val uuids = packet.uuids ?: emptyList()
                val found = if (uuids.isNotEmpty()) HypixelRawPlayerDatabase.get1(uuids) else emptyMap()
                val entries = found.mapNotNull { (uuid, raw) ->
                    val data = runCatching { CompressionUtil.decompressToString(raw.data) }.getOrNull() ?: return@mapNotNull null
                    uuid to WebSocketPartnerPacket.Entry(data, raw.timestamp)
                }.toMap()

                out(session, WebSocketPartnerPacket.Out(WebSocketPartnerRegistry.ClientBound.Query.id, packet.id, data = entries))
            }

            WebSocketPartnerRegistry.ClientBound.Query.id -> {
                val deferred = pending.remove(packet.id) ?: return
                val decoded = packet.data?.mapNotNull { (k, v) -> runCatching { k to entry(v) }.getOrNull() }?.toMap() ?: emptyMap()

                deferred.complete(decoded)
            }
        }
    }

    private fun decode(frame: Frame.Text): WebSocketPartnerPacket.In? {
        return runCatching { json.decodeFromString<WebSocketPartnerPacket.In>(frame.readText()) }.getOrNull()
    }

    private suspend fun out(session: WebSocketServerSession, msg: WebSocketPartnerPacket.Out) {
        try {
            session.send(Frame.Text(json.encodeToString(msg)))
        } catch (_: Exception) {}
    }
}
