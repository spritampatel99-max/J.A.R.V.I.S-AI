package com.jarvis.ai.core.permissions

/**
 * Answers "which of these permissions does the app NOT currently hold?".
 * The Android implementation lives in :app; this module only needs the question.
 */
interface PermissionChecker {
    fun missing(required: Set<String>): Set<String>
}
