package com.jarvis.ai

import com.jarvis.ai.ai.tools.screen.ScreenGuard
import com.jarvis.ai.voice.BluetoothRules
import com.jarvis.ai.voice.NotificationAnnouncer
import com.jarvis.ai.voice.PostedNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothCompanionTest {
    private var clock = 1_000_000L
    private val announcer = NotificationAnnouncer(ScreenGuard("com.jarvis.ai") { emptySet() }) { clock }

    private fun note(
        key: String = "k1",
        pkg: String = "com.whatsapp",
        app: String = "WhatsApp",
        title: String = "Riya",
        text: String = "Are you coming tonight?",
        category: String? = "msg",
        ongoing: Boolean = false,
        summary: Boolean = false,
        important: Boolean = true,
        hide: Boolean = false,
    ) = PostedNotification(key, pkg, app, title, text, ongoing, summary, category, important, hide)

    @Test
    fun aMessageIsReadWithItsSenderAndText() {
        assertEquals("New message from Riya on WhatsApp. Are you coming tonight?", announcer.phrase(note(), readText = true))
    }

    @Test
    fun withTextOffOnlyTheSenderIsSaid() {
        val said = announcer.phrase(note(), readText = false)!!
        assertEquals("New message from Riya on WhatsApp", said)
        assertFalse("coming" in said)
    }

    @Test
    fun quietOngoingCallAndMediaNotificationsStaySilent() {
        assertNull(announcer.phrase(note(important = false), true))
        assertNull(announcer.phrase(note(key = "a", ongoing = true), true))
        assertNull(announcer.phrase(note(key = "b", summary = true), true))
        for (category in listOf("call", "progress", "transport", "service", "sys", "navigation", "promo", "alarm")) {
            assertNull(announcer.phrase(note(key = category, category = category), true), category)
        }
        assertNull(announcer.phrase(note(key = "e", title = "", text = ""), true))
    }

    @Test
    fun bankingPaymentPasswordAndOwnAppsAreNeverRead() {
        for (pkg in listOf("com.jarvis.ai", "com.hdfc.bank", "com.google.android.apps.nbu.paisa.user", "com.x8bit.bitwarden", "com.phonepe.app")) {
            assertNull(announcer.phrase(note(key = pkg, pkg = pkg), true), pkg)
        }
    }

    @Test
    fun theSameNotificationIsNotReadTwiceInAMinuteButIsAfterwards() {
        assertTrue(announcer.phrase(note(), true) != null)
        clock += 10_000
        assertNull(announcer.phrase(note(), true))
        clock += 61_000
        assertTrue(announcer.phrase(note(), true) != null)
    }

    @Test
    fun aBurstIsLimitedAndTheNextOneMentionsWhatWasSkipped() {
        repeat(5) { assertTrue(announcer.phrase(note(key = "m$it", text = "hello $it"), true) != null) }
        assertNull(announcer.phrase(note(key = "x1", text = "six"), true))
        assertNull(announcer.phrase(note(key = "x2", text = "seven"), true))
        clock += 61_000
        val next = announcer.phrase(note(key = "x3", text = "eight"), true)!!
        assertTrue(next.startsWith("2 more notifications were not read out."), next)
    }

    @Test
    fun linksAndControlCharactersAreNotSpokenAndLengthIsCapped() {
        val said = announcer.phrase(note(text = "see https://evil.example/a?b=1 now\u0007\nok " + "x".repeat(500)), true)!!
        assertFalse("evil.example" in said)
        assertTrue("a link" in said)
        assertFalse(said.any { it.code < 32 })
        assertTrue(said.length <= 260)
    }

    @Test
    fun whenTheLockScreenWouldHideTheWordsOnlyTheAppIsNamed() {
        val said = announcer.phrase(note(hide = true), readText = true)!!
        assertEquals("New notification from WhatsApp", said)
        assertFalse("Riya" in said)
        assertFalse("tonight" in said)
        // Hiding still respects every other rule.
        assertNull(announcer.phrase(note(key = "q", hide = true, important = false), true))
        assertNull(announcer.phrase(note(key = "r", hide = true, pkg = "com.hdfc.bank"), true))
    }

    @Test
    fun anAppThatIsNotAMessageIsNamedWithItsTitle() {
        assertEquals("Gmail: Invoice. Paid", announcer.phrase(note(app = "Gmail", pkg = "com.google.android.gm", title = "Invoice", text = "Paid", category = "email"), true))
    }

    @Test
    fun deviceNamesMatchWithoutCaseAndEmptyMeansAny() {
        assertTrue(BluetoothRules.matches("Pritam Buds", emptySet()))
        assertTrue(BluetoothRules.matches(null, emptySet()))
        assertTrue(BluetoothRules.matches("pritam buds", setOf("Pritam Buds")))
        assertFalse(BluetoothRules.matches("Car Kit", setOf("Pritam Buds")))
        assertFalse(BluetoothRules.matches(null, setOf("Pritam Buds")))
    }

    @Test
    fun theConnectedLineSaysOnlyHowManyNotificationsWait() {
        assertEquals("Bluetooth connected: Buds. You have 3 notifications.", BluetoothRules.connectedLine("Buds", 3))
        assertEquals("Bluetooth connected. You have no notifications.", BluetoothRules.connectedLine(null, 0))
        assertEquals("Bluetooth connected: Buds.", BluetoothRules.connectedLine("Buds", null))
        assertEquals("Bluetooth connected: Buds. You have 1 notification.", BluetoothRules.connectedLine(" Buds ", 1))
    }
}
