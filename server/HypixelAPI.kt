// Reference / integration example — not part of the protocol itself.
// Shows how WebSocketPartner.query(...) is wired into an existing Hypixel data layer:
// local cache -> partner cache -> direct Hypixel API, in that order.

package foo.starred.blackbird.service.hypixel.api

import foo.starred.blackbird.core.util.CompressionUtil
import foo.starred.blackbird.database.impl.hypixel.HypixelPlayerDatabase
import foo.starred.blackbird.database.impl.hypixel.HypixelRawPlayerDatabase
import foo.starred.blackbird.service.http.HttpClient
import foo.starred.blackbird.service.hypixel.data.HypixelPlayerData
import foo.starred.blackbird.service.hypixel.impl.HypixelPlayerStats
import foo.starred.blackbird.service.hypixel.util.HypixelApiKey
import foo.starred.blackbird.websocket.partner.impl.WebSocketPartner
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

object HypixelAPI {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<HypixelPlayerData>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val revalidating = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(uuid: String): HypixelPlayerData {
        val clean = clean(uuid)

        pending[clean]?.let { return it.await() }

        val deferred = CompletableDeferred<HypixelPlayerData>()
        val prior = pending.putIfAbsent(clean, deferred)
        if (prior != null) return prior.await()

        return try {
            resolve(clean).also { deferred.complete(it) }
        } catch (e: Exception) {
            HypixelPlayerData(error = true, message = "HypixelAPI resolution error: ${e.message}").also { deferred.complete(it) }
        } finally {
            pending.remove(clean)
        }
    }

    suspend fun get(uuids: List<String>): Map<String, HypixelPlayerData> {
        val results = mutableMapOf<String, HypixelPlayerData>()
        val missing = mutableListOf<String>()

        for (raw in uuids) {
            val clean = clean(raw)
            val hit = lookup(clean)
            if (hit != null) results[clean] = hit else missing.add(clean)
        }

        if (missing.isEmpty()) return results

        val missing1 = mutableListOf<String>()
        val data = WebSocketPartner.query(missing)
        if (data.isNotEmpty()) HypixelRawPlayerDatabase.put1(data)

        for (clean in missing) {
            val parsed = data[clean]?.let { decode(it.data, clean) }
            if (parsed != null) results[clean] = parsed else missing1.add(clean)
        }

        for (clean in missing1) {
            results[clean] = fetch(clean)
        }

        return results
    }

    private suspend fun resolve(uuid: String): HypixelPlayerData {
        return lookup(uuid) ?: fetch(uuid)
    }

    private fun lookup(uuid: String): HypixelPlayerData? {
        val raw = HypixelRawPlayerDatabase.get(uuid)
        raw?.let { decode(it, uuid)?.let { parsed -> return parsed } }

        val entry = HypixelPlayerDatabase.entry(key(uuid)) ?: return null
        val cached = runCatching { json.decodeFromString<HypixelPlayerData>(entry.data) }.getOrNull() ?: return null
        if (cached.error == true) return null

        val age = System.currentTimeMillis() - entry.cached
        if (age > 3.hours.inWholeMilliseconds || raw == null) revalidate(uuid)

        return cached
    }

    private fun revalidate(uuid: String) {
        if (!revalidating.add(uuid)) return
        scope.launch {
            try {
                fetch(uuid)
            } catch (_: Exception) {
            } finally {
                revalidating.remove(uuid)
            }
        }
    }

    private suspend fun fetch(uuid: String): HypixelPlayerData {
        val partner = WebSocketPartner.query(listOf(uuid))[uuid]
        if (partner != null) {
            HypixelRawPlayerDatabase.put(uuid, partner.data, partner.timestamp)
            decode(partner.data, uuid)?.let { return it }
        }

        val response = try {
            HttpClient.self.get("https://api.hypixel.net/v2/skyblock/profiles") {
                parameter("uuid", uuid)
                header("API-Key", HypixelApiKey.key())
            }
        } catch (e: Exception) {
            return HypixelPlayerData(error = true, status = 500, message = "Failed to fetch from Hypixel: ${e.message}")
        }

        if (response.status == HttpStatusCode.TooManyRequests) return HypixelPlayerData(error = true, status = response.status.value, message = "Rate limited")
        if (!response.status.isSuccess()) return HypixelPlayerData(error = true, status = response.status.value, message = "Hypixel API error")

        val bytes = try {
            response.body<ByteArray>()
        } catch (_: Exception) {
            return HypixelPlayerData(error = true, message = "Failed to read Hypixel response body")
        }

        val parsed = HypixelPlayerStats.parse(String(bytes, Charsets.UTF_8), uuid)
        if (parsed.error != true) {
            HypixelRawPlayerDatabase.put(uuid, CompressionUtil.compress(bytes, 3))
            HypixelPlayerDatabase.put(key(uuid), parsed)
        }

        return parsed
    }

    private fun decode(bytes: ByteArray, uuid: String): HypixelPlayerData? {
        val text = runCatching { CompressionUtil.decompressToString(bytes) }.getOrNull() ?: return null
        val parsed = HypixelPlayerStats.parse(text, uuid)
        if (parsed.error == true) return null

        HypixelPlayerDatabase.put(key(uuid), parsed)
        return parsed
    }

    private fun clean(uuid: String): String {
        return uuid.lowercase().replace("-", "")
    }

    private fun key(uuid: String): String {
        return "uuid:$uuid"
    }
}
