package com.jarvis.ai.android.knowledge

import com.github.luben.zstd.ZstdInputStream
import com.jarvis.ai.knowledge.ClusterCompression
import com.jarvis.ai.knowledge.ClusterDecoder
import com.jarvis.ai.knowledge.ZimFormatException
import com.jarvis.ai.knowledge.ZimUnsupportedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.tukaani.xz.XZInputStream

/** Decompresses the content blocks of an offline library: zstd for current Kiwix files, xz for older ones. */
class AndroidClusterDecoder : ClusterDecoder {

    override fun decode(method: ClusterCompression, compressed: ByteArray, maxOutput: Int): ByteArray = when (method) {
        ClusterCompression.NONE -> compressed
        ClusterCompression.ZSTD -> inflate(maxOutput) { ZstdInputStream(ByteArrayInputStream(compressed)) }
        ClusterCompression.XZ -> inflate(maxOutput) { XZInputStream(ByteArrayInputStream(compressed)) }
    }

    private fun inflate(maxOutput: Int, open: () -> InputStream): ByteArray {
        try {
            open().use { input ->
                val out = ByteArrayOutputStream(minOf(maxOutput, 1 shl 20))
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxOutput) throw ZimFormatException("A block of content is larger than this reader allows.")
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: ZimFormatException) {
            throw e
        } catch (e: IOException) {
            throw ZimFormatException("A block of content is damaged.")
        } catch (e: UnsatisfiedLinkError) {
            throw ZimUnsupportedException("This phone could not load the zstd decoder the library needs.")
        } catch (e: NoClassDefFoundError) {
            throw ZimUnsupportedException("This phone could not load the decoder the library needs.")
        } catch (e: OutOfMemoryError) {
            throw ZimFormatException("A block of content was too large for this phone's memory.")
        }
    }
}
