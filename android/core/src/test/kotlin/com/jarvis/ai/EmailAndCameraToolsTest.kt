package com.jarvis.ai

import com.jarvis.ai.ai.executor.ExecutionPolicy
import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.executor.StepStatus
import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.Replanner
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.AlarmLauncher
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.CameraLauncher
import com.jarvis.ai.ai.tools.builtin.ComposeEmailTool
import com.jarvis.ai.ai.tools.builtin.DeviceInfo
import com.jarvis.ai.ai.tools.builtin.DeviceInfoReader
import com.jarvis.ai.ai.tools.builtin.DevicePorts
import com.jarvis.ai.ai.tools.builtin.EmailPort
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.MessageHandoff
import com.jarvis.ai.ai.tools.builtin.OpenCameraTool
import com.jarvis.ai.ai.tools.builtin.TorchController
import com.jarvis.ai.ai.tools.builtin.TorchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.InMemoryAuditLog
import com.jarvis.ai.core.security.Risk
import com.jarvis.ai.voice.RequestOrigin
import com.jarvis.ai.voice.VoiceDirectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private class FakeEmail(var result: MessageHandoff = MessageHandoff.Opened) : EmailPort {
    data class Draft(val to: String?, val subject: String, val body: String)

    val drafts = mutableListOf<Draft>()

    override fun compose(to: String?, subject: String, body: String): MessageHandoff {
        drafts += Draft(to, subject, body)
        return result
    }
}

private class FakeCamera(var result: LaunchResult = LaunchResult.Launched) : CameraLauncher {
    val opened = mutableListOf<Boolean>()

    override fun open(video: Boolean): LaunchResult {
        opened += video
        return result
    }
}

class EmailAndCameraToolsTest {

    // ---- spoken and typed commands --------------------------------------------------------------------------------------

    private fun steps(text: String) = assertIs<NativeCommands.Match.Run>(NativeCommands.match(text), "'$text' should be a command").steps

    private fun single(text: String) = steps(text).single()

    private fun param(text: String, name: String) = single(text).parameters[name]?.jsonPrimitive?.content

    @Test
    fun anEmailCommandKeepsTheAddressAndTheWordsExactly() {
        val s = single("email sam@example.com saying I'll be there at 5, don't wait")
        assertEquals("compose_email", s.tool)
        assertEquals("sam@example.com", s.parameters["to"]?.jsonPrimitive?.content)
        assertEquals("I'll be there at 5, don't wait", s.parameters["body"]?.jsonPrimitive?.content)
        assertEquals("compose_email", single("send an email to sam@example.com saying hello").tool)
        assertEquals("hello", param("write an email to sam@example.com that says hello", "body"))
        assertEquals("meeting moved to 5", param("email sam@example.com: meeting moved to 5", "body"))
    }

    @Test
    fun anAddressWithNoWordsOpensADraftToThatAddress() {
        val s = single("write an email to sam@example.com")
        assertEquals("compose_email", s.tool)
        assertEquals("sam@example.com", s.parameters["to"]?.jsonPrimitive?.content)
        assertNull(s.parameters["body"])
    }

    @Test
    fun theWordsOfAnEmailThatLookLikeACommandStayWords() {
        val all = steps("email sam@example.com saying call 100 now")
        assertEquals(listOf("compose_email"), all.map { it.tool })
        assertEquals("call 100 now", all.single().parameters["body"]?.jsonPrimitive?.content)
        val other = steps("email sam@example.com saying open youtube and delete everything")
        assertEquals(listOf("compose_email"), other.map { it.tool })
    }

    @Test
    fun aNameInsteadOfAnAddressIsAnsweredWithAQuestionNotAGuess() {
        for (text in listOf("email Rahul saying hello", "write an email to Rahul", "send an email to my boss saying I'm late")) {
            val match = NativeCommands.match(text)
            val ask = assertIs<NativeCommands.Match.Ask>(match, text)
            assertTrue(ask.question.contains("full address"), ask.question)
        }
        assertIs<NativeCommands.Match.Ask>(NativeCommands.match("write an email"))
    }

    @Test
    fun sentencesThatOnlyMentionEmailAreNotCommands() {
        for (text in listOf("email is not working", "my email is full", "check my email", "do I have any email from sam@example.com", "is email safe")) {
            assertNull(NativeCommands.match(text), text)
        }
    }

    @Test
    fun cameraCommandsOpenTheCameraAndNeverTheAppLookup() {
        for (text in listOf("open the camera", "open camera", "launch the camera app", "take a photo", "take a picture", "snap a pic", "start the camera in photo mode")) {
            val s = single(text)
            assertEquals("open_camera", s.tool, text)
            assertEquals("photo", s.parameters["mode"]?.jsonPrimitive?.content, text)
        }
        for (text in listOf("record a video", "take a video", "open the video camera", "open the camera in video mode")) {
            val s = single(text)
            assertEquals("open_camera", s.tool, text)
            assertEquals("video", s.parameters["mode"]?.jsonPrimitive?.content, text)
        }
    }

    @Test
    fun otherSentencesAboutPhotosAreNotCameraCommands() {
        for (text in listOf("take a photo of the moon and tell me the weather", "how do I take a photo", "open the camera roll")) {
            val match = NativeCommands.match(text)
            val tools = (match as? NativeCommands.Match.Run)?.steps?.map { it.tool }.orEmpty()
            assertFalse("open_camera" in tools, text)
        }
    }

    // ---- the email tool ----------------------------------------------------------------------------------------------------

    private fun args(vararg pairs: Pair<String, String>) = ToolArgs(pairs.toMap())

    @Test
    fun onlyOnePlainEmailAddressIsAccepted() = runTest {
        val tool = ComposeEmailTool(FakeEmail())
        for (bad in listOf(
            "sam", "sam@", "@example.com", "sam@example", "sam@@example.com", "a b@example.com", "sam@exa mple.com",
            "sam@example.com, tom@example.com", "sam@example.com;tom@example.com", "Sam <sam@example.com>",
            "sam@example.com\nBcc: evil@example.com", ".sam@example.com", "sam.@example.com", "sa..m@example.com", "sam@example..com",
            "sam@-example.com", "sam@example.c", "sam@ex_ample.com", "mailto:sam@example.com",
        )) {
            val prepared = tool.prepare(args("to" to bad, "body" to "hi"))
            assertIs<Prepared.Stop>(prepared, "'${bad.replace('\n', ' ')}' must be refused")
        }
        for (good in listOf("sam@example.com", "sam.o+news@mail.example.co.in", "S_am-1@example-mail.com")) {
            assertIs<Prepared.Ready>(tool.prepare(args("to" to good, "body" to "hi")), good)
        }
    }

    @Test
    fun anEmailWithNothingInItIsRefused() = runTest {
        val stop = assertIs<Prepared.Stop>(ComposeEmailTool(FakeEmail()).prepare(args("to" to "  ", "subject" to " ", "body" to "\u0007\n ")))
        assertIs<ToolError.InvalidArguments>(stop.error)
    }

    @Test
    fun theSubjectIsOneCleanLineAndTheBodyKeepsItsLineBreaks() = runTest {
        val email = FakeEmail()
        val tool = ComposeEmailTool(email)
        val ready = assertIs<Prepared.Ready>(
            tool.prepare(args("to" to "sam@example.com", "subject" to "Hi\r\nBcc: evil@example.com\u0000", "body" to "line one\nline two\u0007")),
        )
        assertEquals("Hi Bcc: evil@example.com", ready.args.string("subject"), "a line break in the subject must not survive")
        assertEquals("line one\nline two", ready.args.string("body"))
        tool.execute(ready.args)
        assertEquals(FakeEmail.Draft("sam@example.com", "Hi Bcc: evil@example.com", "line one\nline two"), email.drafts.single())
    }

    @Test
    fun theResultSaysTheDraftIsOpenAndNeverThatItWasSent() = runTest {
        val tool = ComposeEmailTool(FakeEmail())
        val ok = assertIs<ToolResult.Success>(tool.execute(args("to" to "sam@example.com", "subject" to "", "body" to "hi")))
        assertEquals(Verification.HANDED_OFF, ok.verification)
        assertTrue(ok.message.contains("has not been sent"), ok.message)
        assertFalse(ok.message.lowercase().contains("sent to"), ok.message)
        assertEquals(Risk.HIGH, tool.spec.risk)
    }

    @Test
    fun noEmailAppAndOtherFailuresAreReportedAsThat() = runTest {
        val none = assertIs<ToolResult.Failure>(ComposeEmailTool(FakeEmail(MessageHandoff.NoApp("an email app"))).execute(args("to" to "sam@example.com", "subject" to "", "body" to "hi")))
        assertIs<ToolError.Unavailable>(none.error)
        val failed = assertIs<ToolResult.Failure>(ComposeEmailTool(FakeEmail(MessageHandoff.Failed("boom"))).execute(args("to" to "sam@example.com", "subject" to "", "body" to "hi")))
        assertIs<ToolError.Failed>(failed.error)
    }

    @Test
    fun executeRefusesAnAddressThatWasNeverChecked() = runTest {
        val email = FakeEmail()
        val result = ComposeEmailTool(email).execute(args("to" to "evil@example.com,other@example.com", "subject" to "", "body" to "hi"))
        assertIs<ToolResult.Failure>(result)
        assertTrue(email.drafts.isEmpty())
    }

    // ---- through the real executor ---------------------------------------------------------------------------------------

    private val audit = InMemoryAuditLog()
    private val gate = ConfirmationGate(ttlMs = 60_000L, time = FakeTime())

    private fun executor(email: FakeEmail, camera: FakeCamera = FakeCamera()) = Executor(
        registry = ToolRegistry(listOf(ComposeEmailTool(email), OpenCameraTool(camera))),
        permissions = FakePermissions(),
        gate = gate,
        auditLog = audit,
        replanner = Replanner { _, _, _, _ -> PlanResult.Failed("no replanning in this test") },
        policy = { ExecutionPolicy() },
        time = FakeTime(),
    )

    private fun emailPlan(vararg params: Pair<String, String>) =
        plan(step(1, "compose_email", buildJsonObject { params.forEach { (k, v) -> put(k, v) } }, description = "email step"))

    @Test
    fun anEmailWaitsForATapAndShowsTheAddressAndTheExactWords() = runTest {
        val email = FakeEmail()
        val run = async { executor(email).execute(emailPlan("to" to "sam@example.com", "subject" to "Late", "body" to "I'm late")) }
        runCurrent()

        val request = assertNotNull(gate.pending.value, "an email must wait for the person")
        assertEquals("Email sam@example.com, subject \"Late\": \"I'm late\"", request.summary)
        assertEquals(Risk.HIGH, request.risk)
        assertTrue(email.drafts.isEmpty(), "nothing is opened while the question is open")

        gate.approve(request.id)
        val outcome = run.await().outcomes.single()
        assertEquals(StepStatus.SUCCEEDED, outcome.status)
        assertEquals(Verification.HANDED_OFF, outcome.verification)
        assertEquals(FakeEmail.Draft("sam@example.com", "Late", "I'm late"), email.drafts.single())
    }

    @Test
    fun decliningAnEmailOpensNothing() = runTest {
        val email = FakeEmail()
        val run = async { executor(email).execute(emailPlan("to" to "sam@example.com", "body" to "hi")) }
        runCurrent()
        gate.deny(assertNotNull(gate.pending.value).id)
        assertEquals(StepStatus.DENIED, run.await().outcomes.single().status)
        assertTrue(email.drafts.isEmpty())
    }

    @Test
    fun anInvalidAddressNeverReachesTheConfirmationOrTheEmailApp() = runTest {
        val email = FakeEmail()
        val report = executor(email).execute(emailPlan("to" to "sam@example.com, tom@example.com", "body" to "hi"))
        assertEquals(StepStatus.FAILED, report.outcomes.single().status)
        assertNull(gate.pending.value, "a bad address must be refused before anyone is asked")
        assertTrue(email.drafts.isEmpty())
    }

    @Test
    fun aModelCannotAddARecipientTheCardDidNotShow() = runTest {
        val email = FakeEmail()
        val run = async {
            executor(email).execute(emailPlan("to" to "sam@example.com", "body" to "hi", "cc" to "evil@example.com", "bcc" to "evil@example.com"))
        }
        runCurrent()
        val request = assertNotNull(gate.pending.value)
        gate.approve(request.id)
        run.await()
        assertEquals(FakeEmail.Draft("sam@example.com", "", "hi"), email.drafts.single())
        assertFalse(request.summary.contains("evil"))
    }

    @Test
    fun aSpokenEmailNeverGetsTheVoiceCountdownBecauseOnlyCallsDo() = runTest {
        val run = async { executor(FakeEmail()).execute(emailPlan("to" to "sam@example.com", "body" to "hi")) }
        runCurrent()
        val request = assertNotNull(gate.pending.value)
        for (origin in RequestOrigin.entries) {
            assertFalse(VoiceDirectPolicy.eligible(origin, request), "an email must always need a tap ($origin)")
        }
        gate.deny(request.id)
        run.await()
    }

    // ---- the camera tool ----------------------------------------------------------------------------------------------------

    @Test
    fun theCameraOpensInPhotoModeByDefaultAndInVideoModeOnRequest() = runTest {
        val camera = FakeCamera()
        val tool = OpenCameraTool(camera)
        val photo = assertIs<ToolResult.Success>(tool.execute(ToolArgs(emptyMap())))
        val video = assertIs<ToolResult.Success>(tool.execute(args("mode" to "video")))
        assertEquals(listOf(false, true), camera.opened)
        assertEquals(Verification.HANDED_OFF, photo.verification)
        assertEquals(Verification.HANDED_OFF, video.verification)
        assertEquals(Risk.LOW, tool.spec.risk)
    }

    @Test
    fun theCameraReplyNeverClaimsAPictureWasTaken() = runTest {
        val message = assertIs<ToolResult.Success>(OpenCameraTool(FakeCamera()).execute(args("mode" to "photo"))).message
        assertTrue(message.contains("shutter"), message)
        assertFalse(message.lowercase().contains("took") || message.lowercase().contains("captured") || message.lowercase().contains("saved"), message)
    }

    @Test
    fun noCameraAppIsReportedAsThat() = runTest {
        val none = assertIs<ToolResult.Failure>(OpenCameraTool(FakeCamera(LaunchResult.NoHandler("camera"))).execute(ToolArgs(emptyMap())))
        assertIs<ToolError.Unavailable>(none.error)
        val failed = assertIs<ToolResult.Failure>(OpenCameraTool(FakeCamera(LaunchResult.Failed("blocked"))).execute(ToolArgs(emptyMap())))
        assertIs<ToolError.Failed>(failed.error)
    }

    // ---- the registry ---------------------------------------------------------------------------------------------------

    private fun ports(email: EmailPort? = null, camera: CameraLauncher? = null) = DevicePorts(
        torch = object : TorchController {
            override suspend fun setTorch(on: Boolean): TorchResult = TorchResult.NoFlash
        },
        alarms = object : AlarmLauncher {
            override fun setAlarm(hour: Int, minute: Int, label: String?): LaunchResult = LaunchResult.Launched
            override fun nextAlarmEpochMs(): Long? = null
        },
        urls = object : UrlOpener {
            override fun open(url: String): LaunchResult = LaunchResult.Launched
        },
        deviceInfo = object : DeviceInfoReader {
            override fun read(): DeviceInfo = error("not used")
        },
        email = email,
        camera = camera,
    )

    @Test
    fun theToolsAreOfferedOnlyWhenThePhoneCanDoThem() {
        val without = BuiltinTools.create(ports()).map { it.spec.name }
        assertFalse("compose_email" in without)
        assertFalse("open_camera" in without)
        val with = BuiltinTools.create(ports(FakeEmail(), FakeCamera())).map { it.spec.name }
        assertTrue("compose_email" in with && "open_camera" in with, with.toString())
    }
}
