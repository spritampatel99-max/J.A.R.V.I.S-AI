package com.jarvis.ai.ui

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.JarvisApplication
import com.jarvis.ai.android.voice.HandsFreeService

class MainActivity : ComponentActivity() {

    private lateinit var container: com.jarvis.ai.AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = (application as JarvisApplication).container
        setContent {
            val prefs by container.settings.ui.collectAsStateWithLifecycle()
            val dark = isJarvisDark(prefs.themeMode)
            // The system bar icons must match the look the person chose, which can differ from the phone's own setting.
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            JarvisTheme(prefs) {
                val vm: AgentViewModel = viewModel(factory = AgentViewModelFactory(container))
                val setupVm: SetupViewModel = viewModel(factory = SetupViewModelFactory(container))
                val setup by setupVm.ui.collectAsStateWithLifecycle()

                // Motion follows both the app's own "Reduce motion" setting and Android's "remove animations" switch.
                val systemMotion = remember { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
                val look = Look(ambient = prefs.ambient, animate = !prefs.reduceMotion && systemMotion, flare = prefs.flare)

                CompositionLocalProvider(LocalLook provides look) {
                    // One animated backdrop sits behind both the wizard and the app.
                    AuroraBackdrop(Modifier.fillMaxSize()) {
                        // Until setup is finished the wizard is shown; it resumes at the first unfinished step.
                        if (setup.setup.finished) {
                            JarvisApp(vm, container, onRunSetupAgain = setupVm::reopen)
                        } else {
                            SetupWizard(setupVm, vm)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        // Nothing keeps listening or talking once the app is out of sight, unless the person turned Hands-free on: its service
        // then owns the microphone and the voice, and leaving the app is exactly when it is meant to keep working.
        if (!HandsFreeService.isRunning) {
            container.voice.cancelListening()
            container.voice.stopSpeaking()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !HandsFreeService.isRunning) container.voice.shutdown()
        super.onDestroy()
    }
}
