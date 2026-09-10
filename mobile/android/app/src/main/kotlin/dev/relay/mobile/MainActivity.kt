package dev.relay.mobile

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            RelayConstants.STATUS_CHANNEL
        ).setStreamHandler(RelayStatusBridge)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            RelayConstants.SETTINGS_CHANNEL
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "connect" -> {
                    val host = call.argument<String>("host")?.trim().orEmpty()
                    val port = call.argument<Int>("port") ?: RelayConstants.DEFAULT_PORT
                    val token = call.argument<String>("token")?.trim().orEmpty()
                    val fingerprint = call.argument<String>("fingerprint")?.trim().orEmpty()

                    if (host.isEmpty()) {
                        result.error("invalid_host", "host is required", null)
                        return@setMethodCallHandler
                    }

                    if (fingerprint.isEmpty()) {
                        result.error("invalid_fingerprint", "receiver fingerprint is required", null)
                        return@setMethodCallHandler
                    }

                    RelayConnection.save(context, host, port, token, fingerprint)
                    RelayConnection.connect(context)
                    result.success(null)
                }
                "getConnection" -> {
                    val config = RelayConnection.load(context)
                    result.success(
                        if (config == null) {
                            null
                        } else {
                            mapOf(
                                "host" to config.host,
                                "port" to config.port,
                                "token" to config.token,
                                "fingerprint" to config.fingerprint
                            )
                        }
                    )
                }
                "discover" -> RelayDiscovery.discover(context, result)
                "openNotificationSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}
