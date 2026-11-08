# Build and test report

Date: 2026-11-08 (Phases 1 to 3). This report says what was actually executed and what was not.

**No APK has been built yet.** The sandbox that wrote the code cannot reach Maven Central, Google's Maven repository or
the Gradle plugin portal (the proxy answers 403), and it has no Android SDK, emulator or phone. Everything in "What was run"
below is from that sandbox's offline harness, except the next section.

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

The prompt's acceptance list is **not met**: there is no installable APK, no local model, and nothing has been tested on
Android. Phase 3's own list is met only as far as code that has been written and unit-tested: the wizard exists, is
resumable, tags every device value as measured / estimate / unavailable, never recommends a model on RAM alone, and
cannot select a feature that does not exist. None of it has been seen running. This report will change only when those
things actually run.
