# Status of this zip (2026-11-26)

Read this before building. It says what is finished, what was only compiled, and what was not done. If another document disagrees with this one, check the code; this file was written last.

## Checked here
- `:core` (the logic: tools, safety rules, planner, router, screen guard, phone operator, voice controls, offline library reader, routines, data-usage maths, shortcuts and the rest) compiles and passes **1142 tests**, run on a PC-style JVM in the workspace (`kotlinc` + JUnit, not Gradle), and the same tests pass in GitHub Actions.
- Safety rules were also checked by deliberately breaking them (mutation checks). This round: **30 deliberate breaks** of the new logic (replanning history, clock and time-zone rule, routines, data-usage maths, shortcuts) and **all 30 are caught by a test**. Earlier rounds covered the money-button refusal, the risky-label refusal, the operator's "never" class, the interruption window, the scroll / tap guards, call safety and the automation scheduler. The mutation list is not a proof that nothing is wrong; it shows that those specific mistakes would be noticed.

## Compiled on GitHub, but NEVER run on a phone (`:app`, the Android part)
This workspace cannot reach Google's Android build servers, so the app is built by GitHub Actions (`docs/BUILD_ON_GITHUB.md`). The latest build (run 45815957725, branch `ci/android-build`, commit 2b540ae): `:core:test` passed, `:app:assembleDebug` compiled with **0 compiler warnings**, Android lint finished with **0 errors and 39 warnings** (81 at the start of the audit; what is left is listed in `docs/AUDIT_2026-11-25.md`), and the **16 KB page-size check passed** (12 libraries, 0 problems). **That proves the code compiles and packages. It does not prove any feature works.** Nothing below has been opened on a phone.

- 16 KB page size: the check passes with Vosk 0.3.75 and JNA 5.17.0. That is a check of the files' alignment. **Whether the newer Vosk still recognises "Jarvis" offline has not been tried**; the wake-word model and recogniser are the same idea but a newer library.
- Android 15 phones with 16 KB pages are therefore no longer flagged; the app still has never started on one.

### Written in this round (all unverified on a device)
- **Personal Routines**, **Mobile Data Monitor** and **home shortcuts** (the "Adaptive Dashboard"): see `docs/ROUTINES_DATA_SHORTCUTS.md`. Core tested; screens and the Android data reader compiled only. The data monitor needs Usage access and its numbers have never been compared with the phone's own Settings.
- **Hardening:** the key store no longer shows "saved" for a key that cannot be decrypted and survives a Keystore failure; the Bluetooth "start listening" notification now opens the app when tapped (a plain tap is how Android 14 allows the microphone to start); the clock or time zone changing recomputes automation times (`docs/AUTOMATIONS.md`); the execution report keeps the history of a replanned run ("Tried first, then replaced") and counts only the final attempt; a `mipmap-anydpi` rename for lint; deprecated calls replaced or marked with the reason they cannot be replaced.

### Written earlier and still unverified on a device
- **Voice:** optional ElevenLabs voice chosen in setup (needs your own key; the text to be spoken is sent to ElevenLabs). Bluetooth companion: when a chosen Bluetooth device connects, JARVIS offers hands-free and can read new notifications aloud. Light / dark / system theme switch (**the light theme has never been looked at**).
- **Interrupting JARVIS (barge-in):** while JARVIS speaks the wake-word detector keeps listening. Saying "Jarvis" stops the speech and listens; "stop", "quiet", "repeat that" are handled on the phone without the AI. What was cut off is marked in the conversation so your next sentence can change the answer. **How well it hears you over its own voice depends on the phone's echo cancellation; this is untested.**
- **WhatsApp:** "how many unread chats" opens WhatsApp and reads its chat list. **YouTube:** "play <song>" opens the search results and presses the first real video. Both depend on text the apps show on screen; **those markers were written from general knowledge and never checked against the real apps.**
- **New built-in commands (no AI needed):** brightness, silent / vibrate / normal mode (silent needs Do Not Disturb access), 30 Settings pages, cancel / dismiss an alarm, clipboard, count apps, Maps / directions, permission check, calculator, unit converter, random pick.
- **Contacts:** a name that is not found offers the closest similar names instead of failing.
- **Phone operator** ("do it for me in any app"): off until you switch it on in Prefs > Access > Phone operator (also needs Accessibility and "press buttons and type"). See `docs/PHONE_OPERATOR.md`. **It sends the text on the screen to your AI provider.**
- **Payment mode** ("payment mode", "I am going to pay"): asks Android to switch the accessibility service off and stops listening before you open a payment app. The cause of payment apps closing is **not confirmed**; this is a mitigation, not a fix.

## Automation reliability (2026-11-24, clock rule 2026-11-26)
See CHANGELOG. Core tested; compiled in CI. Nothing has run on a phone. The question of which automation you made and what it did on your phone is still open (see below).

## What "anything I say gets done" can and cannot mean
It cannot mean everything. Android blocks apps from some things by design, and JARVIS does not try to get around that: see `docs/PHONE_OPERATOR.md`. In short: banking, payment and password-manager apps, system permission screens and JARVIS's own screens are never read or pressed; money is never moved; the person's confirmation is always a tap in JARVIS itself, never something the AI or text on a screen can supply.

## Features still shown as "planned", not available
**File Manager** and **Document Intelligence**: both need a file-picker screen and reading of the person's own files, and I cannot see a screen to build and check that safely. **Local AI Lab** stays planned: the default build has no on-device engine, so an installed Gemma model still cannot answer chat. Multilingual covers English and Hindi only (per-app language; most screen text is still English).

## Known limits to expect on the phone
- Android 14+ will not let a microphone service start from a Bluetooth broadcast or at boot. After a reboot or a Bluetooth connect JARVIS shows a notification; tap it to open JARVIS and start listening. **I could not verify what the tap does on a real phone.**
- Hindi song names ("tum hi ho") are speech recognition: pick Hindi in Prefs > Language so the recogniser listens for Hindi.
- AI providers: free tiers rate-limit and models get retired; use Load models and Test in the Cloud tab. Several providers (OpenAI, Claude, Mistral, FreeLLM) work only after you add your own key.
- The on-device AI engine is opt-in and uncompiled (`docs/ai/LOCAL_MODELS.md`).
- The app's libraries (Compose, AndroidX, the Android Gradle plugin) are about a year behind the newest versions and `targetSdk` is 35. I left them: changing the UI libraries of an app nobody has run since its last redesign would make any problem on the phone hard to trace. Do it as its own step after the first phone test.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` would be refused on the Play Store. It is kept for sideloading, where it makes automations more reliable.

## Not built
- Tapping at screen coordinates, swipes and gestures; photo editing; setting ringtones; switching Wi-Fi / Bluetooth / airplane mode (Android does not allow it; the app opens the Settings page).
- Self-modifying code or silent self-update. The nightly 10 pm job only notifies and proposes; you install new versions.
- Streaming replies, Live Updates notifications, a floating orb, a custom "hyee" wake word, a signed release build. **No release APK exists; only a debug build from CI.**
- File manager, document reading, WorkManager, editing an automation.

## Still open, and why
| What | Why |
|---|---|
| Anything that needs a phone | WhatsApp count, YouTube press, barge-in, operator, ElevenLabs, Bluetooth tap, light theme, offline listening with the new Vosk, data monitor numbers, routine reminders arriving, the payment-app cause. I cannot run the app. |
| File Manager, Document Intelligence | Need screens I cannot see; not faked. |
| Library / AGP / targetSdk upgrades | Deliberately left until after a phone test (see above). |
| Play Store policy for battery exemption | Only matters for a Play Store release. |

## Not taken from your JARVIS-Project zip, and why
- Its `core` files did not compile against the current code. Its `LocalEngine` was a template placeholder, `WakeWordService` simulated detection, and `SpeakerVerification` gave a false sense of security. The ideas were kept where they could be built honestly.
