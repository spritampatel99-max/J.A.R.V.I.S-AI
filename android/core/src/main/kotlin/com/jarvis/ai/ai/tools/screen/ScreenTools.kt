package com.jarvis.ai.ai.tools.screen

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk

/**
 * Tools that look at the phone beyond JARVIS 3.0: the screen in front, system navigation, and notifications. They need the
 * person to have switched on the matching Android access (Accessibility, Notification access). What they read from other apps is
 * outside content, so it is marked untrusted: it can never approve anything, and any later step of the same request that
 * changes something asks for a tap.
 */
object ScreenTools {
    const val READ_SCREEN = "read_screen"
    const val SCREEN_SCROLL = "screen_scroll"
    const val PHONE_NAVIGATE = "phone_navigate"
    const val READ_NOTIFICATIONS = "read_notifications"
    const val CLEAR_NOTIFICATIONS = "clear_notifications"
    const val TAP_ON_SCREEN = "tap_on_screen"
    const val TYPE_TEXT = "type_text"
    const val READ_WHATSAPP = "read_whatsapp_unread"
    const val COUNT_WHATSAPP_UNREAD = "count_whatsapp_unread"
    const val PLAY_ON_YOUTUBE = "play_on_youtube"

    /** WhatsApp and WhatsApp Business. The only apps [ReadWhatsAppTool] looks at, and the only ones a message is sent in. */
    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    const val MAX_SCREEN_CHARS = 3_000
    const val MAX_NOTIFICATIONS = 8
    const val MAX_NOTIFICATION_CHARS = 160

    fun create(
        screen: ScreenControl?,
        notifications: NotificationPort?,
        apps: com.jarvis.ai.ai.tools.builtin.AppLauncher? = null,
        urls: com.jarvis.ai.ai.tools.builtin.UrlOpener? = null,
    ): List<Tool> = buildList {
        if (screen != null) {
            if (apps != null) add(CountWhatsAppUnreadTool(apps, screen))
            if (urls != null) add(PlayOnYoutubeTool(urls, screen))
            add(ReadScreenTool(screen))
            add(ScreenScrollTool(screen))
            add(PhoneNavigateTool(screen))
            add(TapOnScreenTool(screen))
            add(TypeTextTool(screen))
        }
        if (notifications != null) {
            add(ReadNotificationsTool(notifications))
            add(ClearNotificationsTool(notifications))
            add(ReadWhatsAppTool(notifications))
        }
    }
}

private const val SCREEN_OFF = "Screen access is off. Turn on JARVIS 3.0 in Settings > Accessibility (Prefs > Access has a shortcut), then ask again."
private const val NOTIFICATIONS_OFF = "Notification access is off. Allow JARVIS 3.0 in Settings > Notification access (Prefs > Access has a shortcut), then ask again."

private fun offLimits(app: String) =
    ToolError.Unsupported("I do not read or control $app. Payment apps, password managers, system permission screens and JARVIS 3.0 itself are off limits.")

class ReadScreenTool(private val screen: ScreenControl) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.READ_SCREEN,
        description = "Read out the text that is on the phone's screen right now, in whatever app is in front. Use it for \"what is on my screen\" or \"read this\".",
        params = emptyList(),
        risk = Risk.MEDIUM,
        timeoutMs = 10_000L,
        resultFormat = "CONFIRMED: the visible text as Android's accessibility service reports it, marked as outside content.",
    )

    override fun describe(args: ToolArgs): String = "Read the text on the screen"

    override suspend fun execute(args: ToolArgs): ToolResult = when (val read = screen.read()) {
        ScreenRead.NotEnabled -> ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF))
        is ScreenRead.OffLimits -> ToolResult.Failure(offLimits(read.app))
        ScreenRead.Empty -> ToolResult.Failure(ToolError.Failed("I could not read anything from the screen. Some apps hide their content from accessibility."))
        is ScreenRead.Text -> {
            val joined = read.lines.joinToString("\n")
            val text = if (joined.length > ScreenTools.MAX_SCREEN_CHARS) joined.take(ScreenTools.MAX_SCREEN_CHARS) + "\n(cut short)" else joined
            if (text.isBlank()) {
                ToolResult.Failure(ToolError.Failed("${read.app} is in front but shows no text I can read."))
            } else {
                ToolResult.Success("On screen in ${read.app}:\n$text", Verification.CONFIRMED, untrusted = true, answer = true, private = true)
            }
        }
    }
}

class ScreenScrollTool(private val screen: ScreenControl) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.SCREEN_SCROLL,
        description = "Scroll the page in front up or down by one screen.",
        params = listOf(ParamSpec("direction", ParamType.ENUM, "Which way", enumValues = listOf("down", "up"))),
        risk = Risk.MEDIUM,
        timeoutMs = 8_000L,
        resultFormat = "HANDED_OFF: Android was asked to scroll; whether the page moved is not reported back.",
    )

    override fun describe(args: ToolArgs): String = "Scroll ${args.string("direction")}"

    override suspend fun execute(args: ToolArgs): ToolResult =
        when (val result = screen.scroll(down = args.string("direction").equals("down", ignoreCase = true))) {
            ScreenAct.NotEnabled -> ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF))
            is ScreenAct.OffLimits -> ToolResult.Failure(offLimits(result.app))
            is ScreenAct.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
            is ScreenAct.Done -> ToolResult.Success(
                "Asked Android to scroll ${args.string("direction")}${result.app?.let { " in $it" }.orEmpty()}. It does not report whether the page moved.",
                Verification.HANDED_OFF,
            )
        }
}

class PhoneNavigateTool(private val screen: ScreenControl) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.PHONE_NAVIGATE,
        description = "Press one of the phone's own navigation controls: back, home, recents, the notification shade, quick settings, lock the screen, or take a screenshot.",
        params = listOf(ParamSpec("action", ParamType.ENUM, "Which control", enumValues = PhoneAction.entries.map { it.wire })),
        risk = Risk.MEDIUM,
        timeoutMs = 8_000L,
        resultFormat = "HANDED_OFF: Android was asked to do it; the result is not reported back.",
    )

    override fun describe(args: ToolArgs): String = "Phone: ${args.string("action").replace('_', ' ')}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val action = PhoneAction.fromWire(args.string("action"))
            ?: return ToolResult.Failure(ToolError.InvalidArguments("Unknown phone action"))
        return when (val result = screen.perform(action)) {
            ScreenAct.NotEnabled -> ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF))
            is ScreenAct.OffLimits -> ToolResult.Failure(offLimits(result.app))
            is ScreenAct.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
            is ScreenAct.Done -> ToolResult.Success("${action.done} (asked Android to; it does not report back).", Verification.HANDED_OFF)
        }
    }
}

class TapOnScreenTool(private val screen: ScreenControl) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.TAP_ON_SCREEN,
        description = "Press a button or item in the app in front by its visible name, for example \"Chats\" or \"Search\". " +
            "It cannot press anything that sends, pays, deletes or grants (those are refused), and it only works if the person turned on tapping and typing.",
        params = listOf(ParamSpec("label", ParamType.STRING, "The exact visible name of the button or item", maxLength = 60)),
        risk = Risk.MEDIUM,
        timeoutMs = 10_000L,
        resultFormat = "HANDED_OFF: Android was asked to press it; whether the app reacted is not reported back.",
    )

    override suspend fun prepare(args: ToolArgs): Prepared {
        val label = args.string("label").trim()
        if (label.isEmpty()) return Prepared.Stop(ToolError.InvalidArguments("There was no button name."))
        if (RiskyLabels.isRisky(label)) {
            return Prepared.Stop(ToolError.Unsupported("\"${label.take(40)}\" sends, pays, deletes or grants something, so I do not press it by voice. Tap it yourself."))
        }
        return Prepared.Ready(args)
    }

    override fun describe(args: ToolArgs): String = "Tap \"${args.string("label").take(40)}\""

    override suspend fun execute(args: ToolArgs): ToolResult = when (val result = screen.tap(args.string("label"))) {
        ScreenAct.NotEnabled -> ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF))
        is ScreenAct.OffLimits -> ToolResult.Failure(offLimits(result.app))
        is ScreenAct.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        is ScreenAct.Done -> ToolResult.Success(
            "Asked Android to press \"${args.string("label").take(40)}\"${result.app?.let { " in $it" }.orEmpty()}. It does not report whether anything happened.",
            Verification.HANDED_OFF,
        )
    }
}

class TypeTextTool(private val screen: ScreenControl) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.TYPE_TEXT,
        description = "Type words into the text box in the app in front (a search box, a note). It adds to what is there, never presses Send or Enter, " +
            "and refuses password boxes. It only works if the person turned on tapping and typing.",
        params = listOf(ParamSpec("text", ParamType.STRING, "The words to type", maxLength = 500)),
        risk = Risk.MEDIUM,
        timeoutMs = 10_000L,
        resultFormat = "HANDED_OFF: Android was asked to put the text in the box; the box is not read back.",
    )

    override fun describe(args: ToolArgs): String = "Type \"${args.string("text").take(40)}\""

    override suspend fun execute(args: ToolArgs): ToolResult = when (val result = screen.type(args.string("text"))) {
        ScreenAct.NotEnabled -> ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF))
        is ScreenAct.OffLimits -> ToolResult.Failure(offLimits(result.app))
        is ScreenAct.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        is ScreenAct.Done -> ToolResult.Success(
            "Asked Android to type that into the box${result.app?.let { " in $it" }.orEmpty()}. Nothing was sent.",
            Verification.HANDED_OFF,
        )
    }
}

/**
 * Reads the WhatsApp notifications that are still showing. That is the only inbox view an ordinary app can get: a message whose
 * notification was dismissed, or that arrived while the chat was open, is not listed. Everything it returns is the sender's own
 * words, so it is outside content: shown and read aloud to the person, never obeyed, never stored, never given to an AI model.
 */
class ReadWhatsAppTool(private val notifications: NotificationPort) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.READ_WHATSAPP,
        description = "Read out the unread WhatsApp messages that are showing as notifications: who they are from and what they say.",
        params = emptyList(),
        risk = Risk.MEDIUM,
        timeoutMs = 8_000L,
        resultFormat = "CONFIRMED: the WhatsApp notifications Android currently lists, marked as outside content. Nothing is stored.",
    )

    override fun describe(args: ToolArgs): String = "Read the unread WhatsApp messages"

    override suspend fun execute(args: ToolArgs): ToolResult {
        if (!notifications.enabled) return ToolResult.Failure(ToolError.Unavailable(NOTIFICATIONS_OFF))
        val chats = notifications.active().filter { it.packageName in ScreenTools.WHATSAPP_PACKAGES }
        if (chats.isEmpty()) {
            return ToolResult.Success(
                "I do not see any WhatsApp notifications. Messages you already opened or dismissed are not listed here. " +
                    "Say \"how many unread chats do I have in WhatsApp\" and I will open it and look at the chat list.",
                Verification.CONFIRMED,
                answer = true,
            )
        }
        val shown = chats.take(ScreenTools.MAX_NOTIFICATIONS)
        val lines = shown.mapIndexed { index, n ->
            val who = n.title.ifBlank { "WhatsApp" }.take(60)
            val said = (n.lines.ifEmpty { listOf(n.text) })
                .filter { it.isNotBlank() }
                .takeLast(MAX_LINES_PER_CHAT)
                .joinToString(" / ") { it.trim().take(ScreenTools.MAX_NOTIFICATION_CHARS) }
            "${index + 1}. $who${if (said.isNotEmpty()) ": $said" else ""}"
        }
        val more = if (chats.size > shown.size) "\n(${chats.size - shown.size} more not shown)" else ""
        val head = if (chats.size == 1) "You have 1 WhatsApp notification:" else "You have ${chats.size} WhatsApp notifications:"
        return ToolResult.Success("$head\n${lines.joinToString("\n")}$more", Verification.CONFIRMED, untrusted = true, answer = true, private = true)
    }

    private companion object {
        const val MAX_LINES_PER_CHAT = 5
    }
}

class ReadNotificationsTool(private val notifications: NotificationPort) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.READ_NOTIFICATIONS,
        description = "Read out the notifications that are showing on the phone now: which app, who or what, and the first part of the text.",
        params = emptyList(),
        risk = Risk.MEDIUM,
        timeoutMs = 8_000L,
        resultFormat = "CONFIRMED: the notifications Android currently lists, marked as outside content. Nothing is stored.",
    )

    override fun describe(args: ToolArgs): String = "Read the current notifications"

    override suspend fun execute(args: ToolArgs): ToolResult {
        if (!notifications.enabled) return ToolResult.Failure(ToolError.Unavailable(NOTIFICATIONS_OFF))
        val all = notifications.active()
        if (all.isEmpty()) return ToolResult.Success("You have no notifications.", Verification.CONFIRMED, answer = true)

        val shown = all.take(ScreenTools.MAX_NOTIFICATIONS)
        val lines = shown.mapIndexed { index, n ->
            val body = listOf(n.title, n.text).filter { it.isNotBlank() }.joinToString(": ").take(ScreenTools.MAX_NOTIFICATION_CHARS)
            "${index + 1}. ${n.app}${if (body.isNotEmpty()) " - $body" else ""}"
        }
        val more = if (all.size > shown.size) "\n(${all.size - shown.size} more not shown)" else ""
        val head = if (all.size == 1) "You have 1 notification:" else "You have ${all.size} notifications:"
        return ToolResult.Success("$head\n${lines.joinToString("\n")}$more", Verification.CONFIRMED, untrusted = true, answer = true, private = true)
    }
}

class ClearNotificationsTool(private val notifications: NotificationPort) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.CLEAR_NOTIFICATIONS,
        description = "Dismiss all notifications that can be dismissed. Ongoing ones, such as a music player, stay.",
        params = emptyList(),
        risk = Risk.MEDIUM,
        timeoutMs = 8_000L,
        resultFormat = "CONFIRMED: how many notifications were showing before and after, as Android lists them.",
    )

    override fun describe(args: ToolArgs): String = "Dismiss all notifications"

    override suspend fun execute(args: ToolArgs): ToolResult {
        if (!notifications.enabled) return ToolResult.Failure(ToolError.Unavailable(NOTIFICATIONS_OFF))
        val result = notifications.clearAll() ?: return ToolResult.Failure(ToolError.Failed("Android did not let me dismiss them."))
        return when {
            result.before == 0 -> ToolResult.Success("There were no notifications to clear.", Verification.CONFIRMED)
            result.after < result.before -> ToolResult.Success(
                "Cleared ${result.before - result.after} of ${result.before} notifications" +
                    (if (result.after > 0) " (${result.after} are ongoing or cannot be dismissed)." else "."),
                Verification.CONFIRMED,
            )
            else -> ToolResult.Failure(ToolError.Failed("Android still lists all ${result.before} notifications. They may be ongoing ones that cannot be dismissed."))
        }
    }
}
