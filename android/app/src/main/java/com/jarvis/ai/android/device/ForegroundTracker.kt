package com.jarvis.ai.android.device

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Knows whether one of this app's activities is on screen.
 *
 * Since Android 10, an app that is not in the foreground cannot start another app's
 * activity, and the system blocks it SILENTLY (no exception). The tools that open other
 * apps therefore check this first and report the real reason instead of claiming they
 * "opened" something that Android in fact suppressed.
 */
class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)

    val isForeground: Boolean get() = started.get() > 0

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
