package com.jarvis.ai.voice

import com.jarvis.ai.ai.tools.screen.ScreenGuard
import java.util.Locale

/** A notification that has just appeared, with only what is needed to decide whether to read it aloud. */
data class PostedNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    /** Android's notification category, such as "msg" or "call". */
    val category: String? = null,
    /** True when the notification is at least normal importance (it would make a sound or show a banner). */
    val important: Boolean = true,
    /**
     * True when the phone would not show this notification's words on its lock screen (the person's own privacy choice). Only the
     * app is then named: no sender, no text.
     */
    val hideContent: Boolean = false,
)

/**
 * Decides what, if anything, to say when a notification arrives while a Bluetooth audio device is connected. The decision is about
 * output only: what is read aloud is the person's own private content, spoken on the phone's own voice, and it can never become a command.
 * Silent, ongoing, call, progress and media notifications and anything from payment, banking or password apps stay silent.
 */
class NotificationAnnouncer(
    private val guard: ScreenGuard,
    private val now: () -> Long,
) {
    private val recent = LinkedHashMap<String, Long>()
    private val spoken = ArrayDeque<Long>()
    private var dropped = 0

    /** The sentence to say, or null to stay silent. [readText] false says who it is from, never what it says. */
    fun phrase(n: PostedNotification, readText: Boolean): String? {
        if (!n.important || n.ongoing || n.groupSummary) return null
        if (n.category?.lowercase(Locale.ROOT) in QUIET_CATEGORIES) return null
        if (!guard.mayRead(n.packageName)) return null
        val title = if (n.hideContent) "" else clean(n.title)
        val text = if (n.hideContent) "" else clean(n.text)
        if (title.isEmpty() && text.isEmpty() && !n.hideContent) return null

        val t = now()
        recent.entries.removeAll { t - it.value > REPEAT_WINDOW_MS }
        val fingerprint = n.key + "|" + text
        if (recent.containsKey(fingerprint)) return null
        recent[fingerprint] = t
        while (recent.size > MAX_REMEMBERED) recent.remove(recent.keys.first())

        while (spoken.isNotEmpty() && t - spoken.first() > RATE_WINDOW_MS) spoken.removeFirst()
        if (spoken.size >= MAX_PER_WINDOW) {
            dropped++
            return null
        }
        spoken.addLast(t)

        val app = n.appLabel.ifBlank { "an app" }
        val isMessage = n.category.equals("msg", ignoreCase = true)
        val lead = when {
            isMessage && title.isNotEmpty() -> "New message from $title on $app"
            title.isNotEmpty() -> "$app: $title"
            else -> "New notification from $app"
        }
        val tail = if (readText && text.isNotEmpty()) ". $text" else ""
        val missed = if (dropped > 0) "$dropped more notifications were not read out. " else ""
        dropped = 0
        return (missed + lead + tail).take(MAX_SPOKEN)
    }

    private fun clean(raw: String): String = raw
        .replace(URL, "a link")
        .replace(Regex("""[\u0000-\u001f\u007f]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(MAX_FIELD)

    private companion object {
        val QUIET_CATEGORIES = setOf("call", "progress", "transport", "service", "sys", "status", "navigation", "promo", "recommendation", "alarm", "stopwatch")
        val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        const val REPEAT_WINDOW_MS = 60_000L
        const val RATE_WINDOW_MS = 60_000L
        const val MAX_PER_WINDOW = 5
        const val MAX_REMEMBERED = 50
        const val MAX_FIELD = 140
        const val MAX_SPOKEN = 260
    }
}

/** Which Bluetooth devices Jarvis reacts to, and what it says on connecting. */
object BluetoothRules {
    /** An empty allow-list means any Bluetooth audio device. */
    fun matches(deviceName: String?, allowed: Set<String>): Boolean =
        allowed.isEmpty() || (deviceName != null && allowed.any { it.equals(deviceName, ignoreCase = true) })

    /** Only counts of notifications are spoken here, never their content. */
    fun connectedLine(deviceName: String?, waitingNotifications: Int?): String {
        val name = deviceName?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
        val head = if (name != null) "Bluetooth connected: $name." else "Bluetooth connected."
        val waiting = when {
            waitingNotifications == null -> ""
            waitingNotifications == 0 -> " You have no notifications."
            waitingNotifications == 1 -> " You have 1 notification."
            else -> " You have $waitingNotifications notifications."
        }
        return head + waiting
    }
}
