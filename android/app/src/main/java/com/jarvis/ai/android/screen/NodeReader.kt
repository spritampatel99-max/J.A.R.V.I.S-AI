package com.jarvis.ai.android.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.ai.ai.tools.screen.ScreenPart
import com.jarvis.ai.ai.tools.screen.ScreenRow

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
    private const val MAX_ROWS = 40
    private const val MAX_ROW_NODES = 120

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
                    best = AccessibilityNodeInfo(node)
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

    /**
     * The distinct controls a person could press that carry [label]: visible nodes whose text, description or view name equals it
     * (ignoring case), or, only if none does and the label is at least three letters, contains it. A node that is not itself
     * pressable stands for its nearest pressable parent. Password boxes are never returned. The caller recycles the result.
     */
    fun pressables(root: AccessibilityNodeInfo, label: String): List<AccessibilityNodeInfo> {
        val wanted = label.trim()
        if (wanted.isEmpty()) return emptyList()
        val exact = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val partial = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val budget = intArrayOf(MAX_NODES)

        fun target(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isClickable) return AccessibilityNodeInfo(node)
            var parent = node.parent
            var hops = 0
            while (parent != null && hops < 4) {
                if (parent.isClickable) return parent
                val next = parent.parent
                recycle(parent)
                parent = next
                hops++
            }
            parent?.let { recycle(it) }
            return null
        }

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (budget[0] <= 0 || depth > MAX_DEPTH) return
            budget[0]--
            if (!node.isVisibleToUser) return
            if (!node.isPassword) {
                val texts = listOfNotNull(
                    node.text?.toString(),
                    node.contentDescription?.toString(),
                    node.viewIdResourceName?.substringAfterLast('/'),
                ).map { it.trim() }
                val isExact = texts.any { it.equals(wanted, ignoreCase = true) }
                val isPartial = !isExact && wanted.length >= 3 && texts.any { it.contains(wanted, ignoreCase = true) }
                if (isExact || isPartial) {
                    val t = target(node)
                    if (t != null) {
                        val bucket = if (isExact) exact else partial
                        if (bucket.keys.none { bucket[it] == t }) bucket[bucket.size] = t else recycle(t)
                    }
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
        val chosen = if (exact.isNotEmpty()) exact else partial
        (if (chosen === exact) partial else exact).values.forEach { recycle(it) }
        return chosen.values.toList()
    }

    /**
     * The message box of the window: the focused text box if there is one, otherwise the lowest visible one (a chat's compose field
     * sits at the bottom). Password boxes are never returned. The caller recycles the result.
     */
    fun messageBox(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
            if (focused.isEditable && !focused.isPassword && focused.isVisibleToUser) return focused
            recycle(focused)
        }
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MIN_VALUE
        val budget = intArrayOf(MAX_NODES)
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (budget[0] <= 0 || depth > MAX_DEPTH) return
            budget[0]--
            if (!node.isVisibleToUser) return
            if (node.isEditable && !node.isPassword) {
                node.getBoundsInScreen(rect)
                if (rect.top > bestTop) {
                    best?.let { recycle(it) }
                    best = AccessibilityNodeInfo(node)
                    bestTop = rect.top
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

    /**
     * The items of the window's main list, each with everything visible inside it (text, description, short view name) and the node
     * that stands for the item. Password and typing boxes are left out. The caller recycles every node in the result.
     */
    fun rowsWithNodes(root: AccessibilityNodeInfo): List<Pair<ScreenRow, AccessibilityNodeInfo>> {
        var container = biggestScrollable(root) ?: return emptyList()
        // Some lists wrap their items in a single holder; look one or two levels down for the real items.
        var hops = 0
        while (container.childCount == 1 && hops < 2) {
            val only = container.getChild(0) ?: break
            recycle(container)
            container = only
            hops++
        }
        val out = ArrayList<Pair<ScreenRow, AccessibilityNodeInfo>>()
        try {
            for (i in 0 until container.childCount) {
                if (out.size >= MAX_ROWS) break
                val item = container.getChild(i) ?: continue
                if (!item.isVisibleToUser) {
                    recycle(item)
                    continue
                }
                val parts = ArrayList<ScreenPart>()
                collectParts(item, 0, intArrayOf(MAX_ROW_NODES), parts)
                if (parts.isEmpty()) recycle(item) else out += ScreenRow(parts) to item
            }
        } finally {
            recycle(container)
        }
        return out
    }

    private fun collectParts(node: AccessibilityNodeInfo, depth: Int, budget: IntArray, out: MutableList<ScreenPart>) {
        if (budget[0] <= 0 || depth > MAX_DEPTH) return
        budget[0]--
        if (!node.isVisibleToUser || node.isPassword || node.isEditable) return
        val text = node.text?.toString()?.trim().orEmpty().take(MAX_LINE_CHARS)
        val description = node.contentDescription?.toString()?.trim().orEmpty().take(MAX_LINE_CHARS)
        val id = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        if (text.isNotEmpty() || description.isNotEmpty() || id.isNotEmpty()) out += ScreenPart(text, description, id)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectParts(child, depth + 1, budget, out)
            } finally {
                recycle(child)
            }
        }
    }

    /** What to press for a list item: the item itself if it can be pressed, else its first pressable part. The caller recycles the result. */
    fun clickTarget(item: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (item.isClickable) return AccessibilityNodeInfo(item)
        val budget = intArrayOf(MAX_ROW_NODES)
        fun find(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (budget[0] <= 0 || depth > MAX_DEPTH) return null
            budget[0]--
            if (!node.isVisibleToUser || node.isPassword || node.isEditable) return null
            if (depth > 0 && node.isClickable) return AccessibilityNodeInfo(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    find(child, depth + 1)?.let { return it }
                } finally {
                    recycle(child)
                }
            }
            return null
        }
        return find(item, 0) ?: AccessibilityNodeInfo(item)
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
