package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.WebLinks
import com.jarvis.ai.core.security.Risk
import java.util.Locale

/**
 * Phone settings the assistant can reach by voice: brightness, sound mode, the Settings pages, the clipboard and the alarm that is
 * ringing. Each one either changes the setting and reads the new value back from Android, or hands the person to the right Settings
 * page and says so. Android does not let ordinary apps flip Wi-Fi, Bluetooth, airplane mode or mobile data directly, so for those
 * the answer is the exact page, opened.
 */

/** Pages of Android's Settings app. The Android side maps each to the page Android provides for it. */
enum class SettingsPage(val spoken: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    MOBILE_NETWORK("mobile network"),
    AIRPLANE_MODE("airplane mode"),
    HOTSPOT("hotspot and tethering"),
    DATA_USAGE("data usage"),
    BATTERY("battery"),
    BATTERY_SAVER("battery saver"),
    DISPLAY("display"),
    SOUND("sound"),
    STORAGE("storage"),
    APPS("apps"),
    LOCATION("location"),
    NOTIFICATIONS("notification"),
    ACCESSIBILITY("accessibility"),
    DATE_TIME("date and time"),
    LANGUAGE("language"),
    VPN("VPN"),
    NFC("NFC"),
    PRIVACY("privacy"),
    SECURITY("security"),
    DEVELOPER("developer options"),
    DEFAULT_APPS("default apps"),
    INTERNET_PANEL("internet quick panel"),
    VOLUME_PANEL("volume quick panel"),
    NOTIFICATION_ACCESS("notification access"),
    DISPLAY_OVER_APPS("display over other apps"),
    WRITE_SETTINGS("modify system settings"),
    DO_NOT_DISTURB_ACCESS("do not disturb access"),
    THIS_APP("JARVIS 3.0 app info"),
}

enum class SoundMode(val spoken: String) {
    NORMAL("normal"),
    VIBRATE("vibrate"),
    SILENT("silent"),
}

sealed interface BrightnessResult {
    /** Android reported the level before and after, as percentages. [adaptiveWasOn] is true when automatic brightness had to be switched off for the level to stick. */
    data class Changed(val before: Int?, val after: Int, val adaptiveWasOn: Boolean = false) : BrightnessResult

    /** The person has not allowed "Modify system settings" for this app. */
    data object NeedsPermission : BrightnessResult

    data class Failed(val reason: String) : BrightnessResult
}

sealed interface SoundResult {
    /** [mode] is what Android reported after the change. */
    data class Changed(val mode: SoundMode?) : SoundResult

    /** Android only lets an app mute the phone once the person allows Do Not Disturb access. */
    data object NeedsPermission : SoundResult

    data class Failed(val reason: String) : SoundResult
}

interface PhoneSettingsPort {
    /** The current screen brightness as a percentage, or null when it cannot be read. */
    fun brightnessPercent(): Int?

    fun setBrightnessPercent(percent: Int): BrightnessResult

    fun setSoundMode(mode: SoundMode): SoundResult

    fun open(page: SettingsPage): LaunchResult

    /** Asks the Clock app to dismiss the alarm that is ringing, or else the next one. Android does not say whether it did. */
    fun dismissAlarm(): LaunchResult

    /** Puts [text] on the clipboard. @return false when Android refused. */
    fun copyToClipboard(text: String): Boolean

    /**
     * Turns off everything of JARVIS that reads or draws over other apps' screens, so a payment or banking app that refuses to run next to
     * them can be used. Cannot be undone from here: Android only lets the person switch the accessibility service back on.
     */
    fun enterPaymentMode(): PaymentModeResult = PaymentModeResult(screenControlWasOn = false, screenControlAsked = false, handsFreeStopped = false)
}

/** [screenControlAsked] = Android was asked to switch the service off; it does not report back, so this is not proof it is off. */
data class PaymentModeResult(val screenControlWasOn: Boolean, val screenControlAsked: Boolean, val handsFreeStopped: Boolean)

/** One kind of access (notification access, accessibility, microphone...) and whether it is switched on right now. */
data class AccessLine(val title: String, val on: Boolean, val unlocks: String)

interface AccessReporter {
    /** The real state of every access, read from Android now. */
    fun report(): List<AccessLine>
}

class SetBrightnessTool(private val settings: PhoneSettingsPort) : Tool {
    override val spec = ToolSpec(
        name = "set_brightness",
        description = "Change the screen brightness: set it to a percentage, or make it brighter or dimmer.",
        params = listOf(
            ParamSpec("action", ParamType.ENUM, "set a percentage, or go up or down", enumValues = listOf("set", "up", "down")),
            ParamSpec("percent", ParamType.INT, "The brightness 1 to 100; only for action set", required = false, min = 1, max = 100),
        ),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "CONFIRMED: the brightness Android reports after the change. If the person has not allowed Modify system settings, the page is opened and nothing changes.",
    )

    override fun describe(args: ToolArgs): String = when (args.string("action").lowercase(Locale.ROOT)) {
        "up" -> "Make the screen brighter"
        "down" -> "Make the screen dimmer"
        else -> "Set the brightness to ${args.intOrNull("percent") ?: "?"}%"
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val current = settings.brightnessPercent()
        val target = when (args.string("action").lowercase(Locale.ROOT)) {
            "set" -> args.intOrNull("percent") ?: return ToolResult.Failure(ToolError.InvalidArguments("Tell me what percentage, for example \"brightness 60 percent\"."))
            "up" -> ((current ?: 50) + STEP).coerceAtMost(100)
            else -> ((current ?: 50) - STEP).coerceAtLeast(MIN_VISIBLE)
        }
        return when (val result = settings.setBrightnessPercent(target)) {
            is BrightnessResult.Changed -> ToolResult.Success(
                if (result.before == result.after) {
                    "The brightness is already ${result.after} percent."
                } else {
                    "The brightness is now ${result.after} percent" + (result.before?.let { ", it was $it" } ?: "") + ", as Android reports it." +
                        if (result.adaptiveWasOn) " I switched off automatic brightness so it stays." else ""
                },
                Verification.CONFIRMED,
                mapOf("brightness" to result.after.toString()),
            )
            BrightnessResult.NeedsPermission -> {
                settings.open(SettingsPage.WRITE_SETTINGS)
                ToolResult.Failure(
                    ToolError.Unavailable(
                        "To change the brightness, Android needs you to allow \"Modify system settings\" for JARVIS 3.0. I opened that page: switch it on, then ask again.",
                    ),
                )
            }
            is BrightnessResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }

    private companion object {
        const val STEP = 20
        const val MIN_VISIBLE = 5
    }
}

class SetSoundModeTool(private val settings: PhoneSettingsPort) : Tool {
    override val spec = ToolSpec(
        name = "set_sound_mode",
        description = "Switch the phone between normal (rings), vibrate and silent.",
        params = listOf(ParamSpec("mode", ParamType.ENUM, "normal, vibrate or silent", enumValues = SoundMode.entries.map { it.name.lowercase(Locale.ROOT) })),
        risk = Risk.MEDIUM,
        timeoutMs = 5_000L,
        resultFormat = "CONFIRMED: the ringer mode Android reports after the change. Silent needs Do Not Disturb access; without it that page is opened.",
    )

    override fun describe(args: ToolArgs): String = "Set the phone to ${mode(args).spoken} mode"

    private fun mode(args: ToolArgs): SoundMode = SoundMode.valueOf(args.string("mode").uppercase(Locale.ROOT))

    override suspend fun execute(args: ToolArgs): ToolResult {
        val wanted = mode(args)
        return when (val result = settings.setSoundMode(wanted)) {
            is SoundResult.Changed -> {
                val reported = result.mode
                when {
                    reported == wanted -> ToolResult.Success("The phone is now in ${wanted.spoken} mode, as Android reports it.", Verification.CONFIRMED)
                    reported == null -> ToolResult.Success("Asked Android for ${wanted.spoken} mode. It did not report the result.", Verification.HANDED_OFF)
                    else -> ToolResult.Failure(ToolError.Failed("I asked for ${wanted.spoken} mode but Android says the phone is in ${reported.spoken} mode."))
                }
            }
            SoundResult.NeedsPermission -> {
                settings.open(SettingsPage.DO_NOT_DISTURB_ACCESS)
                ToolResult.Failure(
                    ToolError.Unavailable(
                        "Android only lets an app change that after you allow \"Do not disturb access\" for JARVIS 3.0. I opened that page: switch it on, then ask again.",
                    ),
                )
            }
            is SoundResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }
}

class OpenSettingsTool(private val settings: PhoneSettingsPort) : Tool {
    override val spec = ToolSpec(
        name = "open_settings",
        description = "Open a page of the phone's Settings, such as Wi-Fi, Bluetooth, battery, display, sound, storage or location. " +
            "Use it for \"turn on Wi-Fi\" too: Android does not let an app flip those switches, so the right page is opened for the person.",
        params = listOf(ParamSpec("page", ParamType.ENUM, "Which Settings page", enumValues = SettingsPage.entries.map { it.name.lowercase(Locale.ROOT) })),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "HANDED_OFF: Android opened the Settings page; nothing was changed.",
    )

    private fun page(args: ToolArgs): SettingsPage = SettingsPage.valueOf(args.string("page").uppercase(Locale.ROOT))

    override fun describe(args: ToolArgs): String = "Open ${page(args).spoken} settings"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val page = page(args)
        return when (val result = settings.open(page)) {
            LaunchResult.Launched -> ToolResult.Success(
                "Opened the ${page.spoken} settings. Android does not let an app flip that switch itself, so it is one tap for you.",
                Verification.HANDED_OFF,
            )
            is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("This phone has no page for ${page.spoken}."))
            is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }
}

class DismissAlarmTool(private val settings: PhoneSettingsPort) : Tool {
    override val spec = ToolSpec(
        name = "dismiss_alarm",
        description = "Stop the alarm that is ringing, or else dismiss the next alarm that is set. Android has no way to pick one alarm out of a list.",
        params = emptyList(),
        risk = Risk.MEDIUM,
        timeoutMs = 5_000L,
        resultFormat = "HANDED_OFF: the Clock app was asked; whether it did is not reported back.",
    )

    override fun describe(args: ToolArgs): String = "Dismiss the ringing or next alarm"

    override suspend fun execute(args: ToolArgs): ToolResult = when (val result = settings.dismissAlarm()) {
        LaunchResult.Launched -> ToolResult.Success(
            "Asked the Clock app to dismiss the alarm that is ringing, or the next one. Android does not report back, so check the Clock app if you are unsure. " +
                "To remove a particular alarm, open the Clock app.",
            Verification.HANDED_OFF,
        )
        is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("The Clock app on this phone does not accept that request. Open the Clock app to turn the alarm off."))
        is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
    }
}

class PaymentModeTool(private val settings: PhoneSettingsPort) : Tool {
    override val spec = ToolSpec(
        name = "payment_mode",
        description = "Before paying in a payment or banking app: switch off JARVIS's screen control and hands-free listening so nothing of JARVIS is reading or drawing over the screen.",
        params = emptyList(),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "HANDED_OFF: Android was asked to switch the screen-control service off; it does not report back.",
    )

    override fun describe(args: ToolArgs): String = "Switch off JARVIS's screen control and listening so a payment app can run"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val r = settings.enterPaymentMode()
        val screen = when {
            r.screenControlAsked -> "I asked Android to switch off JARVIS's screen control."
            r.screenControlWasOn -> "I could not switch off JARVIS's screen control; turn it off in Settings > Accessibility > JARVIS."
            else -> "JARVIS's screen control was already off."
        }
        val listening = if (r.handsFreeStopped) " Hands-free listening is stopped." else ""
        return ToolResult.Success(
            "Payment mode: $screen$listening Some payment apps close or go to the home screen while any screen-reading service is on. " +
                "I never press anything in a payment app. To bring JARVIS back: Settings > Accessibility > JARVIS, then start hands-free again.",
            Verification.HANDED_OFF,
        )
    }
}

class CopyToClipboardTool(private val settings: PhoneSettingsPort) : Tool {
    override val spec = ToolSpec(
        name = "copy_to_clipboard",
        description = "Put the given text on the phone's clipboard so it can be pasted anywhere.",
        params = listOf(ParamSpec("text", ParamType.STRING, "The words to copy", maxLength = 2_000)),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "HANDED_OFF: Android was asked to hold the text; it cannot be read back.",
    )

    override fun describe(args: ToolArgs): String = "Copy ${args.string("text").length} characters to the clipboard"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val text = args.string("text").trim()
        if (text.isEmpty()) return ToolResult.Failure(ToolError.InvalidArguments("Tell me what to copy."))
        return if (settings.copyToClipboard(text)) {
            ToolResult.Success("Asked Android to put ${text.length} characters on the clipboard.", Verification.HANDED_OFF)
        } else {
            ToolResult.Failure(ToolError.Failed("Android did not let me use the clipboard just now."))
        }
    }
}

class CountAppsTool(private val apps: AppLauncher) : Tool {
    override val spec = ToolSpec(
        name = "count_apps",
        description = "Say how many apps are on the phone (those that have an icon in the app drawer).",
        params = emptyList(),
        risk = Risk.LOW,
        timeoutMs = 10_000L,
        resultFormat = "CONFIRMED: the number of launchable apps Android lists.",
    )

    override fun describe(args: ToolArgs): String = "Count the apps on this phone"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val count = try {
            apps.launchable().size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolResult.Failure(ToolError.Failed("I could not read the app list."))
        }
        return ToolResult.Success(
            "There are $count apps on this phone that have an icon in the app drawer. Background components without an icon are not counted.",
            Verification.CONFIRMED,
            mapOf("apps" to count.toString()),
            answer = true,
        )
    }
}

class OpenMapsTool(private val urls: UrlOpener) : Tool {
    override val spec = ToolSpec(
        name = "open_maps",
        description = "Open Google Maps with directions to a place, or a search for places such as \"petrol pump near me\".",
        params = listOf(
            ParamSpec("query", ParamType.STRING, "The place or the search, as the person says it", maxLength = 200),
            ParamSpec("mode", ParamType.ENUM, "directions to one place, or a search for places", enumValues = listOf("directions", "search")),
        ),
        risk = Risk.LOW,
        timeoutMs = 10_000L,
        resultFormat = "HANDED_OFF: Maps was opened with the request; the route is not read back.",
    )

    override fun describe(args: ToolArgs): String =
        if (args.string("mode").equals("search", ignoreCase = true)) "Search Maps for ${args.string("query").take(60)}" else "Directions to ${args.string("query").take(60)}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val query = args.string("query").trim()
        if (query.isEmpty()) return ToolResult.Failure(ToolError.InvalidArguments("Tell me where."))
        val directions = args.string("mode").equals("directions", ignoreCase = true)
        val url = if (directions) WebLinks.mapsDirectionsUrl(query) else WebLinks.mapsSearchUrl(query)
        return when (val result = urls.open(url)) {
            LaunchResult.Launched -> ToolResult.Success(
                if (directions) "Opened Maps with directions to \"${query.take(80)}\". Press Start there to begin." else "Opened Maps with a search for \"${query.take(80)}\".",
                Verification.HANDED_OFF,
            )
            is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("No app or browser can open Maps here."))
            is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }
}

/** "What can't you do right now?": says which accesses are off and what each one would let JARVIS do. Read-only. */
class CheckAccessTool(private val reporter: AccessReporter) : Tool {
    override val spec = ToolSpec(
        name = "check_access",
        description = "Say which phone accesses (notification access, accessibility, microphone, contacts and so on) are switched on or off, and what each off one prevents.",
        params = emptyList(),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "CONFIRMED: the state of each access, read from Android just now.",
    )

    override fun describe(args: ToolArgs): String = "Check which phone accesses are switched on"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val lines = reporter.report()
        if (lines.isEmpty()) return ToolResult.Failure(ToolError.Unavailable("I could not read the access settings."))
        val off = lines.filter { !it.on }
        val on = lines.filter { it.on }
        val message = buildString {
            if (off.isEmpty()) {
                append("Every access I can use is switched on.")
            } else {
                append("${off.size} of ${lines.size} accesses are off. ")
                append(off.joinToString(" ") { "${it.title}: ${it.unlocks}." })
                append(" Open Prefs, then Access, to switch them on.")
            }
            if (on.isNotEmpty()) append(" Switched on: ${on.joinToString(", ") { it.title }}.")
        }
        return ToolResult.Success(message, Verification.CONFIRMED, mapOf("off" to off.size.toString(), "on" to on.size.toString()), answer = true)
    }
}
