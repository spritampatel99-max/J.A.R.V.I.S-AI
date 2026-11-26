# JARVIS 3.0 for Android

A standalone personal AI assistant for Android 11+ (API 30). It is built to run on the phone itself: no
desktop companion, no Ollama server, no computer pairing. Cloud AI is optional.

**Status: early foundation, no release build.** Read `STATUS.md` first; it is the current truth. `:core` is tested offline (1058 tests) and compiled and tested by GitHub Actions on branch `ci/android-build`; `:app` compiles there too (debug APK only). **Nothing in the latest rounds has been run on a phone.** An earlier version of the debug app was built, installed and shown running on the owner's phone (2026-11-08). How this project is built: `docs/BUILD_ON_GITHUB.md`.
[`docs/testing/BUILD_AND_TEST_REPORT.md`](docs/testing/BUILD_AND_TEST_REPORT.md) for exactly what was and was not run, and
[`docs/ROADMAP.md`](docs/ROADMAP.md) for the ten-phase plan.

## What exists in the code today

- Chat with an AI agent that plans a request into steps (at most five), then runs registered tools.
- **Built-in commands that need no AI:** flashlight, alarm, YouTube, open a website, battery/storage. They run on the phone with no AI key, and go through the same safety checks as AI plans.
- Seven cloud providers with ordered fallback: Gemini, Groq, OpenRouter, OpenAI, Mistral, Claude, and **FreeLLM / own endpoint** (an OpenAI-compatible service at an https address you type; nothing is built in, the key is optional, and everything you chat goes to that address). Keys are encrypted with the Android Keystore. The Cloud tab can **load the models your own key can use** and **test** a model, because pre-filled model names go stale.
- **Models tab:** download (with resume and a progress notification), import a file, verify and remove the on-device models Gemma 3 1B and Gemma 4 E2B. **AI mode** (Prefs): Local only, Local first, Cloud assisted, Manual. The router is wired in, but the default build has **no on-device engine**, so an installed model cannot answer chat. A LiteRT-LM engine is written as an opt-in build (`enableLiteRt`), never compiled; the steps and the risks are in [`docs/ai/LOCAL_MODELS.md`](docs/ai/LOCAL_MODELS.md).
- **Access tab:** real permission requests with the state Android reports and an "Allow all" walk-through. See [`docs/setup/ACCESS.md`](docs/setup/ACCESS.md).
- **The Stitch design** (Prismatic Spectral Core): five tabs (Home, Chat, Tools, Logs, Prefs), a living orb tied to the real voice state, glass cards, a floating tab bar, and two bundled open-licence fonts (Inter, Space Grotesk; licences in `docs/legal`). Only dark is built. What was taken from the design and what was left out is in [`docs/ui/DESIGN_NOTES.md`](docs/ui/DESIGN_NOTES.md).
- **Voice:** push-to-talk with Android's speech recogniser, spoken replies for spoken requests, and an optional always-listening **"Jarvis" wake word** (Picovoice Porcupine; needs your own free AccessKey; costs battery; the app must be opened once after a reboot).
- **Calls:** calling a contact or number, answering and ending a call. A call you **speak** as a plain built-in command is read back ("Calling Mom, mobile") and dialled after a 3-second countdown unless you say "stop" or tap Cancel. A call that is **typed**, or planned by the AI, needs a tap on a card that shows the exact name and number. See [`docs/VOICE_AND_CALLS.md`](docs/VOICE_AND_CALLS.md).
- **Messages, timers and media keys:** "text Mom hello" and "WhatsApp Mom hello" open the messaging app with the text ready; **you press Send**. WhatsApp is the one exception, and only if you switch on "Press Send for me in WhatsApp" (Prefs > Phone control, off by default): then the app presses Send itself, but only after your confirmation (or the spoken read-back and 3-second countdown) and only if WhatsApp's box holds exactly the words you approved. It says "pressed Send", never "delivered". "Set a timer for 10 minutes" starts the Clock app's countdown. "Pause", "next track", "volume up" send media keys. Each result says what was *asked* of Android, not that it happened.
- **Phone control:** an accessibility service reads the visible screen text, scrolls, and presses Back / Home / Recents / lock / screenshot; a notification listener reads and clears notifications, and "read my unread WhatsApp messages" reads the WhatsApp notifications still showing (messages you already opened or dismissed are not listed). With the separate, default-off switch "Let JARVIS press buttons and type in other apps", "tap Chats" and "type hello" use Android's click and set-text actions on a control found by its name: at most 12 a minute, never a button called send, pay, buy, delete, confirm or allow, never banking, payment or password apps, never JARVIS itself or system permission screens. Screen and notification text stays on the phone and is never logged or handed to a model, and text read from a screen can never become a command. Both services are off until you switch them on in Android Settings.
- **Nightly review and updates:** an opt-in 10 pm review (Prefs > Nightly review) that lists repeated failures and Android settings that stop reminders working, and (every 10 days, or on "check for updates") looks at this project's public GitHub release page. You choose "Apply" (it only opens a page or settings screen) or "Leave as it is". The app cannot rewrite itself or install a new version without your tap and Android's own confirmation.
- **Photos:** the attach-photo button uses Android's photo picker (only the photos you select; no photo permission). The photo is shrunk, sent only to a cloud AI you set up, not saved, and no action can start from it. On-device models cannot look at photos.
- **Offline library:** a Kiwix `.zim` Wikipedia file (Simple English about 1 GB, Top articles about 2.1 GB) you download or import with a tap. "Search offline for black hole" answers from it with no internet. It looks things up; it does not chat.
- **Logs:** conversations saved on the phone only (keys removed), searchable, deletable, plus the action record.
- Thirty-two tools. **Phone:** flashlight, alarm and timer (handed to the Clock app), open a link, open YouTube, open an app, media keys, device info, current time. **Calls and messages:** call a contact, call a number, answer, end, text (SMS), WhatsApp, email (all drafts: you press Send). **Camera:** open the camera app for a photo or a video (you press the shutter). **Phone control:** read screen, scroll, navigate (Back/Home/Recents/lock/screenshot), read and clear notifications. **Productivity:** add task, add note, add reminder, daily overview, show notification, notify overview. **Lookups (read-only):** weather, Wikipedia, a grounded web answer, and the offline library. Lookup text is untrusted content: it can be shown and read aloud but cannot approve or trigger anything.
- **Personal Routines, Mobile Data Monitor and home shortcuts** (written 2026-11-26, never run on a phone): named step lists you can have read out or shown as a daily notification; your mobile-data use today / this week / this month per app (needs Usage access); and one-tap shortcuts for requests that worked twice. See [`docs/ROUTINES_DATA_SHORTCUTS.md`](docs/ROUTINES_DATA_SHORTCUTS.md).
- **Memory** that saves only what you add or clearly ask to remember ("remember that …"), with delete and clear in Prefs. See [`docs/ai/MEMORY_AND_PLANNER.md`](docs/ai/MEMORY_AND_PLANNER.md).
- **Tasks, notes and reminders** with one Android alarm wake-up, re-armed after reboot and app update, and honest "not shown" reporting when notifications are blocked.
- **Automations** limited to five low-risk actions (add task, add note, show notification, notify overview, show a routine) on a daily, weekly or every-N-minutes schedule. See [`docs/AUTOMATIONS.md`](docs/AUTOMATIONS.md).
- **Custom Agent Creator**: a five-step wizard that builds an agent as plain configuration, previews it with no tools, and activates it only after your Approve tap. Memory is private to each agent. See [`docs/ai/CUSTOM_AGENTS.md`](docs/ai/CUSTOM_AGENTS.md).
- **Offline report and security review** screens that state what works without internet and what was and was not checked. See [`docs/security/OFFLINE_AND_SECURITY_REVIEW.md`](docs/security/OFFLINE_AND_SECURITY_REVIEW.md).
- Native confirmation dialog for sensitive actions, with an activity/audit log.
- A resumable **setup wizard** (Phase 3): privacy information, a read-only analysis of the phone with every value tagged
  measured / estimate / not available, a model recommendation that explains its reasons, a feature chooser, a storage review
  and a permissions step. It downloads nothing and asks for nothing until you tap. See [`docs/setup/ONBOARDING.md`](docs/setup/ONBOARDING.md).

**Not built yet:** a working on-device chat engine (the LiteRT-LM engine is an unverified opt-in, so AI chat needs internet and a cloud key by default), tapping at screen coordinates and swipes (opt-in label tap / type exists; see STATUS.md), streaming replies, a floating orb, document reading, a file manager, agent knowledge sources, a memory-suggestion generator, editing an automation, and a signed release build. See the roadmap for the state of each phase.

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
OpenAI, Mistral and FreeLLM / own endpoint have no pre-filled model: press Load models. For FreeLLM / own endpoint, first type the https address (for example `https://your-gateway.example.com/v1`); a key is optional. Reorder providers with Up/Down to change the fallback order. Keys must never go in source code, commits or logs. If a
key was ever committed to a repository, revoke it and make a new one.

## Release build (not done; see [`docs/release/RELEASE_CHECKLIST.md`](docs/release/RELEASE_CHECKLIST.md))

Create your own signing key and keep it out of Git (`*.jks` and `*.keystore` are already ignored). Provide the signing
settings through `~/.gradle/gradle.properties` or environment variables, add a `signingConfigs` block to
`app/build.gradle.kts`, then run `./gradlew :app:assembleRelease`. R8 minification is off until its rules are tested on a device.

## Platform limits you should know about

- Android 11+ does not let apps silently switch Wi-Fi, Bluetooth or airplane mode; the app can only open the right settings screen.
- Alarms are handed to the system Clock app; the app reports success only after Android lists the alarm.
- Android blocks starting an app from the background unless the app has "Display over other apps" (the Access tab asks for it). Android 14 and 15 tighten this further and may still refuse on some phones; the app then says so.
- Messages are opened in the target app with the text ready. The app presses Send only for WhatsApp, only with your switch on, and it cannot confirm that a message was delivered (it can see whether the box emptied). WhatsApp opens a chat directly only for a number that starts with `+`; otherwise it opens WhatsApp's own picker with the text ready.
- Timers and media keys are *requests*: Android does not report back, so the app says "asked", not "done".
- "Jarvis" is the only wake word. Porcupine ships it as a built-in keyword; "hyee" or other words would need a custom keyword file trained in the Picovoice console.
- Reminders and automations run when Android wakes the app. Without the "Alarms & reminders" special access the time can be minutes late, and some phone makers delay background work further.

## Documentation

[`docs/PROJECT_AUDIT.md`](docs/PROJECT_AUDIT.md) · [`docs/ROADMAP.md`](docs/ROADMAP.md) · [`docs/setup/ONBOARDING.md`](docs/setup/ONBOARDING.md) ·
[`docs/ai/CLOUD_PROVIDERS.md`](docs/ai/CLOUD_PROVIDERS.md) · [`docs/ai/LOCAL_MODELS.md`](docs/ai/LOCAL_MODELS.md) · [`docs/setup/ACCESS.md`](docs/setup/ACCESS.md) · [`docs/security/SECURITY.md`](docs/security/SECURITY.md) ·
[`docs/ai/MEMORY_AND_PLANNER.md`](docs/ai/MEMORY_AND_PLANNER.md) · [`docs/AUTOMATIONS.md`](docs/AUTOMATIONS.md) · [`docs/ROUTINES_DATA_SHORTCUTS.md`](docs/ROUTINES_DATA_SHORTCUTS.md) · [`docs/ai/CUSTOM_AGENTS.md`](docs/ai/CUSTOM_AGENTS.md) ·
[`docs/security/OFFLINE_AND_SECURITY_REVIEW.md`](docs/security/OFFLINE_AND_SECURITY_REVIEW.md) · [`docs/release/RELEASE_CHECKLIST.md`](docs/release/RELEASE_CHECKLIST.md) ·
[`docs/testing/BUILD_AND_TEST_REPORT.md`](docs/testing/BUILD_AND_TEST_REPORT.md) · [`CHANGELOG.md`](CHANGELOG.md)
