# J.A.R.V.I.S.-AI for Android

A standalone personal AI assistant for Android 11+ (API 30). It is built to run on the phone itself: no
desktop companion, no Ollama server, no computer pairing. Cloud AI is optional.

**Status: early foundation, no release build.** `:core:test` passes under Gradle on the owner's PC, and an earlier version of the debug app was built, installed and shown running on the owner's phone (screenshots, 2026-11-08). The latest rounds of changes (model install, access screen, built-in commands, the Stitch-based screens, voice and calls, memory, planner, automations, custom agents, offline and security screens) have **not been compiled yet**. See
[`docs/testing/BUILD_AND_TEST_REPORT.md`](docs/testing/BUILD_AND_TEST_REPORT.md) for exactly what was and was not run, and
[`docs/ROADMAP.md`](docs/ROADMAP.md) for the ten-phase plan.

## What exists in the code today

- Chat with an AI agent that plans a request into steps (at most five), then runs registered tools.
- **Built-in commands that need no AI:** flashlight, alarm, YouTube, open a website, battery/storage. They run on the phone with no AI key, and go through the same safety checks as AI plans.
- Five cloud providers with ordered fallback: Gemini, Groq, OpenRouter, OpenAI, Claude. Keys are encrypted with the Android Keystore. The Cloud tab can **load the models your own key can use** and **test** a model, because pre-filled model names go stale.
- **Models tab:** download (with resume and a progress notification), import a file, verify and remove the on-device models Gemma 3 1B and Gemma 4 E2B. An installed model cannot answer chat yet; that needs the on-device engine, the next step. See [`docs/ai/LOCAL_MODELS.md`](docs/ai/LOCAL_MODELS.md).
- **Access tab:** real permission requests with the state Android reports and an "Allow all" walk-through. See [`docs/setup/ACCESS.md`](docs/setup/ACCESS.md).
- **The Stitch design** (Prismatic Spectral Core): five tabs (Home, Chat, Tools, Logs, Prefs), a living orb tied to the real voice state, glass cards, a floating tab bar, and two bundled open-licence fonts (Inter, Space Grotesk; licences in `docs/legal`). Only dark is built. What was taken from the design and what was left out is in [`docs/ui/DESIGN_NOTES.md`](docs/ui/DESIGN_NOTES.md).
- **Voice and calls:** push-to-talk with Android's speech recogniser, spoken replies for spoken requests, calling a contact or number, answering and ending a call. Every call needs a tap on a confirmation that shows the exact name and number. See [`docs/VOICE_AND_CALLS.md`](docs/VOICE_AND_CALLS.md).
- **Logs:** conversations saved on the phone only (keys removed), searchable, deletable, plus the action record.
- Fifteen Android tools: flashlight, alarm (handed to the system Clock app), open a web link, open YouTube, device info, call a contact, call a number, answer a call, end a call, plus add task, add note, add reminder, daily overview, show notification and notify overview.
- **Memory** that saves only what you add or clearly ask to remember ("remember that …"), with delete and clear in Prefs. See [`docs/ai/MEMORY_AND_PLANNER.md`](docs/ai/MEMORY_AND_PLANNER.md).
- **Tasks, notes and reminders** with one Android alarm wake-up, re-armed after reboot and app update, and honest "not shown" reporting when notifications are blocked.
- **Automations** limited to four low-risk actions (add task, add note, show notification, notify overview) on a daily, weekly or every-N-minutes schedule. See [`docs/AUTOMATIONS.md`](docs/AUTOMATIONS.md).
- **Custom Agent Creator**: a five-step wizard that builds an agent as plain configuration, previews it with no tools, and activates it only after your Approve tap. Memory is private to each agent. See [`docs/ai/CUSTOM_AGENTS.md`](docs/ai/CUSTOM_AGENTS.md).
- **Offline report and security review** screens that state what works without internet and what was and was not checked. See [`docs/security/OFFLINE_AND_SECURITY_REVIEW.md`](docs/security/OFFLINE_AND_SECURITY_REVIEW.md).
- Native confirmation dialog for sensitive actions, with an activity/audit log.
- A resumable **setup wizard** (Phase 3): privacy information, a read-only analysis of the phone with every value tagged
  measured / estimate / not available, a model recommendation that explains its reasons, a feature chooser, a storage review
  and a permissions step. It downloads nothing and asks for nothing until you tap. See [`docs/setup/ONBOARDING.md`](docs/setup/ONBOARDING.md).

**Not built yet:** the on-device engine (LiteRT-LM) that would let an installed model answer (so AI chat needs internet and a cloud key), a light theme, a wake word, web research, document reading, agent knowledge sources, a memory-suggestion generator, editing an automation, and a signed release build. See the roadmap for the state of each phase.

## Requirements

- JDK 17 or newer, Android Studio, Android SDK platform 35.
- A phone or emulator running Android 11 or newer.

## Build

```
./gradlew :core:test          # unit tests for the pure-Kotlin core
./gradlew :app:assembleDebug  # debug APK -> app/build/outputs/apk/debug/ (Windows: .\gradlew)
```

The versions in `gradle/libs.versions.toml` resolved on a real Windows PC (JDK 19, Gradle 8.14.3). Set `sdk.dir` in
`local.properties` (Android Studio does this for you) or the `ANDROID_HOME` variable.

## Install on a phone (once a debug APK exists)

1. Enable Developer options and USB debugging on the phone.
2. Run from Android Studio, or `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Configure AI

Open the **Cloud** tab, paste a key for any provider you want, press **Load models** to see what that key can use, pick one,
press Save, then **Test**. Nothing is required: with no key the built-in commands still work and chat says no provider is configured.
OpenAI has no pre-filled model. Reorder providers with Up/Down to change the fallback order. Keys must never go in source code, commits or logs. If a
key was ever committed to a repository, revoke it and make a new one.

## Release build (not done; see [`docs/release/RELEASE_CHECKLIST.md`](docs/release/RELEASE_CHECKLIST.md))

Create your own signing key and keep it out of Git (`*.jks` and `*.keystore` are already ignored). Provide the signing
settings through `~/.gradle/gradle.properties` or environment variables, add a `signingConfigs` block to
`app/build.gradle.kts`, then run `./gradlew :app:assembleRelease`. R8 minification is off until its rules are tested on a device.

## Platform limits you should know about

- Android 11+ does not let apps silently switch Wi-Fi, Bluetooth or airplane mode; the app can only open the right settings screen.
- Alarms are handed to the system Clock app; the app reports success only after Android lists the alarm.
- An app can only start another app while it is in the foreground.
- Messages can be prepared and opened in the target app, but the app cannot confirm that a message was sent.
- Reminders and automations run when Android wakes the app. Without the "Alarms & reminders" special access the time can be minutes late, and some phone makers delay background work further.

## Documentation

[`docs/PROJECT_AUDIT.md`](docs/PROJECT_AUDIT.md) · [`docs/ROADMAP.md`](docs/ROADMAP.md) · [`docs/setup/ONBOARDING.md`](docs/setup/ONBOARDING.md) ·
[`docs/ai/CLOUD_PROVIDERS.md`](docs/ai/CLOUD_PROVIDERS.md) · [`docs/ai/LOCAL_MODELS.md`](docs/ai/LOCAL_MODELS.md) · [`docs/setup/ACCESS.md`](docs/setup/ACCESS.md) · [`docs/security/SECURITY.md`](docs/security/SECURITY.md) ·
[`docs/ai/MEMORY_AND_PLANNER.md`](docs/ai/MEMORY_AND_PLANNER.md) · [`docs/AUTOMATIONS.md`](docs/AUTOMATIONS.md) · [`docs/ai/CUSTOM_AGENTS.md`](docs/ai/CUSTOM_AGENTS.md) ·
[`docs/security/OFFLINE_AND_SECURITY_REVIEW.md`](docs/security/OFFLINE_AND_SECURITY_REVIEW.md) · [`docs/release/RELEASE_CHECKLIST.md`](docs/release/RELEASE_CHECKLIST.md) ·
[`docs/testing/BUILD_AND_TEST_REPORT.md`](docs/testing/BUILD_AND_TEST_REPORT.md) · [`CHANGELOG.md`](CHANGELOG.md)
