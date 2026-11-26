package com.jarvis.ai.android.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.jarvis.ai.ai.tools.builtin.AppLauncher
import com.jarvis.ai.ai.tools.builtin.InstalledApp
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the apps that have a launcher icon and opens one by package name. It only starts the app's front door; nothing
 * here can tap or type inside another app.
 *
 * The list is read from Android each time it is older than [CACHE_MS], so an app installed a moment ago is found.
 */
class AndroidAppLauncher(
    context: Context,
    private val foreground: ForegroundTracker,
    /** Told the package just before an app is opened, so the floating card can be taken off a payment app before it starts. */
    private val beforeLaunch: (String) -> Unit = {},
) : AppLauncher {

    private val context = context.applicationContext

    @Volatile
    private var cached: List<InstalledApp> = emptyList()

    @Volatile
    private var cachedAtMs = 0L

    override suspend fun launchable(): List<InstalledApp> {
        val now = System.currentTimeMillis()
        val saved = cached
        if (saved.isNotEmpty() && now - cachedAtMs < CACHE_MS) return saved
        return withContext(Dispatchers.IO) { read() }.also {
            cached = it
            cachedAtMs = System.currentTimeMillis()
        }
    }

    private fun read(): List<InstalledApp> {
        val packages = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = packages.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0L))
        return found
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val label = info.loadLabel(packages)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) null else InstalledApp(label, packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun launch(packageName: String): LaunchResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.NoHandler(packageName)
        beforeLaunch(packageName)
        return startActivitySafely(context, foreground, intent, packageName)
    }

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
