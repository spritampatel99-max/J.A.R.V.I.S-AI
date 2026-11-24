package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.ContactsPort
import com.jarvis.ai.ai.tools.builtin.MessageHandoff
import com.jarvis.ai.ai.tools.builtin.MessagingPort
import com.jarvis.ai.ai.tools.builtin.SendWhatsAppTool
import com.jarvis.ai.ai.tools.builtin.WhatsAppSender
import com.jarvis.ai.ai.tools.screen.ActionLimiter
import com.jarvis.ai.ai.tools.screen.BoxAfter
import com.jarvis.ai.ai.tools.screen.ClearResult
import com.jarvis.ai.ai.tools.screen.ComposeText
import com.jarvis.ai.ai.tools.screen.FrontApp
import com.jarvis.ai.ai.tools.screen.GuardedScreenControl
import com.jarvis.ai.ai.tools.screen.NotificationInfo
import com.jarvis.ai.ai.tools.screen.NotificationPort
import com.jarvis.ai.ai.tools.screen.PhoneAction
import com.jarvis.ai.ai.tools.screen.RawBox
import com.jarvis.ai.ai.tools.screen.RawResult
import com.jarvis.ai.ai.tools.screen.RawScreen
import com.jarvis.ai.ai.tools.screen.RawTap
import com.jarvis.ai.ai.tools.screen.ReadWhatsAppTool
import com.jarvis.ai.ai.tools.screen.RiskyLabels
import com.jarvis.ai.ai.tools.screen.ScreenAct
import com.jarvis.ai.ai.tools.screen.ScreenGuard
import com.jarvis.ai.ai.tools.screen.SendPress
import com.jarvis.ai.ai.tools.screen.Snapshot
import com.jarvis.ai.ai.tools.screen.TapOnScreenTool
import com.jarvis.ai.ai.tools.screen.TypeTextTool
import com.jarvis.ai.core.security.ConfirmationRequest
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.voice.RequestOrigin
import com.jarvis.ai.voice.VoiceDirectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ScreenActionsTest {

    private val wa = FrontApp("com.whatsapp", "WhatsApp")
    private val bank = FrontApp("com.hdfcbank.mobile", "Bank")
    private val waSet = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** An accessibility service stand-in that records every press and typed text. */
    private class Raw(
        var front: FrontApp? = FrontApp("com.whatsapp", "WhatsApp"),
        var boxText: String = "",
        var tapResult: (String) -> RawTap = { RawTap.Done(FrontApp("com.whatsapp", "WhatsApp")) },
        /** What the box shows after a press. */
        var afterPress: String? = "",
        /** When set, the message box reports this window instead of the front one (the screen changed between two looks). */
        var boxApp: FrontApp? = null,
        /** False imitates an Android side that forgot to apply the package check itself. */
        var enforceAllow: Boolean = true,
    ) : RawScreen {
        override val connected = true
        val taps = mutableListOf<String>()
        val typed = mutableListOf<String>()
        var pressed = false

        override fun front() = front

        override suspend fun snapshot(allow: (String) -> Boolean): Snapshot = Snapshot.None

        override suspend fun scroll(down: Boolean, allow: (String) -> Boolean): RawResult = RawResult.Failed("n/a")

        override suspend fun perform(action: PhoneAction): RawResult = RawResult.Failed("n/a")

        override suspend fun tap(label: String, allow: (String) -> Boolean): RawTap {
            val app = front ?: return RawTap.Failed("no window")
            if (enforceAllow && !allow(app.packageName)) return RawTap.Refused(app)
            taps += label
            val result = tapResult(label)
            if (result is RawTap.Done) {
                pressed = true
                afterPress?.let { boxText = it }
            }
            return result
        }

        override suspend fun type(text: String, allow: (String) -> Boolean): RawResult {
            val app = front ?: return RawResult.Failed("no window")
            if (!allow(app.packageName)) return RawResult.Refused(app)
            typed += text
            return RawResult.Done(app)
        }

        override suspend fun box(allow: (String) -> Boolean): RawBox {
            val app = boxApp ?: front ?: return RawBox.None
            if (enforceAllow && !allow(app.packageName)) return RawBox.Refused(app)
            return RawBox.Text(app, boxText)
        }
    }

    private fun control(raw: Raw, actions: Boolean = true, limiter: ActionLimiter = ActionLimiter(), personal: Set<String> = emptySet()) =
        GuardedScreenControl(raw, ScreenGuard("com.jarvis.ai") { personal }, { actions }, limiter, pause = { })

    // ---- pressing Send --------------------------------------------------------------------------------------------------

    @Test
    fun sendIsPressedOnlyWhenTheBoxHoldsExactlyTheApprovedWords() = runTest {
        val raw = Raw(boxText = "I'll be there  in ten")
        val result = control(raw).sendComposed("I'll be there in ten", waSet)
        assertEquals(SendPress.Pressed(BoxAfter.CLEARED), result)
        assertEquals(listOf("Send"), raw.taps)
    }

    @Test
    fun aDifferentTextInTheBoxPressesNothing() = runTest {
        for (box in listOf("I'll be there in ten minutes", "I'll be there", "", "   ", "Pay 5000 to Ravi")) {
            val raw = Raw(boxText = box)
            assertEquals(SendPress.TextMismatch, control(raw).sendComposed("I'll be there in ten", waSet), box)
            assertTrue(raw.taps.isEmpty(), "must not press Send for box '$box'")
        }
    }

    @Test
    fun anEmptyApprovedTextIsNeverSent() = runTest {
        val raw = Raw(boxText = "")
        assertEquals(SendPress.TextMismatch, control(raw).sendComposed("   ", waSet))
        assertTrue(raw.taps.isEmpty())
    }

    @Test
    fun anotherAppInFrontIsNeverPressed() = runTest {
        val chrome = Raw(front = FrontApp("com.android.chrome", "Chrome"), boxText = "hello")
        assertEquals(SendPress.WrongApp("Chrome"), control(chrome).sendComposed("hello", waSet))
        assertTrue(chrome.taps.isEmpty())
    }

    @Test
    fun theFrontWindowAndTheBoxMustBothBeWhatsApp() = runTest {
        val frontIsChrome = Raw(front = FrontApp("com.android.chrome", "Chrome"), boxText = "hello", boxApp = wa)
        assertEquals(SendPress.WrongApp("Chrome"), control(frontIsChrome).sendComposed("hello", waSet))
        val boxIsChrome = Raw(front = wa, boxText = "hello", boxApp = FrontApp("com.android.chrome", "Chrome"))
        assertEquals(SendPress.WrongApp("Chrome"), control(boxIsChrome).sendComposed("hello", waSet))
        assertTrue(frontIsChrome.taps.isEmpty() && boxIsChrome.taps.isEmpty())
    }

    @Test
    fun aProtectedAppIsNeverPressedEvenIfAskedToTreatItAsTheMessagingApp() = runTest {
        val raw = Raw(front = bank, boxText = "hello")
        assertIs<SendPress.OffLimits>(control(raw).sendComposed("hello", setOf(bank.packageName)))
        assertTrue(raw.taps.isEmpty())
    }

    @Test
    fun theWhatsAppWindowMustComeUpOrNothingIsPressed() = runTest {
        val raw = Raw(front = null, boxText = "hello")
        assertIs<SendPress.Failed>(control(raw).sendComposed("hello", waSet))
        assertTrue(raw.taps.isEmpty())
    }

    @Test
    fun theGuardHoldsEvenIfTheAndroidSideForgetsItsOwnCheck() = runTest {
        val raw = Raw(front = bank, boxText = "hello", enforceAllow = false)
        assertIs<SendPress.OffLimits>(control(raw).sendComposed("hello", setOf(bank.packageName)))
        assertIs<ScreenAct.OffLimits>(control(raw).tap("Open"))
        assertIs<ScreenAct.OffLimits>(control(raw).type("x"))
        assertTrue(raw.taps.isEmpty() && raw.typed.isEmpty())
    }

    @Test
    fun aMessageThatIsStillInTheBoxIsReportedAsNotGone() = runTest {
        val raw = Raw(boxText = "hello", afterPress = "hello")
        assertEquals(SendPress.Pressed(BoxAfter.STILL_THERE), control(raw).sendComposed("hello", waSet))
    }

    @Test
    fun aMissingOrDoubledSendButtonPressesNothing() = runTest {
        val missing = Raw(boxText = "hi", tapResult = { RawTap.NotFound })
        assertIs<SendPress.Failed>(control(missing).sendComposed("hi", waSet))
        val doubled = Raw(boxText = "hi", tapResult = { RawTap.Ambiguous(2) })
        assertIs<SendPress.Failed>(control(doubled).sendComposed("hi", waSet))
        assertFalse(doubled.pressed)
    }

    @Test
    fun sendingIsRateLimited() = runTest {
        var now = 0L
        val limiter = ActionLimiter(max = 2, windowMs = 60_000L) { now }
        val c = control(Raw(boxText = "hi"), limiter = limiter)
        assertIs<SendPress.Pressed>(c.sendComposed("hi", waSet))
        val second = Raw(boxText = "hi")
        val c2 = control(second, limiter = limiter)
        assertIs<SendPress.Pressed>(c2.sendComposed("hi", waSet))
        val third = Raw(boxText = "hi")
        assertIs<SendPress.Failed>(control(third, limiter = limiter).sendComposed("hi", waSet))
        assertTrue(third.taps.isEmpty())
    }

    @Test
    fun comparisonIgnoresOnlySpacing() {
        assertTrue(ComposeText.same("a \n b", "a b"))
        assertTrue(ComposeText.same("a b", "a b"))
        assertFalse(ComposeText.same("a b.", "a b"))
        assertFalse(ComposeText.same("A b", "a b"))
        assertFalse(ComposeText.same("", ""))
    }

    // ---- tap and type ---------------------------------------------------------------------------------------------------

    @Test
    fun tapAndTypeAreOffUntilThePersonTurnsThemOn() = runTest {
        val raw = Raw()
        val off = control(raw, actions = false)
        assertIs<ScreenAct.Failed>(off.tap("Chats"))
        assertIs<ScreenAct.Failed>(off.type("hello"))
        assertTrue(raw.taps.isEmpty() && raw.typed.isEmpty())
    }

    @Test
    fun anOrdinaryButtonIsPressedAndTextIsTyped() = runTest {
        val raw = Raw()
        val c = control(raw)
        assertEquals(ScreenAct.Done("WhatsApp"), c.tap("Chats"))
        assertEquals(ScreenAct.Done("WhatsApp"), c.type("hello"))
        assertEquals(listOf("Chats"), raw.taps)
        assertEquals(listOf("hello"), raw.typed)
    }

    @Test
    fun buttonsThatSendPayDeleteOrGrantAreNeverPressedByTheGenericTap() = runTest {
        for (label in listOf("Send", "send now", "Pay", "Pay now", "Delete chat", "DELETE", "Confirm", "Allow", "Buy", "Install", "Place order", "भेजें")) {
            val raw = Raw()
            assertIs<ScreenAct.Failed>(control(raw).tap(label), label)
            assertTrue(raw.taps.isEmpty(), label)
            assertTrue(RiskyLabels.isRisky(label), label)
        }
        for (label in listOf("Chats", "Search", "Status", "Calls tab", "Settings")) assertFalse(RiskyLabels.isRisky(label), label)
    }

    @Test
    fun theToolAlsoRefusesRiskyLabelsBeforeAnythingElse() = runTest {
        val raw = Raw()
        val tool = TapOnScreenTool(control(raw))
        val prepared = tool.prepare(ToolArgs(mapOf("label" to "Send")))
        assertIs<com.jarvis.ai.ai.tools.Prepared.Stop>(prepared)
        assertTrue(raw.taps.isEmpty())
        assertEquals(Risk.MEDIUM, tool.spec.risk)
    }

    @Test
    fun protectedAppsAreNeverTappedOrTypedInto() = runTest {
        val raw = Raw(front = bank)
        val c = control(raw)
        assertIs<ScreenAct.OffLimits>(c.tap("Balance"))
        assertIs<ScreenAct.OffLimits>(c.type("1234"))
        val own = Raw(front = FrontApp("com.jarvis.ai", "JARVIS 3.0"))
        assertIs<ScreenAct.OffLimits>(control(own).tap("Yes"))
        val settings = Raw(front = FrontApp("com.android.settings", "Settings"))
        assertIs<ScreenAct.OffLimits>(control(settings).tap("Wi-Fi"))
        assertTrue(raw.taps.isEmpty() && raw.typed.isEmpty() && own.taps.isEmpty() && settings.taps.isEmpty())
    }

    @Test
    fun theWindowThatComesUpBetweenTheCheckAndThePressIsAlsoChecked() = runTest {
        // The front window changed to a bank between front() and tap(): the raw side is handed the same guard.
        val raw = Raw()
        val c = control(raw)
        raw.front = bank
        assertIs<ScreenAct.OffLimits>(c.tap("Open"))
    }

    @Test
    fun anAmbiguousOrMissingButtonIsReportedNotGuessed() = runTest {
        assertIs<ScreenAct.Failed>(control(Raw(tapResult = { RawTap.Ambiguous(3) })).tap("Chats"))
        assertIs<ScreenAct.Failed>(control(Raw(tapResult = { RawTap.NotFound })).tap("Chats"))
    }

    @Test
    fun tapsAreRateLimited() = runTest {
        var now = 0L
        val c = control(Raw(), limiter = ActionLimiter(max = 3, windowMs = 60_000L) { now })
        repeat(3) { assertIs<ScreenAct.Done>(c.tap("Chats")) }
        assertIs<ScreenAct.Failed>(c.tap("Chats"))
        now += 60_000L
        assertIs<ScreenAct.Done>(c.tap("Chats"))
    }

    @Test
    fun typeRejectsEmptyAndOversizedText() = runTest {
        val c = control(Raw())
        assertIs<ScreenAct.Failed>(c.type("  "))
        assertIs<ScreenAct.Failed>(c.type("x".repeat(501)))
        assertEquals(Risk.MEDIUM, TypeTextTool(c).spec.risk)
    }

    // ---- reading WhatsApp -----------------------------------------------------------------------------------------------

    private fun notes(vararg n: NotificationInfo, on: Boolean = true) = object : NotificationPort {
        override val enabled = on
        override suspend fun active() = n.toList()
        override suspend fun clearAll(): ClearResult? = null
    }

    @Test
    fun onlyWhatsAppNotificationsAreReadAndTheyAreMarkedPrivateAndUntrusted() = runTest {
        val tool = ReadWhatsAppTool(
            notes(
                NotificationInfo("WhatsApp", "Asha", "Are you coming?", "com.whatsapp", lines = listOf("Hi", "Are you coming?")),
                NotificationInfo("Bank", "HDFC", "OTP 123456", "com.hdfcbank.mobile"),
                NotificationInfo("Gmail", "Boss", "Report", "com.google.android.gm"),
                NotificationInfo("WhatsApp Business", "Shop", "Order ready", "com.whatsapp.w4b"),
            ),
        )
        val result = assertIs<ToolResult.Success>(tool.execute(ToolArgs(emptyMap())))
        assertTrue("Asha: Hi / Are you coming?" in result.message, result.message)
        assertTrue("Shop: Order ready" in result.message)
        assertFalse("OTP" in result.message || "Boss" in result.message)
        assertTrue(result.untrusted && result.private && result.answer)
        assertEquals(Verification.CONFIRMED, result.verification)
        assertEquals(Risk.MEDIUM, tool.spec.risk)
    }

    @Test
    fun noWhatsAppNotificationsGivesAnHonestAnswerAndNotificationAccessOffIsSaid() = runTest {
        val none = assertIs<ToolResult.Success>(ReadWhatsAppTool(notes(NotificationInfo("Gmail", "x", "y", "com.google.android.gm"))).execute(ToolArgs(emptyMap())))
        assertTrue("do not see any WhatsApp" in none.message)
        assertFalse(none.private)
        val off = ReadWhatsAppTool(notes(on = false)).execute(ToolArgs(emptyMap()))
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(off).error)
    }

    // ---- the WhatsApp tool with hands-free sending ----------------------------------------------------------------------

    private class Messaging : MessagingPort {
        var opened = 0
        var shared = 0
        override fun composeSms(number: String, body: String) = MessageHandoff.Opened

        override fun openWhatsAppChat(internationalNumber: String, body: String): MessageHandoff {
            opened++
            return MessageHandoff.Opened
        }

        override fun shareToWhatsApp(body: String): MessageHandoff {
            shared++
            return MessageHandoff.Opened
        }
    }

    private class Sender(override var enabled: Boolean, val result: SendPress) : WhatsAppSender {
        val asked = mutableListOf<String>()

        override suspend fun pressSend(expected: String): SendPress {
            asked += expected
            return result
        }
    }

    private val prepared = ToolArgs(mapOf("to" to "Asha", "number" to "+919812345610", "contact" to "Asha", "message" to "On my way"))

    @Test
    fun withTheSwitchOffTheToolOnlyOpensWhatsAppAndNeverPresses() = runTest {
        val sender = Sender(false, SendPress.Pressed(BoxAfter.CLEARED))
        val result = assertIs<ToolResult.Success>(SendWhatsAppTool(null, Messaging(), sender).execute(prepared))
        assertTrue(sender.asked.isEmpty())
        assertTrue("has not been sent" in result.message)
        assertFalse(SendWhatsAppTool(null, Messaging(), sender).describe(prepared).endsWith(VoiceDirectPolicy.SEND_PRESSED_NOTE))
    }

    @Test
    fun withTheSwitchOnSendIsPressedWithTheExactApprovedText() = runTest {
        val sender = Sender(true, SendPress.Pressed(BoxAfter.CLEARED))
        val tool = SendWhatsAppTool(null, Messaging(), sender)
        val result = assertIs<ToolResult.Success>(tool.execute(prepared))
        assertEquals(listOf("On my way"), sender.asked)
        assertEquals(Verification.HANDED_OFF, result.verification)
        assertTrue("Pressed Send" in result.message)
        assertTrue("cannot promise it arrived" in result.message)
        assertFalse("delivered" in result.message.lowercase())
        assertTrue(tool.describe(prepared).endsWith(VoiceDirectPolicy.SEND_PRESSED_NOTE))
    }

    @Test
    fun aNumberWithoutACountryCodeIsNeverAutoSent() = runTest {
        val sender = Sender(true, SendPress.Pressed(BoxAfter.CLEARED))
        val messaging = Messaging()
        val local = ToolArgs(mapOf("to" to "Asha", "number" to "9812345610", "contact" to "Asha", "message" to "On my way"))
        val result = assertIs<ToolResult.Success>(SendWhatsAppTool(null, messaging, sender).execute(local))
        assertTrue(sender.asked.isEmpty())
        assertEquals(1, messaging.shared)
        assertTrue("choose" in result.message)
    }

    @Test
    fun everyRefusalFallsBackToOpenWhatsAppAndSaysNothingWasSent() = runTest {
        for (press in listOf(
            SendPress.NotEnabled, SendPress.OffLimits("Bank"), SendPress.WrongApp("Chrome"), SendPress.TextMismatch, SendPress.Failed("No window."),
        )) {
            val result = assertIs<ToolResult.Success>(SendWhatsAppTool(null, Messaging(), Sender(true, press)).execute(prepared), "$press")
            assertTrue("not been sent" in result.message, result.message)
            assertFalse(result.message.startsWith("Pressed Send"), result.message)
        }
        val still = assertIs<ToolResult.Success>(SendWhatsAppTool(null, Messaging(), Sender(true, SendPress.Pressed(BoxAfter.STILL_THERE))).execute(prepared))
        assertTrue("may not have gone" in still.message)
    }

    @Test
    fun theWhatsAppToolIsStillHighRisk() {
        assertEquals(Risk.HIGH, SendWhatsAppTool(null, Messaging(), null).spec.risk)
    }

    // ---- who may skip the tap -------------------------------------------------------------------------------------------

    private fun req(summary: String, tool: String = "send_whatsapp") = ConfirmationRequest("r", tool, summary, Risk.HIGH, 0L)
    private val autoSummary = "WhatsApp Asha on +919812345610 (mobile): \"On my way\"" + VoiceDirectPolicy.SEND_PRESSED_NOTE

    @Test
    fun aSpokenWhatsAppMessageGetsTheCountdownOnlyWithTheSwitchOnAndTheSendNote() {
        assertTrue(VoiceDirectPolicy.eligible(RequestOrigin.SPOKEN_BUILT_IN, req(autoSummary), whatsappSend = true))
        assertFalse(VoiceDirectPolicy.eligible(RequestOrigin.SPOKEN_BUILT_IN, req(autoSummary), whatsappSend = false), "switch off")
        assertFalse(VoiceDirectPolicy.eligible(RequestOrigin.SPOKEN_BUILT_IN, req(autoSummary.removeSuffix(VoiceDirectPolicy.SEND_PRESSED_NOTE)), true), "no send note")
        for (origin in listOf(RequestOrigin.TYPED, RequestOrigin.ASSISTANT_PLANNED)) {
            assertFalse(VoiceDirectPolicy.eligible(origin, req(autoSummary), true), "$origin must always be a tap")
        }
        assertFalse(VoiceDirectPolicy.eligible(RequestOrigin.SPOKEN_BUILT_IN, req(autoSummary, "send_sms"), true), "only WhatsApp")
        assertFalse(VoiceDirectPolicy.eligible(RequestOrigin.SPOKEN_BUILT_IN, req("Tap \"Chats\"", "tap_on_screen"), true))
    }

    @Test
    fun theReadBackNamesTheRecipientAndTheWords() {
        assertEquals("Sending WhatsApp to Asha: On my way. Say stop to cancel.", VoiceDirectPolicy.readBack(req(autoSummary)))
    }

    // ---- phrases ------------------------------------------------------------------------------------------------------

    private fun tool(text: String): String? = (NativeCommands.match(text) as? NativeCommands.Match.Run)?.steps?.singleOrNull()?.tool

    @Test
    fun readingPhrasesGoToTheWhatsAppReader() {
        for (phrase in listOf(
            "read my whatsapp messages", "read unread whatsapp", "check whatsapp", "go to whatsapp and read unread inbox",
            "open whatsapp and read my unread messages", "any new whatsapp messages", "do I have any unread WhatsApp messages",
            "what's new on whatsapp", "what are my unread messages on whatsapp", "read my unread messages",
        )) {
            assertEquals("read_whatsapp_unread", tool(phrase), phrase)
        }
    }

    @Test
    fun writingPhrasesStillWriteAndAreNotTakenForReading() {
        assertEquals("send_whatsapp", tool("whatsapp mom saying I'll be late"))
        assertEquals("send_whatsapp", tool("send whatsapp to Asha saying hello"))
        assertEquals("send_whatsapp", tool("go to whatsapp and send Asha saying I am outside"))
        assertNull(tool("whatsapp unread"), "a bare 'whatsapp unread' is not a message to someone called unread")
    }

    @Test
    fun routerHoldsNothingUnexpectedAboutNotifications() {
        assertNotNull(NativeCommands.match("read my notifications"))
        assertEquals("read_notifications", tool("read my notifications"))
    }

    // ---- guards that must hold even when the Android side forgets them ------------------------------------------------

    @Test
    fun scrollingNeverHappensInABankingApp() = runTest {
        val raw = Raw(front = bank, enforceAllow = false)
        val result = control(raw).scroll(down = true)
        assertIs<ScreenAct.OffLimits>(result)
    }

    @Test
    fun anApprovedPressStillRefusesAMoneyButtonAndNeverTouchesTheScreen() = runTest {
        val raw = Raw()
        for (label in listOf("Pay now", "Buy", "Transfer money", "Send money")) {
            val result = control(raw).tapApproved(label)
            assertIs<ScreenAct.Failed>(result, label)
        }
        assertTrue(raw.taps.isEmpty(), "a money button was pressed: ${raw.taps}")
    }

    @Test
    fun anApprovedPressInABankingAppIsOffLimitsAndNeverTouchesTheScreen() = runTest {
        val raw = Raw(front = bank, enforceAllow = false)
        assertIs<ScreenAct.OffLimits>(control(raw).tapApproved("Pin chat"))
        assertTrue(raw.taps.isEmpty())
    }
}

class TapTypePhrasesTest {
    private fun step(text: String) = (NativeCommands.match(text) as? NativeCommands.Match.Run)?.steps?.singleOrNull()

    @Test
    fun tapAndTypePhrasesBecomeTheirTools() {
        assertEquals("tap_on_screen", step("tap Chats")?.tool)
        assertEquals("chats", step("tap the chats tab")?.parameters?.get("label")?.toString()?.trim('"'))
        assertEquals("type_text", step("type hello there")?.tool)
        assertEquals("hello there", step("type \"hello there\"")?.parameters?.get("text")?.toString()?.trim('"'))
        assertEquals("See you at 5.", step("type See you at 5.")?.parameters?.get("text")?.toString()?.trim('"'), "the person's words keep their case and full stop")
    }

    @Test
    fun navigationAndMessagePhrasesAreNotStolen() {
        assertEquals("phone_navigate", step("press home")?.tool)
        assertEquals("send_whatsapp", step("type whatsapp mom saying hi")?.tool)
        assertEquals("compose_email", step("type email a@b.com saying hi")?.tool)
    }
}
