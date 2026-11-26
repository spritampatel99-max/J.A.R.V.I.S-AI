package com.jarvis.ai.android.device

import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.jarvis.ai.ai.tools.builtin.AlarmLauncher
import com.jarvis.ai.ai.tools.builtin.DeviceInfo
import com.jarvis.ai.ai.tools.builtin.DeviceInfoReader
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.TorchController
import com.jarvis.ai.ai.tools.builtin.TorchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import com.jarvis.ai.core.permissions.PermissionChecker
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val BACKGROUND_REASON =
    "Android only lets JARVIS 3.0 open other apps while it is on screen, or when \"Display over other apps\" is allowed. " +
        "Allow it in Prefs > Access, or keep the app open and try again."

private const val BACKGROUND_OFFERED_REASON =
    "Android does not let JARVIS 3.0 open other apps from the background, so nothing was opened. " +
        "I put a \"Tap to open\" notification on your phone; tap it to continue. " +
        "To skip this step, allow \"Display over other apps\" in Prefs > Access."

/**
 * Whether Android will let this app start another app's screen right now.
 *
 * Since Android 10 a background app is blocked SILENTLY (no exception). The two ways to be allowed are: one of this app's
 * screens is showing, or the person has allowed "Display over other apps" (the hands-free service then also shows a small
 * window while it works, which Android 15 requires for that exemption). This check cannot prove Android will allow it, so
 * a start from the background is still only reported as "handed off", never as confirmed.
 */
internal object BackgroundStart {
    fun allowed(context: Context, foreground: ForegroundTracker): Boolean =
        foreground.isForeground || Settings.canDrawOverlays(context)
}

/** Starts an activity and reports what Android actually did. */
internal fun startActivitySafely(
    context: Context,
    foreground: ForegroundTracker,
    intent: Intent,
    what: String,
): LaunchResult {
    if (!BackgroundStart.allowed(context, foreground)) {
        // Android would drop the start silently. A notification the person taps is always allowed to open the app.
        return if (TapToOpen.offer(context, intent, what)) LaunchResult.Failed(BACKGROUND_OFFERED_REASON) else LaunchResult.Failed(BACKGROUND_REASON)
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LaunchResult.Launched
    } catch (e: ActivityNotFoundException) {
        LaunchResult.NoHandler(what)
    } catch (e: SecurityException) {
        LaunchResult.Failed("Android blocked the request (${e.javaClass.simpleName}).")
    }
}

class AndroidTorchController(private val context: Context) : TorchController {

    override suspend fun setTorch(on: Boolean): TorchResult {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) return TorchResult.NoFlash
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return TorchResult.Unavailable("the camera service is not available")

        val cameraId: String? = try {
            findFlashCameraId(manager)
        } catch (e: CameraAccessException) {
            return TorchResult.Unavailable("the camera service refused access (${e.javaClass.simpleName})")
        }
        if (cameraId == null) return TorchResult.NoFlash

        return withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { toggle(manager, cameraId, on) }
            ?: TorchResult.Failed("Android did not confirm the flashlight change in time.")
    }

    /** Prefers the back camera's flash unit. */
    private fun findFlashCameraId(manager: CameraManager): String? {
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    /**
     * Registers a torch callback and only reports success when the camera service itself
     * says the torch is in the requested state.
     */
    private suspend fun toggle(manager: CameraManager, cameraId: String, on: Boolean): TorchResult =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            lateinit var callback: CameraManager.TorchCallback

            fun finish(result: TorchResult) {
                if (finished.compareAndSet(false, true)) {
                    manager.unregisterTorchCallback(callback)
                    if (continuation.isActive) continuation.resume(result)
                }
            }

            callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(changedId: String, enabled: Boolean) {
                    if (changedId == cameraId && enabled == on) finish(TorchResult.Confirmed(enabled))
                }

                override fun onTorchModeUnavailable(unavailableId: String) {
                    if (unavailableId == cameraId) finish(TorchResult.Unavailable("another app is using the camera"))
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) manager.unregisterTorchCallback(callback)
            }
            manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            try {
                manager.setTorchMode(cameraId, on)
            } catch (e: CameraAccessException) {
                finish(TorchResult.Unavailable("the camera is not available right now"))
            } catch (e: IllegalArgumentException) {
                finish(TorchResult.Failed("Android rejected the request (${e.message})"))
            }
        }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 3_000L
    }
}

class AndroidAlarmLauncher(
    private val context: Context,
    private val foreground: ForegroundTracker,
) : AlarmLauncher {

    override fun setAlarm(hour: Int, minute: Int, label: String?): LaunchResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startActivitySafely(context, foreground, intent, "alarms")
    }

    override fun nextAlarmEpochMs(): Long? =
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
}

class AndroidUrlOpener(
    private val context: Context,
    private val foreground: ForegroundTracker,
) : UrlOpener {

    override fun open(url: String): LaunchResult {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        return startActivitySafely(context, foreground, intent, "web links")
    }
}

class AndroidDeviceInfoReader(private val context: Context) : DeviceInfoReader {

    override fun read(): DeviceInfo {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = if (status == -1) {
            null
        } else {
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }

        val dataDir = Environment.getDataDirectory().path
        val stat = StatFs(dataDir)

        return DeviceInfo(
            batteryPercent = percent,
            charging = charging,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            network = describeNetwork(),
            storageFreeBytes = stat.availableBytes,
            storageTotalBytes = stat.totalBytes,
        )
    }

    private fun describeNetwork(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "Unknown"
        val network = manager.activeNetwork ?: return "Offline"
        val caps = manager.getNetworkCapabilities(network) ?: return "Unknown"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online (other)"
        }
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) kind else "$kind (no internet access)"
    }
}

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun missing(required: Set<String>): Set<String> =
        required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toSet()
}
