package com.jarvis.ai.android.screen

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.ai.ai.tools.screen.NotificationInfo

/**
 * Lets the person ask what notifications are showing, and dismiss them, once they switch on "Notification access" for the app in
 * Settings. It reads the notifications that are showing right now when asked. It does not listen in the background, keep a copy,
 * or send anything anywhere, and it ignores notifications the person marked secret, ongoing ones such as a music player, group
 * summaries, and the app's own.
 */
class JarvisNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        ScreenHub.notifications = this
    }

    override fun onListenerDisconnected() {
        if (ScreenHub.notifications === this) ScreenHub.notifications = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (ScreenHub.notifications === this) ScreenHub.notifications = null
        super.onDestroy()
    }

    /** Newest first. Empty when Android refuses (the access was removed a moment ago). */
    fun showing(): List<NotificationInfo> {
        val all = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()
        return all
            .filter { readable(it) }
            .sortedByDescending { it.postTime }
            .map { sbn ->
                val extras = sbn.notification.extras
                NotificationInfo(
                    app = labelOf(sbn.packageName),
                    title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty(),
                    text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))
                        ?.toString()?.trim().orEmpty(),
                    packageName = sbn.packageName,
                )
            }
    }

    /** How many notifications could be dismissed right now. */
    fun clearableCount(): Int {
        val all = try {
            activeNotifications
        } catch (e: SecurityException) {
            null
        } ?: return 0
        return all.count { it.isClearable && it.packageName != packageName }
    }

    /** Asks Android to dismiss every notification that can be dismissed. */
    fun clearAllNow() {
        cancelAllNotifications()
    }

    private fun readable(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        val notification = sbn.notification
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (notification.visibility == Notification.VISIBILITY_SECRET) return false
        return true
    }

    private fun labelOf(packageName: String): String = try {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString().ifBlank { packageName }
    } catch (e: Exception) {
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { packageName }
    }
}
