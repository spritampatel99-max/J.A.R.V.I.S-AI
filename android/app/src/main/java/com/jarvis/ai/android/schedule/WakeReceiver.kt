package com.jarvis.ai.android.schedule

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.ai.JarvisApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives the alarm set by [AndroidWakeScheduler], the restart / app-update broadcasts that clear Android's alarms, and the one Android 14 sends
 * when the person grants or takes back "Alarms & reminders" (taking it back deletes the exact alarms, so they must be asked for again). All of them do the
 * same thing: deliver what is due and ask for the next wake-up. A change of clock or time zone also works out wall-clock automations again. The work is small and local, so it finishes well inside the time Android gives a receiver.
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val clockChanged = action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED
        if (action != ACTION_WAKE && action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED && !clockChanged
        ) return
        // Java caches the time zone; ask it to look at the phone's setting again so "8:00" means 8:00 where the person is now.
        if (clockChanged) java.util.TimeZone.setDefault(null)
        val engine = (context.applicationContext as JarvisApplication).container.schedule
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Android gives a receiver about ten seconds. The work here is small, but if it is ever cut off nothing is lost: the next wake-up is
                // already requested, and an automation cut off mid-run is recorded as "interrupted" and not run again (see AutomationRunner).
                withTimeoutOrNull(8_000L) { engine.tick(clockChanged) }
            } catch (e: Exception) {
                // A failure must not crash the app from a background broadcast; the engine already recorded what went wrong.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE = "com.jarvis.ai.action.WAKE"
    }
}
