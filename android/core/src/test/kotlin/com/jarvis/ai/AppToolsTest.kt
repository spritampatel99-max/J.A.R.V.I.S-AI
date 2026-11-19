package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.AppLauncher
import com.jarvis.ai.ai.tools.builtin.AppMatch
import com.jarvis.ai.ai.tools.builtin.AppMatching
import com.jarvis.ai.ai.tools.builtin.InstalledApp
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.OpenAppTool
import com.jarvis.ai.core.security.Risk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class AppToolsTest {

    private val apps = listOf(
        InstalledApp("WhatsApp", "com.whatsapp"),
        InstalledApp("WhatsApp Business", "com.whatsapp.w4b"),
        InstalledApp("Camera", "com.cam"),
        InstalledApp("Google Maps", "com.maps"),
        InstalledApp("Clock", "com.clock.a"),
        InstalledApp("Clock", "com.clock.b"),
        InstalledApp("Settings", "com.settings"),
        InstalledApp("Instagram", "com.insta"),
        InstalledApp("YouTube Music", "com.ytm"),
    )

    private class FakeLauncher(val apps: List<InstalledApp>, var result: LaunchResult = LaunchResult.Launched) : AppLauncher {
        val launched = mutableListOf<String>()
        override suspend fun launchable(): List<InstalledApp> = apps
        override fun launch(packageName: String): LaunchResult {
            launched += packageName
            return result
        }
    }

    // ---- matching ---------------------------------------------------------------------------------------------

    @Test
    fun anExactNameWinsOverLongerNamesThatStartTheSame() {
        assertEquals(AppMatch.One(apps[0]), AppMatching.find("whatsapp", apps))
    }

    @Test
    fun spacingAndCaseDoNotMatter() {
        assertEquals(AppMatch.One(apps[0]), AppMatching.find("Whats App", apps))
        assertEquals(AppMatch.One(apps[3]), AppMatching.find("google maps", apps))
        assertEquals(AppMatch.One(apps[3]), AppMatching.find("Google Maps app", apps))
    }

    @Test
    fun aStartOfANameFindsTheOnlyAppThatStartsThatWay() {
        assertEquals(AppMatch.One(apps[7]), AppMatching.find("insta", apps))
        assertEquals(AppMatch.One(apps[2]), AppMatching.find("cam", apps))
    }

    @Test
    fun twoAppsWithTheSameNameAreOfferedAsAChoice() {
        val several = assertIs<AppMatch.Several>(AppMatching.find("clock", apps))
        assertEquals(listOf("com.clock.a", "com.clock.b"), several.apps.map { it.packageName })
    }

    @Test
    fun aNameThatFitsSeveralAppsAsksWhichOne() {
        assertIs<AppMatch.Several>(AppMatching.find("whats", apps))
    }

    @Test
    fun unknownOrTinyNamesFindNothing() {
        assertEquals(AppMatch.None, AppMatching.find("telegram", apps))
        assertEquals(AppMatch.None, AppMatching.find("a", apps))
        assertEquals(AppMatch.None, AppMatching.find("", apps))
    }

    // ---- the tool ---------------------------------------------------------------------------------------------

    @Test
    fun openingAnAppReportsOnlyThatAndroidWasAsked() = runTest {
        val launcher = FakeLauncher(apps)

        val result = OpenAppTool(launcher).execute(ToolArgs(mapOf("name" to "camera")))

        val success = assertIs<ToolResult.Success>(result)
        assertEquals(Verification.HANDED_OFF, success.verification)
        assertEquals(listOf("com.cam"), launcher.launched)
        assertTrue("Android started it" in success.message)
    }

    @Test
    fun anAmbiguousNameLaunchesNothingAndAsks() = runTest {
        val launcher = FakeLauncher(apps)
        val error = assertIs<ToolResult.Failure>(OpenAppTool(launcher).execute(ToolArgs(mapOf("name" to "clock")))).error
        assertIs<ToolError.NeedsInput>(error)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun anAppThatIsNotInstalledIsSaidPlainly() = runTest {
        val launcher = FakeLauncher(apps)
        val error = assertIs<ToolResult.Failure>(OpenAppTool(launcher).execute(ToolArgs(mapOf("name" to "telegram")))).error
        assertTrue("could not find an app called \"telegram\"" in error.message)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun aRefusedLaunchIsNotReportedAsOpened() = runTest {
        val launcher = FakeLauncher(apps, LaunchResult.Failed("Android blocks opening apps in the background."))
        val result = OpenAppTool(launcher).execute(ToolArgs(mapOf("name" to "camera")))
        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun openingAnAppIsLowRiskAndOnlyOpens() {
        val spec = OpenAppTool(FakeLauncher(apps)).spec
        assertEquals(Risk.LOW, spec.risk)
        assertTrue(spec.params.single().name == "name")
    }

    // ---- built-in command matching ---------------------------------------------------------------------------

    private val clock = Clock.fixed(Instant.parse("2026-11-11T05:00:00Z"), ZoneOffset.ofHoursMinutes(5, 30))

    private fun appNameFor(text: String): String? {
        val match = NativeCommands.match(text, clock) as? NativeCommands.Match.Run ?: return null
        val step = match.steps.singleOrNull() ?: return null
        return if (step.tool == "open_app") step.parameters["name"]!!.jsonPrimitive.content else null
    }

    @Test
    fun spokenOpenCommandsFindTheName() {
        assertEquals("WhatsApp", appNameFor("open WhatsApp"))
        assertEquals("camera", appNameFor("launch the camera app"))
        assertEquals("gallery", appNameFor("open my gallery"))
        assertEquals("Google Maps", appNameFor("Hey Jarvis, open Google Maps"))
        assertEquals("settings", appNameFor("please start settings"))
    }

    @Test
    fun otherUsesOfOpenAreNotTreatedAsApps() {
        assertNull(appNameFor("open it"))
        assertNull(appNameFor("open the door"))
        assertNull(appNameFor("open a new tab"))
        assertNull(appNameFor("open notes.txt"))
        assertNull(appNameFor("open https://example.com"))
        assertNull(appNameFor("open the application form for my passport renewal please"))
    }

    @Test
    fun theYouTubeAndWebsiteCommandsKeepWinning() {
        val youtube = NativeCommands.match("open youtube", clock) as NativeCommands.Match.Run
        assertEquals("open_youtube", youtube.steps.single().tool)
        val site = NativeCommands.match("open example.com", clock) as NativeCommands.Match.Run
        assertEquals("open_url", site.steps.single().tool)
    }
}
