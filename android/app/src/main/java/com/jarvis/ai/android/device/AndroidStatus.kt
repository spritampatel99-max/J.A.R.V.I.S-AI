package com.jarvis.ai.android.device

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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

    /** True or false from Android on Android 13+; null on older versions, where Android cannot say. */
    fun offlineSpeechRecognition(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SpeechRecognizer.isOnDeviceRecognitionAvailable(context) else null

    fun textToSpeechAvailable(): Boolean =
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
}
