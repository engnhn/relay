package dev.relay.mobile

object RelayConstants {
    const val APP_NAME = "relay"
    const val SETTINGS_CHANNEL = "relay/settings"
    const val STATUS_CHANNEL = "relay/status"
    const val DEFAULT_PORT = 9876
    const val MAX_PENDING_MESSAGES = 20
    const val DISCOVERY_SERVICE_TYPE = "_relay._tcp."
    const val NOTIFICATION_CREATED = "notification.created"
    const val RELAY_HELLO = "relay.hello"
    const val RELAY_READY = "relay.ready"
    const val RELAY_UNAUTHORIZED = "relay.unauthorized"
}

object RelayStatus {
    const val CONNECTED = "connected"
    const val CONNECTING = "connecting"
    const val DISCONNECTED = "disconnected"
    const val NOT_CONFIGURED = "not configured"
    const val SEND_FAILED = "send failed"
    const val WAITING_TO_RECONNECT = "waiting to reconnect"
    const val DISCOVERING = "discovering receiver"
    const val DISCOVERY_FAILED = "receiver not found"
    const val AUTH_FAILED = "token rejected"

    fun connectionError(message: String?): String {
        return "connection error: ${message ?: "unknown error"}"
    }
}
