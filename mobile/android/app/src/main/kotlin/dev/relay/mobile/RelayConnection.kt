package dev.relay.mobile

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RelayConnection {
    private const val PREFS = RelayConstants.APP_NAME
    private const val HOST = "host"
    private const val PORT = "port"

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var activeConfig: Config? = null
    private var connected = false
    private val pending = ArrayDeque<String>()

    data class Config(val host: String, val port: Int)

    fun save(context: Context, host: String, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST, host)
            .putInt(PORT, port)
            .apply()
    }

    fun load(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(HOST, null)?.trim()
        val port = prefs.getInt(PORT, RelayConstants.DEFAULT_PORT)

        if (host.isNullOrEmpty()) {
            return null
        }

        return Config(host, port)
    }

    fun connect(context: Context) {
        val config = load(context) ?: run {
            RelayStatusBridge.emit(RelayStatus.NOT_CONFIGURED)
            return
        }

        if (socket != null && activeConfig == config) {
            RelayStatusBridge.emit(if (connected) RelayStatus.CONNECTED else RelayStatus.CONNECTING)
            return
        }

        socket?.cancel()
        socket = null
        activeConfig = config
        connected = false
        RelayStatusBridge.emit(RelayStatus.CONNECTING)

        val request = Request.Builder()
            .url("ws://${config.host}:${config.port}")
            .build()

        socket = client.newWebSocket(request, listener())
    }

    fun send(context: Context, event: Map<String, Any?>) {
        val payload = JSONObject(event).toString()
        val currentSocket = socket

        if (currentSocket != null && connected) {
            if (!currentSocket.send(payload)) {
                RelayStatusBridge.emit(RelayStatus.SEND_FAILED)
            }
            return
        }

        pending.addLast(payload)
        while (pending.size > 20) {
            pending.removeFirst()
        }
        connect(context)
    }

    private fun listener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                RelayStatusBridge.emit(RelayStatus.CONNECTED)

                while (pending.isNotEmpty()) {
                    webSocket.send(pending.removeFirst())
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket == webSocket) {
                    socket = null
                    connected = false
                }
                RelayStatusBridge.emit(RelayStatus.DISCONNECTED)
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (socket == webSocket) {
                    socket = null
                    connected = false
                }
                RelayStatusBridge.emit(RelayStatus.connectionError(throwable.message))
            }
        }
    }
}
