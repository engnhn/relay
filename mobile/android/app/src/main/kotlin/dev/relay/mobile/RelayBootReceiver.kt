package dev.relay.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> RelayConnection.prepare(context)
        }
    }
}
