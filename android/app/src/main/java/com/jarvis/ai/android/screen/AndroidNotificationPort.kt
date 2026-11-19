package com.jarvis.ai.android.screen

import com.jarvis.ai.ai.tools.screen.ClearResult
import com.jarvis.ai.ai.tools.screen.NotificationInfo
import com.jarvis.ai.ai.tools.screen.NotificationPort
import kotlinx.coroutines.delay

/** The notification listener seen as the core's [NotificationPort]. It is wrapped in GuardedNotifications before the tools see it. */
class AndroidNotificationPort : NotificationPort {

    override val enabled: Boolean get() = ScreenHub.notifications != null

    override suspend fun active(): List<NotificationInfo> = ScreenHub.notifications?.showing().orEmpty()

    /**
     * Dismisses everything Android allows and reports what Android lists before and after, so the answer is what happened,
     * not what was asked. Dismissal is handled by the system a moment later, so the second count is taken after a short wait.
     */
    override suspend fun clearAll(): ClearResult? {
        val listener = ScreenHub.notifications ?: return null
        val before = listener.clearableCount()
        if (before == 0) return ClearResult(0, 0)
        listener.clearAllNow()
        delay(SETTLE_MS)
        val live = ScreenHub.notifications ?: return null
        return ClearResult(before, live.clearableCount())
    }

    private companion object {
        const val SETTLE_MS = 700L
    }
}
