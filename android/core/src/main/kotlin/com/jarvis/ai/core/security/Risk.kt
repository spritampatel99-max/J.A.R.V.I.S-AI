package com.jarvis.ai.core.security

/**
 * How dangerous a tool is. The order matters (LOW < MEDIUM < HIGH): the executor asks for
 * confirmation when `tool.risk >= policy.confirmAt`. HIGH is the top of the scale, so a
 * HIGH tool can never be configured to run without confirmation.
 *
 * The risk of a tool is declared by the tool in code. It is never taken from model output.
 */
enum class Risk { LOW, MEDIUM, HIGH }
