package com.jarvis.ai.knowledge

import java.util.Locale

/**
 * Turns the HTML of an encyclopedia article into the plain sentences a person would hear. It is a small tolerant tokenizer, not a
 * browser: it never runs anything, ignores what it does not understand, and is bounded in how much it reads.
 *
 * Tables (info boxes and navigation boxes), scripts, styles, figures, citation superscripts and comments are dropped, so the first
 * paragraph that remains is the article's opening.
 */
object HtmlText {
    private const val MAX_INPUT_CHARS = 400_000
    private const val MIN_PARAGRAPH = 40

    private val SKIPPED = setOf("script", "style", "table", "sup", "figure", "noscript", "svg", "math", "form", "nav")
    private val VOID = setOf("br", "hr", "img", "meta", "link", "input", "wbr")

    /** Opening paragraphs joined by a blank line, at most about [maxChars] characters, cut at the end of a sentence. */
    fun lead(html: String, maxChars: Int = 700): String {
        val paragraphs = paragraphs(html)
        val out = StringBuilder()
        var used = 0
        for (p in paragraphs) {
            if (p.length < MIN_PARAGRAPH) continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(p)
            used++
            if (out.length >= maxChars || used >= 3) break
        }
        return cut(out.toString(), maxChars)
    }

    fun paragraphs(html: String): List<String> {
        val input = if (html.length > MAX_INPUT_CHARS) html.substring(0, MAX_INPUT_CHARS) else html
        val result = ArrayList<String>()
        val current = StringBuilder()
        var skipDepth = 0
        val skipStack = ArrayList<String>()
        var inParagraph = false
        var i = 0
        val n = input.length

        fun flush() {
            val text = clean(current.toString())
            current.setLength(0)
            if (text.isNotEmpty()) result += text
        }

        while (i < n) {
            val c = input[i]
            if (c != '<') {
                val next = input.indexOf('<', i).let { if (it < 0) n else it }
                if (skipDepth == 0 && inParagraph) current.append(input, i, next)
                i = next
                continue
            }
            if (input.startsWith("<!--", i)) {
                val end = input.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            val close = input.indexOf('>', i + 1)
            if (close < 0) break
            val raw = input.substring(i + 1, close)
            i = close + 1
            val closing = raw.startsWith("/")
            val body = raw.removePrefix("/").trim()
            val name = body.takeWhile { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
            if (name.isEmpty()) continue
            val selfClosing = body.endsWith("/") || name in VOID

            if (name in SKIPPED) {
                if (closing) {
                    // Pop back to the matching opener; an unbalanced closer is ignored.
                    val at = skipStack.lastIndexOf(name)
                    if (at >= 0) {
                        while (skipStack.size > at) skipStack.removeAt(skipStack.size - 1)
                        skipDepth = skipStack.size
                    }
                } else if (!selfClosing) {
                    skipStack += name
                    skipDepth = skipStack.size
                    if (skipStack.size > 200) return result
                }
                continue
            }
            if (skipDepth > 0) continue
            when {
                name == "p" && !closing -> {
                    if (inParagraph) flush()
                    inParagraph = true
                }
                name == "p" && closing -> {
                    if (inParagraph) flush()
                    inParagraph = false
                }
                name == "br" && inParagraph -> current.append(' ')
                (name == "h1" || name == "h2" || name == "h3" || name == "div" || name == "section" || name == "ul" || name == "ol") && inParagraph -> {
                    flush()
                    inParagraph = false
                }
            }
            if (result.size >= 60) break
        }
        if (inParagraph) flush()
        return result
    }

    private val CITATION = Regex("""\[(?:\d{1,3}|[a-z]|citation needed|note \d{1,3}|nb \d{1,3})]""", RegexOption.IGNORE_CASE)

    private fun clean(raw: String): String {
        var s = decode(raw)
        s = CITATION.replace(s, "")
        s = s.replace(' ', ' ')
        s = s.replace(Regex("""\s+"""), " ").trim()
        s = s.replace(" ,", ",").replace(" .", ".").replace("( ", "(").replace(" )", ")")
        // Empty brackets left by a removed pronunciation or date.
        s = s.replace(Regex("""\(\s*[;,]?\s*\)"""), "").replace(Regex("""\s+"""), " ").trim()
        return s
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "-", "mdash" to " - ",
        "hellip" to "...", "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"", "times" to "x", "deg" to " degrees",
        "eacute" to "e", "egrave" to "e", "agrave" to "a", "aacute" to "a", "ouml" to "o", "uuml" to "u", "auml" to "a", "ntilde" to "n",
        "ccedil" to "c", "shy" to "", "thinsp" to " ", "ensp" to " ", "emsp" to " ", "middot" to ".", "bull" to "-", "copy" to "(c)",
    )

    private fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = text.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                out.append(c)
                i++
                continue
            }
            val entity = text.substring(i + 1, semi)
            val value: String? = when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let { codePoint(it) }
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { codePoint(it) }
                else -> NAMED[entity]
            }
            if (value == null) {
                out.append(c)
                i++
            } else {
                out.append(value)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null

    /** Shortens [text] to at most [max] characters, at the end of a sentence when there is one in the second half, otherwise at a word. */
    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val window = text.substring(0, max)
        val sentence = window.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (sentence > max / 2) return window.substring(0, sentence + 1)
        val space = window.lastIndexOf(' ')
        return (if (space > max / 2) window.substring(0, space) else window).trimEnd(',', ';', ':', ' ') + "..."
    }
}
