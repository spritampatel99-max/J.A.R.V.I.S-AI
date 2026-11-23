package com.jarvis.ai.android.models

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Keeps the app allowed to work while a download or import runs.
 *
 * A download from the internet is started as a user-initiated data-transfer job (no time cap, listed by Android as an active app).
 * Importing a file the person already has needs no network, which such a job requires, so it, and any case where Android refuses the
 * job, uses the foreground service ([ModelDownloadService]) instead. If Android refuses that too, the work still runs while the app
 * is open; it just is not protected in the background.
 */
object DownloadKeeper {
    private const val JOB_ID = 7101

    fun start(context: Context, downloadsFromInternet: Boolean) {
        if (downloadsFromInternet && scheduleJob(context)) return
        startService(context)
    }

    private fun scheduleJob(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        // Already scheduled or running: the watcher inside it covers every download, and scheduling again would restart it.
        if (scheduler.getPendingJob(JOB_ID) != null) return true
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, ModelDownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        return try {
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (e: RuntimeException) {
            false // not allowed right now (for example the app is not on screen), so the service is tried
        }
    }

    private fun startService(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
        } catch (e: RuntimeException) {
            // Android refused to start the service. The work still runs while the app is open.
        }
    }
}
