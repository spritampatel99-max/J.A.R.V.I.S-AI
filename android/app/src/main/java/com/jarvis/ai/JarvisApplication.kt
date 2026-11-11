package com.jarvis.ai

import android.app.Application
import com.jarvis.ai.android.device.ForegroundTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisApplication : Application() {

    private val foreground = ForegroundTracker()

    val container: AppContainer by lazy { AppContainer(this, foreground) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(foreground)
        // Deliver anything that came due while the app was not running and ask Android for the next wake-up.
        CoroutineScope(Dispatchers.Default).launch { container.schedule.tick() }
    }
}
