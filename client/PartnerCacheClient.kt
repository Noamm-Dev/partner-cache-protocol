package init

import init.ws.WebSocketPartnerPacket
import init.ws.WebSocketPartnerRegistry
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import routes.HypixelRouter
import services.JsonService.json
import services.ProxyService
import services.RedisService
import utils.*
import java.util.*
import java.util.concurrent.*
import kotlin.random.Random
import kotlin.system.exitProcess

object PartnerCacheClient {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<WebSocketPartnerPacket.Out>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = ProxyService.defaultClient

    @Volatile private var session: WebSocketSession? = null
    @Volatile private var authenticated = false
    fun isConnected() = authenticated

    fun connect() = scope.launch {
        val token = System.getenv("PARTNER_SECRET") ?: run {
            println("Missing PARTNER_SECRET environment variable")
            exitProcess(0)
        }

        connectLoop("ws://unstable.starred.foo/ws/partner", token)
    }

    private suspend fun getFetchedTimestamp(key: String): Long? {
        val ttl = RedisService.ttl(key)?.takeUnless { it < 0 } ?: return null
        val elapsedSeconds = HypixelRouter.profileCacheTime - ttl
        return System.currentTimeMillis() - (elapsedSeconds * 1000L)
    }

    private suspend fun connectLoop(url: String, token: String) {
        var attempt = 0

        while (scope.isActive) try {
            val block: suspend WebSocketSession.() -> Unit = block@{
                session = this
                authenticated = false

                scope.launch {
                    delay(1000)
                    val authId = UUID.randomUUID().toString()
                    val authDeferred = CompletableDeferred<WebSocketPartnerPacket.Out>()
                    pending[authId] = authDeferred

                    send(Frame.Text(json.encodeToString((WebSocketPartnerPacket.In(
                        type = WebSocketPartnerRegistry.ServerBound.Auth.id,
                        id = authId,
                        token = token
                    )))))

                    val authResult = withTimeoutOrNull(5000) { authDeferred.await() }.also { pending.remove(authId) }
                    if (authResult == null || authResult.success != true) return@launch println("Partner cache auth failed: ${authResult?.error ?: "timed out"}")

                    authenticated = true
                    attempt = 0
                    println("Authenticated with partner cache at $url")
                }

                try {
                    for (frame in incoming) if (frame is Frame.Text) {
                        val text = frame.readText()
                        println("received: ${text.take(200)}")

                        val header = catch { json.decodeFromString<WebSocketPartnerPacket.PacketHeader>(text) } ?: continue

                        when (header.type) {
                            WebSocketPartnerRegistry.ServerBound.Query.id -> {
                                val request = catch { json.decodeFromString<WebSocketPartnerPacket.In>(text) } ?: continue
                                val uuids = request.uuids.orEmpty().map { normalizeUUID(it)!! }

                                val data = uuids.mapNotNull { uuid ->
                                    val key = "profile:$uuid"
                                    val cached = RedisService.get(key) ?: return@mapNotNull null
                                    val ts = getFetchedTimestamp(key) ?: return@mapNotNull null
                                    uuid to WebSocketPartnerPacket.Entry(data = cached, timestamp = ts)
                                }.toMap()

                                if (data.isNotEmpty()) println("sending $uuids")
                                send(Frame.Text(json.encodeToString(WebSocketPartnerPacket.Out(
                                    type = WebSocketPartnerRegistry.ClientBound.Query.id,
                                    id = header.id,
                                    success = true,
                                    uuids = uuids,
                                    data = data
                                ))))
                            }

                            else -> {
                                val packet = catch { json.decodeFromString<WebSocketPartnerPacket.Out>(text) } ?: continue
                                pending.remove(packet.id)?.complete(packet)
                            }
                        }
                    }
                }
                finally {
                    authenticated = false
                    session = null
                }
            }

            httpClient.webSocket(url, block = block)
        }
        catch (e: Exception) {
            println("Partner cache disconnected: ${e.message}")
            authenticated = false
            session = null
        }

        attempt++
        val backoff = (1000L * (1 shl minOf(attempt, 5))) + Random.nextLong(500)
        delay(minOf(backoff, 30_000L))
    }

    suspend fun queryUuids(uuids: List<String>, timeoutMs: Long = 1_000): Map<String, WebSocketPartnerPacket.Entry> {
        val currentSession = session
        if (currentSession == null || !authenticated || uuids.isEmpty()) return emptyMap()

        val requestId = "req_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val deferred = CompletableDeferred<WebSocketPartnerPacket.Out>()
        pending[requestId] = deferred

        currentSession.send(Frame.Text(json.encodeToString(WebSocketPartnerPacket.In(
            type = WebSocketPartnerRegistry.ServerBound.Query.id,
            id = requestId,
            uuids = uuids.map { dashedUUID(it)!! }
        ))))

        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(requestId)

        return (result?.data ?: emptyMap()).mapKeys { normalizeUUID(it.key)!! }
    }

    suspend fun queryUuid(uuid: String, timeoutMs: Long = 1_000) = queryUuids(listOf(uuid), timeoutMs)[normalizeUUID(uuid)]
}