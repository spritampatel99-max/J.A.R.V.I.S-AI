package com.jarvis.ai

import android.app.Application
import com.jarvis.ai.android.device.ForegroundTracker

class JarvisApplication : Application() {

    private val foreground = ForegroundTracker()

    val container: AppContainer by lazy { AppContainer(this, foreground) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(foreground)
    }
}
