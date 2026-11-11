package com.jarvis.ai.android.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.jarvis.ai.productivity.Notifier
import com.jarvis.ai.ui.MainActivity

/** Shows notifications from the app. [canNotify] reads Android's real setting, so a refused permission is never reported as shown. */
class AndroidNotifier(private val context: Context) : Notifier {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders and automations", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders you set and notifications from your automations."
            },
        )
    }

    override fun canNotify(): Boolean = manager.areNotificationsEnabled()

    override fun notify(key: String, title: String, text: String): Boolean {
        if (!canNotify()) return false
        return try {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(key.hashCode(), notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val CHANNEL_ID = "reminders"
    }
}
