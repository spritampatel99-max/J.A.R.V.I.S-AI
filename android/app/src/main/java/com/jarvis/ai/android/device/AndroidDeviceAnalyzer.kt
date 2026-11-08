package com.jarvis.ai.android.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.jarvis.ai.device.ConnectionType
import com.jarvis.ai.device.DeviceAnalyzer
import com.jarvis.ai.device.DeviceProfile
import com.jarvis.ai.device.Reading
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads what the phone reports about itself. Every value is read on its own, so one unreadable value becomes
 * "unavailable" with a note instead of failing the whole report. No identifiers, accounts, contacts, location
 * or list of installed apps is read.
 */
class AndroidDeviceAnalyzer(context: Context) : DeviceAnalyzer {

    private val appContext = context.applicationContext

    override suspend fun analyze(): DeviceProfile = withContext(Dispatchers.IO) {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = activityManager?.let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) } }
        val pm = appContext.packageManager
        val battery = batteryIntent()
        val network = networkState()

        DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty().ifBlank { "unknown" },
            model = Build.MODEL.orEmpty().ifBlank { "unknown" },
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),

            // The runtime reports only the cores that are online right now, which can be fewer than the chip has.
            cpuCores = read("Not reported") { Runtime.getRuntime().availableProcessors().takeIf { it > 0 } }
                .asEstimate("Cores online right now; the chip may have more."),

            totalRamBytes = read("Android did not report total memory") { memory?.totalMem?.takeIf { it > 0 } },
            availableRamBytes = read("Android did not report free memory") { memory?.availMem?.takeIf { it >= 0 } }
                .asEstimate("Free memory right now; it changes all the time."),
            lowMemory = read("Android did not report the low-memory state") { memory?.lowMemory },
            appMemoryClassMb = read("Not reported") { activityManager?.memoryClass },
            largeMemoryClassMb = read("Not reported") { activityManager?.largeMemoryClass },

            totalStorageBytes = read("Storage size could not be read") { StatFs(appContext.filesDir.path).totalBytes },
            freeStorageBytes = read("Free storage could not be read") { StatFs(appContext.filesDir.path).availableBytes },

            openGlEsVersion = read("OpenGL ES version not reported") {
                activityManager?.deviceConfigurationInfo?.glEsVersion?.takeIf { it.isNotBlank() }
            },
            vulkanSupported = read("Could not query Vulkan support") {
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
            },

            cpuInference = Reading.unavailable(NOT_YET_VERIFIED),
            gpuAcceleration = Reading.unavailable(NOT_YET_VERIFIED),
            npuAcceleration = Reading.unavailable(NOT_YET_VERIFIED),

            batteryPercent = read("Battery level not reported") {
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) level * 100 / scale else null
            },
            charging = read("Charging state not reported") {
                val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            },
            ignoringBatteryOptimizations = read("Could not query battery optimisation") {
                (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(appContext.packageName)
            },

            online = network.online,
            connection = network.connection,
            metered = network.metered,

            microphone = feature(pm, PackageManager.FEATURE_MICROPHONE),
            camera = feature(pm, PackageManager.FEATURE_CAMERA_ANY),
            bluetooth = feature(pm, PackageManager.FEATURE_BLUETOOTH),
            audioOutput = feature(pm, PackageManager.FEATURE_AUDIO_OUTPUT),
            speechRecognition = read("Could not query speech recognition") { SpeechRecognizer.isRecognitionAvailable(appContext) },
            onDeviceSpeechRecognition = if (Build.VERSION.SDK_INT >= 33) {
                read("Could not query on-device speech recognition") { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }
            } else {
                Reading.unavailable("Android cannot report this before version 13.")
            },
            textToSpeech = read("Could not query text-to-speech") {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
            },
        )
    }

    private fun batteryIntent(): Intent? = try {
        // A sticky broadcast: passing no receiver just returns the last value, and needs no permission.
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (e: RuntimeException) {
        null
    }

    private class NetworkState(val online: Reading<Boolean>, val connection: Reading<ConnectionType>, val metered: Reading<Boolean>)

    private fun networkState(): NetworkState {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState(
                Reading.unavailable("No connectivity service"),
                Reading.unavailable("No connectivity service"),
                Reading.unavailable("No connectivity service"),
            )
        return try {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            if (caps == null) {
                NetworkState(Reading.measured(false), Reading.measured(ConnectionType.NONE), Reading.measured(false))
            } else {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
                    else -> ConnectionType.OTHER
                }
                NetworkState(Reading.measured(validated), Reading.measured(type), Reading.measured(cm.isActiveNetworkMetered))
            }
        } catch (e: RuntimeException) {
            NetworkState(
                Reading.unavailable("Network state could not be read"),
                Reading.unavailable("Network state could not be read"),
                Reading.unavailable("Network state could not be read"),
            )
        }
    }

    private fun feature(pm: PackageManager, name: String): Reading<Boolean> =
        read("Could not query this hardware feature") { pm.hasSystemFeature(name) }

    private inline fun <T : Any> read(whenUnavailable: String, block: () -> T?): Reading<T> = try {
        block()?.let { Reading.measured(it) } ?: Reading.unavailable(whenUnavailable)
    } catch (e: RuntimeException) {
        Reading.unavailable(whenUnavailable)
    }

    private fun <T : Any> Reading<T>.asEstimate(note: String): Reading<T> =
        value?.let { Reading.estimated(it, note) } ?: this

    private companion object {
        const val NOT_YET_VERIFIED = "Can only be verified by loading a model on this phone; that is done after a model is installed."
    }
}

/** Size of the installed app on disk, read from the APK files Android reports. */
object AndroidAppSize {
    fun read(context: Context): Reading<Long> = try {
        val info = context.applicationContext.applicationInfo
        val files = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty().toList()
        val total = files.sumOf { File(it).length() }
        if (total > 0) Reading.measured(total) else Reading.unavailable("APK size could not be read")
    } catch (e: RuntimeException) {
        Reading.unavailable("APK size could not be read")
    }
}
