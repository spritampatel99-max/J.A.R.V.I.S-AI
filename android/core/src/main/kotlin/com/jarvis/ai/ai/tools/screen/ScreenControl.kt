package com.jarvis.ai.ai.tools.screen

import java.util.Locale

/**
 * What the phone's accessibility service can be asked to do to the system: press its own navigation controls. Looking at the
 * screen, scrolling, and the optional tap, type and WhatsApp-send steps are separate calls on [ScreenControl], each behind
 * [ScreenGuard]. Nothing here uses screen coordinates: a tap finds a control by its label, so it cannot be steered onto JARVIS
 * 3.0's own buttons (that app is on the guard's never-touch list anyway).
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

/** Result of looking for one control by its label. */
sealed interface RawTap {
    data class Done(val app: FrontApp) : RawTap

    data class Refused(val app: FrontApp) : RawTap

    /** Nothing visible in the front window carries that label. */
    data object NotFound : RawTap

    /** More than one visible control carries it, so none was pressed. */
    data class Ambiguous(val count: Int) : RawTap

    data class Failed(val reason: String) : RawTap
}

/** The text in the front window's message box, read in one step with the package check. */
sealed interface RawBox {
    /** [text] is empty when the box shows only its hint. */
    data class Text(val app: FrontApp, val text: String) : RawBox

    data class Refused(val app: FrontApp) : RawBox

    /** The front window has no ordinary text box (a password box does not count). */
    data object NoBox : RawBox

    data object None : RawBox
}

/** The Android side. [allow] is asked with the front window's package BEFORE anything of that window is read or touched. */
interface RawScreen {
    val connected: Boolean

    fun front(): FrontApp?

    suspend fun snapshot(allow: (String) -> Boolean): Snapshot

    suspend fun scroll(down: Boolean, allow: (String) -> Boolean): RawResult

    suspend fun perform(action: PhoneAction): RawResult

    /** Presses the one visible control whose text, description or view name is [label]. Never password boxes, never by coordinates. */
    suspend fun tap(label: String, allow: (String) -> Boolean): RawTap

    /** Adds [text] to the focused text box (or the only one) of the front window. Never a password box. */
    suspend fun type(text: String, allow: (String) -> Boolean): RawResult

    /** What the front window's text box holds right now. */
    suspend fun box(allow: (String) -> Boolean): RawBox
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

/** What happened to a WhatsApp message that was typed in and then sent from the on-screen button. */
sealed interface SendPress {
    /** The Send control was pressed. [box] says what the message box did afterwards, which is the only sign Android gives. */
    data class Pressed(val box: BoxAfter) : SendPress

    data object NotEnabled : SendPress

    data class OffLimits(val app: String) : SendPress

    /** The app in front is not the one the message was meant for, so nothing was pressed. */
    data class WrongApp(val app: String) : SendPress

    /** The box does not hold exactly the approved words (or is empty), so nothing was pressed. */
    data object TextMismatch : SendPress

    data class Failed(val reason: String) : SendPress
}

enum class BoxAfter { CLEARED, STILL_THERE, UNKNOWN }

interface ScreenControl {
    val enabled: Boolean

    suspend fun read(): ScreenRead

    suspend fun scroll(down: Boolean): ScreenAct

    suspend fun perform(action: PhoneAction): ScreenAct

    /** Presses the control labelled [label] in the app in front. Off unless the person switched tapping and typing on. */
    suspend fun tap(label: String): ScreenAct

    /** Types [text] into the text box in the app in front. Off unless the person switched tapping and typing on. */
    suspend fun type(text: String): ScreenAct

    /**
     * Presses Send in a messaging app, but only if one of [inApps] is in front and its message box holds exactly [expected].
     * The caller decides whether sending is allowed at all (the person's own switch); this method only enforces what is on screen.
     */
    suspend fun sendComposed(expected: String, inApps: Set<String>): SendPress
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

/** Labels that move money, send, delete or grant something. A tap on one is never done by the generic tap tool. */
object RiskyLabels {
    private val WORDS = setOf(
        "send", "pay", "payment", "buy", "purchase", "order", "checkout", "confirm", "delete", "remove", "erase", "clear",
        "transfer", "donate", "subscribe", "install", "uninstall", "allow", "grant", "accept", "approve", "authorize",
        "sign", "submit", "book", "reserve", "post", "publish", "share", "call", "dial", "reset", "format", "wipe",
        "भेजें", "भेजो", "भुगतान", "खरीदें", "हटाएं", "मिटाएं", "स्वीकार",
    )

    fun isRisky(label: String): Boolean =
        label.lowercase(Locale.ROOT).split(Regex("""[^\p{L}\p{M}\p{N}]+""")).any { it in WORDS }
}

/** Compares what is in a message box with the words the person approved, ignoring only differences in spaces and line breaks. */
object ComposeText {
    private fun norm(text: String): String = text.replace('\u00A0', ' ').trim().replace(Regex("""\s+"""), " ")

    fun same(box: String, expected: String): Boolean {
        val e = norm(expected)
        return e.isNotEmpty() && norm(box) == e
    }
}

/** At most [max] screen actions in any [windowMs]: a runaway plan cannot keep tapping. */
class ActionLimiter(private val max: Int = 12, private val windowMs: Long = 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        while (times.isNotEmpty() && t - times.first() >= windowMs) times.removeFirst()
        if (times.size >= max) return false
        times.addLast(t)
        return true
    }
}

/**
 * Puts [ScreenGuard] in front of the Android side, so a policy check can never be forgotten by the code that touches the screen.
 *
 * [actionsOn] is the person's own switch for tapping and typing in other apps (off unless they turned it on). Sending a composed
 * message is a separate decision made by the caller. [pause] and [limiter] are replaceable so tests need no real waiting.
 */
class GuardedScreenControl(
    private val raw: RawScreen,
    private val guard: ScreenGuard,
    private val actionsOn: () -> Boolean = { false },
    private val limiter: ActionLimiter = ActionLimiter(),
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : ScreenControl {

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

    override suspend fun tap(label: String): ScreenAct {
        if (!raw.connected) return ScreenAct.NotEnabled
        if (!actionsOn()) return ScreenAct.Failed("Tapping and typing in other apps is switched off. Turn it on in Prefs > Access first.")
        val clean = label.trim()
        if (clean.isEmpty() || clean.length > MAX_LABEL) return ScreenAct.Failed("That is not a button name I can look for.")
        if (RiskyLabels.isRisky(clean)) {
            return ScreenAct.Failed("\"$clean\" sends, pays, deletes or grants something, so I do not press it by voice. Tap it yourself, or use the command made for it.")
        }
        val front = raw.front() ?: return ScreenAct.Failed("I cannot tell which app is in front, so I did not tap.")
        if (!guard.mayAct(front.packageName)) return ScreenAct.OffLimits(front.label)
        if (!limiter.tryAcquire()) return ScreenAct.Failed("That is a lot of taps in a minute, so I stopped. Ask again in a moment.")
        return when (val result = raw.tap(clean, guard::mayAct)) {
            is RawTap.Done -> ScreenAct.Done(result.app.label)
            is RawTap.Refused -> ScreenAct.OffLimits(result.app.label)
            RawTap.NotFound -> ScreenAct.Failed("I cannot see a button called \"$clean\" on this screen.")
            is RawTap.Ambiguous -> ScreenAct.Failed("${result.count} things on this screen are called \"$clean\", so I pressed none. Say which one.")
            is RawTap.Failed -> ScreenAct.Failed(result.reason)
        }
    }

    override suspend fun type(text: String): ScreenAct {
        if (!raw.connected) return ScreenAct.NotEnabled
        if (!actionsOn()) return ScreenAct.Failed("Tapping and typing in other apps is switched off. Turn it on in Prefs > Access first.")
        if (text.isBlank() || text.length > MAX_TYPED) return ScreenAct.Failed("That text is empty or too long to type.")
        val front = raw.front() ?: return ScreenAct.Failed("I cannot tell which app is in front, so I did not type.")
        if (!guard.mayAct(front.packageName)) return ScreenAct.OffLimits(front.label)
        if (!limiter.tryAcquire()) return ScreenAct.Failed("That is a lot of actions in a minute, so I stopped. Ask again in a moment.")
        return convert(raw.type(text, guard::mayAct))
    }

    override suspend fun sendComposed(expected: String, inApps: Set<String>): SendPress {
        if (!raw.connected) return SendPress.NotEnabled
        if (expected.isBlank()) return SendPress.TextMismatch
        // The app opens a moment after the request; wait for it rather than pressing whatever is in front.
        var front = raw.front()
        var waited = 0L
        while ((front == null || front.packageName !in inApps) && waited < FRONT_WAIT_MS) {
            pause(POLL_MS)
            waited += POLL_MS
            front = raw.front()
        }
        val app = front ?: return SendPress.Failed("I cannot tell which app is in front, so I did not press Send.")
        if (!guard.mayAct(app.packageName)) return SendPress.OffLimits(app.label)
        if (app.packageName !in inApps) return SendPress.WrongApp(app.label)

        // The text can arrive a moment after the window does.
        var box = raw.box(guard::mayAct)
        var tries = 0
        while (box is RawBox.Text && box.text.isBlank() && tries < BOX_TRIES) {
            pause(POLL_MS)
            tries++
            box = raw.box(guard::mayAct)
        }
        when (box) {
            is RawBox.Refused -> return SendPress.OffLimits(box.app.label)
            RawBox.NoBox, RawBox.None -> return SendPress.Failed("I cannot see the message box, so I did not press Send.")
            is RawBox.Text -> {
                if (box.app.packageName !in inApps) return SendPress.WrongApp(box.app.label)
                if (!ComposeText.same(box.text, expected)) return SendPress.TextMismatch
            }
        }
        if (!limiter.tryAcquire()) return SendPress.Failed("Too many actions in a minute, so I did not press Send.")
        when (val tap = raw.tap(SEND_LABEL, guard::mayAct)) {
            is RawTap.Done -> Unit
            is RawTap.Refused -> return SendPress.OffLimits(tap.app.label)
            RawTap.NotFound -> return SendPress.Failed("I cannot see WhatsApp's Send button.")
            is RawTap.Ambiguous -> return SendPress.Failed("More than one control is called Send, so I pressed none.")
            is RawTap.Failed -> return SendPress.Failed(tap.reason)
        }
        pause(AFTER_PRESS_MS)
        val after = raw.box(guard::mayAct)
        val state = when {
            after is RawBox.Text && after.text.isBlank() -> BoxAfter.CLEARED
            after is RawBox.Text && ComposeText.same(after.text, expected) -> BoxAfter.STILL_THERE
            else -> BoxAfter.UNKNOWN
        }
        return SendPress.Pressed(state)
    }

    private fun convert(result: RawResult): ScreenAct = when (result) {
        is RawResult.Done -> ScreenAct.Done(result.app?.label)
        is RawResult.Refused -> ScreenAct.OffLimits(result.app.label)
        is RawResult.Failed -> ScreenAct.Failed(result.reason)
    }

    private companion object {
        const val MAX_LABEL = 60
        const val MAX_TYPED = 500
        const val SEND_LABEL = "Send"
        const val POLL_MS = 400L
        const val FRONT_WAIT_MS = 8_000L
        const val BOX_TRIES = 8
        const val AFTER_PRESS_MS = 800L
    }
}

/** [packageName] is what the guard checks; it is blank only for a notification whose app is unknown, which the guard refuses. */
data class NotificationInfo(
    val app: String,
    val title: String,
    val text: String,
    val packageName: String = "",
    /** The individual messages of a chat notification, oldest first, when the app provides them. */
    val lines: List<String> = emptyList(),
)

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
