package foo.starred.blackbird.websocket.partner.enums

sealed class WebSocketPartnerRegistry {
    enum class ServerBound(val id: Int) {
        Auth(100),
        Query(300)
    }

    enum class ClientBound(val id: Int) {
        Auth(200),
        Query(301)
    }
}
