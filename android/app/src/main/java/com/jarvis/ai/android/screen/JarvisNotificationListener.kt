package com.jarvis.ai.android.screen

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.ai.tools.screen.NotificationInfo
import com.jarvis.ai.android.voice.BluetoothAudio
import com.jarvis.ai.voice.BluetoothRules
import com.jarvis.ai.voice.PostedNotification

/**
 * Lets the person ask what notifications are showing, and dismiss them, once they switch on "Notification access" for the app in
 * Settings. It reads the notifications that are showing right now when asked. It keeps no copy and sends nothing anywhere, and it
 * ignores notifications the person marked secret, ongoing ones such as a music player, group summaries, and the app's own.
 *
 * One more thing, only if the person switched it on in Prefs > Bluetooth: while a Bluetooth audio device is connected, a new
 * notification is read aloud (see [announce]). That is spoken by the phone's own voice, never sent to an online voice, never
 * treated as a command, and never done for banking, payment or password apps.
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

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        try {
            announce(sbn, rankingMap)
        } catch (e: RuntimeException) {
            // Announcing is a convenience; it must never take the listener down.
        }
    }

    private fun announce(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val container = (application as? JarvisApplication)?.container ?: return
        val ui = container.settings.ui.value
        if (!ui.announceNotifications || sbn.packageName == packageName) return
        val notification = sbn.notification
        if (notification.visibility == Notification.VISIBILITY_SECRET) return
        val device = BluetoothAudio.connected(this) ?: return
        if (!BluetoothRules.matches(BluetoothAudio.name(device), container.settings.bluetoothDevices)) return

        val ranking = Ranking()
        val important = rankingMap.getRanking(sbn.key, ranking) && ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        // The person's lock-screen privacy choice is respected: what the lock screen would hide is not read aloud while locked,
        // unless they turned that on for this feature.
        val hide = locked && notification.visibility != Notification.VISIBILITY_PUBLIC && !ui.announceLockedText
        val extras = notification.extras
        val posted = PostedNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = labelOf(sbn.packageName),
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            ongoing = sbn.isOngoing,
            groupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            category = notification.category,
            important = important,
            hideContent = hide,
        )
        val phrase = container.announcer.phrase(posted, ui.announceReadText) ?: return
        container.voice.speakPrivately(phrase)
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
                    lines = messageLines(extras),
                )
            }
    }

    /** The individual messages of a chat notification (oldest first), or the lines of an inbox-style one. */
    private fun messageLines(extras: android.os.Bundle?): List<String> {
        if (extras == null) return emptyList()
        val styled = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Bundle::class.java)
                ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                ?.mapNotNull { it.text?.toString()?.trim()?.takeIf { text -> text.isNotEmpty() } }
                .orEmpty()
        } catch (e: RuntimeException) {
            emptyList()
        }
        if (styled.isNotEmpty()) return styled
        return extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .orEmpty()
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
