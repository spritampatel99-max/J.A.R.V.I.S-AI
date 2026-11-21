package com.jarvis.ai.android.local

import android.content.Context
import com.jarvis.ai.ai.local.LocalEngine

/** Used only when the build is made with `enableLiteRt=true`. */
object LocalEngines {
    const val AVAILABLE = true

    fun create(context: Context): LocalEngine? = LiteRtLmEngine(context.applicationContext.cacheDir)
}
