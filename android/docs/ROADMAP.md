# Roadmap and status

Ten phases, taken from the master prompt. "Done" means written *and* verified; the verification actually
performed is described in `testing/BUILD_AND_TEST_REPORT.md`. Nothing here has been built with Gradle or run
on a device or emulator.

| # | Phase | Status |
|---|---|---|
| 1 | Project audit | **Done**: `PROJECT_AUDIT.md` |
| 2 | Android foundation | **Partly done** (see below) |
| 3 | Onboarding, device analysis, feature selection | **Written; logic tested offline, screens never compiled or shown.** See `setup/ONBOARDING.md` |
| 4 | Local AI (LiteRT-LM, Gemma 3 1B / Gemma 4 E2B, model manager) | Not started; research done in the audit |
| 5 | Cloud AI + intelligent router | Partly done: five provider adapters and the fallback client exist; the router does not |
| 6 | Android integration (voice, apps, calls, messages, notifications, files, data monitor) | Started: 5 tools |
| 7 | Memory, planner, automation, Custom Agent Creator | Not started |
| 8 | Offline mode and the Offline Preparation Center | Not started |
| 9 | Security centre, hardening, instrumented tests | Core security pieces exist; the screens do not |
| 10 | Release build, signed APK, final documentation | Not started |

## Phase 2: what exists

| Item | State |
|---|---|
| Kotlin multi-module Gradle project, version catalog, wrapper (Gradle 8.14.3) | Written. Not resolved or built here. |
| Compose theme and a small shell (Chat, Providers, Activity tabs) | Written. Never rendered. |
| Navigation Compose | Declared as a dependency but unused; the shell switches tabs by hand. |
| Dependency injection | Manual `AppContainer`. |
| Room | Not started. |
| DataStore | Declared, not used; `SharedPreferences` holds non-secret settings. |
| Android Keystore storage for keys | Written (`SecureKeyStore`). Not run on a device. |
| HTTPS-only network layer with typed errors, retries and rate-limit cooldown | Written and unit-tested (see report). The OkHttp adapter itself is not. |
| Unit-test setup | 277 tests run in an offline harness. Not yet through Gradle. |

## Already in place that later phases build on

- **Security:** native confirmation gate bound to the exact action, audit log, secret redaction, untrusted-content wrapper (`docs/security/SECURITY.md`).
- **Agent:** planner, executor, tool registry with argument validation and risk levels.
- **Tools (Android side):** `flashlight`, `set_alarm`, `open_url`, `open_youtube`, `get_device_info`.
- **Cloud:** Gemini, Groq, OpenRouter, OpenAI and Claude adapters with ordered fallback.

## Phase 3: what exists

Setup wizard (9 steps, resumable), device analysis with measured/estimate/unavailable tagging, model advisor, exact-size
lookup from Hugging Face on request, 25-feature catalogue (3 selectable), presets, dependency resolution, storage review,
permission explanations. Details and limits in `setup/ONBOARDING.md`.

## Not yet built for the cloud side

Connection test, model listing, streaming, usage metadata, cancel buttons, the task classifier, the four
routing modes, and the Online/Offline/Automatic mode detector.

## Known gaps against the prompt that are deliberately deferred

- Hilt is not used (manual injection instead).
- `minifyEnabled` is off until R8 rules are tested on a device.
- The Kotlin version has to be raised for LiteRT-LM (audit §6).
