package com.jarvis.ai.data

import android.content.Context
import android.util.AtomicFile
import com.jarvis.ai.models.InstalledModelsStorage
import java.io.File
import java.io.IOException

/**
 * Keeps the list of installed models in a private file. [AtomicFile] swaps the new content in, so a crash while saving
 * cannot corrupt the list. The file holds file names and sizes only: no keys, no personal data.
 */
class InstalledModelsFileStorage(context: Context) : InstalledModelsStorage {

    private val file = AtomicFile(File(context.applicationContext.filesDir, "installed_models.json"))

    override fun read(): String? = try {
        if (file.baseFile.exists()) String(file.readFully(), Charsets.UTF_8) else null
    } catch (e: IOException) {
        null
    }

    override fun write(text: String): Boolean {
        val stream = try {
            file.startWrite()
        } catch (e: IOException) {
            return false
        }
        return try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (e: IOException) {
            file.failWrite(stream)
            false
        }
    }
}
