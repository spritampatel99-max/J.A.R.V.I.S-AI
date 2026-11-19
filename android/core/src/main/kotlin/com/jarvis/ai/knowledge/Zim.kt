package com.jarvis.ai.knowledge

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** The file is not a ZIM archive, is damaged, or is cut short. [message] is safe to show. */
class ZimFormatException(message: String) : Exception(message)

/** A compression the archive uses but the phone has no decoder for. */
class ZimUnsupportedException(message: String) : Exception(message)

/** Random access to the bytes of an archive. Reads must be thread-safe. */
interface ByteSource : Closeable {
    val size: Long

    /** Exactly [length] bytes from [position], or fewer only when the file ends first. */
    fun read(position: Long, length: Int): ByteArray
}

class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || position >= bytes.size || length <= 0) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), position + length).toInt()
        return bytes.copyOfRange(position.toInt(), end)
    }

    override fun close() = Unit
}

class FileSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    private val lock = Any()
    override val size: Long = raf.length()

    override fun read(position: Long, length: Int): ByteArray = synchronized(lock) {
        if (position < 0 || position >= size || length <= 0) return ByteArray(0)
        val n = minOf(length.toLong(), size - position).toInt()
        val out = ByteArray(n)
        raf.seek(position)
        raf.readFully(out)
        out
    }

    override fun close() = raf.close()
}

enum class ClusterCompression { NONE, XZ, ZSTD }

/** Turns a compressed cluster back into bytes. The Android build supplies zstd and xz; a build without them reads only uncompressed archives. */
fun interface ClusterDecoder {
    /** @throws ZimUnsupportedException when [method] is not available. Output beyond [maxOutput] bytes must be refused. */
    fun decode(method: ClusterCompression, compressed: ByteArray, maxOutput: Int): ByteArray
}

object NoDecoder : ClusterDecoder {
    override fun decode(method: ClusterCompression, compressed: ByteArray, maxOutput: Int): ByteArray =
        throw ZimUnsupportedException("This archive is compressed with ${method.name.lowercase()}, which this build cannot read.")
}

data class ZimHeader(
    val major: Int,
    val minor: Int,
    val entryCount: Long,
    val clusterCount: Long,
    val urlPtrPos: Long,
    val titlePtrPos: Long,
    val clusterPtrPos: Long,
    val mimeListPos: Long,
    val mainPage: Long?,
    val checksumPos: Long,
) {
    /** The checksum is the last 16 bytes of every complete archive, so this is the exact size of a finished file. */
    val expectedSize: Long get() = checksumPos + CHECKSUM_BYTES

    companion object {
        const val SIZE = 80
        const val MAGIC = 72173914L
        const val CHECKSUM_BYTES = 16L
    }
}

enum class EntryKind { ARTICLE, REDIRECT, OTHER }

data class ZimEntry(
    val index: Long,
    val namespace: Char,
    val url: String,
    val title: String,
    val kind: EntryKind,
    val mimeIndex: Int,
    val cluster: Long,
    val blob: Long,
    val redirectIndex: Long,
)

/**
 * Reads an openZIM archive (the format Kiwix uses for offline Wikipedia) without loading it: only the few bytes a lookup needs
 * are read from the file. It reads and never writes.
 *
 * Everything in the file is treated as untrusted input: every offset, count and size is bounds-checked before use, redirect
 * chains are limited, and no cluster larger than [MAX_CLUSTER_BYTES] is decoded, so a damaged or hostile file produces an
 * error instead of a crash or an out-of-memory.
 */
class ZimArchive private constructor(
    private val source: ByteSource,
    private val decoder: ClusterDecoder,
    val header: ZimHeader,
    private val mimeTypes: List<String>,
) : Closeable {

    /** False for a download that stopped early: the file is shorter than the archive says it should be. */
    val isComplete: Boolean get() = source.size == header.expectedSize

    val entryCount: Long get() = header.entryCount

    fun mimeType(entry: ZimEntry): String? = mimeTypes.getOrNull(entry.mimeIndex)

    fun entry(index: Long): ZimEntry {
        if (index < 0 || index >= header.entryCount) throw ZimFormatException("The archive points at a missing entry.")
        val pointer = readU64(header.urlPtrPos + 8 * index)
        if (pointer < 0 || pointer >= source.size) throw ZimFormatException("The archive points outside itself.")
        val bytes = source.read(pointer, ENTRY_READ)
        if (bytes.size < 12) throw ZimFormatException("An entry is cut short.")
        val mime = u16(bytes, 0)
        val namespace = (bytes[3].toInt() and 0xFF).toChar()
        val fixed: Int
        var cluster = 0L
        var blob = 0L
        var redirect = 0L
        val kind: EntryKind
        when (mime) {
            0xFFFF -> {
                fixed = 12
                redirect = u32(bytes, 8)
                kind = EntryKind.REDIRECT
            }
            0xFFFE, 0xFFFD -> {
                fixed = 16
                kind = EntryKind.OTHER
            }
            else -> {
                fixed = 16
                if (bytes.size < 16) throw ZimFormatException("An entry is cut short.")
                cluster = u32(bytes, 8)
                blob = u32(bytes, 12)
                kind = EntryKind.ARTICLE
            }
        }
        val urlEnd = indexOfZero(bytes, fixed)
        if (urlEnd < 0) throw ZimFormatException("An entry name is too long.")
        val titleEnd = indexOfZero(bytes, urlEnd + 1)
        if (titleEnd < 0) throw ZimFormatException("An entry name is too long.")
        val url = String(bytes, fixed, urlEnd - fixed, Charsets.UTF_8)
        val rawTitle = String(bytes, urlEnd + 1, titleEnd - urlEnd - 1, Charsets.UTF_8)
        return ZimEntry(index, namespace, url, rawTitle.ifEmpty { url }, kind, mime, cluster, blob, redirect)
    }

    /** The entry whose address is exactly [namespace]/[url], or null. The address list is sorted, so this is a binary search. */
    fun find(namespace: Char, url: String): ZimEntry? {
        val index = lowerBound(namespace, url.toByteArray(Charsets.UTF_8))
        if (index >= header.entryCount) return null
        val found = entry(index)
        return if (found.namespace == namespace && found.url == url) found else null
    }

    /** Up to [limit] entries in [namespace] whose address starts with [prefix], in order. */
    fun withPrefix(namespace: Char, prefix: String, limit: Int): List<ZimEntry> {
        val out = ArrayList<ZimEntry>()
        var i = lowerBound(namespace, prefix.toByteArray(Charsets.UTF_8))
        while (i < header.entryCount && out.size < limit) {
            val e = entry(i)
            if (e.namespace != namespace || !e.url.startsWith(prefix)) break
            out += e
            i++
        }
        return out
    }

    /** Follows redirects to the real entry. Null for a loop or a chain that is too long. */
    fun resolve(start: ZimEntry): ZimEntry? {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            if (current.kind != EntryKind.REDIRECT) return current
            current = entry(current.redirectIndex)
        }
        return null
    }

    fun mainPage(): ZimEntry? = header.mainPage?.let { resolve(entry(it)) }

    /** A metadata value such as Title, Language or Description. */
    fun metadata(name: String): String? {
        val e = find('M', name) ?: return null
        val real = resolve(e) ?: return null
        if (real.kind != EntryKind.ARTICLE) return null
        return String(read(real), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    /** The bytes of an article entry (usually HTML). */
    fun read(entry: ZimEntry): ByteArray {
        if (entry.kind != EntryKind.ARTICLE) throw ZimFormatException("That entry has no content.")
        if (entry.cluster >= header.clusterCount) throw ZimFormatException("The archive points at a missing block of content.")
        val start = readU64(header.clusterPtrPos + 8 * entry.cluster)
        val end = if (entry.cluster + 1 < header.clusterCount) readU64(header.clusterPtrPos + 8 * (entry.cluster + 1)) else header.checksumPos
        if (start < 0 || end <= start || end > source.size) throw ZimFormatException("A block of content is damaged.")
        val length = end - start
        if (length > MAX_CLUSTER_BYTES) throw ZimFormatException("A block of content is larger than this reader allows.")
        val raw = source.read(start, length.toInt())
        if (raw.size.toLong() != length) throw ZimFormatException("The archive is cut short.")

        val info = raw[0].toInt() and 0xFF
        val extended = (info and 0x10) != 0
        val body = raw.copyOfRange(1, raw.size)
        val data = when (info and 0x0F) {
            0, 1 -> body
            4 -> decoder.decode(ClusterCompression.XZ, body, MAX_CLUSTER_BYTES)
            5 -> decoder.decode(ClusterCompression.ZSTD, body, MAX_CLUSTER_BYTES)
            else -> throw ZimUnsupportedException("This archive uses a compression this reader does not know.")
        }
        if (data.size > MAX_CLUSTER_BYTES) throw ZimFormatException("A block of content is larger than this reader allows.")
        return blob(data, entry.blob, if (extended) 8 else 4)
    }

    private fun blob(data: ByteArray, number: Long, width: Int): ByteArray {
        if (data.size < width) throw ZimFormatException("A block of content is damaged.")
        val first = offset(data, 0, width)
        if (first < width || first % width != 0L || first > data.size) throw ZimFormatException("A block of content is damaged.")
        val blobCount = first / width - 1
        if (number < 0 || number >= blobCount) throw ZimFormatException("The archive points at a missing item.")
        val from = offset(data, (number * width).toInt(), width)
        val to = offset(data, ((number + 1) * width).toInt(), width)
        if (from < first || to < from || to > data.size) throw ZimFormatException("A block of content is damaged.")
        return data.copyOfRange(from.toInt(), to.toInt())
    }

    private fun offset(data: ByteArray, at: Int, width: Int): Long = if (width == 8) {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        if (v < 0) throw ZimFormatException("A block of content is damaged.")
        v
    } else {
        u32(data, at)
    }

    /** The first index whose (namespace, url) is not smaller than the key. */
    private fun lowerBound(namespace: Char, url: ByteArray): Long {
        var lo = 0L
        var hi = header.entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val e = entry(mid)
            if (compare(e.namespace, e.url.toByteArray(Charsets.UTF_8), namespace, url) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compare(nsA: Char, a: ByteArray, nsB: Char, b: ByteArray): Int {
        if (nsA != nsB) return nsA.compareTo(nsB)
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private fun readU64(position: Long): Long {
        val b = source.read(position, 8)
        if (b.size < 8) throw ZimFormatException("The archive is cut short.")
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    override fun close() = source.close()

    companion object {
        const val MAX_CLUSTER_BYTES = 48 * 1024 * 1024
        private const val MAX_REDIRECTS = 8
        private const val ENTRY_READ = 2048
        private const val MAX_ENTRIES = 200_000_000L
        private const val MAX_MIME_BYTES = 64 * 1024

        /**
         * The size a complete archive must have, read from the header alone, or null when [source] does not start like an archive.
         * It works on a download that is still partly there, which is how an unfinished file is told from a damaged one.
         */
        fun declaredSize(source: ByteSource): Long? {
            val raw = source.read(0, ZimHeader.SIZE)
            if (raw.size < ZimHeader.SIZE || u32(raw, 0) != ZimHeader.MAGIC) return null
            if (u16(raw, 4) !in 5..7) return null
            val checksumPos = u64(raw, 72)
            return if (checksumPos in ZimHeader.SIZE..MAX_FILE_BYTES) checksumPos + ZimHeader.CHECKSUM_BYTES else null
        }

        private const val MAX_FILE_BYTES = 1L shl 41

        /** Opens the archive, or throws [ZimFormatException] saying why it cannot be used. An incomplete download opens, with [isComplete] false. */
        fun open(source: ByteSource, decoder: ClusterDecoder = NoDecoder): ZimArchive {
            val raw = source.read(0, ZimHeader.SIZE)
            if (raw.size < ZimHeader.SIZE) throw ZimFormatException("This file is too small to be an offline library.")
            if (u32(raw, 0) != ZimHeader.MAGIC) throw ZimFormatException("This is not an offline library file (.zim).")
            val major = u16(raw, 4)
            val minor = u16(raw, 6)
            if (major !in 5..7) throw ZimFormatException("This library file is version $major.$minor, which this reader does not support.")
            val mainRaw = u32(raw, 64)
            val header = ZimHeader(
                major = major,
                minor = minor,
                entryCount = u32(raw, 24),
                clusterCount = u32(raw, 28),
                urlPtrPos = u64(raw, 32),
                titlePtrPos = u64(raw, 40),
                clusterPtrPos = u64(raw, 48),
                mimeListPos = u64(raw, 56),
                mainPage = if (mainRaw == 0xFFFFFFFFL) null else mainRaw,
                checksumPos = u64(raw, 72),
            )
            val size = source.size
            val sane = header.entryCount in 1..MAX_ENTRIES &&
                header.clusterCount in 1..MAX_ENTRIES &&
                header.urlPtrPos in ZimHeader.SIZE..size &&
                header.clusterPtrPos in ZimHeader.SIZE..size &&
                header.mimeListPos in ZimHeader.SIZE..size &&
                header.checksumPos in ZimHeader.SIZE..(size + ZimHeader.CHECKSUM_BYTES) &&
                header.urlPtrPos + 8 * header.entryCount <= size &&
                header.clusterPtrPos + 8 * header.clusterCount <= size
            if (!sane) {
                // A download that stopped early has a real header but its tables are not all there yet.
                throw ZimFormatException("The library file is incomplete or damaged.")
            }
            val mime = readMimeTypes(source, header.mimeListPos)
            if (header.mainPage != null && header.mainPage >= header.entryCount) {
                throw ZimFormatException("The library file is damaged.")
            }
            return ZimArchive(source, decoder, header, mime)
        }

        private fun readMimeTypes(source: ByteSource, position: Long): List<String> {
            val bytes = source.read(position, MAX_MIME_BYTES)
            val out = ArrayList<String>()
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i].toInt() != 0) continue
                if (i == start) return out
                out += String(bytes, start, i - start, Charsets.UTF_8)
                start = i + 1
                if (out.size > 4096) break
            }
            throw ZimFormatException("The library file's type list is damaged.")
        }

        private fun indexOfZero(bytes: ByteArray, from: Int): Int {
            for (i in from until bytes.size) if (bytes[i].toInt() == 0) return i
            return -1
        }

        internal fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        internal fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        internal fun u64(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return if (v < 0) Long.MAX_VALUE else v
        }
    }
}

/** Opens [file] as an archive, closing it again if it is not usable. */
fun openZim(file: File, decoder: ClusterDecoder): ZimArchive {
    val source = try {
        FileSource(file)
    } catch (e: IOException) {
        throw ZimFormatException("The library file could not be opened.")
    }
    return try {
        ZimArchive.open(source, decoder)
    } catch (e: Exception) {
        source.close()
        throw e
    }
}

/** What a library file on disk turned out to be. */
sealed interface LibraryCheck {
    /** Complete, readable, and a block of content decoded. [title] is the library's own name for itself, when it has one. */
    data class Ready(val title: String?, val bytes: Long) : LibraryCheck

    /** A genuine library file that has not been fully received yet. */
    data class Incomplete(val have: Long, val expected: Long) : LibraryCheck

    /** Not usable. [reason] is safe to show. */
    data class Invalid(val reason: String) : LibraryCheck
}

object LibraryChecker {
    /** Blocking: reads from disk. It opens the file, decodes one block of content, and closes it again. */
    fun check(file: File, decoder: ClusterDecoder): LibraryCheck {
        val source = try {
            FileSource(file)
        } catch (e: IOException) {
            return LibraryCheck.Invalid("The library file could not be opened.")
        }
        source.use {
            val declared = ZimArchive.declaredSize(source)
                ?: return LibraryCheck.Invalid(
                    if (source.size < ZimHeader.SIZE) "This file is too small to be an offline library." else "This is not an offline library file (.zim).",
                )
            if (source.size < declared) return LibraryCheck.Incomplete(source.size, declared)
            if (source.size > declared) return LibraryCheck.Invalid("This library file is larger than it says it is, so it is damaged.")
            return try {
                val archive = ZimArchive.open(source, decoder)
                // Reading one value proves the block table and the decompression path work on this phone.
                val title = archive.metadata("Title")
                LibraryCheck.Ready(title, source.size)
            } catch (e: ZimFormatException) {
                LibraryCheck.Invalid(e.message ?: "The library file is damaged.")
            } catch (e: ZimUnsupportedException) {
                LibraryCheck.Invalid(e.message ?: "This library uses a format this build cannot read.")
            } catch (e: IOException) {
                LibraryCheck.Invalid("The library file could not be read.")
            }
        }
    }
}
