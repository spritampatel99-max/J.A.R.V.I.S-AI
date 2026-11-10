package com.jarvis.ai.productivity

/** Asks Android to wake the app at a time, so due reminders and automations can run. Implemented with AlarmManager. */
interface WakeScheduler {
    fun schedule(atMs: Long): WakeResult
    fun cancel()
}

sealed interface WakeResult {
    /** Android accepted the request. [exact] is true only when the exact-alarm permission lets it fire on time; otherwise it can be late. */
    data class Scheduled(val exact: Boolean) : WakeResult

    data class Unavailable(val reason: String) : WakeResult
}

/** Shows a notification. [canNotify] reflects Android's notification permission right now. */
interface Notifier {
    fun canNotify(): Boolean
    fun notify(key: String, title: String, text: String): Boolean
}
