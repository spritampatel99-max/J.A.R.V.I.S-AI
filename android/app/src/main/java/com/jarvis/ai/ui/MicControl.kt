package com.jarvis.ai.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.ai.android.device.AccessChecks
import com.jarvis.ai.android.device.AccessKey
import com.jarvis.ai.android.device.AccessState

/**
 * What tapping the mic button does, shared by the Home and Chat screens.
 *
 * Listening: stop. Speaking: be quiet. Otherwise: start one listening session, asking Android for the microphone first
 * when it has not been allowed. If it has been blocked for good, the person is told to use the Prefs tab. Nothing listens
 * unless this was tapped.
 */
@Composable
fun rememberMicToggle(vm: AgentViewModel): () -> Unit {
    val context = LocalContext.current
    val voice by vm.voice.collectAsStateWithLifecycle()
    val chat by vm.chatState.collectAsStateWithLifecycle()
    val currentVoice by rememberUpdatedState(voice)
    val currentChat by rememberUpdatedState(chat)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.listen() else vm.microphoneOff()
    }

    return {
        when {
            currentVoice.listening -> vm.stopListening()
            currentVoice.speaking -> vm.stopSpeaking()
            currentChat.running -> Unit
            AccessChecks.state(context, AccessKey.MICROPHONE) == AccessState.ALLOWED -> vm.listen()
            AccessChecks.isBlocked(context, AccessKey.MICROPHONE) -> vm.microphoneOff()
            else -> {
                AccessChecks.markAsked(context, AccessKey.MICROPHONE)
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
