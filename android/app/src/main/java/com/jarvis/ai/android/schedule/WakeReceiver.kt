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
 * same thing: deliver what is due and ask for the next wake-up. The work is small and local, so it finishes well inside the time Android gives a receiver.
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_WAKE && action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return
        val engine = (context.applicationContext as JarvisApplication).container.schedule
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(8_000L) { engine.tick() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WAKE = "com.jarvis.ai.action.WAKE"
    }
}
