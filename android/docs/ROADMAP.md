# Roadmap and status

Ten phases, taken from the master prompt. "Done" means written *and* verified; the verification actually
performed is described in `testing/BUILD_AND_TEST_REPORT.md`. The `:core` module has been built and its tests run
under Gradle on the project owner's PC (they passed). The `:app` module (Android code and screens) has **not**
compiled successfully yet in its latest form. An earlier version of the debug app was built and shown running on the owner's phone (screenshots), but this round's changes have not been compiled, and no release build exists.

| # | Phase | Status |
|---|---|---|
| 1 | Project audit | **Done**: `PROJECT_AUDIT.md` |
| 2 | Android foundation | **Partly done** (see below) |
| 3 | Onboarding, device analysis, feature selection | **Written; logic tested offline, screens never compiled or shown.** See `setup/ONBOARDING.md` |
| 4 | Local AI (LiteRT-LM, Gemma 3 1B / Gemma 4 E2B, model manager) | **Partly done:** model manager (download, resume, verify, import, remove) written and unit-tested offline. **Not done:** the inference engine, so an installed model cannot answer chat. See `ai/LOCAL_MODELS.md` |
| 5 | Cloud AI + intelligent router | Partly done: five provider adapters, the fallback client, model listing and a connection test exist; built-in commands run before the AI. The route planner and the on-device/cloud router (four modes) are written and unit-tested but **not wired into the app**; the task classifier and the mode settings screen do not exist |
| 6 | Android integration (voice, apps, calls, messages, notifications, files, data monitor) | Started: 5 tools, and the Access screen that requests real permissions (most are "not used yet") |
| 7 | Memory, planner, automation, Custom Agent Creator | **Core logic written and unit-tested offline; screens and Android wiring written, never compiled.** Room replaced by atomic JSON files. Not built: memory-suggestion generator, automation edit, agent knowledge sources, web/document features. See `ai/MEMORY_AND_PLANNER.md`, `AUTOMATIONS.md`, `ai/CUSTOM_AGENTS.md` |
| 8 | Offline mode and the Offline Preparation Center | **Report and self-test written (core tested, screen uncompiled).** AI chat without internet is **not built** (no engine). See `security/OFFLINE_AND_SECURITY_REVIEW.md` |
| 9 | Security centre, hardening, instrumented tests | Security review written (core tested, screen uncompiled). **No instrumented tests, lint, dependency scan or penetration test have been run.** |
| 10 | Release build, signed APK, final documentation | **Documentation and a release checklist only (`release/RELEASE_CHECKLIST.md`). No signed build, no APK.** |

## Phase 2: what exists

| Item | State |
|---|---|
| Kotlin multi-module Gradle project, version catalog, wrapper (Gradle 8.14.3) | Written. `:core` resolved, compiled and tested on the owner's PC; `:app` not compiled successfully yet. |
| Compose theme and shell (Chat, Models, Cloud, Access, Log tabs), animated backdrop, bundled fonts | Written. The earlier shell was seen running on a phone; the new one has not been compiled. |
| Navigation Compose | Declared as a dependency but unused; the shell switches tabs by hand. |
| Dependency injection | Manual `AppContainer`. |
| Room | Not started. |
| DataStore | Declared, not used; `SharedPreferences` holds non-secret settings. |
| Android Keystore storage for keys | Written (`SecureKeyStore`). Not run on a device. |
| HTTPS-only network layer with typed errors, retries and rate-limit cooldown | Written and unit-tested (see report). The OkHttp adapter itself is not. |
| Unit-test setup | 277 tests run in an offline harness; `:core:test` also passed through Gradle on the owner's PC (including the OkHttp/MockWebServer tests). |

## Already in place that later phases build on

- **Security:** native confirmation gate bound to the exact action, audit log, secret redaction, untrusted-content wrapper (`docs/security/SECURITY.md`).
- **Agent:** planner, executor, tool registry with argument validation and risk levels.
- **Tools (Android side):** `flashlight`, `set_alarm`, `open_url`, `open_youtube`, `get_device_info`.
- **Cloud:** Gemini, Groq, OpenRouter, OpenAI and Claude adapters with ordered fallback.

## Phase 3: what exists

Setup wizard (9 steps, resumable), device analysis with measured/estimate/unavailable tagging, model advisor, exact-size
lookup from Hugging Face on request, 25-feature catalogue (3 selectable), presets, dependency resolution, storage review,
permission explanations. Details and limits in `setup/ONBOARDING.md`.

## Phase 7 to 9: what exists

See the three documents named in the table. In short: memory with explicit saving, tasks / notes / reminders with an alarm wake-up, automations restricted to four low-risk actions, custom agents as configuration only (draft, preview without tools, activate on a tap), an offline report with a real storage self-test, and a security review that lists what it did not check.

## Not yet built for the cloud side

Streaming, usage metadata, cancel buttons, the task classifier, the four
routing modes, and the Online/Offline/Automatic mode detector.

## Known gaps against the prompt that are deliberately deferred

- Hilt is not used (manual injection instead).
- `minifyEnabled` is off until R8 rules are tested on a device.
- The Kotlin version has to be raised for LiteRT-LM (audit §6).
