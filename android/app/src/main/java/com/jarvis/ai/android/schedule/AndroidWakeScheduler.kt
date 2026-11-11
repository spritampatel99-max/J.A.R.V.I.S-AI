package com.jarvis.ai.android.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.ai.productivity.WakeResult
import com.jarvis.ai.productivity.WakeScheduler

/**
 * Asks Android to wake the app once, at the next time a reminder or automation is due. Android keeps only this one request; each time
 * something runs, the next one is asked for. Without the exact-alarm permission Android may deliver it a few minutes late (and the app
 * says so); with it, Android delivers it on time.
 */
class AndroidWakeScheduler(private val context: Context) : WakeScheduler {

    private val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WakeReceiver::class.java).setAction(WakeReceiver.ACTION_WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun schedule(atMs: Long): WakeResult = try {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending())
        }
        WakeResult.Scheduled(exact)
    } catch (e: SecurityException) {
        WakeResult.Unavailable("Android refused to schedule the alarm.")
    }

    override fun cancel() {
        alarms.cancel(pending())
    }

    private companion object {
        const val REQUEST_CODE = 4101
    }
}
