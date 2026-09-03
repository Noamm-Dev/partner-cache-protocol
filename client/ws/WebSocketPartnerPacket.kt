package init.ws

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object WebSocketPartnerPacket {
    @Serializable
    data class Entry(
        @SerialName("d") val data: String,
        @SerialName("ts") val timestamp: Long,
    )

    @Serializable
    data class In(
        @SerialName("t") val type: Int,
        @SerialName("id") val id: String,
        @SerialName("token") val token: String? = null,
        @SerialName("uuids") val uuids: List<String>? = null,
        @SerialName("data") val data: Map<String, Entry>? = null,
    )

    @Serializable
    data class Out(
        @SerialName("t") val type: Int,
        @SerialName("id") val id: String,
        @SerialName("success") val success: Boolean? = null,
        @SerialName("error") val error: String? = null,
        @SerialName("uuids") val uuids: List<String>? = null,
        @SerialName("data") val data: Map<String, Entry>? = null,
    )

    @Serializable
    data class PacketHeader(
        @SerialName("t") val type: Int,
        @SerialName("id") val id: String
    )
}
