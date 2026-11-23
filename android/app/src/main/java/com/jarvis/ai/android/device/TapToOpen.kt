package com.jarvis.ai.android.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * The fallback when Android refuses to open another app from the background: a notification that opens it when the person taps.
 * A tap on a notification is always allowed to start an activity. The intent inside is the one the app already built for the request
 * the person made; nothing new is added to it, and the notification only shows a short, cleaned label.
 */
internal object TapToOpen {
    private const val CHANNEL_ID = "tap_to_open"
    private const val MAX_LABEL = 40

    /** @return true when the notification was posted (so the person can finish the request by tapping it). */
    fun offer(context: Context, intent: Intent, what: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false

        val channel = NotificationChannel(CHANNEL_ID, "Tap to open", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Shown when Android does not let JARVIS 3.0 open an app by itself"
        manager.createNotificationChannel(channel)

        val label = label(what)
        val open = PendingIntent.getActivity(
            context,
            label.hashCode(),
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Tap to open $label")
            .setContentText("JARVIS 3.0 could not open it from the background.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID_BASE + (label.hashCode() and 0xFF), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Printable characters only, one line, short: the label can come from an installed app's own name. */
    fun label(what: String): String {
        val clean = what.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)
        return clean.ifEmpty { "the app" }
    }

    private const val NOTIFICATION_ID_BASE = 5200
}
