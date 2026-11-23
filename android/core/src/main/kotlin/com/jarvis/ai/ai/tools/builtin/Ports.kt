package com.jarvis.ai.ai.tools.builtin

/**
 * The Android-facing side of each built-in tool. `:core` defines the interfaces and all
 * the decision logic; `:app` implements them with real Android APIs. That keeps the logic
 * testable on a plain JVM, while nothing here pretends to do work on its own.
 */

sealed interface TorchResult {
    /** The system reported the torch state. [isOn] is what the system said, not what we asked for. */
    data class Confirmed(val isOn: Boolean) : TorchResult

    data object NoFlash : TorchResult

    /** For example another app is using the camera. */
    data class Unavailable(val reason: String) : TorchResult

    data class Failed(val reason: String) : TorchResult
}

interface TorchController {
    suspend fun setTorch(on: Boolean): TorchResult
}

sealed interface LaunchResult {
    /** Android accepted the request and started the target; what the target then does is not observable. */
    data object Launched : LaunchResult

    /** No installed app can handle the request. */
    data class NoHandler(val what: String) : LaunchResult

    data class Failed(val reason: String) : LaunchResult
}

interface AlarmLauncher {
    /** Asks the system Clock app to create an alarm for the next time [hour]:[minute] occurs. */
    fun setAlarm(hour: Int, minute: Int, label: String?): LaunchResult

    /** The system-wide next alarm (epoch ms), or null if none is set. Used to verify [setAlarm]. */
    fun nextAlarmEpochMs(): Long?
}

interface UrlOpener {
    /** Opens an http(s) URL in the user's browser or the app registered for that link. */
    fun open(url: String): LaunchResult
}

data class DeviceInfo(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** "Wi-Fi", "Cellular", "Ethernet", "Offline", "Online (other)" or "Unknown". */
    val network: String,
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
)

interface DeviceInfoReader {
    fun read(): DeviceInfo
}

/** Everything the built-in tools need from the platform. */
class DevicePorts(
    val torch: TorchController,
    val alarms: AlarmLauncher,
    val urls: UrlOpener,
    val deviceInfo: DeviceInfoReader,
    /** Both are needed for the call tools; without them those tools are simply not offered. */
    val contacts: ContactsPort? = null,
    val calls: CallPort? = null,
    /** Lets "open WhatsApp" work. Without it the open-app tool is simply not offered. */
    val apps: AppLauncher? = null,
    /** Without these, the matching tools are simply not offered. */
    val messaging: MessagingPort? = null,
    val timers: TimerLauncher? = null,
    val media: MediaKeys? = null,
    val email: EmailPort? = null,
    val camera: CameraLauncher? = null,
    /** Lets a WhatsApp message be sent hands-free. Absent, or [WhatsAppSender.enabled] false, the person presses Send. */
    val whatsappSender: WhatsAppSender? = null,
)

/** Presses Send in WhatsApp for a message that was just opened with the text typed in. Off unless the person switched it on. */
interface WhatsAppSender {
    /** True only while the person's own "press Send for me in WhatsApp" switch is on. Read fresh for every message. */
    val enabled: Boolean

    /** Presses Send if WhatsApp is in front and its message box holds exactly [expected]. */
    suspend fun pressSend(expected: String): com.jarvis.ai.ai.tools.screen.SendPress
}
