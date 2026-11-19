package com.jarvis.ai.knowledge

import com.jarvis.ai.models.InstallTarget

/**
 * An offline library the person can download: a Wikipedia snapshot published by the Kiwix project, as one .zim file. They are
 * large, so nothing here downloads by itself; the download starts only when the person taps it.
 *
 * Kiwix publishes a new dated file every few months and removes old ones, so a name can stop working. Then the app says so and the
 * person can fetch any current .zim file from library.kiwix.org and import it instead.
 */
data class KnowledgePackSpec(
    val id: String,
    override val displayName: String,
    override val fileName: String,
    /** Rounded up from the size Kiwix lists. It is used for the free-space check and the on-screen estimate only. */
    val approxBytes: Long,
    val summary: String,
) : InstallTarget {
    override val downloadUrl: String get() = "${KnowledgePacks.DOWNLOAD_BASE}/$fileName"
    override val gated: Boolean get() = false
    override val hostName: String get() = "Kiwix"
    override val noun: String get() = "library file"

    /** What an answer says it came from. */
    val sourceName: String get() = KnowledgePacks.SOURCE_NAMES[id] ?: displayName
}

object KnowledgePacks {
    const val DOWNLOAD_BASE = "https://download.kiwix.org/zim/wikipedia"
    const val LIBRARY_URL = "https://library.kiwix.org/"
    const val LICENSE_NOTE = "Wikipedia text is available under the Creative Commons Attribution-ShareAlike licence (CC BY-SA). Answers say where they came from."

    val SIMPLE = KnowledgePackSpec(
        id = "simple",
        displayName = "Simple English Wikipedia (about 1 GB)",
        fileName = "wikipedia_en-simple_all_nopic_2026-09.zim",
        approxBytes = 1_100_000_000L,
        summary = "Every article of Simple English Wikipedia, without pictures. Written in short, plain sentences, so it reads well aloud.",
    )

    val TOP = KnowledgePackSpec(
        id = "top",
        displayName = "Top English Wikipedia articles (about 2.1 GB)",
        fileName = "wikipedia_en_top_nopic_2026-06.zim",
        approxBytes = 2_200_000_000L,
        summary = "The most-read articles of English Wikipedia, without pictures. Fewer topics than the full encyclopedia, with more detail on each.",
    )

    val all: List<KnowledgePackSpec> = listOf(SIMPLE, TOP)

    internal val SOURCE_NAMES = mapOf(
        SIMPLE.id to "Simple English Wikipedia (CC BY-SA)",
        TOP.id to "English Wikipedia (CC BY-SA)",
    )

    fun byId(id: String): KnowledgePackSpec? = all.firstOrNull { it.id == id }

    /** A file name worth trying to open as a library. The real check is reading its header. */
    fun looksLikeLibraryFile(name: String): Boolean = name.trim().endsWith(".zim", ignoreCase = true)
}
