package com.jarvis.ai.android.device

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.jarvis.ai.offline.Connectivity

/** Small, honest readings from Android for the Offline and Security screens. Each returns "unknown" instead of guessing when Android does not say. */
class AndroidStatus(private val context: Context) {

    fun connectivity(): Connectivity = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> Connectivity.OFFLINE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Connectivity.ONLINE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Connectivity.UNKNOWN
            else -> Connectivity.OFFLINE
        }
    } catch (e: SecurityException) {
        Connectivity.UNKNOWN
    }

    fun speechRecognitionAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Whether the phone has an on-device speech recogniser (always answerable on Android 14 and later). */
    fun offlineSpeechRecognition(): Boolean? = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun textToSpeechAvailable(): Boolean =
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
}
