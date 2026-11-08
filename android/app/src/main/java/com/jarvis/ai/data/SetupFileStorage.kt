package com.jarvis.ai.data

import android.content.Context
import android.util.AtomicFile
import com.jarvis.ai.setup.SetupStorage
import java.io.File
import java.io.IOException

/**
 * Keeps the wizard state in a private file. [AtomicFile] writes to a temporary file and swaps it in, so a crash or a
 * full disk while saving cannot leave a half-written state behind. The file contains no keys or personal data.
 */
class SetupFileStorage(context: Context) : SetupStorage {

    private val file = AtomicFile(File(context.applicationContext.filesDir, "setup_state.json"))

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
