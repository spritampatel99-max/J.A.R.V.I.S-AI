# Build and test report

Date: 2026-11-07. This report says what was actually executed and what was not.

**No APK has been built. Gradle has never run on this project.** The sandbox that wrote the code cannot reach
Maven Central, Google's Maven repository or the Gradle plugin portal (the proxy answers 403), and it has no
Android SDK, emulator or phone.

## What was run

An offline harness compiled and tested the pure-Kotlin `:core` module:

| Piece | What was used |
|---|---|
| Compiler | Kotlin 2.1.20, from JetBrains' GitHub release |
| Libraries | kotlinx-coroutines-core **1.6.4** and kotlinx-serialization-json **1.6.2** (jars bundled inside the Gradle distribution), not the 1.10.1 / 1.8.0 pinned in `libs.versions.toml` |
| Test libraries | kotlinx-coroutines-test 1.6.4 and atomicfu 0.17.3, **compiled by me from their GitHub sources**; JUnit 4.13.2 with `kotlin-test-junit` (the Gradle build uses JUnit 5) |
| Command | `kotlinc` for main, then tests, then `JUnitCore` on every `*Test` class |

Results:

- 24 of the 26 `:core` source files compiled with **no errors and no warnings**.
- 8 of the 9 test files compiled and **all 138 tests passed** (0.78 s). Per file: Executor 26, JsonHttp/providers 28, Tools 24, Planner 14, Fallback 11, Security utils 13, Builtin tools 15, Confirmation gate 7.
- **Mutation check:** six security-relevant lines were deliberately broken one at a time (skipping confirmation, not binding approval to the exact arguments, dropping the https-only rule, sending `temperature` to Claude, putting the Gemini key in the URL, allowing `javascript:` URLs). Five were caught at once. One was not: no test covered the approval/argument mismatch check. I added that test, and it now fails when the check is broken. All files were restored and re-verified afterwards.

What the harness found in my own work this session: a test file using an extension property without its import,
two `assertEquals` calls whose type inference the real kotlin-test rejects, and a regression where a URL helper
had stopped producing canonical addresses. All three are fixed.

## What was NOT run

| Not run | Why |
|---|---|
| `./gradlew build`, `test`, `lint`, `assembleDebug`, `assembleRelease` | Gradle cannot download dependencies here. |
| Compilation of the `:app` module (10 files, Compose UI, Keystore, device ports) | No Android SDK. It was reviewed by reading only. |
| `OkHttpTransport.kt` and `HttpClients.kt` | Need OkHttp; not available offline. I checked the OkHttp calls by reading, not compiling. |
| `OkHttpTransportTest` (5 tests: MockWebServer) | Same reason. Written, never compiled or run. |
| JUnit 5 execution, the pinned library versions | The harness used JUnit 4 and older libraries. |
| Emulator (API 30), instrumented tests, device tests | None available. The flashlight, alarm, URL, Keystore and UI code has never executed. |
| Real provider calls | No API keys were used; provider behaviour is tested against scripted replies only. |
| LiteRT-LM and the Gemma models | Not integrated yet. |

## Likely first-run problems

- `libs.versions.toml` versions were never resolved. Any of them may need bumping at the first Gradle sync.
- LiteRT-LM (Phase 4) needs a newer Kotlin than the pinned 2.1.20.
- `:app` code has not met a compiler, so expect some compile errors there.

## Acceptance criteria

The prompt's acceptance list is **not met**: there is no installable APK, no onboarding, no local model, and nothing
has been tested on Android. This report will change only when those things actually run.
