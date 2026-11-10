package com.jarvis.ai

import com.jarvis.ai.ai.executor.Executor
import com.jarvis.ai.ai.executor.StepStatus
import com.jarvis.ai.ai.planner.PlanResult
import com.jarvis.ai.ai.planner.Replanner
import com.jarvis.ai.ai.tools.ContactChoice
import com.jarvis.ai.ai.tools.ContactEntry
import com.jarvis.ai.ai.tools.ContactNumber
import com.jarvis.ai.ai.tools.ContactResolver
import com.jarvis.ai.ai.tools.PhoneNumbers
import com.jarvis.ai.ai.tools.Prepared
import com.jarvis.ai.ai.tools.ToolArgs
import com.jarvis.ai.ai.tools.ToolError
import com.jarvis.ai.ai.tools.ToolRegistry
import com.jarvis.ai.ai.tools.ToolResult
import com.jarvis.ai.ai.tools.Verification
import com.jarvis.ai.ai.tools.builtin.AlarmLauncher
import com.jarvis.ai.ai.tools.builtin.AnswerCallTool
import com.jarvis.ai.ai.tools.builtin.AnswerResult
import com.jarvis.ai.ai.tools.builtin.BuiltinTools
import com.jarvis.ai.ai.tools.builtin.CallContactTool
import com.jarvis.ai.ai.tools.builtin.CallNumberTool
import com.jarvis.ai.ai.tools.builtin.CallPermissions
import com.jarvis.ai.ai.tools.builtin.CallPort
import com.jarvis.ai.ai.tools.builtin.CallStart
import com.jarvis.ai.ai.tools.builtin.ContactSearch
import com.jarvis.ai.ai.tools.builtin.ContactsPort
import com.jarvis.ai.ai.tools.builtin.DeviceInfo
import com.jarvis.ai.ai.tools.builtin.DeviceInfoReader
import com.jarvis.ai.ai.tools.builtin.DevicePorts
import com.jarvis.ai.ai.tools.builtin.EndCallResult
import com.jarvis.ai.ai.tools.builtin.EndCallTool
import com.jarvis.ai.ai.tools.builtin.LaunchResult
import com.jarvis.ai.ai.tools.builtin.TorchController
import com.jarvis.ai.ai.tools.builtin.TorchResult
import com.jarvis.ai.ai.tools.builtin.UrlOpener
import com.jarvis.ai.core.security.AuditKind
import com.jarvis.ai.core.security.ConfirmationGate
import com.jarvis.ai.core.security.InMemoryAuditLog
import com.jarvis.ai.core.security.Risk
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private class ScriptedContacts(var respond: (String) -> ContactSearch) : ContactsPort {
    val queries = mutableListOf<String>()

    constructor(vararg entries: ContactEntry) : this({ ContactSearch.Found(entries.toList()) })

    override suspend fun search(query: String): ContactSearch {
        queries += query
        return respond(query)
    }
}

private class ScriptedCalls(
    var start: CallStart = CallStart.Placed,
    var answer: AnswerResult = AnswerResult.Requested,
    var end: EndCallResult = EndCallResult.Ended,
) : CallPort {
    val started = mutableListOf<Pair<String, Boolean>>()
    var answers = 0
    var ends = 0

    override fun call(number: String, allowDirect: Boolean): CallStart {
        started += number to allowDirect
        return start
    }

    override fun answerRinging(): AnswerResult {
        answers++
        return answer
    }

    override fun endCall(): EndCallResult {
        ends++
        return end
    }
}

private fun person(id: String, name: String, vararg numbers: Pair<String, String>) =
    ContactEntry(id, name, numbers.map { ContactNumber(it.first, it.second) })

class CallToolsTest {

    // ---- phone numbers -------------------------------------------------------------------------------------------------

    @Test
    fun formattedNumbersAreCleanedToDigits() {
        assertEquals("+919876543210", (PhoneNumbers.parse("+91 98765-43210") as PhoneNumbers.Parsed.Valid).dialable)
        assertEquals("02223456789", (PhoneNumbers.parse("(022) 2345 6789") as PhoneNumbers.Parsed.Valid).dialable)
        assertEquals("112", (PhoneNumbers.parse(" 112 ") as PhoneNumbers.Parsed.Valid).dialable)
    }

    @Test
    fun carrierCodesAreNeverNumbers() {
        for (code in listOf("*21*5551234#", "#31#", "*#06#", "123#", "1*2", "*123", "+91*98765")) {
            val parsed = PhoneNumbers.parse(code)
            assertIs<PhoneNumbers.Parsed.Invalid>(parsed, "must be refused: $code")
            assertTrue(parsed.reason.contains("never dialled"), parsed.reason)
        }
    }

    @Test
    fun lettersMisplacedPlusAndBadLengthsAreRefused() {
        for (bad in listOf("", "   ", "call me", "555-CALL", "12+34", "12", "1234567890123456", "9876543210;ATD", "98765\n43210")) {
            assertIs<PhoneNumbers.Parsed.Invalid>(PhoneNumbers.parse(bad), "must be refused: '$bad'")
        }
        assertIs<PhoneNumbers.Parsed.Valid>(PhoneNumbers.parse("123456789012345"))
    }

    @Test
    fun emergencyNumbersAreRecognisedExactly() {
        for (n in listOf("112", "911", "100", "108", "999")) assertTrue(PhoneNumbers.isEmergency(n), n)
        for (n in listOf("1122", "+112", "11", "9111", "9876543210")) assertFalse(PhoneNumbers.isEmergency(n), n)
    }

    // ---- choosing a contact and a number -------------------------------------------------------------------------------

    @Test
    fun oneContactWithOneNumberIsChosen() {
        val choice = ContactResolver.choose("mom", listOf(person("1", "Mom", "+91 98765 43210" to "Mobile")))
        val chosen = assertIs<ContactChoice.Chosen>(choice)
        assertEquals("Mom", chosen.name)
        assertEquals("+919876543210", chosen.number)
        assertEquals("Mobile", chosen.label)
    }

    @Test
    fun aBlankLabelIsShownAsNumber() {
        val chosen = assertIs<ContactChoice.Chosen>(ContactResolver.choose("mom", listOf(person("1", "Mom", "9876543210" to ""))))
        assertEquals("number", chosen.label)
    }

    @Test
    fun twoDifferentPeopleAreAskedAboutNeverGuessed() {
        val ask = assertIs<ContactChoice.Ask>(
            ContactResolver.choose("ram", listOf(person("1", "Ramesh Kumar", "111 222 333" to "mobile"), person("2", "Ram Prasad", "444 555 666" to "mobile"))),
        )
        assertTrue(ask.question.contains("Ramesh Kumar") && ask.question.contains("Ram Prasad"), ask.question)
    }

    @Test
    fun anExactNameBeatsPartialMatches() {
        val choice = ContactResolver.choose("ram", listOf(person("1", "Ramesh", "111 222 333" to "mobile"), person("2", "Ram", "444 555 666" to "mobile")))
        assertEquals("Ram", assertIs<ContactChoice.Chosen>(choice).name)
    }

    @Test
    fun twoContactsWithTheSameNameAreNotGuessedBetween() {
        val ask = assertIs<ContactChoice.Ask>(
            ContactResolver.choose("sam", listOf(person("1", "Sam", "111 222 333" to "mobile"), person("2", "Sam", "444 555 666" to "mobile"))),
        )
        assertTrue(ask.question.contains("2 contacts called Sam"), ask.question)
    }

    @Test
    fun theSameContactListedTwiceIsOnePerson() {
        val one = person("1", "Mom", "111 222 333" to "mobile")
        assertIs<ContactChoice.Chosen>(ContactResolver.choose("mom", listOf(one, one)))
    }

    @Test
    fun theSameNumberWrittenTwoWaysIsOneNumber() {
        val choice = ContactResolver.choose("mom", listOf(person("1", "Mom", "+91 98765-43210" to "home", "+919876543210" to "mobile")))
        assertIs<ContactChoice.Chosen>(choice)
    }

    @Test
    fun severalNumbersPickTheOnlyMobileOtherwiseAsk() {
        val withMobile = person("1", "Dad", "111 222 333" to "Home", "444 555 666" to "Mobile")
        assertEquals("444555666", assertIs<ContactChoice.Chosen>(ContactResolver.choose("dad", listOf(withMobile))).number)

        val noMobile = person("1", "Dad", "111 222 333" to "Home", "444 555 666" to "Work")
        val ask = assertIs<ContactChoice.Ask>(ContactResolver.choose("dad", listOf(noMobile)))
        assertTrue(ask.question.contains("home") && ask.question.contains("work"), ask.question)

        val twoMobiles = person("1", "Dad", "111 222 333" to "Mobile", "444 555 666" to "Mobile")
        assertIs<ContactChoice.Ask>(ContactResolver.choose("dad", listOf(twoMobiles)))
    }

    @Test
    fun aRequestedLabelSelectsThatNumberOrAsks() {
        val dad = person("1", "Dad", "111 222 333" to "Home", "444 555 666" to "Work")
        assertEquals("444555666", assertIs<ContactChoice.Chosen>(ContactResolver.choose("dad", listOf(dad), "work")).number)
        val ask = assertIs<ContactChoice.Ask>(ContactResolver.choose("dad", listOf(dad), "mobile"))
        assertTrue(ask.question.contains("no mobile number"), ask.question)
    }

    @Test
    fun contactsWithoutADialableNumberCannotBeCalled() {
        assertIs<ContactChoice.NoMatch>(ContactResolver.choose("bank", listOf(person("1", "Bank", "*123#" to "mobile"))))
        assertIs<ContactChoice.NoMatch>(ContactResolver.choose("nobody", emptyList()))
    }

    @Test
    fun aTrailingLabelIsSplitOffOnlyWhenANameRemains() {
        assertEquals("mom" to "mobile", ContactResolver.splitTrailingLabel("mom mobile"))
        assertEquals("mom" to "mobile", ContactResolver.splitTrailingLabel("mom cell"))
        assertEquals("John" to "work", ContactResolver.splitTrailingLabel("John Work"))
        assertEquals("mobile" to null, ContactResolver.splitTrailingLabel("mobile"))
        assertEquals("mom" to null, ContactResolver.splitTrailingLabel("mom"))
    }

    // ---- call_contact: preparing ------------------------------------------------------------------------------------------

    @Test
    fun callContactResolvesTheNameAndNumberBeforeAnyConfirmation() = runTest {
        val tool = CallContactTool(ScriptedContacts(person("1", "Mom", "+91 98765 43210" to "Mobile")), ScriptedCalls())

        val ready = assertIs<Prepared.Ready>(tool.prepare(ToolArgs(mapOf("name" to "mom"))))

        assertEquals("Mom", ready.args.string("contact"))
        assertEquals("+919876543210", ready.args.string("number"))
        assertEquals("Call Mom on +919876543210 (Mobile)", tool.describe(ready.args))
        assertEquals(Risk.HIGH, tool.spec.risk)
    }

    @Test
    fun theSummaryBeforePreparationNamesOnlyWhatWasAskedFor() {
        val tool = CallContactTool(ScriptedContacts(), ScriptedCalls())
        assertEquals("Call the contact named \"mom\"", tool.describe(ToolArgs(mapOf("name" to "mom"))))
    }

    @Test
    fun aMissingContactsPermissionIsReportedAsThatNotAsNoSuchPerson() = runTest {
        val tool = CallContactTool(ScriptedContacts { ContactSearch.PermissionMissing }, ScriptedCalls())

        val stop = assertIs<Prepared.Stop>(tool.prepare(ToolArgs(mapOf("name" to "mom"))))

        val error = assertIs<ToolError.PermissionDenied>(stop.error)
        assertTrue(CallPermissions.READ_CONTACTS in error.permissions)
    }

    @Test
    fun aFailingContactsSearchIsAFailureNotAGuess() = runTest {
        val thrown = CallContactTool(ScriptedContacts { throw IllegalStateException("provider crashed") }, ScriptedCalls())
        assertIs<ToolError.Failed>(assertIs<Prepared.Stop>(thrown.prepare(ToolArgs(mapOf("name" to "mom")))).error)

        val reported = CallContactTool(ScriptedContacts { ContactSearch.Failed("The contacts database is unavailable.") }, ScriptedCalls())
        val error = assertIs<ToolError.Failed>(assertIs<Prepared.Stop>(reported.prepare(ToolArgs(mapOf("name" to "mom")))).error)
        assertEquals("The contacts database is unavailable.", error.message)
    }

    @Test
    fun anAmbiguousNameBecomesAQuestionThatIsNotReplannedAround() = runTest {
        val contacts = ScriptedContacts(person("1", "Ramesh", "111 222 333" to "mobile"), person("2", "Ram Prasad", "444 555 666" to "mobile"))
        val tool = CallContactTool(contacts, ScriptedCalls())

        val error = assertIs<ToolError.NeedsInput>(assertIs<Prepared.Stop>(tool.prepare(ToolArgs(mapOf("name" to "ram")))).error)

        assertFalse(error.replannable, "asking the user must not be turned into a different plan")
    }

    @Test
    fun aNoMatchIsReportedAsUnavailableAndNotReplanned() = runTest {
        val tool = CallContactTool(ScriptedContacts(), ScriptedCalls())
        val error = assertIs<ToolError.Unavailable>(assertIs<Prepared.Stop>(tool.prepare(ToolArgs(mapOf("name" to "ghost")))).error)
        assertTrue(error.message.contains("ghost"))
    }

    @Test
    fun aLabelAfterTheNameIsUsedWhenTheWholePhraseFindsNobody() = runTest {
        val contacts = ScriptedContacts { query ->
            ContactSearch.Found(if (query == "mom") listOf(person("1", "Mom", "111 222 333" to "Home", "444 555 666" to "Mobile")) else emptyList())
        }
        val tool = CallContactTool(contacts, ScriptedCalls())

        val ready = assertIs<Prepared.Ready>(tool.prepare(ToolArgs(mapOf("name" to "mom home"))))

        assertEquals(listOf("mom home", "mom"), contacts.queries)
        assertEquals("111222333", ready.args.string("number"))
    }

    @Test
    fun aBlankNameIsRefused() = runTest {
        val tool = CallContactTool(ScriptedContacts(), ScriptedCalls())
        assertIs<ToolError.InvalidArguments>(assertIs<Prepared.Stop>(tool.prepare(ToolArgs(mapOf("name" to "   ")))).error)
    }

    // ---- placing the call ---------------------------------------------------------------------------------------------------

    @Test
    fun aPlacedCallIsReportedAsRequestedNeverAsMade() = runTest {
        val calls = ScriptedCalls(CallStart.Placed)
        val tool = CallContactTool(ScriptedContacts(), calls)

        val result = assertIs<ToolResult.Success>(
            tool.execute(ToolArgs(mapOf("name" to "mom", "contact" to "Mom", "number" to "+919876543210", "label" to "Mobile"))),
        )

        assertEquals(Verification.HANDED_OFF, result.verification)
        assertTrue(result.message.startsWith("Asked Android to call Mom"), result.message)
        assertTrue(result.message.contains("does not tell this app whether the call connected"), result.message)
        assertEquals(listOf("+919876543210" to true), calls.started)
    }

    @Test
    fun withoutThePhonePermissionTheDialerIsOpenedAndThePersonPressesCall() = runTest {
        val calls = ScriptedCalls(CallStart.DialerOpened)
        val tool = CallContactTool(ScriptedContacts(), calls)

        val result = assertIs<ToolResult.Success>(
            tool.execute(ToolArgs(mapOf("name" to "mom", "contact" to "Mom", "number" to "+919876543210", "label" to "Mobile"))),
        )

        assertEquals(Verification.HANDED_OFF, result.verification)
        assertTrue(result.message.contains("Press the call button"), result.message)
    }

    @Test
    fun emergencyNumbersAreNeverDialledDirectly() = runTest {
        val calls = ScriptedCalls(CallStart.DialerOpened)
        val tool = CallNumberTool(calls)

        val result = assertIs<ToolResult.Success>(tool.execute(ToolArgs(mapOf("number" to "112"))))

        assertEquals(listOf("112" to false), calls.started, "allowDirect must be false for an emergency number")
        assertTrue(result.message.contains("never dialled by this app"), result.message)
        assertEquals(Verification.HANDED_OFF, result.verification)
    }

    @Test
    fun anOrdinaryNumberMayBeDialledDirectly() = runTest {
        val calls = ScriptedCalls(CallStart.Placed)
        CallNumberTool(calls).execute(ToolArgs(mapOf("number" to "9876543210")))
        assertEquals(listOf("9876543210" to true), calls.started)
    }

    @Test
    fun noAppToPlaceCallsAndPlatformFailuresAreReportedAsFailures() = runTest {
        val noHandler = CallNumberTool(ScriptedCalls(CallStart.NoHandler)).execute(ToolArgs(mapOf("number" to "9876543210")))
        assertIs<ToolError.Unavailable>(assertIs<ToolResult.Failure>(noHandler).error)

        val failed = CallNumberTool(ScriptedCalls(CallStart.Failed("Android refused the call."))).execute(ToolArgs(mapOf("number" to "9876543210")))
        assertEquals("Android refused the call.", assertIs<ToolResult.Failure>(failed).error.message)
    }

    @Test
    fun aContactCallWithoutAResolvedNumberDoesNothing() = runTest {
        val calls = ScriptedCalls()
        val result = CallContactTool(ScriptedContacts(), calls).execute(ToolArgs(mapOf("name" to "mom")))
        assertIs<ToolResult.Failure>(result)
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun executeRechecksTheNumberEvenAfterPreparation() = runTest {
        val calls = ScriptedCalls()
        val viaContact = CallContactTool(ScriptedContacts(), calls).execute(ToolArgs(mapOf("name" to "x", "contact" to "X", "number" to "*21*5551234#")))
        val viaNumber = CallNumberTool(calls).execute(ToolArgs(mapOf("number" to "#31#")))

        assertIs<ToolResult.Failure>(viaContact)
        assertIs<ToolResult.Failure>(viaNumber)
        assertTrue(calls.started.isEmpty(), "a carrier code must never reach the dialer")
    }

    @Test
    fun callNumberCleansTheNumberBeforeConfirmation() = runTest {
        val tool = CallNumberTool(ScriptedCalls())

        val ready = assertIs<Prepared.Ready>(tool.prepare(ToolArgs(mapOf("number" to "+91 98765-43210"))))
        assertEquals("+919876543210", ready.args.string("number"))
        assertEquals("Call +919876543210", tool.describe(ready.args))

        assertIs<ToolError.InvalidArguments>(assertIs<Prepared.Stop>(tool.prepare(ToolArgs(mapOf("number" to "*21*5551234#")))).error)
    }

    // ---- answering and hanging up -------------------------------------------------------------------------------------------

    @Test
    fun answeringIsReportedAsRequested() = runTest {
        val calls = ScriptedCalls()
        val tool = AnswerCallTool(calls)

        val result = assertIs<ToolResult.Success>(tool.execute(ToolArgs(emptyMap())))

        assertEquals(1, calls.answers)
        assertEquals(Verification.HANDED_OFF, result.verification)
        assertTrue(result.message.contains("If nothing was ringing, nothing happened"), result.message)
        assertEquals(Risk.MEDIUM, tool.spec.risk)
        assertTrue(CallPermissions.ANSWER_PHONE_CALLS in tool.spec.requiredPermissions)
    }

    @Test
    fun aFailedAnswerIsAFailure() = runTest {
        val result = AnswerCallTool(ScriptedCalls(answer = AnswerResult.Failed("Android needs the Phone permission."))).execute(ToolArgs(emptyMap()))
        assertEquals("Android needs the Phone permission.", assertIs<ToolResult.Failure>(result).error.message)
    }

    @Test
    fun hangingUpReportsWhatAndroidSaid() = runTest {
        val ended = EndCallTool(ScriptedCalls(end = EndCallResult.Ended)).execute(ToolArgs(emptyMap()))
        assertEquals(Verification.CONFIRMED, assertIs<ToolResult.Success>(ended).verification)

        val none = EndCallTool(ScriptedCalls(end = EndCallResult.NoCall)).execute(ToolArgs(emptyMap()))
        assertTrue(assertIs<ToolResult.Failure>(none).error.message.contains("no call to end"))

        val failed = EndCallTool(ScriptedCalls(end = EndCallResult.Failed("Android refused."))).execute(ToolArgs(emptyMap()))
        assertEquals("Android refused.", assertIs<ToolResult.Failure>(failed).error.message)
    }

    // ---- the registry ---------------------------------------------------------------------------------------------------------

    private fun ports(contacts: ContactsPort?, calls: CallPort?) = DevicePorts(
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
        contacts = contacts,
        calls = calls,
    )

    @Test
    fun callToolsExistOnlyWhenTheDeviceCanReallyDoThem() {
        val names = { p: DevicePorts -> BuiltinTools.create(p).map { it.spec.name }.toSet() }
        val callTools = setOf("call_contact", "call_number", "answer_call", "end_call")

        assertTrue(names(ports(null, null)).none { it in callTools })
        assertTrue(names(ports(ScriptedContacts(), null)).none { it in callTools }, "contacts alone must not expose half-working call tools")
        assertTrue(names(ports(null, ScriptedCalls())).none { it in callTools })
        assertTrue(names(ports(ScriptedContacts(), ScriptedCalls())).containsAll(callTools))
        assertEquals(5, BuiltinTools.create(ports(null, null)).size)
        assertEquals(9, BuiltinTools.create(ports(ScriptedContacts(), ScriptedCalls())).size)
    }

    // ---- through the real executor: confirmation, audit, honest report ----------------------------------------------------

    private val audit = InMemoryAuditLog()
    private val gate = ConfirmationGate(ttlMs = 60_000L, time = FakeTime())

    private fun executor(contacts: ScriptedContacts, calls: ScriptedCalls, denied: Set<String> = emptySet()) = Executor(
        registry = ToolRegistry(
            listOf(CallContactTool(contacts, calls), CallNumberTool(calls), AnswerCallTool(calls), EndCallTool(calls)),
        ),
        permissions = FakePermissions(denied),
        gate = gate,
        auditLog = audit,
        replanner = Replanner { _, _, _, _ -> PlanResult.Failed("no replanning in this test") },
        policy = { com.jarvis.ai.ai.executor.ExecutionPolicy() },
        time = FakeTime(),
    )

    private fun callPlan(tool: String, vararg params: Pair<String, String>) =
        plan(step(1, tool, buildJsonObject { params.forEach { (k, v) -> put(k, v) } }, description = "call step"))

    @Test
    fun aCallAsksWithTheResolvedNameAndNumberAndDialsOnlyAfterApproval() = runTest {
        val calls = ScriptedCalls()
        val executor = executor(ScriptedContacts(person("1", "Mom", "+91 98765 43210" to "Mobile")), calls)

        val run = async { executor.execute(callPlan("call_contact", "name" to "mom")) }
        runCurrent()

        val request = assertNotNull(gate.pending.value, "the call must wait for the user")
        assertEquals("Call Mom on +919876543210 (Mobile)", request.summary)
        assertEquals(Risk.HIGH, request.risk)
        assertTrue(calls.started.isEmpty(), "nothing is dialled while the question is open")

        gate.approve(request.id)
        val report = run.await()

        assertEquals(listOf("+919876543210" to true), calls.started)
        val outcome = report.outcomes.single()
        assertEquals(StepStatus.SUCCEEDED, outcome.status)
        assertEquals(Verification.HANDED_OFF, outcome.verification)
        assertTrue(report.summary().startsWith("➜"), "a handed-off call must not get the confirmed tick: ${report.summary()}")
        assertTrue(
            audit.recent().map { it.kind }.containsAll(listOf(AuditKind.CONFIRMATION_REQUESTED, AuditKind.CONFIRMATION_APPROVED, AuditKind.EXECUTED)),
        )
    }

    @Test
    fun decliningTheCallDialsNothing() = runTest {
        val calls = ScriptedCalls()
        val executor = executor(ScriptedContacts(person("1", "Mom", "+91 98765 43210" to "Mobile")), calls)

        val run = async { executor.execute(callPlan("call_contact", "name" to "mom")) }
        runCurrent()
        gate.deny(assertNotNull(gate.pending.value).id)

        assertEquals(StepStatus.DENIED, run.await().outcomes.single().status)
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun anAmbiguousNameAsksTheUserAndNeverReachesAConfirmationOrTheDialer() = runTest {
        val calls = ScriptedCalls()
        val executor = executor(
            ScriptedContacts(person("1", "Ramesh", "111 222 333" to "mobile"), person("2", "Ram Prasad", "444 555 666" to "mobile")),
            calls,
        )

        val report = executor.execute(callPlan("call_contact", "name" to "ram"))

        val outcome = report.outcomes.single()
        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(outcome.message.contains("Say the full name"), outcome.message)
        assertNull(gate.pending.value)
        assertTrue(calls.started.isEmpty())
        assertEquals(AuditKind.BLOCKED, audit.recent().single().kind)
    }

    @Test
    fun withContactsAccessOffNothingIsSearchedOrDialled() = runTest {
        val contacts = ScriptedContacts(person("1", "Mom", "+91 98765 43210" to "Mobile"))
        val calls = ScriptedCalls()
        val executor = executor(contacts, calls, denied = setOf(CallPermissions.READ_CONTACTS))

        val outcome = executor.execute(callPlan("call_contact", "name" to "mom")).outcomes.single()

        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(contacts.queries.isEmpty(), "the permission check comes before the search")
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun aCarrierCodeNeverReachesAConfirmationOrTheDialer() = runTest {
        val calls = ScriptedCalls()
        val executor = executor(ScriptedContacts(), calls)

        val outcome = executor.execute(callPlan("call_number", "number" to "*21*5551234#")).outcomes.single()

        assertEquals(StepStatus.FAILED, outcome.status)
        assertTrue(outcome.message.contains("never dialled"), outcome.message)
        assertNull(gate.pending.value, "the user is not asked to approve something that can never run")
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun anEmergencyNumberOpensTheDialerAfterApprovalAndNeverCallsDirectly() = runTest {
        val calls = ScriptedCalls(CallStart.DialerOpened)
        val executor = executor(ScriptedContacts(), calls)

        val run = async { executor.execute(callPlan("call_number", "number" to "112")) }
        runCurrent()
        gate.approve(assertNotNull(gate.pending.value).id)
        run.await()

        assertEquals(listOf("112" to false), calls.started)
    }

    @Test
    fun answeringAndHangingUpNeedNoTapButDoNeedThePhonePermission() = runTest {
        val calls = ScriptedCalls()

        val answered = executor(ScriptedContacts(), calls).execute(callPlan("answer_call")).outcomes.single()
        assertEquals(StepStatus.SUCCEEDED, answered.status)
        assertNull(gate.pending.value)
        assertEquals(1, calls.answers)
        assertEquals(AuditKind.EXECUTED, audit.recent().last().kind, "answering is audited")

        val blocked = executor(ScriptedContacts(), calls, denied = setOf(CallPermissions.ANSWER_PHONE_CALLS))
            .execute(callPlan("end_call")).outcomes.single()
        assertEquals(StepStatus.FAILED, blocked.status)
        assertTrue(blocked.message.contains("Permission needed"), blocked.message)
        assertEquals(0, calls.ends)
    }

    @Test
    fun theModelCannotSupplyTheResolvedNumberItself() = runTest {
        // A plan that names a contact AND smuggles in a different number must still dial the contact's real number.
        val calls = ScriptedCalls()
        val executor = executor(ScriptedContacts(person("1", "Mom", "+91 98765 43210" to "Mobile")), calls)

        val smuggled = plan(
            step(1, "call_contact", buildJsonObject { put("name", "mom"); put("number", "9999999999") }, description = "call step"),
        )
        val run = async { executor.execute(smuggled) }
        runCurrent()
        val request = assertNotNull(gate.pending.value)
        gate.approve(request.id)
        run.await()

        // Either the extra field is rejected by the schema check, or the contact's own number is the one dialled.
        if (calls.started.isNotEmpty()) assertEquals("+919876543210", calls.started.single().first)
        assertFalse(request.summary.contains("9999999999"))
    }
}
