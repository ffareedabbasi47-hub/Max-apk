package com.example.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notifications arriving on the user's OWN device so MAX can summarize
 * them on request ("Max, kya naya hai?"). This only sees notifications on
 * THIS phone — it cannot access anyone else's device, account, or messages.
 *
 * Requires the user to manually enable it once under:
 * Settings > Apps > Special app access > Notification access > MAX
 * (Android requires this to be a manual, explicit grant — no app can turn
 * this on for itself, by design, to protect user privacy.)
 */
class NotificationReaderService : NotificationListenerService() {

    data class CapturedNotification(
        val appLabel: String,
        val title: String,
        val text: String,
        val timestamp: Long
    )

    companion object {
        val recentNotifications = mutableListOf<CapturedNotification>()
        private const val MAX_STORED = 50
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return

            val appLabel = try {
                val pm = applicationContext.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn.packageName
            }

            synchronized(recentNotifications) {
                recentNotifications.add(
                    CapturedNotification(appLabel, title, text, System.currentTimeMillis())
                )
                while (recentNotifications.size > MAX_STORED) {
                    recentNotifications.removeAt(0)
                }
            }
        } catch (e: Exception) {
            // Never crash the listener over a malformed notification
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No-op: we only care about capturing, not tracking dismissal
    }
}
