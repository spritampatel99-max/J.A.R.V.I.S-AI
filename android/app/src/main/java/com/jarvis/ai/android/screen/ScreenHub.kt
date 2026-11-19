package com.jarvis.ai.android.screen

/**
 * Where the two Android services say they are running. Android creates and binds those services itself, so the rest of the
 * app cannot hold them directly; each sets its own reference when Android connects it and clears it when Android disconnects it.
 * A null reference means the person has not switched that service on in Settings (or Android has stopped it), and the tools
 * that need it then say so instead of pretending to work.
 */
object ScreenHub {
    @Volatile
    var accessibility: JarvisAccessibilityService? = null

    @Volatile
    var notifications: JarvisNotificationListener? = null
}
