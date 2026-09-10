package dev.relay.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object RelayConnection {
    private const val PREFS = RelayConstants.APP_NAME
    private const val HOST = "host"
    private const val PORT = "port"
    private const val TOKEN = "token"
    private const val FINGERPRINT = "fingerprint"
    private const val PENDING_MESSAGES = "pending_messages"
    private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L

    private val trustManager = PinnedTrustManager()
    private val client = secureClient(trustManager)
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private var socket: WebSocket? = null
    private var activeConfig: Config? = null
    private var connected = false
    private val pending = ArrayDeque<String>()
    private var reconnectAttempts = 0
    private var reconnectScheduled = false

    data class Config(
        val host: String,
        val port: Int,
        val token: String,
        val fingerprint: String,
    )

    fun save(context: Context, host: String, port: Int, token: String, fingerprint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOST, host)
            .putInt(PORT, port)
            .putString(TOKEN, token)
            .putString(FINGERPRINT, normalizeFingerprint(fingerprint))
            .apply()
    }

    fun load(context: Context): Config? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(HOST, null)?.trim()
        val port = prefs.getInt(PORT, RelayConstants.DEFAULT_PORT)
        val token = prefs.getString(TOKEN, null)?.trim().orEmpty()
        val fingerprint = normalizeFingerprint(prefs.getString(FINGERPRINT, null).orEmpty())

        if (host.isNullOrEmpty() || fingerprint.length != 64) {
            return null
        }

        return Config(host, port, token, fingerprint)
    }

    fun prepare(context: Context) {
        val appContext = context.applicationContext
        val config = load(appContext) ?: return
        discoverThenConnect(appContext, config)
    }

    fun connect(context: Context) {
        val appContext = context.applicationContext
        reconnectScheduled = false
        reconnectHandler.removeCallbacksAndMessages(null)
        val config = load(appContext) ?: run {
            RelayStatusBridge.emit(RelayStatus.NOT_CONFIGURED)
            return
        }

        if (socket != null && activeConfig == config) {
            RelayStatusBridge.emit(if (connected) RelayStatus.CONNECTED else RelayStatus.CONNECTING)
            return
        }

        if (activeConfig != null && activeConfig != config) {
            pending.clear()
        }

        socket?.cancel()
        socket = null
        activeConfig = config
        connected = false
        reconnectAttempts = 0
        RelayStatusBridge.emit(RelayStatus.CONNECTING)

        openSocket(appContext, config)
    }

    fun send(context: Context, event: Map<String, Any?>) {
        val appContext = context.applicationContext
        val config = load(appContext)
        val payload = JSONObject(event.withToken(config)).toString()
        val currentSocket = socket

        if (currentSocket != null && connected) {
            if (!currentSocket.send(payload)) {
                RelayStatusBridge.emit(RelayStatus.SEND_FAILED)
                enqueuePending(appContext, payload)
            }
            return
        }

        enqueuePending(appContext, payload)
        if (config == null) {
            RelayStatusBridge.emit(RelayStatus.NOT_CONFIGURED)
            return
        }

        discoverThenConnect(appContext, config)
    }

    private fun openSocket(context: Context, config: Config) {
        trustManager.pinnedFingerprint = config.fingerprint
        val request = Request.Builder()
            .url("wss://${config.host}:${config.port}")
            .build()

        socket = client.newWebSocket(request, listener(context.applicationContext))
    }

    private fun listener(context: Context): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(helloPayload())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = runCatching { JSONObject(text).optString("type") }.getOrNull()

                when (type) {
                    RelayConstants.RELAY_READY -> {
                        connected = true
                        reconnectAttempts = 0
                        reconnectScheduled = false
                        RelayStatusBridge.emit(RelayStatus.CONNECTED)

                        flushPending(context, webSocket)
                    }
                    RelayConstants.RELAY_UNAUTHORIZED -> {
                        if (socket == webSocket) {
                            socket = null
                            connected = false
                        }
                        RelayStatusBridge.emit(RelayStatus.AUTH_FAILED)
                        webSocket.close(1008, RelayStatus.AUTH_FAILED)
                    }
                }
            }

            private fun helloPayload(): String {
                val config = activeConfig
                val payload = mutableMapOf<String, Any?>("type" to RelayConstants.RELAY_HELLO)
                val token = config?.token?.takeIf { it.isNotBlank() }
                if (token != null) {
                    payload["token"] = token
                }
                return JSONObject(payload as Map<*, *>).toString()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val wasActiveSocket = socket == webSocket
                if (socket == webSocket) {
                    socket = null
                    connected = false
                }
                RelayStatusBridge.emit(RelayStatus.DISCONNECTED)
                if (wasActiveSocket) {
                    scheduleReconnect(context)
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                val wasActiveSocket = socket == webSocket
                if (socket == webSocket) {
                    socket = null
                    connected = false
                }
                RelayStatusBridge.emit(RelayStatus.connectionError(throwable.message))
                if (wasActiveSocket) {
                    scheduleReconnect(context)
                }
            }
        }
    }

    private fun scheduleReconnect(context: Context) {
        val config = activeConfig ?: return
        if (reconnectScheduled) {
            return
        }

        reconnectScheduled = true
        reconnectAttempts += 1
        val delay = reconnectDelayMs(reconnectAttempts)
        RelayStatusBridge.emit("${RelayStatus.WAITING_TO_RECONNECT}: ${delay / 1_000}s")

        reconnectHandler.postDelayed({
            reconnectScheduled = false
            if (connected || activeConfig != config) {
                return@postDelayed
            }

            discoverThenConnect(context, config)
        }, delay)
    }

    private fun discoverThenConnect(context: Context, fallbackConfig: Config) {
        RelayDiscovery.discover(
            context = context,
            onFound = { found ->
                val host = found["host"]?.toString()?.trim().orEmpty()
                val port = found["port"] as? Int ?: fallbackConfig.port
                val config = if (host.isEmpty()) {
                    fallbackConfig
                } else {
                    fallbackConfig.copy(host = host, port = port)
                }

                save(context, config.host, config.port, config.token, config.fingerprint)
                startConfig(context, config)
            },
            onNotFound = {
                startConfig(context, fallbackConfig)
            },
        )
    }

    private fun startConfig(context: Context, config: Config) {
        if (socket != null && activeConfig == config) {
            RelayStatusBridge.emit(if (connected) RelayStatus.CONNECTED else RelayStatus.CONNECTING)
            return
        }

        socket?.cancel()
        socket = null
        activeConfig = config
        connected = false
        RelayStatusBridge.emit(RelayStatus.CONNECTING)
        openSocket(context, config)
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceAtMost(5)
        return (INITIAL_RECONNECT_DELAY_MS * multiplier).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun Map<String, Any?>.withToken(config: Config?): Map<String, Any?> {
        val token = config?.token?.takeIf { it.isNotBlank() } ?: return this
        return this + ("token" to token)
    }

    @Synchronized
    private fun enqueuePending(context: Context, payload: String) {
        pending.addLast(payload)
        while (pending.size > RelayConstants.MAX_PENDING_MESSAGES) {
            pending.removeFirst()
        }
        savePending(context)
    }

    @Synchronized
    private fun flushPending(context: Context, webSocket: WebSocket) {
        if (pending.isEmpty()) {
            loadPending(context).forEach { pending.addLast(it) }
        }
        while (pending.size > RelayConstants.MAX_PENDING_MESSAGES) {
            pending.removeFirst()
        }

        val remaining = ArrayDeque<String>()
        while (pending.isNotEmpty()) {
            val payload = pending.removeFirst()
            if (!webSocket.send(payload)) {
                remaining.addLast(payload)
            }
        }

        pending.addAll(remaining)
        savePending(context)
    }

    @Synchronized
    private fun savePending(context: Context) {
        val values = JSONArray()
        pending.forEach { values.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PENDING_MESSAGES, values.toString())
            .apply()
    }

    private fun loadPending(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PENDING_MESSAGES, "[]")
        val values = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun normalizeFingerprint(value: String): String {
        return value.filter { it.isLetterOrDigit() }.uppercase()
    }

    private class PinnedTrustManager : X509TrustManager {
        @Volatile
        var pinnedFingerprint: String = ""

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val expected = pinnedFingerprint
            if (expected.length != 64) {
                throw java.security.cert.CertificateException("receiver fingerprint is required")
            }

            val leaf = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("server certificate is missing")
            val actual = certificateFingerprint(leaf)
            if (actual != expected) {
                throw java.security.cert.CertificateException("receiver fingerprint mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun certificateFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(separator = "") { "%02X".format(it) }
    }

    private fun secureClient(trustManager: PinnedTrustManager): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
