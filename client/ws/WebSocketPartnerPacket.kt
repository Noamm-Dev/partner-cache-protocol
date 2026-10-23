package services.partner

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import services.ProfileCacheService
import services.partner.WebSocketPartnerRegistry.PacketType

object WebSocketPartnerPacket {
    sealed interface PartnerPacket {
        val id: String
        val type: PacketType
    }

    @Serializable
    data class AuthRequest(
        @SerialName("id") override val id: String,
        @SerialName("token") val token: String
    ): PartnerPacket {
        override val type get() = PacketType.AuthRequest
    }

    @Serializable
    data class AuthResponse(
        @SerialName("id") override val id: String,
        @SerialName("success") val success: Boolean = false,
        @SerialName("error") val error: String? = null
    ): PartnerPacket {
        override val type get() = PacketType.AuthResponse
    }

    @Serializable
    data class QueryRequest(
        @SerialName("id") override val id: String,
        @SerialName("uuids") val uuids: List<String> = emptyList(),
        @EncodeDefault @SerialName("maxAge") val maxAge: Long = ProfileCacheService.maxAge
    ): PartnerPacket {
        override val type get() = PacketType.QueryRequest
    }

    @Serializable
    data class QueryResponse(
        @SerialName("id") override val id: String,
        @EncodeDefault @SerialName("success") val success: Boolean = false,
        @EncodeDefault @SerialName("uuids") val uuids: List<String> = emptyList(),
        @EncodeDefault @SerialName("data") val data: Map<String, Entry> = emptyMap()
    ): PartnerPacket {
        override val type get() = PacketType.QueryResponse
    }

    @Serializable
    data class Header(@SerialName("t") val type: Int)

    @Serializable
    data class Entry(
        @SerialName("d") val data: String,
        @SerialName("ts") val timestamp: Long
    )
}