package services.partner

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import services.JsonService
import services.partner.WebSocketPartnerPacket.PartnerPacket

object WebSocketPartnerRegistry {
    enum class PacketType(val id: Int, val serializer: KSerializer<out PartnerPacket>) {
        AuthRequest(100, WebSocketPartnerPacket.AuthRequest.serializer()),
        AuthResponse(200, WebSocketPartnerPacket.AuthResponse.serializer()),
        QueryRequest(300, WebSocketPartnerPacket.QueryRequest.serializer()),
        QueryResponse(301, WebSocketPartnerPacket.QueryResponse.serializer());
    }

    // the protocol tags packets with a numeric "t", which kotlinx's class discriminator can't express
    @Suppress("UNCHECKED_CAST")
    fun encode(packet: PartnerPacket): String {
        val serializer = packet.type.serializer as KSerializer<PartnerPacket>
        val body = JsonService.json.encodeToJsonElement(serializer, packet).jsonObject
        return JsonService.encode(JsonObject(body + ("t" to JsonPrimitive(packet.type.id))))
    }

    fun decode(raw: String): PartnerPacket? {
        val header = JsonService.decode<WebSocketPartnerPacket.Header>(raw) ?: return null
        val type = PacketType.entries.find { it.id == header.type } ?: return null
        return runCatching { JsonService.json.decodeFromString(type.serializer, raw) }.getOrNull()
    }
}