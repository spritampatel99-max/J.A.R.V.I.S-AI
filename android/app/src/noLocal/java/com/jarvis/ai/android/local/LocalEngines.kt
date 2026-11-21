package com.jarvis.ai.android.local

import android.content.Context
import com.jarvis.ai.ai.local.LocalEngine

/**
 * The default build has no on-device engine: installed models are stored and verified, but nothing runs them, and the app says so.
 * Turn the engine on with `enableLiteRt=true` (see docs/ai/LOCAL_MODELS.md), which swaps this file for the one in src/litert.
 */
object LocalEngines {
    const val AVAILABLE = false

    fun create(context: Context): LocalEngine? = null
}
