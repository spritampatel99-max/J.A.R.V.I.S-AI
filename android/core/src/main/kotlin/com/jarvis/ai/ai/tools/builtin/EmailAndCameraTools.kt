package com.jarvis.ai.ai.tools.builtin

import com.jarvis.ai.ai.tools.ParamSpec
import com.jarvis.ai.ai.tools.ParamType
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.Tool
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.ToolSpec
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.core.security.Risk
import java.util.Locale

/** What the platform can do for email and the camera. Both only open another app; neither sends or captures anything. */
interface EmailPort {
    /**
     * Opens the person's email app with a new draft ([to] may be null, [subject] and [body] may be empty).
     * Nothing is sent: the person presses Send there.
     */
    fun compose(to: String?, subject: String, body: String): MessageHandoff
}

interface CameraLauncher {
    /** Opens the phone's camera app in still-photo mode, or in video mode when [video] is true. It takes nothing by itself. */
    fun open(video: Boolean): LaunchResult
}

/** One plain email address. Display names, several addresses and non-ASCII addresses are not accepted: the app asks for a simple one. */
internal object EmailAddress {
    private const val MAX_LENGTH = 254
    private val SHAPE = Regex("""^[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9\-]{0,61}[A-Za-z0-9])?)*\.[A-Za-z]{2,24}$""")

    /** The address trimmed and unchanged, or null when it is not one plain address. */
    fun parse(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LENGTH || !SHAPE.matches(text)) return null
        val local = text.substringBefore('@')
        if (local.startsWith('.') || local.endsWith('.') || ".." in text) return null
        return text
    }
}

/**
 * Email. Like the message tools it never sends: it opens the email app with the draft typed in and the person presses Send. The
 * person first sees the exact address, subject and text and must tap to confirm ([Risk.HIGH]); the model can never approve it.
 * The address must be a typed email address: this app has no way to look up a contact's email, and it will not guess one.
 */
class ComposeEmailTool(private val email: EmailPort) : Tool {
    override val spec = ToolSpec(
        name = "compose_email",
        description = "Write an email draft. It opens the email app with the address, subject and text typed in; the user presses Send there. " +
            "The address must be a full email address the user gave; do not guess one. The user must confirm first.",
        params = listOf(
            ParamSpec("to", ParamType.STRING, "The recipient's full email address, for example name@example.com", required = false, maxLength = 254),
            ParamSpec("subject", ParamType.STRING, "The subject line", required = false, maxLength = MAX_SUBJECT),
            ParamSpec("body", ParamType.STRING, "The words of the email, exactly as the user wants them", required = false, maxLength = MAX_BODY),
        ),
        risk = Risk.HIGH,
        timeoutMs = 10_000L,
        resultFormat = "States that the email app was opened with the draft ready. It never says the email was sent.",
    )

    private fun cleanSubject(raw: String?): String =
        raw.orEmpty().map { if (it == '\n' || it == '\t') ' ' else it }.filter { !it.isISOControl() }.joinToString("").trim()

    private fun cleanBody(raw: String?): String = MessageText.clean(raw.orEmpty())

    override suspend fun prepare(args: ToolArgs): Prepared {
        val rawTo = args.stringOrNull("to")?.trim().orEmpty()
        val to = if (rawTo.isEmpty()) "" else EmailAddress.parse(rawTo)
            ?: return Prepared.Stop(ToolError.InvalidArguments("That is not a plain email address. Give the full address, for example name@example.com."))
        val subject = cleanSubject(args.stringOrNull("subject"))
        val body = cleanBody(args.stringOrNull("body"))
        if (to.isEmpty() && subject.isEmpty() && body.isEmpty()) {
            return Prepared.Stop(ToolError.InvalidArguments("Say who the email is to, or what it should say."))
        }
        return Prepared.Ready(ToolArgs(mapOf("to" to to, "subject" to subject, "body" to body)))
    }

    override fun describe(args: ToolArgs): String {
        val to = args.stringOrNull("to").orEmpty()
        val subject = args.stringOrNull("subject").orEmpty()
        val body = args.stringOrNull("body").orEmpty()
        return buildString {
            append("Email ").append(to.ifEmpty { "(no recipient yet)" })
            if (subject.isNotEmpty()) append(", subject \"").append(subject.take(100)).append('"')
            if (body.isNotEmpty()) append(": \"").append(MessageText.preview(body)).append('"')
        }
    }

    override suspend fun execute(args: ToolArgs): ToolResult {
        val to = args.stringOrNull("to").orEmpty()
        if (to.isNotEmpty() && EmailAddress.parse(to) == null) {
            return ToolResult.Failure(ToolError.InvalidArguments("That is not a plain email address."))
        }
        val subject = cleanSubject(args.stringOrNull("subject"))
        val body = cleanBody(args.stringOrNull("body"))
        if (to.isEmpty() && subject.isEmpty() && body.isEmpty()) {
            return ToolResult.Failure(ToolError.InvalidArguments("The email was empty."))
        }
        return when (val result = email.compose(to.ifEmpty { null }, subject, body)) {
            MessageHandoff.Opened -> ToolResult.Success(
                "Opened your email app with the draft${if (to.isEmpty()) "" else " to $to"} ready. It has not been sent: read it and press Send there.",
                Verification.HANDED_OFF,
            )
            is MessageHandoff.NoApp -> ToolResult.Failure(ToolError.Unavailable("This phone has no email app that can write a draft."))
            is MessageHandoff.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }

    companion object {
        const val MAX_SUBJECT = 200
        const val MAX_BODY = 4000
    }
}

/**
 * Opens the camera app. It cannot press the shutter, record, or read what the camera sees, and it needs no camera permission
 * because it only starts the phone's own camera app.
 */
class OpenCameraTool(private val camera: CameraLauncher) : Tool {
    override val spec = ToolSpec(
        name = "open_camera",
        description = "Open the phone's camera app, ready to take a photo or to record a video. " +
            "It only opens the camera: the user presses the shutter or record button; this cannot take the picture.",
        params = listOf(
            ParamSpec("mode", ParamType.ENUM, "photo (default) or video", required = false, enumValues = listOf("photo", "video")),
        ),
        risk = Risk.LOW,
        timeoutMs = 5_000L,
        resultFormat = "HANDED_OFF: Android opened the camera app; nothing was captured and whether it is showing is not observable.",
    )

    private fun video(args: ToolArgs): Boolean = args.stringOrNull("mode")?.lowercase(Locale.ROOT) == "video"

    override fun describe(args: ToolArgs): String = if (video(args)) "Open the camera for a video" else "Open the camera for a photo"

    override suspend fun execute(args: ToolArgs): ToolResult {
        val video = video(args)
        return when (val result = camera.open(video)) {
            LaunchResult.Launched -> ToolResult.Success(
                "Opened the camera ready for ${if (video) "a video" else "a photo"}. I can't press the ${if (video) "record" else "shutter"} button for you; do that yourself.",
                Verification.HANDED_OFF,
                mapOf("mode" to if (video) "video" else "photo"),
            )
            is LaunchResult.NoHandler -> ToolResult.Failure(ToolError.Unavailable("This phone has no camera app that can be opened."))
            is LaunchResult.Failed -> ToolResult.Failure(ToolError.Failed(result.reason))
        }
    }
}
