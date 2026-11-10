package com.jarvis.ai.data

import android.content.Context
import android.util.AtomicFile
import com.jarvis.ai.history.HistoryStorage
import java.io.File
import java.io.IOException

/**
 * Keeps the conversation history in a private file that only this app can read. [AtomicFile] writes a temporary file and
 * swaps it in, so a crash or a full disk while saving cannot leave half a history behind. A failed save throws, which the
 * store turns into its "not saved" flag; it never loses what is already in memory.
 */
class HistoryFileStorage(context: Context) : HistoryStorage {

    private val file = AtomicFile(File(context.applicationContext.filesDir, "history.json"))

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
}
