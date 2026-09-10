package dev.relay.mobile

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.MethodChannel

object RelayDiscovery {
    private const val TIMEOUT_MS = 8_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeDiscovery: NsdManager.DiscoveryListener? = null

    fun discover(context: Context, result: MethodChannel.Result) {
        discover(
            context = context,
            onFound = { result.success(it) },
            onNotFound = {
                RelayStatusBridge.emit(RelayStatus.DISCOVERY_FAILED)
                result.success(null)
            },
        )
    }

    fun discover(
        context: Context,
        onFound: (Map<String, Any?>) -> Unit,
        onNotFound: () -> Unit,
    ) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        activeDiscovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        RelayStatusBridge.emit(RelayStatus.DISCOVERING)

        var completed = false

        fun finish(listener: NsdManager.DiscoveryListener, value: Map<String, Any?>?) {
            mainHandler.post {
                if (completed) {
                    return@post
                }
                completed = true
                activeDiscovery = null
                runCatching { nsd.stopServiceDiscovery(listener) }

                if (value == null) {
                    onNotFound()
                } else {
                    onFound(value)
                }
            }
        }

        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != RelayConstants.DISCOVERY_SERVICE_TYPE) {
                    return
                }

                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        finish(
                            listener,
                            mapOf(
                                "host" to host,
                                "port" to serviceInfo.port
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finish(this, null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        activeDiscovery = listener
        nsd.discoverServices(
            RelayConstants.DISCOVERY_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
        mainHandler.postDelayed({ finish(listener, null) }, TIMEOUT_MS)
    }
}
