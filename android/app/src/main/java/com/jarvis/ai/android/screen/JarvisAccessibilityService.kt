package com.jarvis.ai.android.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.ai.ai.tools.screen.FrontApp
import com.jarvis.ai.ai.tools.screen.RawBox
import com.jarvis.ai.ai.tools.screen.RawResult
import com.jarvis.ai.ai.tools.screen.RawRows
import com.jarvis.ai.ai.tools.screen.RawTap
import com.jarvis.ai.ai.tools.screen.ScreenRow

/**
 * The phone-control service the person switches on in Settings > Accessibility. It can: read the text of the window in front,
 * scroll it, press the system's own Back, Home, Recents, Notifications, Quick settings, Lock and Screenshot controls, press one
 * labelled control, and put text into one message box.
 *
 * It is declared without the gesture capability: nothing here touches screen coordinates or injects swipes. A press is Android's
 * own "click" action on a control found by its label, and typing is Android's own "set text" action on a text box, so neither can
 * land anywhere that is not a real control of the window in front. It ignores every event except to know a window changed, and it
 * never stores or sends what it reads: the text goes straight back to the caller. Whether a window may be read or acted on at all
 * is decided by the caller's package check, which runs on the same window object before anything of it is touched. Whether the
 * person has allowed pressing and typing, and whether a message may be sent, is decided by the caller too.
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

    /** Presses the one visible control called [label] in the front window, if [allow] accepts the window's package. */
    fun tap(label: String, allow: (String) -> Boolean): RawTap {
        val root = rootInActiveWindow ?: return RawTap.Failed("No window could be read.")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return RawTap.Failed("No window could be read.")
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return RawTap.Refused(app)
            val found = NodeReader.pressables(root, label)
            try {
                if (found.isEmpty()) return RawTap.NotFound
                if (found.size > 1) return RawTap.Ambiguous(found.size)
                return if (found[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) RawTap.Done(app) else RawTap.Failed("${app.label} did not accept the press.")
            } finally {
                found.forEach { NodeReader.recycle(it) }
            }
        } finally {
            NodeReader.recycle(root)
        }
    }

    /** Adds [text] to the message box of the front window, if [allow] accepts the window's package. Never a password box. */
    fun type(text: String, allow: (String) -> Boolean): RawResult {
        val root = rootInActiveWindow ?: return RawResult.Failed("No window could be read.")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return RawResult.Failed("No window could be read.")
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return RawResult.Refused(app)
            val box = NodeReader.messageBox(root) ?: return RawResult.Failed("There is no text box on this screen to type into.")
            try {
                val existing = if (box.isShowingHintText) "" else box.text?.toString().orEmpty()
                val joined = if (existing.isNotBlank() && !existing.last().isWhitespace()) "$existing $text" else existing + text
                if (!box.isFocused) box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, joined) }
                return if (box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) RawResult.Done(app) else RawResult.Failed("${app.label} did not accept the text.")
            } finally {
                NodeReader.recycle(box)
            }
        } finally {
            NodeReader.recycle(root)
        }
    }

    /** What the message box of the front window holds ("" while it only shows its hint), if [allow] accepts the window's package. */
    fun box(allow: (String) -> Boolean): RawBox {
        val root = rootInActiveWindow ?: return RawBox.None
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return RawBox.None
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return RawBox.Refused(app)
            val box = NodeReader.messageBox(root) ?: return RawBox.NoBox
            try {
                return RawBox.Text(app, if (box.isShowingHintText) "" else box.text?.toString().orEmpty())
            } finally {
                NodeReader.recycle(box)
            }
        } finally {
            NodeReader.recycle(root)
        }
    }

    /** The items of the front window's main list, if [allow] accepts the window's package. */
    fun rows(allow: (String) -> Boolean): RawRows {
        val root = rootInActiveWindow ?: return RawRows.None
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return RawRows.None
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return RawRows.Refused(app)
            val found = NodeReader.rowsWithNodes(root)
            try {
                return if (found.isEmpty()) RawRows.None else RawRows.Found(app, found.map { it.first })
            } finally {
                found.forEach { NodeReader.recycle(it.second) }
            }
        } finally {
            NodeReader.recycle(root)
        }
    }

    /**
     * Presses the first list item that [pick] accepts, in one step on the same window object, if [allow] accepts its package and it is
     * one of [inApps]. The press is Android's own "click" action; no coordinates are used.
     */
    fun pressRow(allow: (String) -> Boolean, inApps: Set<String>, pick: (ScreenRow) -> Boolean): RawTap {
        val root = rootInActiveWindow ?: return RawTap.Failed("No window could be read.")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank()) return RawTap.Failed("No window could be read.")
            val app = FrontApp(pkg, labelOf(pkg))
            if (!allow(pkg)) return RawTap.Refused(app)
            if (pkg !in inApps) return RawTap.Failed("${app.label} is in front, not the app I was asked to use.")
            val found = NodeReader.rowsWithNodes(root)
            try {
                val hit = found.firstOrNull { pick(it.first) } ?: return RawTap.NotFound
                val target = NodeReader.clickTarget(hit.second)
                try {
                    return if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) RawTap.Done(app) else RawTap.Failed("${app.label} did not accept the press.")
                } finally {
                    NodeReader.recycle(target)
                }
            } finally {
                found.forEach { NodeReader.recycle(it.second) }
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
