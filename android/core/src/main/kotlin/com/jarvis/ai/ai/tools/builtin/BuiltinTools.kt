package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.AlarmTime
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
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** The Phase 1 tool set. Each tool says only what the platform actually reported. */
object BuiltinTools {
    fun create(ports: DevicePorts, clock: Clock = Clock.systemDefaultZone()): List<Tool> = listOf(
        FlashlightTool(ports.torch),
        SetAlarmTool(ports.alarms, clock),
        OpenUrlTool(ports.urls),
        OpenYoutubeTool(ports.urls),
        DeviceInfoTool(ports.deviceInfo),
    )
}

private fun launchFailure(result: LaunchResult): ToolResult.Failure? = when (result) {
    LaunchResult.Launched -> null
    is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("No app on this phone can open ${result.what}."))
    is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
}

class FlashlightTool(private val torch: TorchController) : Tool {
    override val spec = ToolSpec(
        name = "flashlight",
        description = "Turn the phone's flashlight (torch) on or off.",
        params = listOf(
            ParamSpec("state", ParamType.ENUM, "Desired state", enumValues = listOf("on", "off")),
        ),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        maxRetries = 1,
        resultFormat = "Message states the torch state as reported by Android's camera service.",
    )

    override fun describe(args: ToolArgs): String = "Turn the flashlight ${args.string("state")}"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val wantOn = args.string("state") == "on"
        return when (val result = torch.setTorch(wantOn)) {
            is TorchResult.Confirmed ->
                if (result.isOn == wantOn) {
                    ToolResult.Success(
                        message = "Flashlight is ${if (result.isOn) "on" else "off"} (confirmed by Android).",
                        verification = Verification.CONFIRMED,
                        data = mapOf("torch" to if (result.isOn) "on" else "off"),
                    )
                } else {
                    ToolResult.Failure(
                        ToolError.Failed("Android reports the flashlight is ${if (result.isOn) "on" else "off"}, not ${if (wantOn) "on" else "off"}.", retryable = true),
                    )
                }
            TorchResult.NoFlash -> ToolResult.Failure(ToolError.Unavailable("This phone has no flashlight."))
            is TorchResult.Unavailable -> ToolResult.Failure(ToolError.Unavailable("Flashlight unavailable: ${result.reason}"))
            is TorchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason, retryable = true))
        }
    }
}

class SetAlarmTool(
    private val alarms: AlarmLauncher,
    private val clock: Clock,
) : Tool {
    override val spec = ToolSpec(
        name = "set_alarm",
        description = "Create an alarm in the phone's Clock app for the next time the given clock time occurs.",
        params = listOf(
            ParamSpec("hour", ParamType.INT, "Hour in 24-hour time", min = 0, max = 23),
            ParamSpec("minute", ParamType.INT, "Minute", min = 0, max = 59),
            ParamSpec("label", ParamType.STRING, "Optional alarm name", required = false, maxLength = 60),
            ParamSpec(
                "date",
                ParamType.STRING,
                "Optional date the user asked for, YYYY-MM-DD. Android can only set the NEXT occurrence of the time, so a different date is refused.",
                required = false,
                maxLength = 10,
            ),
        ),
        risk = Risk.MEDIUM,
        timeoutMs = 10_000L,
        resultFormat = "CONFIRMED when the system's next-alarm matches; otherwise HANDED_OFF to the Clock app.",
    )

    override fun describe(args: ToolArgs): String {
        val label = args.stringOrNull("label")?.let { " \"$it\"" }.orEmpty()
        return "Set an alarm$label for %02d:%02d".format(Locale.ROOT, args.int("hour"), args.int("minute"))
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val hour = args.int("hour")
        val minute = args.int("minute")
        val requestedDate = args.stringOrNull("date")?.let {
            AlarmTime.parseDate(it) ?: return ToolResult.Failure(ToolError.InvalidArguments("date must look like 2026-11-07"))
        }

        val next: ZonedDateTime = AlarmTime.nextOccurrence(hour, minute, ZonedDateTime.now(clock))
        AlarmTime.dateMismatch(next, requestedDate)?.let {
            return ToolResult.Failure(ToolError.Unsupported(it))
        }

        launchFailure(alarms.setAlarm(hour, minute, args.stringOrNull("label")))?.let { return it }

        val whenText = next.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH))
        val expectedMinute = next.toInstant().toEpochMilli() / MS_PER_MINUTE
        repeat(VERIFY_POLLS) {
            delay(VERIFY_INTERVAL_MS)
            val scheduled = alarms.nextAlarmEpochMs()
            if (scheduled != null && scheduled / MS_PER_MINUTE == expectedMinute) {
                return ToolResult.Success(
                    message = "Alarm set for $whenText (the system now lists it as your next alarm).",
                    verification = Verification.CONFIRMED,
                    data = mapOf("alarm_time" to whenText),
                )
            }
        }
        return ToolResult.Success(
            message = "Asked the Clock app to set an alarm for $whenText. Android did not report it back " +
                "(it may be hidden behind an earlier alarm), so please check the Clock app.",
            verification = Verification.HANDED_OFF,
            data = mapOf("alarm_time" to whenText),
        )
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
        const val VERIFY_POLLS = 4
        const val VERIFY_INTERVAL_MS = 400L
    }
}

class OpenUrlTool(private val urls: UrlOpener) : Tool {
    override val spec = ToolSpec(
        name = "open_url",
        description = "Open a website (http or https) in the phone's browser.",
        params = listOf(ParamSpec("url", ParamType.STRING, "Web address, e.g. example.com or https://example.com/page", maxLength = 2000)),
        risk = Risk.LOW,
        resultFormat = "HANDED_OFF: Android launched the browser; the page load itself is not observable.",
    )

    override fun describe(args: ToolArgs): String = "Open ${args.string("url").take(120)} in the browser"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val url = WebLinks.normalizeWebUrl(args.string("url"))
            ?: return ToolResult.Failure(ToolError.InvalidArguments("Only plain http/https web addresses can be opened."))
        launchFailure(urls.open(url.value))?.let { return it }
        return ToolResult.Success(
            message = "Opened ${url.host} in your browser (Android launched it; the page itself is not checked).",
            verification = Verification.HANDED_OFF,
            data = mapOf("host" to url.host),
        )
    }
}

class OpenYoutubeTool(private val urls: UrlOpener) : Tool {
    override val spec = ToolSpec(
        name = "open_youtube",
        description = "Open YouTube, optionally searching for something. Uses the YouTube app if installed, otherwise the browser.",
        params = listOf(ParamSpec("query", ParamType.STRING, "What to search for; omit to just open YouTube", required = false, maxLength = 200)),
        risk = Risk.LOW,
        resultFormat = "HANDED_OFF: Android launched YouTube or the browser.",
    )

    override fun describe(args: ToolArgs): String =
        args.stringOrNull("query")?.let { "Search YouTube for \"${it.take(80)}\"" } ?: "Open YouTube"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val query = args.stringOrNull("query")
        val target = if (query == null) WebLinks.YOUTUBE_HOME else WebLinks.youtubeSearchUrl(query)
        launchFailure(urls.open(target))?.let { return it }
        return ToolResult.Success(
            message = if (query == null) "Opened YouTube (Android launched it)." else "Opened YouTube search for \"${query.take(80)}\" (Android launched it).",
            verification = Verification.HANDED_OFF,
        )
    }
}

class DeviceInfoTool(private val reader: DeviceInfoReader) : Tool {
    override val spec = ToolSpec(
        name = "get_device_info",
        description = "Read battery level and charging state, phone model, Android version, network status and free storage.",
        params = emptyList(),
        risk = Risk.LOW,
        resultFormat = "CONFIRMED: values read directly from Android; unavailable values are omitted.",
    )

    override fun describe(args: ToolArgs): String = "Read device status"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val info = reader.read()
        val parts = mutableListOf<String>()
        val data = LinkedHashMap<String, String>()

        if (info.batteryPercent != null) {
            val state = when (info.charging) {
                true -> ", charging"
                false -> ", not charging"
                null -> ""
            }
            parts += "Battery ${info.batteryPercent}%$state"
            data["battery_percent"] = info.batteryPercent.toString()
        }
        if (info.charging != null) data["charging"] = info.charging.toString()

        val device = "${info.manufacturer} ${info.model}".trim()
        parts += "$device, Android ${info.androidRelease} (API ${info.sdkInt})"
        data["device"] = device
        data["android"] = "${info.androidRelease} (API ${info.sdkInt})"

        parts += "Network: ${info.network}"
        data["network"] = info.network

        if (info.storageFreeBytes != null && info.storageTotalBytes != null) {
            parts += "Storage: ${gb(info.storageFreeBytes)} free of ${gb(info.storageTotalBytes)}"
            data["storage_free_bytes"] = info.storageFreeBytes.toString()
        }
        return ToolResult.Success(parts.joinToString(". ") + ".", Verification.CONFIRMED, data)
    }

    private fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
}
