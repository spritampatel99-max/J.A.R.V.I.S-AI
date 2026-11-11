package com.jarvis.ai.data

import android.content.Context
import android.util.AtomicFile
import com.jarvis.ai.core.common.TextStorage
import java.io.File
import java.io.IOException

/**
 * One small private file, written atomically: [AtomicFile] writes a temporary file and swaps it in, so a crash or a full disk while saving cannot
 * leave half a file behind. A failed save throws, which each store turns into its "not saved" flag; what is already in memory is never lost.
 */
class AtomicTextStorage(directory: File, fileName: String) : TextStorage {

    constructor(context: Context, fileName: String) : this(context.applicationContext.filesDir, fileName)

    private val file = AtomicFile(File(directory, fileName))

    override fun read(): String? = try {
        if (file.baseFile.exists()) String(file.readFully(), Charsets.UTF_8) else null
    } catch (e: IOException) {
        null
    }

    override fun write(text: String) {
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /** Removes the file. Used for the scratch files of the offline self-test. */
    fun delete() {
        file.delete()
    }
}
