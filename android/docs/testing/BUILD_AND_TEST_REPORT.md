# Build and test report

Date: 2026-11-08 to 2026-11-11 (Phases 1 to 3, the model-install, access, built-in-command and UI round, and the memory, planner, automation, agent, offline and security round). This report says what was
actually executed and what was not.

**Where things stand.** The sandbox that wrote the code cannot reach Maven Central, Google's Maven repository or the Gradle plugin
portal (the proxy answers 403), and it has no Android SDK, emulator or phone, so it cannot build the app. After the packaging fix below,
the owner built an earlier version on their PC and showed screenshots of it running on their phone (Providers and Chat tabs); I did not see
that build's log. **The code written since then (this report's "Round 2" section) has not been compiled by anyone.** Everything in "What was
run" is from the sandbox's offline harness, except the next section.

## Round 4 (2026-11-10/11): memory, planner, automations, agents, offline, security

**Run (offline harness, `:core` only):** 618 tests pass (456 before this round). New test files: `MemoryStoreTest`, `ProductivityTest`, `AutomationTest`, `AgentTest`,
`OfflineAndSecurityTest`, `MemoryCommandsTest` (5). JUnit 4 and kotlinx-coroutines 1.6.4 here, not the pinned versions; Gradle has **not** run these tests.
The last Gradle result on the owner's PC is the 2026-11-08 `:core:test` pass for older code.

**Mutation checks (29 new, re-run in full on 2026-11-11): 27 caught, 2 survived.** Rules broken one at a time:

- Automation save rules: allow-list ignored, non-low-risk tools allowed, interval floor removed, daily trigger not strictly after now, never rescheduling, pause keeping the next run. All caught.
- Automation runner: skipping the live re-check (caught); **its registry not risk-filtered and its confirmation gate approving instead of denying both survived.** Those two are backup layers behind the save-time check and behind the live re-check, so with the first layer working there is no input that makes them matter. They were left in as defence in depth and are **not independently tested**; if the earlier layers were broken, these two would be the only protection and no test would show whether they work.
- Agents: created active, draft can be enabled, approve from any status, delete keeping memory, tools taken from the stored file, validator allowing risky tools, unbuilt features or secrets, runtime trusting stored tools, preview having tools, own-only memory including general memory, no-memory agent seeing memory, fixed rules removed, local-only agent allowed to use the cloud, main assistant seeing agent memory. All caught.
- Security and offline: review missing key-like memory notes or unused permissions; offline report claiming local AI without an engine or hiding the missing engine. All caught.
- Reminders: delivered without recording whether the notification was shown. Caught only after a test was added for it (the first version of the tests missed it).

The mutation script snapshots every source file first and reported "identical to the pre-run snapshot: True" after each batch.

**Not run:** compilation of any new `:app` code (about 20 new or changed files: storage, alarm scheduler, receiver, notifier, status checks, eight screens, `AgentChat`, wiring in `AppContainer`, manifest). The Kotlin compiler's syntax pass over `:app` found no parse errors; every other error it printed was an unresolved Android or Compose name. Android and Compose API validity (for example `TimeInput`, `LocalClipboardManager`, `SpeechRecognizer.isOnDeviceRecognitionAvailable`, `Notification.Builder`) is unchecked. No alarm, boot receiver, notification, permission dialog, wizard or screen has run on a device or emulator. Lint, instrumented tests, a dependency scan and a release build were not run.

## Round 2 (2026-11-08/09): what was run and what was not

What the owner's first run showed: the app ran, but every cloud provider returned HTTP 404 for a stale pre-filled model name, so no command
worked; local model selection downloaded nothing; no permission was requested; the look was plain. Round 2 addresses each.

**Run (offline harness, `:core` only):** 335 tests pass (up from 277). New: `NativeCommandsTest` 17, `ModelListerTest` 12,
`ModelInstallerTest` 28, and 1 added to `ModelMetadataClientTest`. All of `:core` compiled with no errors.
JUnit 4 and kotlinx-coroutines 1.6.4 here, not the pinned versions.

**Mutation checks (21 new):** rules were broken one at a time and the tests had to notice. Installer: checksum comparison skipped, a file
with a bad checksum kept, size mismatch accepted, free space not checked, resume offset not checked, an oversized partial file not discarded,
a full answer appended to an old partial file, cancellation not checked while copying, the Hugging Face token never sent. Installed-models
record: file size ignored, path traversal accepted, delete leaving the partial file. Command matcher: an ambiguous hour guessed, "12 am"
read as 12, an unknown part of a chained command silently skipped, file names treated as websites, "tomorrow" ignored. Model lister: free
models not listed first, the non-chat filter removed, the Gemini `generateContent` filter removed. `getJson` accepting plain http. All 21
are caught now. Three needed fixing first: two mutants were invalid (they did not change behaviour) and were rewritten, and one ("free models not
first") survived because alphabetical order hid the bug, so a test model was renamed and it is now caught. A background mutation run was killed
part-way once and left one source file mutated; I restored it from the snapshot, checked it was identical, and ran the rest in the foreground.
One installer test was flaky once (`lateinit property job has not been initialized`, a race inside the test); it now starts the job lazily and was
stable over 5 runs.

**Round 3 (2026-11-09), `:core` only:** 369 tests pass (34 new: 12 route-planner rules, 19 routed-client behaviours, 3 planner changes). 18 more mutation
checks, all caught on the first run: Local only allowing the cloud, offline still using the cloud, an unconfigured cloud used, a missing engine ignored,
a not-installed model used, the heavy order not swapped, Cloud assisted putting the cloud last, Manual falling back to other routes, a model reloaded
on every call, the engine lock removed, out-of-memory not unloading, an empty answer accepted, cancellation not stopping the engine, an unexpected
engine crash not contained, route notes dropped, a response allowed to have two sources, the planner not marking its requests heavy, and the planner
catching only the old exception. The rule that a request that is only waiting its turn must not stop another request's engine work is covered by a
test but was not mutation-checked. The router is tested against a fake engine only; no real engine exists. The `:app` module was not touched in this round,
apart from types it already compiled against becoming nullable (`LlmResponse.provider`, `PlanResult.provider`).

**Syntax probe of `:app`:** the Kotlin compiler was run over every `:app` file against the real `:core` classes. It found **no syntax errors**; its other
errors are all unresolved Android and Compose references or follow from them. That is a syntax check only.

**Not run, and therefore unverified:**

| Not run | Why |
|---|---|
| Compilation of the new `:app` code (Models, Access and Cloud screens, theme, components, `ModelManager`, `ModelDownloadService`, `AccessChecks`, manifest, fonts as resources) | No Android SDK. Likely to need one or more compile fixes: paste the Gradle errors. |
| `OkHttpDownloadSource` (the real HTTP download with Range requests and redirect handling) | Needs OkHttp. The installer logic above it is tested against a fake source. |
| Any real download from Hugging Face, the gated-model token flow, and a real checksum comparison | Not possible here. File sizes and checksums were only read through a web fetch. |
| The foreground service and its notification, Android 14/15 foreground-service rules | Never on a device. |
| Permission dialogs, Settings pages and the "blocked" detection | Never on a device. |
| Animations, fonts and layout on real screens | Never rendered. Frame rate on a mid-range phone is unknown. |
| Whether `gemini-3.8-flash`, the Groq pool and `openrouter/free` work with the owner's keys | No keys here. **Load models** and **Test** in the Cloud tab are how to find out. |
| LiteRT-LM and the Gemma models | Not integrated; chat with an installed model does not exist. |


## First real Gradle run (on the owner's Windows PC, 2026-11-08, JDK 19, Gradle 8.14.3)

Taken from the console log the owner pasted; I did not run it.

| Command | Result |
|---|---|
| `.\gradlew :core:test` | **BUILD SUCCESSFUL** (3 min 9 s). Compiles and passes under Gradle with the pinned library versions and JUnit 5, which the offline harness could not check. Only warnings: tests use an API that needs the coroutines-test opt-in. The log does not show a per-test count. |
| `.\gradlew :app:assembleDebug` | **FAILED** at `:app:compileDebugKotlin` with 14 errors in `AppContainer.kt` and `AgentViewModel.kt`. Dependencies did resolve (it got as far as compiling), so `libs.versions.toml` versions are good for `:core` and `:app`. |

**Cause of the 14 errors: a packaging mistake of mine, not a code bug.** The repository root `.gitignore` (from the desktop
project) contains `data/`, which also matched the Android package `com.jarvis.ai.data`. Git silently left out
`SecureKeyStore.kt`, `SettingsStore.kt` and `SetupFileStorage.kt`, and my zip was built from `git ls-files`, so it left them
out too. Every reported error is a knock-on effect of those missing classes (`keys.get(...)` and `settings.providerOrder`
resolving to nothing). Fixed by `!data/` and `!memory/` in `android/.gitignore`, and I checked that every file on disk under
`android/` (excluding build output) is now tracked. **Not yet confirmed:** that the build passes once the files are present.
Other errors may be hidden behind these.

## What was run

An offline harness compiled and tested the pure-Kotlin `:core` module:

| Piece | What was used |
|---|---|
| Compiler | Kotlin 2.1.20, from JetBrains' GitHub release |
| Libraries | kotlinx-coroutines-core **1.6.4** and kotlinx-serialization-json **1.6.2** (jars bundled inside the Gradle distribution), not the 1.10.1 / 1.8.0 pinned in `libs.versions.toml` |
| Test libraries | kotlinx-coroutines-test 1.6.4 and atomicfu 0.17.3, **compiled by me from their GitHub sources**; JUnit 4.13.2 with `kotlin-test-junit` (the Gradle build uses JUnit 5) |
| Command | `kotlinc` for main, then tests, then `JUnitCore` on every `*Test` class |

Results:

- 35 of the 37 `:core` source files compiled with **no errors and no warnings** (the two left out need OkHttp).
- 14 of the 15 test files compiled and **all 277 tests passed** (about 0.6 s). Per file: Executor 26, JsonHttp/providers 28, Tools 24,
  Planner 14, Fallback 11, Security utils 13, Builtin tools 15, Confirmation gate 7, and for Phase 3: Feature catalogue and
  selection 38, Model advisor 24, Setup flow 22, Storage calculator 19, Model metadata client 14, Setup repository and
  codec 14, Device readings and formatting 8.

### Mutation checks

Key rules were broken one at a time to prove the tests notice.

- **Earlier (6):** skipping confirmation, not binding approval to the exact arguments, dropping the https-only rule, sending
  `temperature` to Claude, putting the Gemini key in the URL, allowing `javascript:` URLs. All caught (one needed a new test first).
- **Phase 3 (22), all caught, none survived:**
  advisor: ignoring a storage shortfall, accepting any architecture, labelling an estimate as measured, letting an unknown
  size pass, dropping the RAM rule; metadata: trusting a malformed checksum, ignoring the file name; features: selecting an
  unbuilt feature, not pulling in dependencies, leaving dependents behind on deselect; storage: counting the installed app in
  the totals, consent threshold off by one, ignoring unknown sizes for consent, treating a missing size as zero; setup flow:
  skipping required steps, finishing early, completing privacy unticked, resuming into skipped steps, not resetting storage
  after a model change, trusting a forged "finished" flag, unticking privacy keeping the step, reopening forgetting earlier steps.

**A harness incident, found and fixed:** the first Phase 3 mutation run was a background process and was cut off part-way,
leaving one mutation (`if (false)` in the privacy gate of `SetupFlow.kt`) in the source. I noticed that the run had no
closing line, searched the sources, found it, restored it, and confirmed with a full run (273 tests green at that point). The script now
snapshots every source file first and checks at the end that they are identical to the snapshot (it reported identical).
Nothing mutated was left in the tree.

### The `:app` Phase 3 files

They cannot be compiled without the Android SDK. As a partial check I ran the Kotlin compiler on the new `:app` files
against the real `:core` classes: **no syntax errors**, and every unresolved reference is an Android or Compose class (or
something that follows from one). That is a syntax check only. Type-checking against Android and Compose, the layouts, and
every screen are **unverified**.

## What was NOT run

| Not run | Why |
|---|---|
| `lint`, `assembleRelease`, and `:app:assembleDebug` after the fix | Gradle cannot run in this sandbox; the owner's PC ran `:core:test` only (above). |
| Compilation of the `:app` module (Compose UI, Keystore, device ports, the new wizard and analyzer) | No Android SDK. Reviewed by reading only. |
| `OkHttpTransport.kt` (now with a `get` method) and `HttpClients.kt` | Need OkHttp; not available offline. Checked by reading, not compiling. |
| `OkHttpTransportTest` (7 tests: MockWebServer, 2 new for GET) | Not run in the sandbox. It is part of `:core:test`, which passed on the owner's PC. |
| JUnit 5 execution, the pinned library versions | The harness used JUnit 4 and older libraries. |
| Emulator, instrumented tests, device tests | None available. Nothing in the device analyzer (Build, ActivityManager, StatFs, ConnectivityManager, SpeechRecognizer, TTS lookups), `AtomicFile` persistence, the Keystore, the flashlight, alarm or URL code has ever executed. |
| Real provider calls, and a real Hugging Face call from the app | No API keys used; no outbound calls from the app code. The metadata parser is tested against hand-written JSON that follows the shape a web fetch reported; it has not been checked against a captured live response. |
| LiteRT-LM and the Gemma models | Not integrated yet. |

## Likely first-run problems

- ~~`libs.versions.toml` versions never resolved~~ They resolved on the owner's PC. LiteRT-LM has not been added yet.
- LiteRT-LM (Phase 4) needs a newer Kotlin than the pinned 2.1.20.
- `:app` code has not met a compiler, so expect some compile errors there, especially in the Compose screens (about 1,300 new lines across the wizard, view model, analyzer and storage).
- The Phase 3 memory rule of thumb is uncalibrated and may be too cautious (`ONBOARDING.md`).

## Acceptance criteria

The prompt's acceptance list is **not met**: there is no release build, no local model that can answer, and the newest code has not been tested on
Android. Phase 3's own list is met only as far as code that has been written and unit-tested: the wizard exists, is
resumable, tags every device value as measured / estimate / unavailable, never recommends a model on RAM alone, and
cannot select a feature that does not exist. None of it has been seen running. This report will change only when those
things actually run.
