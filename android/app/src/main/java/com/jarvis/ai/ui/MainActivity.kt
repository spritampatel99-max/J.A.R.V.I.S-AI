package com.jarvis.ai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.ai.JarvisApplication

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as JarvisApplication).container
        setContent {
            JarvisTheme {
                val vm: AgentViewModel = viewModel(factory = AgentViewModelFactory(container))
                val setupVm: SetupViewModel = viewModel(factory = SetupViewModelFactory(container))
                val setup by setupVm.ui.collectAsStateWithLifecycle()

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
