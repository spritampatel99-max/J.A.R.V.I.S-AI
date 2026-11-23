package com.jarvis.ai.android.voice

import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import com.jarvis.ai.voice.SafeZip
import com.jarvis.ai.voice.VoskResult
import com.jarvis.ai.voice.WakeDebounce
import com.jarvis.ai.voice.WakePhrase
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Something that listens for the wake word. Starting and stopping it must be cheap, because the service pauses it whenever the microphone is needed. */
interface WakeDetector {
    /** @throws WakeException when listening could not begin (the message is safe to show; it never holds a key). */
    fun start()

    fun stop()

    /** Frees everything. The detector cannot be used afterwards. */
    fun release()
}

class WakeException(message: String) : Exception(message)

/** The Picovoice Porcupine detector (needs the person's own AccessKey). */
class PorcupineWake(private val manager: PorcupineManager) : WakeDetector {
    override fun start() = try {
        manager.start()
    } catch (e: PorcupineException) {
        throw WakeException(e.message.orEmpty().take(120))
    }

    override fun stop() = try {
        manager.stop()
    } catch (e: PorcupineException) {
        // Stopping failed; the next start reports it if the microphone really is stuck.
    }

    override fun release() = try {
        manager.delete()
    } catch (e: PorcupineException) {
        // Nothing more can be done.
    }
}

/**
 * Listens for "Jarvis" with Vosk, an offline recogniser that needs no account or key. The recogniser is limited to the one word plus
 * "unknown", so it only reports "jarvis" or nothing. It runs the whole time the service waits, so it uses more battery than Porcupine,
 * and it can mishear other words; that cannot be tuned from here. The audio stays on the phone: Vosk has no network code.
 */
class VoskWake(
    private val model: Model,
    private val onWake: () -> Unit,
    private val onHeard: (String) -> Unit,
    private val onProblem: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : WakeDetector {
    private var service: SpeechService? = null
    private val debounce = WakeDebounce()

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = handle(VoskResult.text(hypothesis))
        override fun onResult(hypothesis: String?) = handle(VoskResult.text(hypothesis))
        override fun onFinalResult(hypothesis: String?) = handle(VoskResult.text(hypothesis))
        override fun onError(exception: Exception?) = onProblem("The wake-word listener stopped (${exception?.javaClass?.simpleName ?: "error"}).")
        override fun onTimeout() = Unit
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        onHeard(text.take(60))
        if (WakePhrase.heard(text) && debounce.accept(nowMs())) onWake()
    }

    override fun start() {
        if (service != null) return
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR)
            val created = SpeechService(recognizer, SAMPLE_RATE)
            if (!created.startListening(listener)) {
                created.shutdown()
                throw WakeException("The microphone is busy.")
            }
            service = created
        } catch (e: IOException) {
            throw WakeException("The wake-word listener could not open the microphone.")
        }
    }

    override fun stop() {
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
    }

    override fun release() {
        stop()
        model.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f

        /** Only these can come out: the wake word, or "something else". */
        const val GRAMMAR = """["jarvis", "[unk]"]"""
    }
}

sealed interface VoskModelState {
    data object NotInstalled : VoskModelState
    data class Downloading(val bytesDone: Long, val totalBytes: Long?) : VoskModelState
    data object Installing : VoskModelState
    data object Installed : VoskModelState
    data class Failed(val reason: String) : VoskModelState
}

/**
 * The small English speech model Vosk needs (about 40 MB, Apache-2.0, from alphacephei.com). Downloaded only when the person taps
 * Download, over https only. **There is no published checksum to pin, so the file is not verified beyond being a well-formed zip of
 * a Vosk model**; the zip is unpacked with path checks so it cannot write outside the app's own folder.
 */
class VoskModelStore(private val context: Context, private val http: OkHttpClient) {
    private val root = File(context.filesDir, "vosk")
    private val modelDir = File(root, "model")
    private val mutable = MutableStateFlow<VoskModelState>(if (isInstalled()) VoskModelState.Installed else VoskModelState.NotInstalled)
    val state: StateFlow<VoskModelState> = mutable.asStateFlow()

    fun isInstalled(): Boolean = File(modelDir, "am/final.mdl").isFile || File(modelDir, "conf/model.conf").isFile

    /** Opens the installed model, or null when it is missing or damaged. */
    fun open(): Model? = if (!isInstalled()) null else try {
        Model(modelDir.absolutePath)
    } catch (e: IOException) {
        null
    }

    fun delete() {
        modelDir.deleteRecursively()
        mutable.value = VoskModelState.NotInstalled
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        if (mutable.value is VoskModelState.Downloading || mutable.value is VoskModelState.Installing) return@withContext
        val part = File(root, "model.zip.part")
        val staging = File(root, "staging")
        try {
            root.mkdirs()
            mutable.value = VoskModelState.Downloading(0, null)
            http.newCall(Request.Builder().url(MODEL_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The download server answered ${response.code}.")
                val body = response.body ?: throw IOException("The download was empty.")
                val total = body.contentLength().takeIf { it > 0 }
                if (total != null && total > MAX_DOWNLOAD_BYTES) throw IOException("The file is larger than expected.")
                var done = 0L
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            done += read
                            if (done > MAX_DOWNLOAD_BYTES) throw IOException("The file is larger than expected.")
                            out.write(buffer, 0, read)
                            mutable.value = VoskModelState.Downloading(done, total)
                        }
                    }
                }
            }
            mutable.value = VoskModelState.Installing
            staging.deleteRecursively()
            staging.mkdirs()
            unzip(part, staging)
            val top = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
            if (!File(top, "am/final.mdl").isFile && !File(top, "conf/model.conf").isFile) throw IOException("That file is not a Vosk speech model.")
            modelDir.deleteRecursively()
            if (!top.renameTo(modelDir)) throw IOException("Could not move the model into place.")
            mutable.value = VoskModelState.Installed
        } catch (e: IOException) {
            mutable.value = VoskModelState.Failed(e.message.orEmpty().take(140).ifBlank { "The download failed." })
        } finally {
            part.delete()
            staging.deleteRecursively()
        }
    }

    private fun unzip(zip: File, into: File) {
        val base = into.canonicalFile
        var entries = 0
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (++entries > SafeZip.MAX_ENTRIES) throw IOException("The model has too many files.")
                val relative = SafeZip.safeRelativePath(entry.name) ?: throw IOException("The model contains an unsafe file path.")
                val target = File(base, relative).canonicalFile
                if (!target.path.startsWith(base.path + File.separator)) throw IOException("The model contains an unsafe file path.")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > SafeZip.MAX_TOTAL_BYTES) throw IOException("The model is larger than expected.")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024
    }
}
