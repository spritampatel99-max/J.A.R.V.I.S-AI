# Status of this zip (2026-11-24)

Read this before building. It says what is finished, what was only compiled, and what was not done. If another document disagrees with this one, check the code; this file was written last.

## Checked here
- `:core` (the logic: tools, safety rules, planner, router, screen guard, phone operator, voice controls, offline library reader and the rest) compiles and passes **1029 tests**, run on a PC-style JVM in the workspace (`kotlinc` + JUnit, not Gradle).
- Safety rules were also checked by deliberately breaking them (mutation checks). This round: the money-button refusal, the risky-label refusal, the operator's "never" class, the interruption time window, and the scroll / tap guards. Two breaks survived at first (scrolling in a banking app, and an approved press on a money button); tests were added and both are now caught. Mutation checks cover only the rules named here, not the whole project.

## Compiled on GitHub, but NEVER run on a phone (`:app`, the Android part)
This workspace cannot reach Google's Android build servers, so the app is built by GitHub Actions (`docs/BUILD_ON_GITHUB.md`). The latest build I read (run 45349376355, branch `ci/android-build`): `:core:test` passed, `:app:assembleDebug` compiled, Android lint finished with no errors. **That proves the code compiles and packages. It does not prove any feature works.** Nothing below has been opened on a phone; the light theme has not been looked at; no independent person has reviewed the code.

- 16 KB page-size check: `libvosk.so` (both ABIs) and `libjnidispatch.so` (x86_64 emulator only) are 4 KB-aligned. Those are other people's prebuilt files. On a phone with 16 KB pages the Vosk wake word may not load (the app reports that instead of crashing). Phones with 4 KB pages, including Android 14, are not affected.

### Written and compiled in this round (all unverified on a device)
- **Voice:** optional ElevenLabs voice chosen in setup (needs your own key; the text to be spoken is sent to ElevenLabs). Bluetooth companion: when a chosen Bluetooth device connects, JARVIS offers hands-free and can read new notifications aloud. Light / dark / system theme switch.
- **Interrupting JARVIS (barge-in):** while JARVIS speaks the wake-word detector keeps listening. Saying "Jarvis" stops the speech and listens; "stop", "quiet", "repeat that" are handled on the phone without the AI. What was cut off is marked in the conversation so your next sentence can change the answer. **How well it hears you over its own voice depends on the phone's echo cancellation; this is untested.** On/off switch in Prefs.
- **WhatsApp:** "how many unread chats" opens WhatsApp and reads its chat list. **YouTube:** "play <song>" opens the search results and presses the first real video. Both depend on text the apps show on screen; **those markers were written from general knowledge and never checked against the real apps.**
- **New built-in commands (no AI needed):** set brightness, silent / vibrate / normal mode (silent needs Do Not Disturb access), open any of 30 Settings pages, cancel / dismiss an alarm, copy to clipboard, count installed apps, open Maps / directions, check which permissions are on, calculator, unit converter, random pick. Brightness and sound mode report the real before / after value from Android.
- **Contacts:** a name that is not found now offers the closest similar names instead of failing.
- **Phone operator** ("do it for me in any app"): off until you switch it on in Prefs > Access > Phone operator (also needs Accessibility and "press buttons and type"). See `docs/PHONE_OPERATOR.md` for exactly what it will and will not do.

## What "anything I say gets done" can and cannot mean
It cannot mean everything. Android blocks apps from some things by design, and JARVIS does not try to get around that: see `docs/PHONE_OPERATOR.md`. In short: banking, payment and password-manager apps, system permission screens and JARVIS's own screens are never read or pressed; money is never moved; the person's confirmation is always a tap in JARVIS itself, never something the AI or text on a screen can give.

## Features the setup list now shows as "planned", not available
Mobile Data Monitor, File Manager, Document Intelligence, Personal Routines and Adaptive Dashboard were marked "available" in an earlier catalogue edit although no code backs them. They are back to "planned". Local AI Lab stays planned: the default build has no on-device engine, so an installed Gemma model still cannot answer chat. Multilingual covers English and Hindi only (per-app language; the recogniser listens for it).

## Known limits to expect on the phone
- Android 14+ will not let a microphone service start from a Bluetooth broadcast or at boot. After a reboot or a Bluetooth connect JARVIS shows a notification; tap it (or open the app) to start listening. I could not verify what a tap does on a real phone.
- Hindi song names ("tum hi ho") are speech recognition: pick Hindi in Prefs > Language so the recogniser listens for Hindi, otherwise it may hear "tum hi tu".
- The phone operator sends text read from the screen to your AI provider. Do not use it on screens with private information.
- AI providers: free tiers rate-limit and models get retired; use Load models and Test in the Cloud tab. Several providers (OpenAI, Claude, Mistral, FreeLLM) work only after you add your own key.
- The on-device AI engine is opt-in and uncompiled (`docs/ai/LOCAL_MODELS.md`).

## Not built
- Tapping at screen coordinates, swipes and gestures; photo editing; setting ringtones; switching Wi-Fi / Bluetooth / airplane mode (Android does not allow it; the app opens the Settings page).
- Self-modifying code or silent self-update. The nightly 10 pm job only notifies and proposes; you install new versions.
- Streaming replies, Live Updates notifications, a floating orb, a custom "hyee" wake word, a signed release build. **No release APK exists; only a debug build from CI.**
- Mobile data monitor, file manager, document reading, routines, adaptive dashboard (see above).

## Not taken from your JARVIS-Project zip, and why
- Its `core` files did not compile against the current code. Its `LocalEngine` was a template placeholder, `WakeWordService` simulated detection, and `SpeakerVerification` gave a false sense of security. The ideas were kept where they could be built honestly.
