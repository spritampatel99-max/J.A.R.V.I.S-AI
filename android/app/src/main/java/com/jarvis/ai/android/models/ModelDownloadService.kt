package com.jarvis.ai.android.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.core.common.ByteFormat
import com.jarvis.ai.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while a model downloads or imports, and shows the progress in a notification. The downloading
 * itself is done by [ModelManager]; this service only holds the foreground slot and stops itself when no model is busy.
 * If the user has not allowed notifications the service still works, the notification is just not shown.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Preparing the download…", percent = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watcher?.isActive != true) {
            val manager = (application as JarvisApplication).container.modelManager
            watcher = scope.launch {
                manager.states.collect { states ->
                    val busy = states.values.filter { it.busy }
                    if (busy.isEmpty()) {
                        ServiceCompat.stopForeground(this@ModelDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val first = busy.first()
                        val progress = first.progress
                        val text = when {
                            progress?.totalBytes != null ->
                                "${first.spec.displayName}: ${ByteFormat.format(progress.bytesDone)} of ${ByteFormat.format(progress.totalBytes!!)}"
                            progress != null -> "${first.spec.displayName}: ${ByteFormat.format(progress.bytesDone)}"
                            else -> "${first.spec.displayName}: checking…"
                        }
                        val percent = progress?.fraction?.let { (it * 100).toInt() }
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, percent))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits a data-sync service to about six hours. Downloads are resumable, so stopping is safe. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as JarvisApplication).container.modelManager.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Progress of on-device AI model downloads"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String, percent: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("J.A.R.V.I.S. model")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "model_downloads"
        const val NOTIFICATION_ID = 4101
    }
}
