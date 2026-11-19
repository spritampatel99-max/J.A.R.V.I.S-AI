package com.jarvis.ai.android.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.ai.ai.tools.screen.FrontApp

/**
 * The phone-control service the person switches on in Settings > Accessibility. It does three things only: read the text of
 * the window in front, scroll it, and press the system's own Back, Home, Recents, Notifications, Quick settings, Lock and
 * Screenshot controls.
 *
 * It is declared without the gesture capability, and no code here injects a tap, a swipe or typing into another app.
 * It ignores every event except to know a window changed, and it never stores or sends what it reads: the text goes straight
 * back to the caller. Whether a window may be read at all is decided by the caller's package check, which runs before the
 * window's content is touched.
 */
class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenHub.accessibility = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing is collected from events. The window is read on request, never in the background.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (ScreenHub.accessibility === this) ScreenHub.accessibility = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (ScreenHub.accessibility === this) ScreenHub.accessibility = null
        super.onDestroy()
    }

    /** The package of the window Android says is active, or null when none can be read right now. */
    fun frontPackage(): String? {
        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()?.takeIf { it.isNotBlank() }
        } finally {
            NodeReader.recycle(root)
        }
    }

    /**
     * Reads the active window if [allow] accepts its package. The package is checked on the same window object that is then
     * read, so a window that appears in between cannot be read under another app's permission.
     */
    fun read(allow: (String) -> Boolean): Read {
        val root = rootInActiveWindow ?: return Read.None
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return Read.None
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return Read.Refused(app)
            return Read.Text(app, NodeReader.lines(root))
        } finally {
            NodeReader.recycle(root)
        }
    }

    /** Scrolls the biggest scrollable area of the active window, if [allow] accepts the window's package. */
    fun scroll(down: Boolean, allow: (String) -> Boolean): Scroll {
        val root = rootInActiveWindow ?: return Scroll.NoWindow
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return Scroll.NoWindow
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return Scroll.Refused(app)
            val target = NodeReader.biggestScrollable(root) ?: return Scroll.NothingToScroll(app)
            try {
                val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                return if (target.performAction(action)) Scroll.Done(app) else Scroll.CouldNot(app)
            } finally {
                NodeReader.recycle(target)
            }
        } finally {
            NodeReader.recycle(root)
        }
    }

    /** Presses one of the system's own controls. The return value is Android's answer to the request. */
    fun press(globalAction: Int): Boolean = performGlobalAction(globalAction)

    fun labelOf(packageName: String): String = try {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString().ifBlank { packageName }
    } catch (e: Exception) {
        // Apps without a launcher icon are not visible to this app on Android 11 and later; say what Android calls them.
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { packageName }
    }

    sealed interface Read {
        data class Text(val app: FrontApp, val lines: List<String>) : Read

        data class Refused(val app: FrontApp) : Read

        data object None : Read
    }

    sealed interface Scroll {
        data class Done(val app: FrontApp) : Scroll

        data class Refused(val app: FrontApp) : Scroll

        data class NothingToScroll(val app: FrontApp) : Scroll

        data class CouldNot(val app: FrontApp) : Scroll

        data object NoWindow : Scroll
    }
}
