package dev.relay.mobile

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object RelayStatusBridge : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sink: EventChannel.EventSink? = null
    private var lastStatus = RelayStatus.DISCONNECTED

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        emit(lastStatus)
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    fun emit(status: String) {
        lastStatus = status
        mainHandler.post {
            sink?.success(status)
        }
    }
}
