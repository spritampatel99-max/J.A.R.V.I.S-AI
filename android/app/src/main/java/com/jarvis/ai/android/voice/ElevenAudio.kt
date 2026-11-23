package com.jarvis.ai.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.jarvis.ai.ai.providers.ProviderError
import com.jarvis.ai.voice.AudioFetcher
import com.jarvis.ai.voice.AudioResult
import com.jarvis.ai.voice.ElevenLabsClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Fetches speech audio over OkHttp. The key travels only in the request headers it is given. The response body is read only when the
 * answer is a success, and never more than the allowed size; an error answer is not read at all, so nothing it says can be shown or logged.
 */
class OkHttpAudioFetcher(private val client: OkHttpClient) : AudioFetcher {

    override suspend fun postJsonForAudio(url: String, headers: Map<String, String>, body: String, maxBytes: Int): AudioResult =
        withContext(Dispatchers.IO) {
            val httpUrl = url.toHttpUrlOrNull() ?: throw ProviderError.MissingConfig("Invalid URL")
            val request = Request.Builder().url(httpUrl).post(body.toRequestBody(JSON)).apply {
                for ((name, value) in headers) header(name, value)
            }.build()
            val response = try {
                client.newCall(request).await()
            } catch (e: InterruptedIOException) {
                throw ProviderError.Timeout("Request timed out", e)
            } catch (e: IOException) {
                throw ProviderError.Network(e.message ?: e.javaClass.simpleName, e)
            }
            response.use { resp ->
                if (resp.code != 200) return@use AudioResult(resp.code, ByteArray(0), "")
                val source = resp.body?.source() ?: return@use AudioResult(resp.code, ByteArray(0), "")
                try {
                    val out = okio.Buffer()
                    while (source.read(out, CHUNK) != -1L) {
                        // Longer than any short reply could be: refuse rather than fill the phone's memory.
                        if (out.size > maxBytes) return@use AudioResult(413, ByteArray(0), "")
                    }
                    AudioResult(200, out.readByteArray(), "")
                } catch (e: InterruptedIOException) {
                    throw ProviderError.Timeout("Reading the audio timed out", e)
                } catch (e: IOException) {
                    throw ProviderError.Network(e.message ?: e.javaClass.simpleName, e)
                }
            }
        }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val CHUNK = 8192L
    }
}

/**
 * Plays one MP3 reply. The audio is written to a private cache file for the player and deleted when it ends, so spoken replies are not
 * kept on the phone. Every method must be called on the main thread.
 */
class ElevenPlayer(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var ended: ((Boolean) -> Unit)? = null

    /**
     * Starts [mp3]. [onEnd] runs once: with true when it played to the end (or was cut short after starting), with false when no sound
     * could be made at all, so the caller can fall back to the phone's own voice.
     */
    fun play(mp3: ByteArray, onEnd: (Boolean) -> Unit) {
        stop()
        val target = File(appContext.cacheDir, "jarvis-voice.mp3")
        try {
            target.writeBytes(mp3)
        } catch (e: IOException) {
            onEnd(false)
            return
        }
        file = target
        ended = onEnd
        var started = false
        val created = MediaPlayer()
        player = created
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setDataSource(target.absolutePath)
            created.setOnPreparedListener {
                started = true
                it.start()
            }
            created.setOnCompletionListener { finish(created, true) }
            created.setOnErrorListener { _, _, _ ->
                finish(created, started)
                true
            }
            created.prepareAsync()
        } catch (e: Exception) {
            finish(created, false)
        }
    }

    /** Stops playback. The pending [play] callback is dropped without being called: the caller that stopped it already knows. */
    fun stop() {
        ended = null
        release()
    }

    private fun finish(which: MediaPlayer, played: Boolean) {
        if (player !== which) return
        val callback = ended
        ended = null
        release()
        callback?.invoke(played)
    }

    private fun release() {
        player?.let {
            try {
                it.setOnCompletionListener(null)
                it.setOnErrorListener(null)
                it.setOnPreparedListener(null)
                it.release()
            } catch (e: IllegalStateException) {
                // Already released.
            }
        }
        player = null
        file?.delete()
        file = null
    }
}

/** What [com.jarvis.ai.voice.VoiceController] needs to speak with ElevenLabs. Built once by the container. */
class ElevenSpeaker(
    val client: ElevenLabsClient,
    private val keyPresent: () -> Boolean,
    private val context: Context,
    val player: ElevenPlayer,
) {
    fun hasKey(): Boolean = keyPresent()

    /** True when the phone has a connection that reaches the internet. */
    fun online(): Boolean = Connectivity.isOnline(context)
}

object Connectivity {
    fun isOnline(context: Context): Boolean {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
