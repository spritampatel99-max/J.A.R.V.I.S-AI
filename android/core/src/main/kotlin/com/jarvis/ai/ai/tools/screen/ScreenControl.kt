package com.jarvis.ai.ai.tools.screen

import java.util.Locale

/**
 * What the phone's accessibility service can be asked to do. It is deliberately small: look at the screen in front, scroll it,
 * and press the system's own navigation controls. It cannot tap or type inside another app, and it never uses screen
 * coordinates, so it cannot be steered onto JARVIS 3.0's own buttons.
 */
enum class PhoneAction(val wire: String, val done: String) {
    BACK("back", "Went back"),
    HOME("home", "Went to the home screen"),
    RECENTS("recents", "Opened the recent apps"),
    NOTIFICATIONS("notifications", "Opened the notification shade"),
    QUICK_SETTINGS("quick_settings", "Opened quick settings"),
    LOCK("lock", "Locked the screen"),
    SCREENSHOT("screenshot", "Took a screenshot"),
    ;

    companion object {
        fun fromWire(value: String): PhoneAction? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** The app in front, as the accessibility service sees it. */
data class FrontApp(val packageName: String, val label: String)

/** One look at the front window, taken in a single step so what was checked is what was read. */
sealed interface Snapshot {
    data class Text(val app: FrontApp, val lines: List<String>) : Snapshot

    /** The window belongs to an app the policy refuses; its content was not read. */
    data class Refused(val app: FrontApp) : Snapshot

    /** No window could be read. */
    data object None : Snapshot
}

sealed interface RawResult {
    data class Done(val app: FrontApp?) : RawResult

    data class Refused(val app: FrontApp) : RawResult

    data class Failed(val reason: String) : RawResult
}

/** The Android side. [allow] is asked with the front window's package BEFORE anything of that window is read or touched. */
interface RawScreen {
    val connected: Boolean

    fun front(): FrontApp?

    suspend fun snapshot(allow: (String) -> Boolean): Snapshot

    suspend fun scroll(down: Boolean, allow: (String) -> Boolean): RawResult

    suspend fun perform(action: PhoneAction): RawResult
}

sealed interface ScreenRead {
    data class Text(val app: String, val lines: List<String>) : ScreenRead

    data object NotEnabled : ScreenRead

    data class OffLimits(val app: String) : ScreenRead

    data object Empty : ScreenRead
}

sealed interface ScreenAct {
    data class Done(val app: String?) : ScreenAct

    data object NotEnabled : ScreenAct

    data class OffLimits(val app: String) : ScreenAct

    data class Failed(val reason: String) : ScreenAct
}

interface ScreenControl {
    val enabled: Boolean

    suspend fun read(): ScreenRead

    suspend fun scroll(down: Boolean): ScreenAct

    suspend fun perform(action: PhoneAction): ScreenAct
}

/**
 * Which apps the screen tools must never look at or touch.
 *
 *  - Always: JARVIS 3.0 itself (so nothing can read or press its own confirmation buttons), payment apps, banking apps,
 *    password managers and authenticators, and anything the person added to their own list. This is a best-effort list by
 *    package name, not a guarantee; the person's own list covers what it misses.
 *  - Reading and looking only: system settings, permission and install dialogs and the system's own interface. These screens
 *    can be read aloud, but the tools never act on them.
 */
class ScreenGuard(
    private val ownPackage: String,
    private val personalList: () -> Set<String> = { emptySet() },
) {
    fun mayRead(packageName: String): Boolean = !neverTouched(packageName)

    fun mayAct(packageName: String): Boolean {
        if (neverTouched(packageName)) return false
        return packageName.lowercase(Locale.ROOT) !in NO_ACTION
    }

    private fun neverTouched(packageName: String): Boolean {
        val name = packageName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return true
        if (name == ownPackage.lowercase(Locale.ROOT)) return true
        if (personalList().any { it.trim().lowercase(Locale.ROOT) == name }) return true
        if (name in NEVER) return true
        return SENSITIVE_WORDS.any { it in name }
    }

    companion object {
        /** Parts of a package name that mark a money or secrets app. Deliberately broad: a wrongly blocked app is an inconvenience, a wrongly read bank screen is not. */
        val SENSITIVE_WORDS = listOf(
            "bank", "paisa", "upi", "bhim", "paytm", "phonepe", "paypal", "venmo", "wallet", "razorpay", "cred.club",
            "password", "authenticator", "keepass", "bitwarden", "lastpass", "1password", "onepassword", "dashlane", "keeper.security",
        )

        private val NEVER = setOf(
            "com.samsung.android.spay", "com.squareup.cash", "in.org.npci.upiapp", "com.google.android.apps.walletnfcrel",
        )

        /** Looked at but never acted on: the screens where Android asks to allow something. */
        private val NO_ACTION = setOf(
            "com.android.settings", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.systemui",
        )
    }
}

/** Puts [ScreenGuard] in front of the Android side, so a policy check can never be forgotten by the code that touches the screen. */
class GuardedScreenControl(private val raw: RawScreen, private val guard: ScreenGuard) : ScreenControl {

    override val enabled: Boolean get() = raw.connected

    override suspend fun read(): ScreenRead {
        if (!raw.connected) return ScreenRead.NotEnabled
        return when (val snap = raw.snapshot(guard::mayRead)) {
            is Snapshot.Text -> if (guard.mayRead(snap.app.packageName)) {
                ScreenRead.Text(snap.app.label, snap.lines)
            } else {
                ScreenRead.OffLimits(snap.app.label)
            }
            is Snapshot.Refused -> ScreenRead.OffLimits(snap.app.label)
            Snapshot.None -> ScreenRead.Empty
        }
    }

    override suspend fun scroll(down: Boolean): ScreenAct {
        if (!raw.connected) return ScreenAct.NotEnabled
        val front = raw.front() ?: return ScreenAct.Failed("I cannot tell which app is in front, so I did not scroll.")
        if (!guard.mayAct(front.packageName)) return ScreenAct.OffLimits(front.label)
        return convert(raw.scroll(down, guard::mayAct))
    }

    override suspend fun perform(action: PhoneAction): ScreenAct {
        if (!raw.connected) return ScreenAct.NotEnabled
        // A screenshot copies whatever is in front, so it follows the reading rule. Pressing back, home, recents and the like touches no app's content.
        if (action == PhoneAction.SCREENSHOT) {
            val front = raw.front() ?: return ScreenAct.Failed("I cannot tell which app is in front, so I did not take the screenshot.")
            if (!guard.mayRead(front.packageName)) return ScreenAct.OffLimits(front.label)
        }
        return convert(raw.perform(action))
    }

    private fun convert(result: RawResult): ScreenAct = when (result) {
        is RawResult.Done -> ScreenAct.Done(result.app?.label)
        is RawResult.Refused -> ScreenAct.OffLimits(result.app.label)
        is RawResult.Failed -> ScreenAct.Failed(result.reason)
    }
}

/** [packageName] is what the guard checks; it is blank only for a notification whose app is unknown, which the guard refuses. */
data class NotificationInfo(val app: String, val title: String, val text: String, val packageName: String = "")

data class ClearResult(val before: Int, val after: Int)

/** Notifications that are showing now, read through Android's notification access. Nothing is stored. */
interface NotificationPort {
    val enabled: Boolean

    suspend fun active(): List<NotificationInfo>

    suspend fun clearAll(): ClearResult?
}

/** Leaves out notifications from apps the guard protects (banking and payment apps, password managers, the person's own list), where one-time codes and balances would otherwise be read out. */
class GuardedNotifications(private val raw: NotificationPort, private val guard: ScreenGuard) : NotificationPort {
    override val enabled: Boolean get() = raw.enabled

    override suspend fun active(): List<NotificationInfo> = raw.active().filter { guard.mayRead(it.packageName) }

    override suspend fun clearAll(): ClearResult? = raw.clearAll()
}
