package com.jarvis.ai.ai.agent

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

/**
 * "Do it for me in any app": hands a task to [PhoneAgent]. Always HIGH risk, so the person sees and approves the task in words before
 * anything starts, and only available while their own "phone operator" switch is on (it is off until they turn it on).
 */
class OperatePhoneTool(
    private val enabled: () -> Boolean,
    private val agent: PhoneAgent,
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Carry out a task inside other apps by looking at the screen and pressing controls, one step at a time, for example " +
            "\"open Instagram and go to my messages\" or \"search Amazon for a phone case\". Use it ONLY when no other tool does the job. " +
            "It asks the person before anything critical (send, post, delete, install) and never pays or buys. It is off unless the person turned it on.",
        params = listOf(ParamSpec("goal", ParamType.STRING, "What to achieve, in the person's own words", maxLength = 300)),
        risk = Risk.HIGH,
        timeoutMs = 600_000L,
        resultFormat = "HANDED_OFF: the steps were carried out by pressing controls; Android does not report whether each app did what was pressed. The text is the helper's own summary.",
    )

    override suspend fun prepare(args: ToolArgs): Prepared {
        if (!enabled()) {
            return Prepared.Stop(
                ToolError.Unavailable(
                    "The phone operator is switched off. Turn it on in Prefs > Access > Phone operator (it also needs Accessibility and \"press buttons and type\"), then ask again.",
                ),
            )
        }
        if (args.string("goal").isBlank()) return Prepared.Stop(ToolError.InvalidArguments("Tell me what to do."))
        return Prepared.Ready(args)
    }

    override fun describe(args: ToolArgs): String =
        "Operate apps on this phone to: ${args.string("goal").filter { !it.isISOControl() }.take(120)}. " +
            "JARVIS will look at the screen, send what it sees to your AI provider, and press controls one at a time. It asks again before anything critical and never pays."

    override suspend fun execute(args: ToolArgs): ToolResult {
        val report = agent.run(args.string("goal"))
        val steps = report.done.size
        val trail = if (report.done.isEmpty()) "" else "\nSteps: " + report.done.takeLast(MAX_TRAIL).joinToString("; ") + "."
        return when (val end = report.end) {
            is AgentEnd.Finished -> ToolResult.Success(
                "${end.message.trim().ifEmpty { "Done." }} (I pressed controls in $steps step${if (steps == 1) "" else "s"}; Android does not tell me whether each app did what was pressed, so please check.)$trail",
                Verification.HANDED_OFF,
                untrusted = true,
                answer = true,
                private = true,
            )
            is AgentEnd.NeedsAnswer -> ToolResult.Failure(ToolError.NeedsInput(end.question))
            is AgentEnd.GaveUp -> ToolResult.Failure(ToolError.Unsupported("I stopped after $steps step${if (steps == 1) "" else "s"}: ${end.reason}$trail"))
            is AgentEnd.Declined -> ToolResult.Failure(ToolError.Unsupported("You said no to pressing \"${end.label.take(40)}\", so I stopped there.$trail"))
            is AgentEnd.Refused -> ToolResult.Failure(
                ToolError.Unsupported("The next step was \"${end.label.take(40)}\", which pays or buys something. I never press those: please do that step yourself.$trail"),
            )
            is AgentEnd.OffLimits -> ToolResult.Failure(
                ToolError.Unsupported("I do not read or control ${end.app}. Payment apps, password managers, system permission screens and JARVIS 3.0 itself are off limits.$trail"),
            )
            AgentEnd.NotEnabled -> ToolResult.Failure(
                ToolError.Unavailable("Screen access is off. Turn on JARVIS 3.0 in Settings > Accessibility (Prefs > Access has a shortcut), then ask again."),
            )
        }
    }

    companion object {
        const val NAME = "operate_phone"
        private const val MAX_TRAIL = 6
    }
}
