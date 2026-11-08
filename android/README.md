# J.A.R.V.I.S.-AI for Android

A standalone personal AI assistant for Android 11+ (API 30). It is built to run on the phone itself: no
desktop companion, no Ollama server, no computer pairing. Cloud AI is optional.

**Status: early foundation. There is no APK yet.** `:core:test` passes under Gradle on the owner's PC; the `:app` module has not compiled successfully yet. See
[`docs/testing/BUILD_AND_TEST_REPORT.md`](docs/testing/BUILD_AND_TEST_REPORT.md) for exactly what was and was not run, and
[`docs/ROADMAP.md`](docs/ROADMAP.md) for the ten-phase plan.

## What exists in the code today

- Chat with an AI agent that plans a request into steps (at most five), then runs registered tools.
- Five cloud providers with ordered fallback: Gemini, Groq, OpenRouter, OpenAI, Claude. Keys are encrypted with the Android Keystore.
- Five Android tools: flashlight, alarm (handed to the system Clock app), open a web link, open YouTube, device info.
- Native confirmation dialog for sensitive actions, with an activity/audit log.
- A resumable **setup wizard** (Phase 3): privacy information, a read-only analysis of the phone with every value tagged
  measured / estimate / not available, a model recommendation that explains its reasons, a feature chooser, a storage review
  and a permissions explainer. It downloads nothing and asks for no permission. See [`docs/setup/ONBOARDING.md`](docs/setup/ONBOARDING.md).

**Not built yet:** on-device Gemma models (LiteRT-LM) and their download, the task router,
voice, memory, planner screens, the Custom Agent Creator, offline mode, file manager, messaging and the rest of the prompt.

## Requirements

- JDK 17 or newer, Android Studio, Android SDK platform 35.
- A phone or emulator running Android 11 or newer.

## Build

```
./gradlew :core:test          # unit tests for the pure-Kotlin core
./gradlew :app:assembleDebug  # debug APK -> app/build/outputs/apk/debug/
```

The versions in `gradle/libs.versions.toml` resolved on a real Windows PC (JDK 19, Gradle 8.14.3). Set `sdk.dir` in
`local.properties` (Android Studio does this for you) or the `ANDROID_HOME` variable.

## Install on a phone (once a debug APK exists)

1. Enable Developer options and USB debugging on the phone.
2. Run from Android Studio, or `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Configure AI

Open the **Providers** tab, paste a key for any provider you want, check the model name, and press Save. Nothing is
required: with no key the app says no provider is configured. OpenAI has no pre-filled model; type the one you want.
Reorder providers with the arrows to change the fallback order. Keys must never go in source code, commits or logs. If a
key was ever committed to a repository, revoke it and make a new one.

## Release build (untested)

Create your own signing key and keep it out of Git (`*.jks` and `*.keystore` are already ignored). Provide the signing
settings through `~/.gradle/gradle.properties` or environment variables, add a `signingConfigs` block to
`app/build.gradle.kts`, then run `./gradlew :app:assembleRelease`. R8 minification is off until its rules are tested on a device.

## Platform limits you should know about

- Android 11+ does not let apps silently switch Wi-Fi, Bluetooth or airplane mode; the app can only open the right settings screen.
- Alarms are handed to the system Clock app; the app reports success only after Android lists the alarm.
- An app can only start another app while it is in the foreground.
- Messages can be prepared and opened in the target app, but the app cannot confirm that a message was sent.

## Documentation

[`docs/PROJECT_AUDIT.md`](docs/PROJECT_AUDIT.md) · [`docs/ROADMAP.md`](docs/ROADMAP.md) · [`docs/setup/ONBOARDING.md`](docs/setup/ONBOARDING.md) ·
[`docs/ai/CLOUD_PROVIDERS.md`](docs/ai/CLOUD_PROVIDERS.md) · [`docs/security/SECURITY.md`](docs/security/SECURITY.md) ·
[`docs/testing/BUILD_AND_TEST_REPORT.md`](docs/testing/BUILD_AND_TEST_REPORT.md) · [`CHANGELOG.md`](CHANGELOG.md)
