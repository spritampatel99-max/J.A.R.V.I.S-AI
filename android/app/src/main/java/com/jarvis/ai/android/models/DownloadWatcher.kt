package com.jarvis.ai.android.models

import com.jarvis.ai.AppContainer
import com.jarvis.ai.models.DownloadStatus
import com.jarvis.ai.models.SpeedMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Watches the model and library downloads and reports one line of progress, or "idle" when nothing is busy. Used by both the
 * user-initiated download job and the fallback foreground service, so the two always say the same thing.
 */
class DownloadWatcher(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null
    private val meter = SpeedMeter()

    val isWatching: Boolean get() = job?.isActive == true

    fun start(onUpdate: (text: String, percent: Int?) -> Unit, onIdle: () -> Unit) {
        if (isWatching) return
        job = scope.launch {
            combine(container.modelManager.states, container.library.state) { models, library -> models to library }.collect { (states, library) ->
                val busy = states.values.filter { it.busy }
                if (busy.isEmpty() && !library.busy) {
                    meter.reset()
                    onIdle()
                } else {
                    val first = busy.firstOrNull()
                    val name = first?.spec?.displayName ?: "Offline library"
                    val progress = first?.progress ?: library.progress
                    val text = if (progress == null) {
                        "$name: checking…"
                    } else {
                        meter.add(now(), progress.bytesDone)
                        DownloadStatus.line(name, progress.bytesDone, progress.totalBytes, meter.bytesPerSecond())
                    }
                    onUpdate(text, progress?.fraction?.let { (it * 100).toInt() })
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
