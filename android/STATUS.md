# Status of this zip (2026-11-21)

Read this before building. It says what is finished, what was only written, and what was not done. If any other document disagrees with this one, check the code; this file was written last.

## Checked here
- `:core` (the logic: tools, safety rules, planner, router, voice countdown, screen guard, offline library reader, message / timer / media tools) compiles and passes **798 tests**, run on a PC-style JVM in the workspace (`kotlinc` + JUnit, not Gradle).
- Safety rules were also checked by deliberately breaking them (mutation checks). This round: 17 deliberate breaks of the new message, timer, media and routing code; 16 were caught by a failing test. The one that survived changes nothing observable (an equivalent mutant). Earlier rounds left two surviving mutants in the automation runner's backup safety layers; they are described in `docs/testing/BUILD_AND_TEST_REPORT.md`.
- Adversarial inputs for the new tools are tested: message bodies that look like commands, quotes, Hindi text, line breaks, over-long text, carrier codes in the recipient field.

## Written but NOT compiled or run (`:app`, the Android part)
The workspace cannot reach Google's Android build servers, so nothing in `app/` has been built, in this round or the previous two. Expect to fix some compile errors on the first build, as happened before. The never-compiled parts are:
- Hands-free "Jarvis" wake word (Picovoice Porcupine, needs your own free AccessKey in Prefs > Hands-free). Only the word "Jarvis" is detected.
- Opening apps by voice (needs "Display over other apps" to start apps from the background).
- Spoken calls: read back "Calling X, mobile" and dial after 3 seconds unless you say "stop". Typed or AI-planned calls need a tap.
- Texting and WhatsApp (open the app with the text ready; **you** press Send), timers (Clock app), media keys and volume.
- Phone control: accessibility service (read screen, scroll, back/home/recents/lock/screenshot) and notification reader.
- Offline library: download or import a Kiwix `.zim` Wikipedia file (Simple English about 1 GB, Top articles about 2.1 GB). Nothing downloads until you tap.
- The AI-mode router wired into the app (Prefs > AI mode: Local only, Local first, Cloud assisted, Manual) and the Mistral provider.
- The LiteRT-LM engine (`app/src/litert/`), which is **off by default**. See below.
- Libraries added: `porcupine-android 4.0.2`, `zstd-jni 1.5.7-12 (@aar)`, `org.tukaani:xz 1.11`. `litertlm 0.18.0` is declared but only used when you opt in.

## The on-device AI engine: opt-in, unverified
The default build contains **no** engine, so an installed Gemma model still cannot answer chat, and Prefs says so. I wrote a LiteRT-LM engine from Google's published Android guide. I could not compile it, and the library's artifact is built with Kotlin 2.4 metadata while this project pins Kotlin 2.1.20. To try it: set `enableLiteRt=true`, raise Kotlin, build, and expect small API-name fixes in `LiteRtLmEngine.kt`. Steps and risks: `docs/ai/LOCAL_MODELS.md`. Do not count "works offline" for AI chat until a model answers in airplane mode on your phone.

## Not built
- Tapping or typing inside other apps, and pressing Send for you (deliberately not requested from Android; the accessibility service can read and press system buttons only).
- Streaming replies / speaking the first sentence early.
- A light theme, a floating orb over other apps.
- A custom "hyee" wake word (needs a keyword file trained in the Picovoice console).
- Wi-Fi, Bluetooth or airplane-mode switching (Android does not allow it; the app can only open the Settings page).
- A signed release build. No APK exists.

## Not taken from your JARVIS-Project zip, and why
- Its `core` files did not compile against the current code, so they were not copied.
- `LocalEngine` there was a template-based placeholder (not a model), `WakeWordService` simulated detection instead of listening for a word, and `SpeakerVerification` gave a false sense of security. The wake word is replaced by the real Porcupine service; the other two were left out.
- The accessibility auto-sender (taps Send in WhatsApp for you) was left out: it breaks the rule that sending is your own tap.
- `WhatsAppWidget` and `MiniOrbService` did not compile; the half-finished light-theme refactor, `DynamicToolRegistry`/`AppCatalog` and the FreeLLM provider were left out. His manifest had invalid permissions, exported widget receivers and no notification-listener service.
- The ideas were kept where they could be built honestly: messaging, timers, media keys, Mistral, the AI-mode router and the engine opt-in.

## About the audit you asked for (3-4 passes)
Four review passes were done over the new code: tests and mutation checks, adversarial inputs, a read through the diff for safety rules (confirmation, untrusted content, secrets), and a check that the docs match the code. These were done by me, not by an independent reviewer: the separate reviewer agent failed four times with rate-limit errors. That means the code has had no independent review and no compiler pass on the Android side. Treat "audited" as "self-reviewed".

## Git
The previous round (hands-free, calls, phone control, offline library) is commit `89339a4`. This round (messages, timers, media, Mistral, router wiring, LiteRT opt-in, these docs) is in the commit made after this file.

## First run on the phone
1. Build `:app` in Android Studio. Send the build log back if it fails.
2. For phone control: Settings > Accessibility > J.A.R.V.I.S. If the switch is greyed out: Settings > Apps > J.A.R.V.I.S. > three dots > "Allow restricted settings", then try again. Same for Notification access.
3. Hands-free: Prefs > Hands-free, paste the Picovoice AccessKey, switch on while the app is open. After a reboot, open the app once. Always-listening uses battery.
4. Offline library: Prefs > Offline library > Download (use Wi-Fi). Then say "search offline for black hole".
5. Messages: say "text Mom hello". The messaging app opens with the text ready; press Send yourself. WhatsApp opens a chat directly only for a number starting with `+`.
