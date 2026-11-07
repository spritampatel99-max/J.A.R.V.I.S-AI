package com.jarvis.ai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                JarvisApp(vm)
            }
        }
    }
}
