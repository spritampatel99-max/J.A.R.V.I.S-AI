package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.AppLauncher
import com.jarvis.ai.ai.tools.builtin.InstalledApp
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import com.jarvis.ai.ai.tools.screen.ChatPage
import com.jarvis.ai.ai.tools.screen.ChatTally
import com.jarvis.ai.ai.tools.screen.CountWhatsAppUnreadTool
import com.jarvis.ai.ai.tools.screen.FrontApp
import com.jarvis.ai.ai.tools.screen.GuardedScreenControl
import com.jarvis.ai.ai.tools.screen.PhoneAction
import com.jarvis.ai.ai.tools.screen.PlayOnYoutubeTool
import com.jarvis.ai.ai.tools.screen.RawBox
import com.jarvis.ai.ai.tools.screen.RawResult
import com.jarvis.ai.ai.tools.screen.RawRows
import com.jarvis.ai.ai.tools.screen.RawScreen
import com.jarvis.ai.ai.tools.screen.RawTap
import com.jarvis.ai.ai.tools.screen.ScreenAct
import com.jarvis.ai.ai.tools.screen.ScreenGuard
import com.jarvis.ai.ai.tools.screen.ScreenPart
import com.jarvis.ai.ai.tools.screen.ScreenRow
import com.jarvis.ai.ai.tools.screen.ScreenRows
import com.jarvis.ai.ai.tools.screen.Snapshot
import com.jarvis.ai.ai.tools.screen.WhatsAppChatList
import com.jarvis.ai.ai.tools.screen.YoutubeResults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ScreenRowsTest {

    // ---- helpers ---------------------------------------------------------------------------------------------

    private fun chat(name: String, badge: String? = null, desc: String? = null, preview: String = "see you", time: String = "10:30", unreadId: Boolean = false) = ScreenRow(
        listOfNotNull(
            ScreenPart(text = name, id = "conversations_row_contact_name"),
            ScreenPart(text = time, id = "conversations_row_date"),
            ScreenPart(text = preview, id = "conversations_row_message"),
            badge?.let { ScreenPart(text = it, id = "conversations_row_message_count") },
            desc?.let { ScreenPart(description = it) },
            if (unreadId) ScreenPart(id = "conversations_row_unread_indicator") else null,
        ),
    )

    private val whatsapp = FrontApp("com.whatsapp", "WhatsApp")
    private val youtube = FrontApp("com.google.android.youtube", "YouTube")

    /** Shows a different list on each screen; a scroll moves to the next one, and at the last one the scroll fails like Android's does. */
    private class FakeList(
        var front: FrontApp?,
        val screens: MutableList<List<ScreenRow>>,
        override var connected: Boolean = true,
        val sameContentOnScroll: Boolean = false,
    ) : RawScreen {
        var index = 0
        val performed = mutableListOf<PhoneAction>()
        var pressed: ScreenRow? = null
        var pressCalls = 0
        var backOpensList: List<ScreenRow>? = null
        override fun front(): FrontApp? = front
        override suspend fun snapshot(allow: (String) -> Boolean): Snapshot = Snapshot.None
        override suspend fun scroll(down: Boolean, allow: (String) -> Boolean): RawResult {
            val app = front ?: return RawResult.Failed("no window")
            if (down) {
                if (index >= screens.lastIndex) return RawResult.Failed("end")
                index++
                if (sameContentOnScroll) index--
            } else if (index > 0) {
                index--
            }
            return RawResult.Done(app)
        }
        override suspend fun perform(action: PhoneAction): RawResult {
            performed += action
            if (action == PhoneAction.BACK) backOpensList?.let { screens[0] = it; backOpensList = null }
            return RawResult.Done(front)
        }
        override suspend fun tap(label: String, allow: (String) -> Boolean): RawTap = RawTap.NotFound
        override suspend fun type(text: String, allow: (String) -> Boolean): RawResult = RawResult.Failed("n/a")
        override suspend fun box(allow: (String) -> Boolean): RawBox = RawBox.None
        override suspend fun rows(allow: (String) -> Boolean): RawRows {
            val app = front ?: return RawRows.None
            if (!allow(app.packageName)) return RawRows.Refused(app)
            return RawRows.Found(app, screens[index])
        }
        override suspend fun pressRow(allow: (String) -> Boolean, inApps: Set<String>, pick: (ScreenRow) -> Boolean): RawTap {
            val app = front ?: return RawTap.Failed("no window")
            if (!allow(app.packageName)) return RawTap.Refused(app)
            pressCalls++
            val row = screens[index].firstOrNull(pick) ?: return RawTap.NotFound
            pressed = row
            return RawTap.Done(app)
        }
    }

    private class Apps(val raw: FakeList, val installed: Boolean = true) : AppLauncher {
        var launches = 0
        override suspend fun launchable(): List<InstalledApp> = if (installed) listOf(InstalledApp("WhatsApp", "com.whatsapp"), InstalledApp("YouTube", "com.google.android.youtube")) else emptyList()
        override fun launch(packageName: String): LaunchResult {
            launches++
            raw.front = FrontApp(packageName, "WhatsApp")
            return LaunchResult.Launched
        }
    }

    private fun control(raw: RawScreen, tapOn: Boolean = true, personal: Set<String> = emptySet()) =
        GuardedScreenControl(raw, ScreenGuard("com.jarvis.ai") { personal }, actionsOn = { tapOn }, pause = {})

    private suspend fun runCount(raw: FakeList, apps: Apps = Apps(raw), screen: GuardedScreenControl = control(raw)): ToolResult =
        CountWhatsAppUnreadTool(apps, screen, pause = {}).execute(ToolArgs(emptyMap()))

    // ---- parsing WhatsApp's chat list -------------------------------------------------------------------------

    @Test
    fun aCountBadgeGivesTheNumberOfNewMessages() {
        val page = WhatsAppChatList.parse(listOf(chat("Asha", badge = "3"), chat("Ravi")))
        assertEquals(2, page.chats.size)
        assertEquals(3, page.chats.first { it.name == "Asha" }.unread)
        assertEquals(0, page.chats.first { it.name == "Ravi" }.unread)
        assertTrue(page.recognised)
    }

    @Test
    fun aSpokenDescriptionIsUsedWhenThereIsNoBadgeName() {
        val page = WhatsAppChatList.parse(listOf(chat("Mom", desc = "Mom, 2 unread messages, hello")))
        assertEquals(2, page.chats.single().unread)
    }

    @Test
    fun aChatMarkedUnreadByHandCountsAsOneUnreadChatWithoutAMessageCount() {
        val page = WhatsAppChatList.parse(listOf(chat("Dad", unreadId = true)))
        val dad = page.chats.single()
        assertTrue(dad.isUnread)
        assertFalse(dad.countKnown)
    }

    @Test
    fun aMessagePreviewThatSaysUnreadIsNotAnUnreadMarker() {
        val page = WhatsAppChatList.parse(listOf(chat("Boss", preview = "please clear the unread mail 5 unread")))
        assertEquals(0, page.chats.single().unread)
    }

    @Test
    fun rowsThatAreNotChatsAreIgnoredAndASingleTitleIsNotAChatList() {
        val filterChips = ScreenRow(listOf(ScreenPart(text = "Unread"), ScreenPart(text = "Groups")))
        val archived = ScreenRow(listOf(ScreenPart(text = "Archived", id = "conversations_row_archived"), ScreenPart(text = "3")))
        val page = WhatsAppChatList.parse(listOf(filterChips, archived))
        assertFalse(page.recognised)
    }

    @Test
    fun chatsWithoutViewNamesAreRecognisedByTheirTimeStamp() {
        val row = ScreenRow(listOf(ScreenPart(text = "Neha"), ScreenPart(text = "ok done"), ScreenPart(text = "Yesterday")))
        val page = WhatsAppChatList.parse(listOf(row))
        assertEquals("Neha", page.chats.single().name)
        assertEquals(0, page.chats.single().unread)
    }

    @Test
    fun theTallyCountsAChatOnTwoScreensOnce() {
        val tally = ChatTally()
        tally.add(ChatPage(WhatsAppChatList.parse(listOf(chat("A", badge = "2"), chat("B"))).chats))
        val fresh = tally.add(ChatPage(WhatsAppChatList.parse(listOf(chat("B"), chat("C", badge = "1"))).chats))
        assertEquals(1, fresh)
        assertEquals(3, tally.seen)
        assertEquals(2, tally.unreadChats.size)
        assertEquals(3, tally.knownMessages)
    }

    // ---- the tool ---------------------------------------------------------------------------------------------

    @Test
    fun countsUnreadChatsAcrossScreensAndReportsHonestly() = runTest {
        val raw = FakeList(whatsapp, mutableListOf(listOf(chat("Asha", badge = "2"), chat("Ravi")), listOf(chat("Ravi"), chat("Mom", badge = "5")), listOf(chat("Mom", badge = "5"), chat("Zed"))))
        val result = runCount(raw)
        val ok = assertIs<ToolResult.Success>(result)
        assertTrue("2 unread chats" in ok.message, ok.message)
        assertTrue("Asha (2)" in ok.message && "Mom (5)" in ok.message, ok.message)
        assertTrue("all 4 chats" in ok.message, ok.message)
        assertEquals(Verification.CONFIRMED, ok.verification)
        assertTrue(ok.private && ok.untrusted && ok.answer)
        // and the list is put back at the top
        assertEquals(0, raw.index)
    }

    @Test
    fun saysNoUnreadChatsOnlyWhenTheListWasRecognised() = runTest {
        val raw = FakeList(whatsapp, mutableListOf(listOf(chat("Asha"), chat("Ravi"))))
        val ok = assertIs<ToolResult.Success>(runCount(raw))
        assertTrue("no unread chats" in ok.message, ok.message)
    }

    @Test
    fun neverSaysZeroWhenTheScreenIsNotAChatList() = runTest {
        val unknown = listOf(ScreenRow(listOf(ScreenPart(text = "Something else"))))
        val raw = FakeList(whatsapp, mutableListOf(unknown))
        val failure = assertIs<ToolResult.Failure>(runCount(raw))
        assertTrue("could not recognise" in failure.error.message, failure.error.message)
        assertFalse("no unread" in failure.error.message.lowercase())
    }

    @Test
    fun opensWhatsAppFirstWhenSomethingElseIsInFront() = runTest {
        val raw = FakeList(FrontApp("com.android.launcher", "Home"), mutableListOf(listOf(chat("Asha", badge = "1"))))
        val apps = Apps(raw)
        val ok = assertIs<ToolResult.Success>(runCount(raw, apps))
        assertEquals(1, apps.launches)
        assertTrue("1 unread chat" in ok.message, ok.message)
    }

    @Test
    fun pressesBackToLeaveAnOpenChatAndThenCounts() = runTest {
        val inChat = listOf(ScreenRow(listOf(ScreenPart(text = "Type a message"))))
        val raw = FakeList(whatsapp, mutableListOf(inChat))
        raw.backOpensList = listOf(chat("Asha", badge = "4"))
        val ok = assertIs<ToolResult.Success>(runCount(raw))
        assertEquals(listOf(PhoneAction.BACK), raw.performed)
        assertTrue("Asha (4)" in ok.message, ok.message)
    }

    @Test
    fun stopsWhenScrollingShowsNothingNew() = runTest {
        val raw = FakeList(whatsapp, mutableListOf(listOf(chat("Asha", badge = "1")), listOf(chat("Asha", badge = "1"))), sameContentOnScroll = true)
        val ok = assertIs<ToolResult.Success>(runCount(raw))
        assertTrue("1 unread chat" in ok.message, ok.message)
    }

    @Test
    fun refusesWithoutAccessibilityWhatsAppOrWhenGuarded() = runTest {
        val off = FakeList(whatsapp, mutableListOf(listOf(chat("A"))), connected = false)
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(runCount(off)).error)
        val none = FakeList(whatsapp, mutableListOf(listOf(chat("A"))))
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(runCount(none, Apps(none, installed = false))).error)
        // a personal block list that includes WhatsApp wins
        val blocked = FakeList(whatsapp, mutableListOf(listOf(chat("A", badge = "1"))))
        val result = assertIs<ToolResult.Failure>(runCount(blocked, screen = control(blocked, personal = setOf("com.whatsapp"))))
        assertIs<ToolError.Unsupported>(result.error)
    }

    // ---- YouTube ----------------------------------------------------------------------------------------------

    private fun video(title: String, length: String = "6 minutes, 22 seconds") =
        ScreenRow(listOf(ScreenPart(description = "$title - $length - Go to channel - T-Series - 1.2B views")))

    @Test
    fun onlyRealVideoResultsArePlayable() {
        assertTrue(YoutubeResults.isPlayable(video("Tum Hi Ho")))
        assertTrue(YoutubeResults.isPlayable(ScreenRow(listOf(ScreenPart(description = "Tum Hi Ho - play video")))))
        assertFalse(YoutubeResults.isPlayable(ScreenRow(listOf(ScreenPart(description = "Sponsored - Buy shoes - 1 minute, 2 seconds")))))
        assertFalse(YoutubeResults.isPlayable(ScreenRow(listOf(ScreenPart(text = "Shorts")))))
        assertFalse(YoutubeResults.isPlayable(ScreenRow(listOf(ScreenPart(text = "T-Series"), ScreenPart(text = "Channel")))))
        assertFalse(YoutubeResults.isPlayable(ScreenRow(emptyList())))
    }

    @Test
    fun theTitleStopsBeforeTheLength() {
        assertEquals("Tum Hi Ho - Aashiqui 2", YoutubeResults.titleOf(video("Tum Hi Ho - Aashiqui 2")))
    }

    private class Urls(val raw: FakeList?, val front: FrontApp?) : UrlOpener {
        var opened: String? = null
        override fun open(url: String): LaunchResult {
            opened = url
            if (front != null) raw?.front = front
            return LaunchResult.Launched
        }
    }

    private suspend fun play(raw: FakeList, screen: GuardedScreenControl, urls: Urls): ToolResult =
        PlayOnYoutubeTool(urls, screen, pause = {}).execute(ToolArgs(mapOf("query" to "tum hi ho")))

    @Test
    fun pressesTheFirstRealVideoAndSkipsTheAdvert() = runTest {
        val ad = ScreenRow(listOf(ScreenPart(description = "Sponsored - Cheap flights - 30 seconds")))
        val raw = FakeList(FrontApp("com.android.launcher", "Home"), mutableListOf(listOf(ad, video("Tum Hi Ho"), video("Tum Hi Ho (Lyrics)"))))
        val urls = Urls(raw, youtube)
        val ok = assertIs<ToolResult.Success>(play(raw, control(raw), urls))
        assertTrue(urls.opened!!.contains("search_query=tum+hi+ho") || urls.opened!!.contains("search_query=tum%20hi%20ho"), urls.opened)
        assertEquals("Tum Hi Ho", YoutubeResults.titleOf(raw.pressed!!))
        assertEquals(Verification.HANDED_OFF, ok.verification)
        assertTrue("Tum Hi Ho" in ok.message && "does not tell me whether" in ok.message, ok.message)
    }

    @Test
    fun withoutTheTapSwitchItOpensTheSearchAndSaysWhatIsMissing() = runTest {
        val raw = FakeList(FrontApp("com.android.launcher", "Home"), mutableListOf(listOf(video("Tum Hi Ho"))))
        val ok = assertIs<ToolResult.Success>(play(raw, control(raw, tapOn = false), Urls(raw, youtube)))
        assertNull(raw.pressed)
        assertTrue("Opened the YouTube search" in ok.message && "switched off" in ok.message, ok.message)
    }

    @Test
    fun withoutAccessibilityItStillOpensTheSearch() = runTest {
        val raw = FakeList(youtube, mutableListOf(listOf(video("x"))), connected = false)
        val urls = Urls(raw, null)
        val ok = assertIs<ToolResult.Success>(play(raw, control(raw), urls))
        assertTrue(urls.opened != null)
        assertTrue("Accessibility is off" in ok.message, ok.message)
    }

    @Test
    fun saysSoWhenNoVideoCanBeFoundAndPressesNothing() = runTest {
        val raw = FakeList(FrontApp("com.android.launcher", "Home"), mutableListOf(listOf(ScreenRow(listOf(ScreenPart(text = "Shorts"))))))
        val ok = assertIs<ToolResult.Success>(play(raw, control(raw), Urls(raw, youtube)))
        assertEquals(0, raw.pressCalls)
        assertTrue("could not find a video" in ok.message, ok.message)
    }

    @Test
    fun doesNotPressInsideAnotherAppEvenIfYouTubeWasAsked() = runTest {
        // The browser opened instead of the YouTube app.
        val raw = FakeList(FrontApp("com.android.chrome", "Chrome"), mutableListOf(listOf(video("Tum Hi Ho"))))
        val ok = assertIs<ToolResult.Success>(play(raw, control(raw), Urls(raw, null)))
        assertEquals(0, raw.pressCalls)
        assertTrue("did not come to the front" in ok.message, ok.message)
    }

    // ---- the guarded layer --------------------------------------------------------------------------------------

    @Test
    fun pressRowRefusesBlockedAppsWrongAppsAndTheOffSwitch() = runTest {
        val bank = FakeList(FrontApp("com.hdfc.bank", "Bank"), mutableListOf(listOf(video("x"))))
        assertIs<ScreenAct.OffLimits>(control(bank).pressRow(setOf("com.hdfc.bank")) { true })
        val wrong = FakeList(whatsapp, mutableListOf(listOf(video("x"))))
        assertIs<ScreenAct.Failed>(control(wrong).pressRow(setOf("com.google.android.youtube")) { true })
        assertEquals(0, wrong.pressCalls)
        val off = FakeList(youtube, mutableListOf(listOf(video("x"))))
        assertIs<ScreenAct.Failed>(control(off, tapOn = false).pressRow(setOf("com.google.android.youtube")) { true })
        assertEquals(0, off.pressCalls)
    }

    @Test
    fun rowsAreNotReadFromGuardedApps() = runTest {
        val own = FakeList(FrontApp("com.jarvis.ai", "JARVIS 3.0"), mutableListOf(listOf(video("x"))))
        assertIs<ScreenRows.OffLimits>(control(own).rows())
        val off = FakeList(whatsapp, mutableListOf(listOf(video("x"))), connected = false)
        assertEquals(ScreenRows.NotEnabled, control(off).rows())
    }

    // ---- what is said ---------------------------------------------------------------------------------------------

    private fun tool(text: String): Pair<String, Map<String, String>>? {
        val match = NativeCommands.match(text) as? NativeCommands.Match.Run ?: return null
        val step = match.steps.firstOrNull() ?: return null
        return step.tool to step.parameters.mapValues { it.value.toString().trim('"') }
    }

    @Test
    fun spokenPhrasesReachTheRightTool() {
        for (phrase in listOf(
            "go to whatsapp and tell me how many inbox are unread",
            "how many unread chats do I have on WhatsApp",
            "check how many new messages are waiting in whatsapp",
            "whatsapp pe kitne unread chats hain",
        )) assertEquals("count_whatsapp_unread", tool(phrase)?.first, phrase)
        assertEquals("tum hi ho", tool("open youtube and play tum hi ho")?.second?.get("query"))
        assertEquals("play_on_youtube", tool("open youtube and play tum hi ho")?.first)
        assertEquals("tum hi ho", tool("Open YouTube and play tum hi ho and tap on the song")?.second?.get("query"))
        assertEquals("tum hi ho", tool("play tum hi ho on youtube")?.second?.get("query"))
        assertEquals("tum hi ho", tool("youtube par tum hi ho chalao")?.second?.get("query"))
        assertEquals("tum hi ho", tool("tum hi ho youtube pe laga do")?.second?.get("query"))
        // plain searching still only searches
        assertEquals("open_youtube", tool("search for cats on youtube")?.first)
        assertEquals("open_youtube", tool("open youtube")?.first)
    }

    @Test
    fun aMessageToSomeoneIsNeverTakenForACount() {
        val sent = tool("send whatsapp to Riya saying how many unread chats do you have")
        assertEquals("send_whatsapp", sent?.first)
    }

    @Test
    fun theOlderReadCommandStillReadsNotifications() {
        assertEquals("read_whatsapp_unread", tool("read my unread whatsapp messages")?.first)
    }
}
