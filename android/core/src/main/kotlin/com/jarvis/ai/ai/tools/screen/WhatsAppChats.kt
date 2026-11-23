package com.jarvis.ai.ai.tools.screen

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.AppLauncher
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.core.security.Risk
import java.util.Locale

/** One chat in WhatsApp's chat list. [unread] is how many new messages it shows; 0 means no marker at all. */
data class ChatSummary(val name: String, val unread: Int, val countKnown: Boolean) {
    val isUnread: Boolean get() = unread > 0
}

/** What one look at the screen showed. [recognised] is false when the screen is not a WhatsApp chat list, which is never the same as "no unread chats". */
data class ChatPage(val chats: List<ChatSummary>) {
    val recognised: Boolean get() = chats.isNotEmpty()
}

/**
 * Reads WhatsApp's chat list from the rows Android's accessibility service reports. The markers it relies on are the ones the app puts
 * on its own list: a view named like "contact_name", a count badge named like "message_count", and the words "unread" in the
 * description a screen reader would speak. WhatsApp can rename these in any update, which is why a screen that has no recognisable
 * chat rows is reported as "not recognised" and never as "zero unread".
 */
object WhatsAppChatList {
    private val TIME_LIKE = Regex(
        """^(?:\d{1,2}[:.]\d{2}(?:\s?[ap]\.?m\.?)?|yesterday|today|(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*|\d{1,2}/\d{1,2}/\d{2,4}|\d{1,2}\s+[a-z]{3,9}(?:\s+\d{2,4})?|कल|आज)$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNREAD_COUNT = Regex("""(\d{1,4})\s+(?:new\s+|unread\s+)+(?:messages?|chats?)|(\d{1,4})\s+unread|unread\s*(?:messages?)?\s*[:,]?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
    private val UNREAD_WORD = Regex("""\bunread\b|अपठित|अनपढ़ा""", RegexOption.IGNORE_CASE)
    private val DIGITS_ONLY = Regex("""^\d{1,4}$""")

    fun parse(rows: List<ScreenRow>): ChatPage = ChatPage(rows.mapNotNull(::chat))

    private fun chat(row: ScreenRow): ChatSummary? {
        val named = row.parts.firstOrNull { it.id.contains("contact_name", ignoreCase = true) }?.text?.trim().orEmpty()
        val texts = row.parts.map { it.text.trim() }.filter { it.isNotEmpty() }
        val timeLike = texts.any { TIME_LIKE.matches(it) } || row.parts.any { it.id.contains("date", ignoreCase = true) && it.text.isNotBlank() }
        val hasChatId = named.isNotEmpty() || row.parts.any { it.id.contains("conversations_row", ignoreCase = true) && !it.id.contains("archived", ignoreCase = true) }
        // A chat row has its marked name, or a time stamp with at least a name and a preview beside it.
        val name = when {
            named.isNotEmpty() -> named
            timeLike && texts.count { !TIME_LIKE.matches(it) && !DIGITS_ONLY.matches(it) } >= 2 ->
                texts.first { !TIME_LIKE.matches(it) && !DIGITS_ONLY.matches(it) }
            else -> return null
        }
        if (!hasChatId && !timeLike) return null

        // 1. A count badge, by its name. 2. A spoken "3 unread messages". 3. A bare "unread" marker (a chat marked unread by hand).
        row.parts.firstOrNull { part ->
            val id = part.id.lowercase(Locale.ROOT)
            (id.contains("message_count") || id.contains("unread_count") || id.contains("unread_badge")) &&
                (DIGITS_ONLY.matches(part.text.trim()) || DIGITS_ONLY.matches(part.description.trim()))
        }?.let { badge ->
            val n = (badge.text.trim().ifEmpty { badge.description.trim() }).toIntOrNull() ?: 0
            if (n > 0) return ChatSummary(name.take(MAX_NAME), n, countKnown = true)
        }
        // Only descriptions are searched for the words: a message preview that happens to say "unread" must not count.
        val descriptions = row.parts.map { it.description.trim() }.filter { it.isNotEmpty() }
        for (text in descriptions) {
            val m = UNREAD_COUNT.find(text) ?: continue
            val n = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: continue
            if (n > 0) return ChatSummary(name.take(MAX_NAME), n, countKnown = true)
        }
        if (row.parts.any { it.id.contains("unread", ignoreCase = true) } ||
            descriptions.any { UNREAD_WORD.containsMatchIn(it) && it.length <= MAX_MARKER }
        ) {
            return ChatSummary(name.take(MAX_NAME), 1, countKnown = false)
        }
        return ChatSummary(name.take(MAX_NAME), 0, countKnown = true)
    }

    private const val MAX_NAME = 60
    private const val MAX_MARKER = 300
}

/** Chats seen over several screens, merged by name so a chat that is on two screens counts once. */
class ChatTally {
    private val byName = LinkedHashMap<String, ChatSummary>()

    /** @return how many chats on [page] had not been seen before. */
    fun add(page: ChatPage): Int {
        var fresh = 0
        for (chat in page.chats) {
            val old = byName[chat.name]
            if (old == null) {
                byName[chat.name] = chat
                fresh++
            } else if (chat.unread > old.unread) {
                byName[chat.name] = chat
            }
        }
        return fresh
    }

    val seen: Int get() = byName.size
    val unreadChats: List<ChatSummary> get() = byName.values.filter { it.isUnread }

    /** New messages in chats that show a number. Chats marked unread without a number add nothing here. */
    val knownMessages: Int get() = unreadChats.filter { it.countKnown }.sumOf { it.unread }
}

/**
 * "Go to WhatsApp and tell me how many chats are unread." Opens WhatsApp, makes sure its chat list is showing, and counts what the
 * list marks as unread, scrolling a few screens. It reads the screen, so it needs Accessibility; it presses nothing (only Back, if a chat is
 * open, and scrolling). What it reads is the person's private content from another app: shown to them, never stored, never given to an AI.
 */
class CountWhatsAppUnreadTool(
    private val apps: AppLauncher,
    private val screen: ScreenControl,
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : Tool {
    override val spec = ToolSpec(
        name = ScreenTools.COUNT_WHATSAPP_UNREAD,
        description = "Open WhatsApp and count how many chats are unread by looking at its chat list on screen (not notifications). " +
            "Use it for \"how many unread chats do I have in WhatsApp\" or \"go to WhatsApp and check my inbox\".",
        params = emptyList<ParamSpec>(),
        risk = Risk.MEDIUM,
        timeoutMs = 60_000L,
        resultFormat = "CONFIRMED: the unread chats WhatsApp's own list shows, marked as outside content. Nothing is stored.",
    )

    override fun describe(args: ToolArgs): String = "Open WhatsApp and count the unread chats"

    override suspend fun execute(args: ToolArgs): ToolResult {
        if (!screen.enabled) return ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF_MESSAGE))
        val whatsapp = apps.launchable().firstOrNull { it.packageName in ScreenTools.WHATSAPP_PACKAGES }
            ?: return ToolResult.Failure(ToolError.Unavailable("WhatsApp is not installed on this phone."))

        if (!bringToFront(whatsapp.packageName)) {
            return ToolResult.Failure(ToolError.Failed("I could not bring WhatsApp to the front. Open it yourself and ask again."))
        }

        // Get to the chat list: WhatsApp may reopen inside a chat. Back is pressed at most twice, and only while WhatsApp is in front.
        var page: ChatPage? = null
        var attempts = 0
        while (page == null && attempts < MAX_ATTEMPTS) {
            pause(SETTLE_MS)
            when (val rows = screen.rows()) {
                ScreenRows.NotEnabled -> return ToolResult.Failure(ToolError.Unavailable(SCREEN_OFF_MESSAGE))
                is ScreenRows.OffLimits -> return ToolResult.Failure(ToolError.Unsupported("I do not read or control ${rows.app}."))
                ScreenRows.Empty -> Unit
                is ScreenRows.Found -> {
                    if (rows.app.packageName in ScreenTools.WHATSAPP_PACKAGES) {
                        val parsed = WhatsAppChatList.parse(rows.rows)
                        if (parsed.recognised) page = parsed
                    }
                }
            }
            if (page != null) break
            attempts++
            if (attempts >= MAX_ATTEMPTS) break
            if (screen.awaitFront(ScreenTools.WHATSAPP_PACKAGES, 0)) {
                screen.perform(PhoneAction.BACK)
                pause(SETTLE_MS)
                // Back from the chat list itself leaves WhatsApp; bring it back rather than leave the person on the home screen.
                if (!screen.awaitFront(ScreenTools.WHATSAPP_PACKAGES, 0) && !bringToFront(whatsapp.packageName)) break
            } else if (!bringToFront(whatsapp.packageName)) {
                break
            }
        }
        val first = page ?: return ToolResult.Failure(
            ToolError.Failed(
                "I opened WhatsApp but could not recognise its chat list, so I cannot say how many chats are unread. " +
                    "WhatsApp may have changed its layout, or it is showing something else. Say \"read the screen\" to hear what is there.",
            ),
        )

        val tally = ChatTally()
        tally.add(first)
        var screens = 1
        var reachedEnd = false
        while (screens < MAX_SCREENS) {
            if (screen.scroll(down = true) !is ScreenAct.Done) {
                reachedEnd = true
                break
            }
            pause(SCROLL_MS)
            val rows = screen.rows() as? ScreenRows.Found
            if (rows == null || rows.app.packageName !in ScreenTools.WHATSAPP_PACKAGES) break
            screens++
            if (tally.add(WhatsAppChatList.parse(rows.rows)) == 0) {
                reachedEnd = true
                break
            }
        }
        // Leave the list where the person expects it: at the top.
        repeat(screens - 1) {
            if (screen.scroll(down = false) is ScreenAct.Done) pause(SCROLL_MS / 2)
        }

        val unread = tally.unreadChats
        val scope = if (reachedEnd) "all ${tally.seen} chats" else "the first ${tally.seen} chats (I stopped after $screens screens)"
        val text = if (unread.isEmpty()) {
            "WhatsApp shows no unread chats. I looked at $scope."
        } else {
            val names = unread.take(MAX_NAMES).joinToString(", ") { c -> if (c.countKnown) "${c.name} (${c.unread})" else "${c.name} (marked unread)" }
            val more = if (unread.size > MAX_NAMES) " and ${unread.size - MAX_NAMES} more" else ""
            val messages = if (tally.knownMessages > 0) ", about ${tally.knownMessages} new messages" else ""
            "You have ${unread.size} unread ${if (unread.size == 1) "chat" else "chats"} in WhatsApp$messages: $names$more. I looked at $scope."
        }
        return ToolResult.Success(text, Verification.CONFIRMED, untrusted = true, answer = true, private = true)
    }

    private suspend fun bringToFront(packageName: String): Boolean {
        if (screen.awaitFront(ScreenTools.WHATSAPP_PACKAGES, 0)) return true
        if (apps.launch(packageName) !is LaunchResult.Launched) return false
        return screen.awaitFront(ScreenTools.WHATSAPP_PACKAGES, FRONT_WAIT_MS)
    }

    private companion object {
        const val SCREEN_OFF_MESSAGE =
            "Screen access is off. Turn on JARVIS 3.0 in Settings > Accessibility (Prefs > Access has a shortcut), then ask again."
        const val MAX_ATTEMPTS = 3
        const val MAX_SCREENS = 10
        const val MAX_NAMES = 8
        const val SETTLE_MS = 700L
        const val SCROLL_MS = 600L
        const val FRONT_WAIT_MS = 9_000L
    }
}
