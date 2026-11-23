package com.jarvis.ai.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put

/** A version like `3.0.1` or `v0.1.0-dev`. A name after a dash (dev, beta, rc) ranks below the same number without one. */
data class AppVersion(val parts: List<Int>, val pre: String?) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return when {
            pre == null && other.pre == null -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    override fun toString(): String = parts.joinToString(".") + (pre?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,6}(?:\.\d{1,6}){0,3})(?:-([0-9A-Za-z.]{1,30}))?(?:\+.*)?$""")

        /** Null when the text is not a version, so a strange tag is ignored instead of being guessed at. */
        fun parse(text: String): AppVersion? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            return AppVersion(m.groupValues[1].split('.').map { it.toInt() }, m.groupValues[2].ifEmpty { null })
        }
    }
}

/** What the app learns about a published release. [notes] is the publisher's text: outside content, shown but never obeyed. */
data class ReleaseInfo(
    val version: AppVersion,
    val tag: String,
    val pageUrl: String,
    val apkUrl: String?,
    val notes: String,
)

object ReleaseParser {
    private const val ALLOWED_PREFIX = "https://github.com/"
    private const val MAX_NOTES = 1_200

    /** Reads GitHub's "latest release" answer. Drafts, pre-releases, odd tags and links that do not point at github.com give null. */
    fun parse(json: String): ReleaseInfo? {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (root["draft"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
        if (root["prerelease"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) return null
        val tag = text(root, "tag_name") ?: return null
        val version = AppVersion.parse(tag) ?: return null
        val page = text(root, "html_url")?.takeIf { it.startsWith(ALLOWED_PREFIX) } ?: return null
        val apk = (root["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (text(it, "name") ?: "").endsWith(".apk", ignoreCase = true) }
            ?.let { text(it, "browser_download_url") }
            ?.takeIf { it.startsWith(ALLOWED_PREFIX) }
        val notes = (text(root, "body") ?: "").trim().take(MAX_NOTES)
        return ReleaseInfo(version, tag.take(40), page, apk, notes)
    }

    private fun text(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** Keeps the last release found, in GitHub's own shape, so reading it back goes through the same checks as a fresh answer. */
object ReleaseStore {
    fun encode(info: ReleaseInfo): String = kotlinx.serialization.json.buildJsonObject {
        put("tag_name", info.tag)
        put("html_url", info.pageUrl)
        put("body", info.notes)
        put(
            "assets",
            kotlinx.serialization.json.buildJsonArray {
                info.apkUrl?.let { url ->
                    add(kotlinx.serialization.json.buildJsonObject { put("name", "jarvis.apk"); put("browser_download_url", url) })
                }
            },
        )
    }.toString()

    fun decode(json: String?): ReleaseInfo? = json?.takeIf { it.isNotBlank() }?.let { ReleaseParser.parse(it) }
}

/** When to look for a new version. The check is a read of the public release page; nothing is downloaded or installed by it. */
object UpdateRules {
    const val EVERY_DAYS = 10
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun checkDue(lastCheckMs: Long?, nowMs: Long, everyDays: Int = EVERY_DAYS): Boolean =
        lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= everyDays * DAY_MS

    /** The release only counts when it is strictly newer than [current]. A current version that cannot be read never triggers an update. */
    fun newer(current: String, release: ReleaseInfo?): ReleaseInfo? {
        val have = AppVersion.parse(current) ?: return null
        return release?.takeIf { it.version > have }
    }
}
