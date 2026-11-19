# Status of this zip (2026-11-19)

Read this before building. It says what is finished, what was only written, and what was not done.

## Checked here
- `:core` (the logic: tools, safety rules, planner, voice countdown, screen guard, offline library reader) compiles and passes **756 tests**, run on a PC-style JVM in the workspace.
- A few safety rules were also checked by deliberately breaking them (mutation checks) in earlier rounds.

## Written but NOT compiled or run (`:app`, the Android part)
The workspace cannot reach Google's Android build servers, so nothing in `app/` has been built. Expect to fix some compile errors on the first build, as happened before. The new, never-compiled parts are:
- Hands-free "Jarvis" wake word (Picovoice Porcupine, needs your own free AccessKey in Prefs > Hands-free)
- Opening apps by voice (needs "Display over other apps" to start apps from the background)
- Spoken calls: read back "Calling X, mobile" and dial after 3 seconds unless you say "stop"
- Phone control: accessibility service (read screen, scroll, back/home/recents/lock/screenshot) and notification reader
- Offline library: download or import a Kiwix `.zim` Wikipedia file (Simple English about 1 GB, Top articles about 2.1 GB). Nothing downloads until you tap.
- New libraries: `porcupine-android 4.0.2`, `zstd-jni 1.5.7-12 (@aar)`, `org.tukaani:xz 1.11`

## Not built
- On-device AI model engine (LiteRT-LM). It needs a Kotlin upgrade I could not test. The offline library answers lookups; it does not chat.
- Tapping or typing inside other apps (deliberately not requested from Android).
- Streaming replies / speaking the first sentence early.
- The 3-4 full audit passes you asked for were NOT done. Docs (README, VOICE_AND_CALLS.md, CHANGELOG) still contain older statements such as "no always-listening mode" and "calls always need a tap". Trust this file over them.
- Nothing from this round is committed to git.

## First run on the phone
1. Build `:app` in Android Studio. Send the build log back if it fails.
2. For phone control: Settings > Accessibility > J.A.R.V.I.S. If the switch is greyed out: Settings > Apps > J.A.R.V.I.S. > three dots > "Allow restricted settings", then try again. Same for Notification access.
3. Hands-free: Prefs > Hands-free, paste the Picovoice AccessKey, switch on while the app is open. After a reboot, open the app once. Always-listening uses battery.
4. Offline library: Prefs > Offline library > Download (use Wi-Fi). Then say "search offline for black hole".
