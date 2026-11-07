package com.jarvis.ai.core.common

/** Wall-clock access behind an interface so time-dependent logic can be tested deterministically. */
interface TimeSource {
    fun nowMs(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}
