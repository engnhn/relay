package dev.relay.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class RelayNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        RelayConnection.prepare(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) {
            return
        }

        val extras = sbn.notification.extras
        val event = mapOf(
            "type" to RelayConstants.NOTIFICATION_CREATED,
            "package" to sbn.packageName,
            "app" to appLabel(sbn.packageName),
            "title" to extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            "body" to notificationBody(extras),
            "timestamp" to sbn.postTime / 1000
        )

        RelayConnection.send(applicationContext, event)
    }

    private fun appLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun notificationBody(extras: android.os.Bundle): String? {
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            return text
        }

        return extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    }
}
