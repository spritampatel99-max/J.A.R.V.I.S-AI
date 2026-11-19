package com.jarvis.ai.android.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Turns one window's accessibility tree into the lines a person would read, top to bottom.
 *
 * It leaves out everything that could expose something the person did not mean to have read aloud: password fields, any
 * text box the person can type in (a draft or a code they are entering), and anything not actually visible. It is bounded
 * in nodes, depth and lines so a huge or cyclic page cannot stall the phone.
 */
internal object NodeReader {
    private const val MAX_NODES = 2_000
    private const val MAX_DEPTH = 40
    private const val MAX_LINES = 150
    private const val MAX_LINE_CHARS = 300

    fun lines(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        val budget = intArrayOf(MAX_NODES)
        walk(root, 0, budget, out)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, budget: IntArray, out: MutableList<String>) {
        if (budget[0] <= 0 || depth > MAX_DEPTH || out.size >= MAX_LINES) return
        budget[0]--
        if (!node.isVisibleToUser) return
        // A password or editable field is skipped together with everything under it.
        if (node.isPassword || node.isEditable) return

        val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && out.lastOrNull() != text.take(MAX_LINE_CHARS)) out += text.take(MAX_LINE_CHARS)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                walk(child, depth + 1, budget, out)
            } finally {
                recycle(child)
            }
            if (budget[0] <= 0 || out.size >= MAX_LINES) return
        }
    }

    /** The largest visible node that can scroll, which is the list or page the person is looking at. */
    fun biggestScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        val budget = intArrayOf(MAX_NODES)
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (budget[0] <= 0 || depth > MAX_DEPTH) return
            budget[0]--
            if (!node.isVisibleToUser) return
            if (node.isScrollable) {
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height().toLong()
                if (area > bestArea) {
                    best?.let { recycle(it) }
                    best = AccessibilityNodeInfo.obtain(node)
                    bestArea = area
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    recycle(child)
                }
            }
        }

        visit(root, 0)
        return best
    }

    @Suppress("DEPRECATION")
    fun recycle(node: AccessibilityNodeInfo) {
        // A no-op from Android 13; on Android 11 and 12 it returns the node to the pool.
        try {
            node.recycle()
        } catch (_: IllegalStateException) {
            // Already recycled; nothing to do.
        }
    }
}
