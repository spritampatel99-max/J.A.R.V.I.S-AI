package com.jarvis.ai.android.screen

import android.accessibilityservice.AccessibilityService
import com.jarvis.ai.ai.tools.screen.FrontApp
import com.jarvis.ai.ai.tools.screen.PhoneAction
import com.jarvis.ai.ai.tools.screen.RawBox
import com.jarvis.ai.ai.tools.screen.RawResult
import com.jarvis.ai.ai.tools.screen.RawScreen
import com.jarvis.ai.ai.tools.screen.RawTap
import com.jarvis.ai.ai.tools.screen.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The accessibility service seen as the core's [RawScreen]. It is only ever handed to the core inside a GuardedScreenControl,
 * which supplies the package check and refuses actions on protected apps. Every call asks Android for the service afresh, so
 * a service the person switched off is noticed on the next request.
 */
class AndroidRawScreen : RawScreen {

    override val connected: Boolean get() = ScreenHub.accessibility != null

    override fun front(): FrontApp? {
        val service = ScreenHub.accessibility ?: return null
        val pkg = service.frontPackage() ?: return null
        return FrontApp(pkg, service.labelOf(pkg))
    }

    override suspend fun snapshot(allow: (String) -> Boolean): Snapshot {
        val service = ScreenHub.accessibility ?: return Snapshot.None
        return withContext(Dispatchers.Default) {
            when (val read = service.read(allow)) {
                is JarvisAccessibilityService.Read.Text -> Snapshot.Text(read.app, read.lines)
                is JarvisAccessibilityService.Read.Refused -> Snapshot.Refused(read.app)
                JarvisAccessibilityService.Read.None -> Snapshot.None
            }
        }
    }

    override suspend fun scroll(down: Boolean, allow: (String) -> Boolean): RawResult {
        val service = ScreenHub.accessibility ?: return RawResult.Failed("The accessibility service is not running.")
        return withContext(Dispatchers.Default) {
            when (val result = service.scroll(down, allow)) {
                is JarvisAccessibilityService.Scroll.Done -> RawResult.Done(result.app)
                is JarvisAccessibilityService.Scroll.Refused -> RawResult.Refused(result.app)
                is JarvisAccessibilityService.Scroll.NothingToScroll -> RawResult.Failed("There is nothing to scroll in ${result.app.label}.")
                is JarvisAccessibilityService.Scroll.CouldNot -> RawResult.Failed("${result.app.label} did not scroll.")
                JarvisAccessibilityService.Scroll.NoWindow -> RawResult.Failed("No window could be read.")
            }
        }
    }

    override suspend fun tap(label: String, allow: (String) -> Boolean): RawTap {
        val service = ScreenHub.accessibility ?: return RawTap.Failed("The accessibility service is not running.")
        return withContext(Dispatchers.Default) { service.tap(label, allow) }
    }

    override suspend fun type(text: String, allow: (String) -> Boolean): RawResult {
        val service = ScreenHub.accessibility ?: return RawResult.Failed("The accessibility service is not running.")
        return withContext(Dispatchers.Default) { service.type(text, allow) }
    }

    override suspend fun box(allow: (String) -> Boolean): RawBox {
        val service = ScreenHub.accessibility ?: return RawBox.None
        return withContext(Dispatchers.Default) { service.box(allow) }
    }

    override suspend fun perform(action: PhoneAction): RawResult {
        val service = ScreenHub.accessibility ?: return RawResult.Failed("The accessibility service is not running.")
        val accepted = service.press(globalAction(action))
        return if (accepted) RawResult.Done(front()) else RawResult.Failed("Android did not accept that.")
    }

    private fun globalAction(action: PhoneAction): Int = when (action) {
        PhoneAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        PhoneAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
        PhoneAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        PhoneAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        PhoneAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        PhoneAction.LOCK -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        PhoneAction.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    }
}
