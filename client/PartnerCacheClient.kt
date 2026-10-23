package services.partner

import NoammApi
import events.HypixelEvent
import init.types.ISelfInit
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.*
import kotlinx.coroutines.*
import services.ProfileCacheService
import services.ProxyService
import services.partner.WebSocketPartnerPacket.AuthRequest
import services.partner.WebSocketPartnerPacket.AuthResponse
import services.partner.WebSocketPartnerPacket.Entry
import services.partner.WebSocketPartnerPacket.PartnerPacket
import services.partner.WebSocketPartnerPacket.QueryRequest
import services.partner.WebSocketPartnerPacket.QueryResponse
import utils.*
import java.util.*
import java.util.concurrent.*
import kotlin.random.Random

object PartnerCacheClient: ISelfInit {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<PartnerPacket>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = ProxyService.defaultClient
    private const val timeoutMs = 1500L

    // only set once authenticated so a not null session is always authed
    @Volatile private var session: WebSocketSession? = null
    fun isConnected() = session != null

    override fun init() {
        if (! NoammApi.isLocal) scope.launch {
            connectLoop("ws://unstable.starred.foo/ws/partner", env("PARTNER_SECRET"))
        }
    }

    private suspend fun connectLoop(url: String, token: String) {
        var attempt = 0

        while (scope.isActive) {
            try {
                httpClient.webSocket(url) {
                    scope.launch {
                        delay(1000)
                        val response = request<AuthResponse>(
                            AuthRequest(
                                id = createRequestId(),
                                token = token
                            )
                        )
                        if (response?.success != true) return@launch NoammApi.LOGGER.error("Partner cache auth failed: ${response?.error ?: "timed out"}")

                        session = this@webSocket
                        attempt = 0
                        NoammApi.LOGGER.info("Authenticated with partner cache at $url")
                    }

                    try {
                        for (frame in incoming) if (frame is Frame.Text) {
                            when (val packet = WebSocketPartnerRegistry.decode(frame.readText())) {
                                is QueryRequest -> handleQuery(packet)
                                is AuthResponse, is QueryResponse -> pending.remove(packet.id)?.complete(packet)
                                is AuthRequest, null -> Unit // client does not auth xd
                            }
                        }

                        NoammApi.LOGGER.info("Partner cache closed: ${closeReason.await()}")
                    }
                    finally {
                        session = null
                    }
                }
            }
            catch (e: Exception) {
                NoammApi.LOGGER.info("Partner cache disconnected: ${e.message}")
                session = null
            }

            attempt ++
            val backoff = (1000L * (1 shl minOf(attempt, 5))) + Random.nextLong(500)
            delay(minOf(backoff, 30_000L))
        }
    }

    private suspend fun WebSocketSession.handleQuery(request: QueryRequest) {
        val uuids = request.uuids.mapNotNull(::normalizeUUID)
        val oldestAllowed = System.currentTimeMillis() - request.maxAge
        val data = uuids.mapNotNull { uuid ->
            ProfileCacheService.get(uuid)?.takeIf { it.timestamp >= oldestAllowed }?.let { uuid to it }
        }.toMap()

        send(QueryResponse(id = request.id, success = true, uuids = uuids, data = data))
        if (data.isNotEmpty()) NoammApi.eventBus.postAsync(HypixelEvent.PartnerServed(data.size))
    }

    suspend fun queryUuids(uuids: List<String>, maxAge: Long = ProfileCacheService.maxAge): Map<String, Entry> {
        if (uuids.isEmpty()) return emptyMap()
        val currentSession = session ?: return emptyMap()

        val response = currentSession.request<QueryResponse>(
            QueryRequest(
                id = createRequestId(),
                uuids = uuids.mapNotNull(::dashedUUID),
                maxAge = maxAge
            )
        ) ?: return emptyMap()

        return response.data.mapNotNull { (uuid, entry) -> normalizeUUID(uuid)?.let { it to entry } }.toMap()
    }

    suspend fun queryUuid(uuid: String, maxAge: Long = ProfileCacheService.maxAge) = queryUuids(listOf(uuid), maxAge)[normalizeUUID(uuid)]

    private suspend inline fun <reified R: PartnerPacket> WebSocketSession.request(packet: PartnerPacket): R? {
        val deferred = CompletableDeferred<PartnerPacket>()
        pending[packet.id] = deferred

        return try {
            send(packet)
            withTimeoutOrNull(timeoutMs) { deferred.await() } as? R
        }
        catch (_: Exception) {
            null
        }
        finally {
            pending.remove(packet.id)
        }
    }

    private suspend fun WebSocketSession.send(packet: PartnerPacket) = send(Frame.Text(WebSocketPartnerRegistry.encode(packet)))
    private fun createRequestId() = "req_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
}