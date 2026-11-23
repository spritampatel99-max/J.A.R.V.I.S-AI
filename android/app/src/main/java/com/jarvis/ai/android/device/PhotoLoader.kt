package com.jarvis.ai.android.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import com.jarvis.ai.ai.providers.ImageAttachment
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns one photo the person chose in Android's photo picker into a small JPEG that can be sent to a cloud AI. The picker hands over only the
 * photo(s) the person selected, so the app needs no photo or storage permission and never sees the rest of the gallery (this is also what
 * Android's "selected photos only" access gives). The photo is shrunk to at most [MAX_SIDE] pixels on its long side, stripped of its location
 * and other metadata by the re-encode, held in memory only, and never saved by the app.
 */
object PhotoLoader {
    const val MAX_SIDE = 1280
    private const val MAX_BYTES = 1_500_000

    /** Null when the photo cannot be read or decoded. */
    suspend fun load(context: Context, uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
            try {
                var quality = 85
                var bytes: ByteArray
                do {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    bytes = out.toByteArray()
                    quality -= 15
                } while (bytes.size > MAX_BYTES && quality >= 40)
                if (bytes.size > MAX_BYTES) null else ImageAttachment("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
