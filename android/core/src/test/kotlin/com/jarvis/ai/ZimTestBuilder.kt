package com.jarvis.ai

import java.io.ByteArrayOutputStream

/** Builds small, valid ZIM archives for tests, following the openZIM layout. Not shipped. */
class ZimTestBuilder(
    private val extendedOffsets: Boolean = false,
    /** When set, every cluster is stored with compression type 5 (zstd) and this function "compresses" its payload. */
    private val compress: ((ByteArray) -> ByteArray)? = null,
    private val blobsPerCluster: Int = 2,
) {
    private class Item(val ns: Char, val url: String, val title: String, val mime: String, val content: ByteArray?, val redirectUrl: String?)

    private val items = ArrayList<Item>()

    fun article(url: String, html: String, title: String = url.replace('_', ' '), ns: Char = 'C', mime: String = "text/html") = apply {
        items += Item(ns, url, title, mime, html.toByteArray(Charsets.UTF_8), null)
    }

    fun metadata(name: String, value: String) = apply {
        items += Item('M', name, name, "text/plain", value.toByteArray(Charsets.UTF_8), null)
    }

    fun redirect(url: String, target: String, ns: Char = 'C') = apply {
        items += Item(ns, url, url.replace('_', ' '), "", null, target)
    }

    fun build(): ByteArray {
        val sorted = items.sortedWith(compareBy<Item>({ it.ns }, { String(it.url.toByteArray(Charsets.ISO_8859_1), Charsets.ISO_8859_1) }))
        val mimes = sorted.filter { it.content != null }.map { it.mime }.distinct()
        val indexOf = sorted.withIndex().associate { (i, item) -> (item.ns to item.url) to i }

        // Clusters: blobs are numbered within their cluster.
        val articles = sorted.filter { it.content != null }
        val clusterOf = HashMap<Item, Pair<Int, Int>>()
        val clusters = ArrayList<ByteArray>()
        var i = 0
        var clusterNumber = 0
        while (i < articles.size) {
            val group = articles.subList(i, minOf(articles.size, i + blobsPerCluster))
            group.forEachIndexed { b, item -> clusterOf[item] = clusterNumber to b }
            clusters += cluster(group.map { it.content!! })
            i += blobsPerCluster
            clusterNumber++
        }

        val mimeBytes = ByteArrayOutputStream().also { out ->
            for (m in mimes) {
                out.write(m.toByteArray())
                out.write(0)
            }
            out.write(0)
        }.toByteArray()

        val dirEntries = sorted.map { item ->
            val out = ByteArrayOutputStream()
            if (item.content == null) {
                u16(out, 0xFFFF)
                out.write(0)
                out.write(item.ns.code)
                u32(out, 0)
                u32(out, (indexOf[item.ns to item.redirectUrl!!] ?: error("no redirect target ${item.redirectUrl}")).toLong())
            } else {
                val (c, b) = clusterOf.getValue(item)
                u16(out, mimes.indexOf(item.mime))
                out.write(0)
                out.write(item.ns.code)
                u32(out, 0)
                u32(out, c.toLong())
                u32(out, b.toLong())
            }
            out.write(item.url.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.write(if (item.title == item.url) ByteArray(0) else item.title.toByteArray(Charsets.UTF_8))
            out.write(0)
            out.toByteArray()
        }

        val n = sorted.size
        val mimePos = 80L
        val urlPtrPos = mimePos + mimeBytes.size
        val titlePtrPos = urlPtrPos + 8L * n
        val clusterPtrPos = titlePtrPos + 4L * n
        val entriesPos = clusterPtrPos + 8L * clusters.size
        val entryOffsets = ArrayList<Long>()
        var at = entriesPos
        for (e in dirEntries) {
            entryOffsets += at
            at += e.size
        }
        val clustersPos = at
        val clusterOffsets = ArrayList<Long>()
        at = clustersPos
        for (c in clusters) {
            clusterOffsets += at
            at += c.size
        }
        val checksumPos = at

        val out = ByteArrayOutputStream()
        u32(out, 72173914L)
        u16(out, 6)
        u16(out, 1)
        out.write(ByteArray(16))
        u32(out, n.toLong())
        u32(out, clusters.size.toLong())
        u64(out, urlPtrPos)
        u64(out, titlePtrPos)
        u64(out, clusterPtrPos)
        u64(out, mimePos)
        u32(out, 0xFFFFFFFFL)
        u32(out, 0xFFFFFFFFL)
        u64(out, checksumPos)
        check(out.size() == 80)
        out.write(mimeBytes)
        for (o in entryOffsets) u64(out, o)
        val byTitle = sorted.indices.sortedBy { sorted[it].title }
        for (idx in byTitle) u32(out, idx.toLong())
        for (o in clusterOffsets) u64(out, o)
        for (e in dirEntries) out.write(e)
        for (c in clusters) out.write(c)
        out.write(ByteArray(16))
        return out.toByteArray()
    }

    /** Where cluster [number] starts, found by scanning the built bytes' header. */
    companion object {
        fun clusterStart(zim: ByteArray, number: Int): Int {
            val clusterPtrPos = readU64(zim, 48).toInt()
            return readU64(zim, clusterPtrPos + 8 * number).toInt()
        }

        fun readU64(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        /** A fake "compression" for tests: XOR with a constant. Its decoder is [xorDecoder]. */
        val xorCompress: (ByteArray) -> ByteArray = { bytes -> ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() } }
    }

    private fun cluster(blobs: List<ByteArray>): ByteArray {
        val width = if (extendedOffsets) 8 else 4
        val payload = ByteArrayOutputStream()
        var offset = (blobs.size + 1) * width.toLong()
        val offsets = ArrayList<Long>()
        offsets += offset
        for (b in blobs) {
            offset += b.size
            offsets += offset
        }
        for (o in offsets) if (width == 8) u64(payload, o) else u32(payload, o)
        for (b in blobs) payload.write(b)
        val body = payload.toByteArray()
        val out = ByteArrayOutputStream()
        val info = (if (compress != null) 5 else 1) or (if (extendedOffsets) 0x10 else 0)
        out.write(info)
        out.write(compress?.invoke(body) ?: body)
        return out.toByteArray()
    }

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun u32(out: ByteArrayOutputStream, v: Long) {
        for (i in 0..3) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun u64(out: ByteArrayOutputStream, v: Long) {
        for (i in 0..7) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }
}
