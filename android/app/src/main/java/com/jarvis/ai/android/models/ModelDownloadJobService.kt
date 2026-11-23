package com.jarvis.ai.android.models

import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import com.jarvis.ai.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runs while a large download is going, as a user-initiated data transfer (Android 14 and later). Unlike a data-sync foreground
 * service it has no six-hour cap and shows up in Android's "active apps" list where the person can stop it. The downloading itself
 * is done by [ModelManager] and the offline library; this class only holds the slot and shows the progress.
 *
 * Android only allows such a job to be scheduled while the app is on screen, which is the case, because a download starts with a tap.
 * Stopping the job pauses the downloads; what was downloaded is kept and Resume continues from there.
 */
class ModelDownloadJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: DownloadWatcher? = null

    override fun onStartJob(params: JobParameters): Boolean {
        DownloadNotifications.ensureChannel(this)
        // A user-initiated job must show a notification as soon as it starts.
        setNotification(
            params,
            DownloadNotifications.ID,
            DownloadNotifications.build(this, "Preparing the download…", percent = null),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val container = (application as JarvisApplication).container
        val manager = getSystemService(NotificationManager::class.java)
        val created = DownloadWatcher(container, scope)
        watcher = created
        created.start(
            onUpdate = { text, percent -> manager.notify(DownloadNotifications.ID, DownloadNotifications.build(this, text, percent)) },
            onIdle = {
                created.stop()
                jobFinished(params, false)
            },
        )
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        watcher?.stop()
        watcher = null
        val container = (application as JarvisApplication).container
        container.modelManager.cancelAll()
        container.library.cancel()
        // Not rescheduled by Android: the person starts it again with Resume.
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
