package dev.relay.mobile

object RelayConstants {
    const val APP_NAME = "relay"
    const val SETTINGS_CHANNEL = "relay/settings"
    const val STATUS_CHANNEL = "relay/status"
    const val DEFAULT_PORT = 9876
    const val NOTIFICATION_CREATED = "notification.created"
}

object RelayStatus {
    const val CONNECTED = "connected"
    const val CONNECTING = "connecting"
    const val DISCONNECTED = "disconnected"
    const val NOT_CONFIGURED = "not configured"
    const val SEND_FAILED = "send failed"

    fun connectionError(message: String?): String {
        return "connection error: ${message ?: "unknown error"}"
    }
}
