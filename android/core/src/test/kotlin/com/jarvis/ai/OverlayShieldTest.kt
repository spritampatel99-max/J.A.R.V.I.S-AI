package com.jarvis.ai

import com.jarvis.ai.ai.tools.screen.OverlayShield
import com.jarvis.ai.ai.tools.screen.ScreenGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayShieldTest {
    private val own = "com.jarvis.ai"
    private var clock = 1_000_000L
    private var frontApp: String? = null
    private var lookups = 0
    private var personal = emptySet<String>()
    private val guard = ScreenGuard(own) { personal }

    private fun shield(holdMs: Long = 45_000L, cacheMs: Long = 400L) =
        OverlayShield(guard, front = { lookups++; frontApp }, now = { clock }, holdMs = holdMs, cacheMs = cacheMs)

    // ---- the rule for one package ----------------------------------------------------------------------------------

    @Test fun `payment banking and password apps must not be drawn over`() {
        for (pkg in listOf("com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
            "com.hdfcbank.android", "com.samsung.android.spay", "com.paypal.android.p2pmobile", "com.x8bit.bitwarden")) {
            assertFalse(guard.mayDrawOver(pkg), pkg)
        }
    }

    @Test fun `ordinary apps JARVIS itself and an unknown app may be drawn over`() {
        for (pkg in listOf("com.whatsapp", "com.google.android.youtube", own, "COM.JARVIS.AI", "", "   ")) {
            assertTrue(guard.mayDrawOver(pkg), "'$pkg'")
        }
    }

    @Test fun `an app on the person's own list is not drawn over`() {
        personal = setOf("com.example.mycard")
        assertFalse(guard.mayDrawOver("com.example.mycard"))
        assertFalse(guard.mayDrawOver(" Com.Example.MyCard "))
        assertTrue(guard.mayDrawOver("com.example.other"))
    }

    // ---- what is in front ------------------------------------------------------------------------------------------

    @Test fun `the card may show when nothing is known about the front app`() {
        frontApp = null
        assertTrue(shield().mayShow())
    }

    @Test fun `the card may show over an ordinary app`() {
        frontApp = "com.whatsapp"
        assertTrue(shield().mayShow())
    }

    @Test fun `the card may not show over a payment app`() {
        frontApp = "com.phonepe.app"
        assertFalse(shield().mayShow())
    }

    @Test fun `a blank front package counts as unknown`() {
        frontApp = " "
        assertTrue(shield().mayShow())
    }

    @Test fun `a failing lookup counts as unknown and does not crash`() {
        val s = OverlayShield(guard, front = { throw IllegalStateException("service gone") }, now = { clock })
        assertTrue(s.mayShow())
    }

    @Test fun `the front app is looked up at most once in the cache window`() {
        val s = shield(cacheMs = 400)
        frontApp = "com.whatsapp"
        repeat(5) { assertTrue(s.mayShow()) }
        assertEquals(1, lookups)
        clock += 399
        s.mayShow()
        assertEquals(1, lookups)
        clock += 1
        s.mayShow()
        assertEquals(2, lookups)
    }

    @Test fun `once the cache window is over a new payment app in front is noticed`() {
        val s = shield(cacheMs = 400)
        frontApp = "com.whatsapp"
        assertTrue(s.mayShow())
        frontApp = "com.phonepe.app"
        clock += 400
        assertFalse(s.mayShow())
    }

    @Test fun `a window change is noticed at once even inside the cache window`() {
        val s = shield(cacheMs = 10_000)
        frontApp = "com.whatsapp"
        assertTrue(s.mayShow())
        var blocked = 0
        s.onBlocked = { blocked++ }
        frontApp = "com.phonepe.app"
        s.recheck()
        assertEquals(1, blocked)
        assertFalse(s.mayShow())
    }

    @Test fun `a window change to an ordinary app does not take the card down`() {
        val s = shield()
        var blocked = 0
        s.onBlocked = { blocked++ }
        frontApp = "com.whatsapp"
        s.recheck()
        assertEquals(0, blocked)
        assertTrue(s.mayShow())
    }

    // ---- opening a guarded app -------------------------------------------------------------------------------------

    @Test fun `opening a payment app holds the card back and takes a shown card down`() {
        val s = shield()
        var blocked = 0
        s.onBlocked = { blocked++ }
        frontApp = null
        s.noteLaunch("com.phonepe.app")
        assertEquals(1, blocked)
        assertFalse(s.mayShow())
    }

    @Test fun `the hold is on even when the front app cannot be known`() {
        val s = shield()
        frontApp = "com.whatsapp"
        s.noteLaunch("net.one97.paytm")
        assertFalse(s.mayShow())
    }

    @Test fun `the hold ends after its time`() {
        val s = shield(holdMs = 45_000, cacheMs = 0)
        frontApp = "com.whatsapp"
        s.noteLaunch("com.phonepe.app")
        clock += 44_999
        assertFalse(s.mayShow())
        clock += 1
        assertTrue(s.mayShow())
    }

    @Test fun `opening an ordinary app does not hold the card back`() {
        val s = shield()
        var blocked = 0
        s.onBlocked = { blocked++ }
        s.noteLaunch("com.whatsapp")
        s.noteLaunch(own)
        assertEquals(0, blocked)
        assertTrue(s.mayShow())
    }

    @Test fun `opening an app on the person's own list holds the card back`() {
        personal = setOf("com.example.mycard")
        val s = shield()
        s.noteLaunch("com.example.mycard")
        assertFalse(s.mayShow())
    }

    @Test fun `a second launch of a guarded app extends the hold`() {
        val s = shield(holdMs = 45_000, cacheMs = 0)
        frontApp = null
        s.noteLaunch("com.phonepe.app")
        clock += 30_000
        s.noteLaunch("com.phonepe.app")
        clock += 30_000
        assertFalse(s.mayShow())
        clock += 15_000
        assertTrue(s.mayShow())
    }

    @Test fun `no listener is fine`() {
        val s = shield()
        s.noteLaunch("com.phonepe.app")
        frontApp = "com.phonepe.app"
        s.recheck()
        assertFalse(s.mayShow())
    }
}
