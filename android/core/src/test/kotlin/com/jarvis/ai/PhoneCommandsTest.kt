package com.jarvis.ai

import com.jarvis.ai.ai.intent.NativeCommands
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.WebLinks
import com.jarvis.ai.ai.tools.builtin.AccessLine
import com.jarvis.ai.ai.tools.builtin.AccessReporter
import com.jarvis.ai.ai.tools.builtin.AppLauncher
import com.jarvis.ai.ai.tools.builtin.BrightnessResult
import com.jarvis.ai.ai.tools.builtin.CalculateTool
import com.jarvis.ai.ai.tools.builtin.Calculator
import com.jarvis.ai.ai.tools.builtin.CheckAccessTool
import com.jarvis.ai.ai.tools.builtin.ConvertUnitsTool
import com.jarvis.ai.ai.tools.builtin.CopyToClipboardTool
import com.jarvis.ai.ai.tools.builtin.CountAppsTool
import com.jarvis.ai.ai.tools.builtin.DismissAlarmTool
import com.jarvis.ai.ai.tools.builtin.InstalledApp
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.OpenMapsTool
import com.jarvis.ai.ai.tools.builtin.OpenSettingsTool
import com.jarvis.ai.ai.tools.builtin.PhoneSettingsPort
import com.jarvis.ai.ai.tools.builtin.RandomPickTool
import com.jarvis.ai.ai.tools.builtin.SetBrightnessTool
import com.jarvis.ai.ai.tools.builtin.SetSoundModeTool
import com.jarvis.ai.ai.tools.builtin.SettingsPage
import com.jarvis.ai.ai.tools.builtin.SoundMode
import com.jarvis.ai.ai.tools.builtin.SoundResult
import com.jarvis.ai.ai.tools.builtin.UnitConverter
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive

class PhoneCommandsTest {

    private fun value(text: String): String = assertIs<Calculator.Outcome.Value>(Calculator.evaluate(text), text).text
    private fun error(text: String): String = assertIs<Calculator.Outcome.Error>(Calculator.evaluate(text), text).message

    // ---- calculator ------------------------------------------------------------------------------------------------------

    @Test
    fun spokenArithmeticIsWorkedOutExactly() {
        assertEquals("360", value("15 percent of 2400"))
        assertEquals("20", value("(2 + 3) * 4"))
        assertEquals("1024", value("2 to the power of 10"))
        assertEquals("12", value("square root of 144"))
        assertEquals("9", value("3 squared"))
        assertEquals("0.3", value("0.1 + 0.2"))
        assertEquals("5", value("10 divided by 2"))
        assertEquals("14", value("2 plus 3 times 4"))
        assertEquals("1500", value("1,000 plus 500"))
        assertEquals("-3", value("2 minus 5"))
        assertEquals("6", value("2 x 3"))
        assertEquals("3.33333333333", value("10 / 3"))
        assertEquals("0.5", value("50%"))
    }

    @Test
    fun badOrDangerousArithmeticIsRefusedNotGuessed() {
        assertTrue(error("1 / 0").contains("zero"))
        assertTrue(error("square root of -4").contains("negative"))
        assertTrue(error("10 ^ 500").contains("large"))
        assertTrue(error("9 ^ 9 ^ 9").isNotEmpty())
        assertTrue(error("(".repeat(60) + "1" + ")".repeat(60)).isNotEmpty())
        assertTrue(error("1 + ".repeat(80) + "1").isNotEmpty())
        assertTrue(error("2 + System.exit(0)").isNotEmpty())
        assertTrue(error("2; rm -rf /").isNotEmpty())
        assertTrue(error("2 +").isNotEmpty())
        assertTrue(error("").isNotEmpty())
        assertTrue(error("1".repeat(200)).isNotEmpty())
    }

    @Test
    fun aSentenceIsNeverTakenForASum() {
        assertFalse(Calculator.looksLikeArithmetic("call mom"))
        assertFalse(Calculator.looksLikeSum("2024"))
        assertFalse(Calculator.looksLikeSum("hello 5"))
        assertTrue(Calculator.looksLikeSum("5 plus 5"))
    }

    // ---- unit converter --------------------------------------------------------------------------------------------------

    private fun converted(value: Double, from: String, to: String): String =
        assertIs<UnitConverter.Outcome.Done>(UnitConverter.convert(value, from, to), "$value $from to $to").text

    @Test
    fun unitsConvertCorrectly() {
        assertTrue(converted(5.0, "km", "miles").contains("3.10686"), converted(5.0, "km", "miles"))
        assertTrue(converted(100.0, "celsius", "fahrenheit").contains("212"))
        assertTrue(converted(32.0, "fahrenheit", "celsius").contains(" 0 "), converted(32.0, "fahrenheit", "celsius"))
        assertTrue(converted(1.0, "gigabyte", "megabytes").contains("1000"))
        assertTrue(converted(1.0, "kg", "pounds").contains("2.20462"))
    }

    @Test
    fun unitsOfDifferentKindsAreRefused() {
        assertIs<UnitConverter.Outcome.Refused>(UnitConverter.convert(5.0, "kilometres", "kilograms"))
        assertIs<UnitConverter.Outcome.Refused>(UnitConverter.convert(5.0, "banana", "miles"))
        assertNull(UnitConverter.resolve("people"))
    }

    // ---- phrases ---------------------------------------------------------------------------------------------------------

    private fun step(text: String) = (NativeCommands.match(text) as? NativeCommands.Match.Run)?.steps?.singleOrNull()
    private fun tool(text: String): String? = step(text)?.tool
    private fun arg(text: String, name: String): String? = step(text)?.parameters?.get(name)?.jsonPrimitive?.content

    @Test
    fun calculatorPhrasesRunTheCalculator() {
        for (t in listOf("what is 15 percent of 2400", "calculate 12 times 12", "what's 5 plus 7", "how much is 100 divided by 8", "15 percent of 2400", "2 plus 2", "what is the square root of 144")) {
            assertEquals("calculate", tool(t), t)
        }
        assertEquals("15 percent of 2400", arg("what is 15 percent of 2400", "expression"))
    }

    @Test
    fun plainNumbersAndQuestionsAreNotCalculations() {
        for (t in listOf("what is 2024", "what is the capital of france", "call 555-1234", "9876-5432", "what is 5", "what is love")) {
            assertTrue(tool(t) != "calculate", t)
        }
    }

    @Test
    fun conversionPhrasesRunTheConverter() {
        assertEquals("convert_units", tool("convert 5 km to miles"))
        assertEquals("convert_units", tool("how many miles is 5 kilometres"))
        assertEquals("convert_units", tool("what is 100 fahrenheit in celsius"))
        assertEquals("convert_units", tool("how many metres in a kilometre"))
        assertEquals("1", arg("how many metres in a kilometre", "value"))
        assertEquals("kilometre", arg("how many metres in a kilometre", "from"))
        assertEquals("metres", arg("how many metres in a kilometre", "to"))
        assertNull(tool("how many people in a car"))
        assertNull(tool("convert 5 apples to oranges"))
    }

    @Test
    fun coinDiceAndRandomNumberPhrases() {
        assertEquals("random_pick", tool("flip a coin"))
        assertEquals("random_pick", tool("heads or tails"))
        assertEquals("dice", arg("roll a dice", "kind"))
        assertEquals("20", arg("roll a 20 sided die", "sides"))
        assertEquals("number", arg("pick a random number between 5 and 50", "kind"))
        assertEquals("5", arg("pick a random number between 5 and 50", "low"))
        assertNull(tool("pick a random number between 50 and 5"))
    }

    @Test
    fun brightnessPhrases() {
        assertEquals("60", arg("set brightness to 60 percent", "percent"))
        assertEquals("60", arg("brightness 60", "percent"))
        assertEquals("100", arg("max brightness", "percent"))
        assertEquals("up", arg("increase brightness", "action"))
        assertEquals("up", arg("make the screen brighter", "action"))
        assertEquals("down", arg("dim the screen", "action"))
        assertEquals("down", arg("lower the brightness", "action"))
        assertNull(tool("set brightness to 300"))
        assertNull(tool("set brightness to 0"))
    }

    @Test
    fun ringerModePhrases() {
        assertEquals("silent", arg("put the phone on silent", "mode"))
        assertEquals("vibrate", arg("vibrate mode", "mode"))
        assertEquals("normal", arg("turn off silent mode", "mode"))
        assertEquals("silent", arg("mute the phone", "mode"))
        assertEquals("normal", arg("unmute my phone", "mode"))
    }

    @Test
    fun switchesAndSettingsOpenTheRightPage() {
        assertEquals("wifi", arg("turn on wifi", "page"))
        assertEquals("bluetooth", arg("turn off bluetooth", "page"))
        assertEquals("airplane_mode", arg("turn airplane mode on", "page"))
        assertEquals("wifi", arg("wi-fi settings", "page"))
        assertEquals("battery", arg("open battery settings", "page"))
        assertEquals("hotspot", arg("enable hotspot", "page"))
        assertEquals("location", arg("go to location settings", "page"))
    }

    @Test
    fun settingsPhrasesDoNotCatchOtherRequests() {
        assertNull(tool("turn off the screen"))
        assertTrue(tool("open whatsapp settings") != "open_settings")
        assertNull(tool("turn on the lights"))
        assertEquals("flashlight", tool("turn on the flashlight"))
        assertEquals("open_app", tool("open settings"))
    }

    @Test
    fun alarmDismissCountAppsMapsAccessAndClipboardPhrases() {
        assertEquals("dismiss_alarm", tool("stop the alarm"))
        assertEquals("dismiss_alarm", tool("cancel my alarm"))
        assertNull(tool("cancel all alarms"))
        assertEquals("count_apps", tool("how many apps do i have"))
        assertEquals("count_apps", tool("how many apps are installed on my phone"))
        assertEquals("open_maps", tool("navigate to the airport"))
        assertEquals("directions", arg("navigate to the airport", "mode"))
        assertEquals("airport", arg("navigate to the airport", "query"))
        assertEquals("search", arg("find petrol pump near me", "mode"))
        assertEquals("petrol pump near me", arg("find petrol pump near me", "query"))
        assertEquals("check_access", tool("which permissions are off"))
        assertEquals("check_access", tool("what can't you do"))
        assertEquals("copy_to_clipboard", tool("copy hello world to clipboard"))
        assertEquals("hello world", arg("copy hello world to clipboard", "text"))
        assertNull(tool("copy it to clipboard"))
    }

    @Test
    fun severalNewCommandsCanBeChainedInOneSentence() {
        val match = NativeCommands.match("set brightness to 30 percent and put the phone on vibrate")
        val steps = assertIs<NativeCommands.Match.Run>(match).steps
        assertEquals(listOf("set_brightness", "set_sound_mode"), steps.map { it.tool })
    }

    // ---- tools -----------------------------------------------------------------------------------------------------------

    private class FakeSettings : PhoneSettingsPort {
        var brightness: Int? = 50
        var canWrite = true
        var canMute = true
        var reportedMode: SoundMode? = null
        var clipboard: String? = null
        var clipboardWorks = true
        val opened = mutableListOf<SettingsPage>()
        var dismiss: LaunchResult = LaunchResult.Launched
        var openResult: LaunchResult = LaunchResult.Launched
        var set: Int? = null

        override fun brightnessPercent() = brightness
        override fun setBrightnessPercent(percent: Int): BrightnessResult {
            if (!canWrite) return BrightnessResult.NeedsPermission
            val before = brightness
            brightness = percent
            set = percent
            return BrightnessResult.Changed(before, percent)
        }
        override fun setSoundMode(mode: SoundMode): SoundResult {
            if (mode == SoundMode.SILENT && !canMute) return SoundResult.NeedsPermission
            return SoundResult.Changed(reportedMode ?: mode)
        }
        override fun open(page: SettingsPage): LaunchResult {
            opened += page
            return openResult
        }
        override fun dismissAlarm(): LaunchResult = dismiss
        override fun copyToClipboard(text: String): Boolean {
            if (clipboardWorks) clipboard = text
            return clipboardWorks
        }
    }

    private fun args(vararg pairs: Pair<String, Any>) = ToolArgs(mapOf(*pairs))

    @Test
    fun calculateToolAnswersAndRefuses() = runTest {
        val ok = assertIs<ToolResult.Success>(CalculateTool().execute(args("expression" to "15 percent of 2400")))
        assertTrue(ok.answer)
        assertTrue(ok.message.endsWith("is 360."), ok.message)
        val bad = assertIs<ToolResult.Failure>(CalculateTool().execute(args("expression" to "1 / 0")))
        assertTrue(bad.error.message.contains("zero"))
    }

    @Test
    fun convertToolAnswersAndRefuses() = runTest {
        val ok = assertIs<ToolResult.Success>(ConvertUnitsTool().execute(args("value" to "5", "from" to "km", "to" to "miles")))
        assertTrue(ok.message.contains("miles"))
        assertIs<ToolResult.Failure>(ConvertUnitsTool().execute(args("value" to "abc", "from" to "km", "to" to "miles")))
        assertIs<ToolResult.Failure>(ConvertUnitsTool().execute(args("value" to "NaN", "from" to "km", "to" to "miles")))
        assertIs<ToolResult.Failure>(ConvertUnitsTool().execute(args("value" to "5", "from" to "km", "to" to "kg")))
    }

    @Test
    fun randomToolStaysInsideItsRange() = runTest {
        val tool = RandomPickTool(Random(7))
        repeat(200) {
            val n = assertIs<ToolResult.Success>(tool.execute(args("kind" to "number", "low" to 3, "high" to 9))).data.getValue("result").toInt()
            assertTrue(n in 3..9)
            val d = assertIs<ToolResult.Success>(tool.execute(args("kind" to "dice"))).data.getValue("result").toInt()
            assertTrue(d in 1..6)
        }
        assertIs<ToolResult.Failure>(tool.execute(args("kind" to "number", "low" to 9, "high" to 3)))
        val coin = assertIs<ToolResult.Success>(tool.execute(args("kind" to "coin"))).data.getValue("result")
        assertTrue(coin == "heads" || coin == "tails")
    }

    @Test
    fun brightnessIsConfirmedFromAndroidOrSendsThePersonToTheSwitch() = runTest {
        val settings = FakeSettings()
        val tool = SetBrightnessTool(settings)
        val done = assertIs<ToolResult.Success>(tool.execute(args("action" to "set", "percent" to 80)))
        assertEquals(Verification.CONFIRMED, done.verification)
        assertTrue(done.message.contains("80"))

        assertIs<ToolResult.Success>(tool.execute(args("action" to "up")))
        assertEquals(100, settings.set)
        assertIs<ToolResult.Success>(tool.execute(args("action" to "down")))
        assertEquals(80, settings.set)

        assertIs<ToolResult.Failure>(tool.execute(args("action" to "set")))

        settings.canWrite = false
        val denied = assertIs<ToolResult.Failure>(tool.execute(args("action" to "set", "percent" to 10)))
        assertIs<ToolError.Unavailable>(denied.error)
        assertEquals(listOf(SettingsPage.WRITE_SETTINGS), settings.opened)
    }

    @Test
    fun soundModeIsOnlyClaimedWhenAndroidReportsIt() = runTest {
        val settings = FakeSettings()
        val tool = SetSoundModeTool(settings)
        assertEquals(Verification.CONFIRMED, assertIs<ToolResult.Success>(tool.execute(args("mode" to "vibrate"))).verification)

        settings.reportedMode = SoundMode.NORMAL
        assertIs<ToolResult.Failure>(tool.execute(args("mode" to "silent")))

        settings.reportedMode = null
        settings.canMute = false
        val denied = assertIs<ToolResult.Failure>(tool.execute(args("mode" to "silent")))
        assertTrue(denied.error.message.contains("Do not disturb"))
        assertEquals(listOf(SettingsPage.DO_NOT_DISTURB_ACCESS), settings.opened)
    }

    @Test
    fun settingsPagesAreHandedOffNeverClaimedAsChanged() = runTest {
        val settings = FakeSettings()
        val tool = OpenSettingsTool(settings)
        val done = assertIs<ToolResult.Success>(tool.execute(args("page" to "wifi")))
        assertEquals(Verification.HANDED_OFF, done.verification)
        assertTrue(done.message.contains("one tap for you"))
        assertEquals(listOf(SettingsPage.WIFI), settings.opened)

        settings.openResult = LaunchResult.NoHandler("settings")
        assertIs<ToolResult.Failure>(tool.execute(args("page" to "nfc")))
    }

    @Test
    fun dismissAlarmAndClipboardAreHonest() = runTest {
        val settings = FakeSettings()
        val dismissed = assertIs<ToolResult.Success>(DismissAlarmTool(settings).execute(args()))
        assertEquals(Verification.HANDED_OFF, dismissed.verification)
        settings.dismiss = LaunchResult.NoHandler("alarm")
        assertIs<ToolResult.Failure>(DismissAlarmTool(settings).execute(args()))

        val copy = CopyToClipboardTool(settings)
        assertEquals(Verification.HANDED_OFF, assertIs<ToolResult.Success>(copy.execute(args("text" to "hello"))).verification)
        assertEquals("hello", settings.clipboard)
        assertIs<ToolResult.Failure>(copy.execute(args("text" to "   ")))
        settings.clipboardWorks = false
        assertIs<ToolResult.Failure>(copy.execute(args("text" to "hello")))
    }

    @Test
    fun appCountComesFromTheLauncherList() = runTest {
        val launcher = object : AppLauncher {
            override suspend fun launchable() = List(42) { InstalledApp("App $it", "pkg.$it") }
            override fun launch(packageName: String): LaunchResult = LaunchResult.Launched
        }
        val result = assertIs<ToolResult.Success>(CountAppsTool(launcher).execute(args()))
        assertTrue(result.message.contains("42"))
        assertEquals(Verification.CONFIRMED, result.verification)
        val broken = object : AppLauncher {
            override suspend fun launchable(): List<InstalledApp> = throw IllegalStateException("no")
            override fun launch(packageName: String): LaunchResult = LaunchResult.Launched
        }
        assertIs<ToolResult.Failure>(CountAppsTool(broken).execute(args()))
    }

    @Test
    fun mapsOpensOnlyAGoogleMapsWebAddressAndEncodesTheWords() = runTest {
        val opened = mutableListOf<String>()
        val urls = object : UrlOpener {
            override fun open(url: String): LaunchResult {
                opened += url
                return LaunchResult.Launched
            }
        }
        val tool = OpenMapsTool(urls)
        assertIs<ToolResult.Success>(tool.execute(args("query" to "Delhi Airport & Metro", "mode" to "directions")))
        assertIs<ToolResult.Success>(tool.execute(args("query" to "petrol pump near me", "mode" to "search")))
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=Delhi+Airport+%26+Metro", opened[0])
        assertEquals("https://www.google.com/maps/search/?api=1&query=petrol+pump+near+me", opened[1])
        assertTrue(opened.all { WebLinks.normalizeWebUrl(it) != null })
        assertIs<ToolResult.Failure>(tool.execute(args("query" to "  ", "mode" to "search")))
    }

    @Test
    fun accessCheckReportsOnlyWhatAndroidSays() = runTest {
        val reporter = object : AccessReporter {
            var lines = listOf(AccessLine("Notification access", false, "I cannot read your notifications"), AccessLine("Microphone", true, "I cannot hear you"))
            override fun report() = lines
        }
        val result = assertIs<ToolResult.Success>(CheckAccessTool(reporter).execute(args()))
        assertTrue(result.message.startsWith("1 of 2 accesses are off."), result.message)
        assertTrue(result.message.contains("Notification access"))
        assertTrue(result.answer)

        reporter.lines = listOf(AccessLine("Microphone", true, "x"))
        assertTrue(assertIs<ToolResult.Success>(CheckAccessTool(reporter).execute(args())).message.startsWith("Every access"))
        reporter.lines = emptyList()
        assertIs<ToolResult.Failure>(CheckAccessTool(reporter).execute(args()))
    }
}

class ContactNameFallbackTest {

    private class Book(private val people: List<com.jarvis.ai.ai.tools.ContactEntry>) : com.jarvis.ai.ai.tools.builtin.ContactsPort {
        val searches = mutableListOf<String>()
        override suspend fun search(query: String): com.jarvis.ai.ai.tools.builtin.ContactSearch {
            searches += query
            return com.jarvis.ai.ai.tools.builtin.ContactSearch.Found(people.filter { it.name.contains(query, ignoreCase = true) })
        }
    }

    private fun person(id: String, name: String) =
        com.jarvis.ai.ai.tools.ContactEntry(id, name, listOf(com.jarvis.ai.ai.tools.ContactNumber("+919876543210", "mobile")))


    @Test
    fun aNameThatIsNotSavedOffersWhatIsSavedButNeverPicksIt() = runTest {
        val book = Book(listOf(person("1", "Rohit Verma")))
        val outcome = com.jarvis.ai.ai.tools.builtin.ContactLookupForTest.resolve(book, "Rohit Kumar")
        assertTrue(outcome.startsWith("ASK:"), outcome)
        assertTrue(outcome.contains("Rohit Verma"))
    }

    @Test
    fun aSingleWordNameIsNotWidenedAndNothingMatchingStaysNoMatch() = runTest {
        val book = Book(listOf(person("1", "Rohit Verma")))
        assertTrue(com.jarvis.ai.ai.tools.builtin.ContactLookupForTest.resolve(book, "Zorblax").startsWith("STOP:"))
        assertTrue(com.jarvis.ai.ai.tools.builtin.ContactLookupForTest.resolve(book, "Zorblax Qux").startsWith("STOP:"))
        assertEquals(listOf("Zorblax"), book.searches.take(1))
    }

    @Test
    fun anExactNameStillResolvesDirectly() = runTest {
        val book = Book(listOf(person("1", "Rohit Verma")))
        assertTrue(com.jarvis.ai.ai.tools.builtin.ContactLookupForTest.resolve(book, "Rohit Verma").startsWith("FOUND:"))
    }
}
