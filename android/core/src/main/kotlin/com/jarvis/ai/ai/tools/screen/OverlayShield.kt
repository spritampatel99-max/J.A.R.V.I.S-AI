package com.jarvis.ai.ai.tools.screen

/**
 * Keeps JARVIS's floating card off payment, banking and password apps.
 *
 * Many of those apps close themselves, or drop to the home screen, when another app is drawn over them. The card is drawn
 * while JARVIS listens, thinks or speaks, so it must not be on screen while such an app is in front or is being opened.
 *
 *  - [noteLaunch] is called when JARVIS itself opens an app. If that app is one the card must stay off ([ScreenGuard.mayDrawOver]),
 *    the card is held back for [holdMs] and [onBlocked] fires so a card already on screen is taken down at once.
 *  - [mayShow] asks the app in front ([front], null when it cannot be known) and answers no for such an app. An unknown app is
 *    treated as fine: there is nothing else to go on, and "payment mode" is the way to switch JARVIS off before paying.
 *  - [recheck] is called when Android says a window changed; it takes the card down if a guarded app has come to the front.
 *
 * Only package names are used. Nothing on a screen is read.
 */
class OverlayShield(
    private val guard: ScreenGuard,
    private val front: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val holdMs: Long = HOLD_MS,
    private val cacheMs: Long = CACHE_MS,
) {
    private val lock = Any()
    private var heldUntilMs = 0L
    private var checked = false
    private var checkedAtMs = 0L
    private var checkedOk = true

    /** Called when the card must come down now. May be called from any thread. */
    @Volatile
    var onBlocked: (() -> Unit)? = null

    fun noteLaunch(packageName: String) {
        val hold = synchronized(lock) {
            if (guard.mayDrawOver(packageName)) {
                false
            } else {
                heldUntilMs = now() + holdMs
                checked = false
                true
            }
        }
        if (hold) onBlocked?.invoke()
    }

    fun mayShow(): Boolean {
        synchronized(lock) {
            val t = now()
            if (t < heldUntilMs) return false
            if (checked && t - checkedAtMs in 0 until cacheMs) return checkedOk
        }
        return look()
    }

    fun recheck() {
        if (onBlocked == null) return
        synchronized(lock) { checked = false }
        if (!mayShow()) onBlocked?.invoke()
    }

    private fun look(): Boolean {
        val pkg = try {
            front()
        } catch (e: RuntimeException) {
            null
        }
        val ok = pkg.isNullOrBlank() || guard.mayDrawOver(pkg)
        synchronized(lock) {
            checked = true
            checkedAtMs = now()
            checkedOk = ok
        }
        return ok
    }

    companion object {
        /** How long the card stays off after JARVIS opens a guarded app. Long enough for the app to start and be used for a payment. */
        const val HOLD_MS = 45_000L

        /** The app in front is not looked up more often than this, because the card asks on every partial word while listening. */
        const val CACHE_MS = 400L
    }
}
