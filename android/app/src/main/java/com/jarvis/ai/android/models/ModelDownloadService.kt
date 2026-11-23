package com.jarvis.ai.android.models

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.jarvis.ai.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The fallback for [ModelDownloadJobService]: keeps the app alive while a model imports, or while a download runs on a phone that
 * refused the user-initiated job. The downloading itself is done by [ModelManager]; this service only holds the foreground slot,
 * shows the progress, and stops itself when nothing is busy. If notifications are not allowed the service still works.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: DownloadWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DownloadNotifications.ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            DownloadNotifications.ID,
            DownloadNotifications.build(this, "Preparing the download…", percent = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (watcher?.isWatching != true) {
            val container = (application as JarvisApplication).container
            val manager = getSystemService(NotificationManager::class.java)
            val created = DownloadWatcher(container, scope)
            watcher = created
            created.start(
                onUpdate = { text, percent -> manager.notify(DownloadNotifications.ID, DownloadNotifications.build(this, text, percent)) },
                onIdle = {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                },
            )
        }
        return START_NOT_STICKY
    }

    /** Android limits a data-sync service to about six hours. Downloads are resumable, so stopping is safe. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val container = (application as JarvisApplication).container
        container.modelManager.cancelAll()
        container.library.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
