# Release checklist (Phase 10)

**No APK has been built from this code. No release build exists.** This file is the list of things that must happen before anyone can honestly call a build a release.
Nothing here has been done yet unless a line says so.

## 1. Make the debug build compile

The `:app` module has not compiled in its current form. The owner's PC compiled an earlier version once and then failed on a packaging mistake that has since been fixed.
The first real compile will probably report errors in the newest screens (Compose APIs, Android API levels, imports).

```
.\gradlew :core:test            # Windows PowerShell; use ./gradlew on macOS/Linux
.\gradlew :app:assembleDebug
```

Paste the error list; each round shrinks it. Do not move to step 2 until `:app:assembleDebug` ends with BUILD SUCCESSFUL.

- [ ] `:core:test` passes under Gradle (it did on 2026-11-08 for the older code; re-run for the current code)
- [ ] `:app:assembleDebug` passes
- [ ] `:app:lintDebug` reviewed (not run yet; warnings may include missing `exported` flags, permission use, hard-coded text)

## 2. Run it on a phone and tick each line only after seeing it work

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or Run in Android Studio.

First start
- [ ] App opens; setup wizard appears; nothing is downloaded or requested before a tap
- [ ] Fonts and glass cards render; animation is smooth on this phone (note the phone model and any dropped frames)

AI
- [ ] Cloud tab: save a key, **Load models**, pick one, **Test** passes
- [ ] Chat reply from the cloud model; with no key the app says so instead of failing silently
- [ ] Built-in commands work with no key (flashlight on/off, 6 a.m. alarm shows in the Clock app, battery)

Access and safety
- [ ] Each permission opens its real Android dialog or settings page; denied and "don't ask again" states show correctly
- [ ] **Typed** "call Asha" shows the exact name and number, and **nothing** dials until the Call button is tapped
- [ ] **Spoken** "call Asha" (push to talk) reads back "Calling Asha, mobile. Say stop to cancel.", dials after 3 seconds, and "stop" / the Cancel button prevents the call
- [ ] A call that an AI plan produces (for example "call Asha and then …" through the cloud model) asks for a tap, never the countdown
- [ ] Declining or leaving the confirmation expire does not call
- [ ] Ending/answering a call needs its permission and reports honestly

Memory, planner, reminders
- [ ] "Remember that my sister is called Asha" appears in Prefs, Memory with exactly that text; "remember to call mom" does **not** save a memory
- [ ] Memory switch off: the next chat gets no memory block
- [ ] Reminder set for 2 minutes ahead notifies on time with the screen off (with and without the "Alarms & reminders" access; note the difference)
- [ ] Reboot the phone with a reminder pending; it still fires or shows as late
- [ ] Notifications blocked: the reminder is listed as not shown, not as "notified"

Automations
- [ ] Create "every 15 minutes: show notification"; it runs; Pause stops it; Delete removes it
- [ ] An attempt to include a call, message or link is refused with a reason
- [ ] Leave the phone idle for an hour and note how late the runs are

Agents
- [ ] Build an agent in the wizard; preview chat works; no tools run in preview; nothing is saved
- [ ] "Save as draft" cannot be opened for real until Approve
- [ ] Approve, chat, Disable, Edit, Duplicate (no memory copied), Export, Delete
- [ ] An agent with "No memory" never mentions a saved note; two agents never see each other's notes

Offline and security screens
- [ ] Airplane mode: Offline screen says what works and what does not, and AI chat says it needs internet
- [ ] Offline self-test passes
- [ ] Security review lists real permissions and never says "secure"

### Hands-free, messages, timers, media, phone control, offline library, AI mode

- [ ] Hands-free: with a Picovoice AccessKey saved, saying "Jarvis" plays the tone and listens once; it never reacts to other words; a wrong key shows a clear message and no key text
- [ ] Hands-free after a reboot does not start by itself; opening the app and switching it on does
- [ ] "Text Asha hello" opens the messaging app with the text filled in and **does not send**; the reply says it was not sent
- [ ] "WhatsApp +91… hello" opens that chat with the text ready; a name with no `+` number opens WhatsApp's picker with the text ready
- [ ] A message body such as "call 100" is delivered as text and starts nothing
- [ ] "Set a timer for 1 minute" shows a timer in the Clock app; "pause" / "next track" / "volume up" act on the player (the replies say "asked", and volume reports before and after)
- [ ] Phone control: with the accessibility service off, "read the screen" says it is off; with it on, it reads the visible text; Back / Home / Recents work; **no** tapping or typing happens in any app
- [ ] Notification reader: off says it is off; on lists notifications; neither screen text nor notification text appears in the Logs tab
- [ ] "Open Maps" by voice with the screen off or the app in the background: works with "Display over other apps", and says so honestly when Android refuses (Android 14/15)
- [ ] Offline library: download or import a `.zim`; airplane mode; "search offline for black hole" answers with the source named
- [ ] AI mode: Local only with no engine says so and does not use the cloud; Cloud assisted answers from the cloud
- [ ] "Email sam@example.com saying hello" shows the address and words on a card; after the tap the email app opens with the draft ready and **nothing is sent**; "email Rahul saying hello" asks for a full address
- [ ] "Open the camera" / "take a photo" opens the camera app (no photo is taken); "record a video" opens it in video mode
- [ ] FreeLLM / own endpoint: an `http://` address or one with a password is refused with a clear message; an https address you trust lists its models and answers a chat; with no address the provider shows "Not configured"
- [ ] The app is named "JARVIS 3.0" on the home screen, in Settings > Apps, in the hands-free notification and in the accessibility service entry

## 3. Local AI (blocked)

The default build has no on-device engine. An opt-in LiteRT-LM engine is written (`enableLiteRt`) but has never been compiled. Do not tick "works offline" for AI chat until a model actually answers a prompt in airplane mode on a real phone.
Open items: the Kotlin version the library needs, the exact API names, and cancelling a running generation (see `docs/ai/LOCAL_MODELS.md`).

## 4. Sign and build a release

1. Make a keystore **outside** the repository (`keytool -genkeypair -v -keystore jarvis-release.jks -alias jarvis -keyalg RSA -keysize 4096 -validity 10000`). Back it up; losing it means you cannot update the app. Never commit it (`*.jks` and `*.keystore` are ignored).
2. Put the store path, alias and both passwords in `~/.gradle/gradle.properties` or environment variables; not in the project.
3. Add a `signingConfigs` block to `app/build.gradle.kts` that reads those, and point the `release` build type at it. (Not written yet.)
4. R8/minify is **off**. Turning it on needs keep rules for OkHttp, kotlinx.serialization and Compose and a full device test afterwards. Decide deliberately.
5. `.\gradlew :app:assembleRelease`, then `apksigner verify --verbose app-release.apk`.
6. Install the signed release build over a clean install and repeat the section 2 list. A release build behaves differently from a debug build.
7. Confirm the APK contains no API key: unzip it and search for your key prefixes. Check `git log -p` and logs too.

## 5. Release notes (fill in only with what was verified)

```
JARVIS 3.0 for Android  <version>
Verified on: <phone model, Android version>, <date>
Works: <list only items ticked in section 2>
Not built: on-device AI chat (unless you verified the opt-in engine), custom wake words other than "Jarvis", tapping or typing inside other apps, streaming replies, light theme, document search, agent knowledge sources
Known limits: reminders can be minutes late without the Alarms & reminders access; calls can only be started, not confirmed as connected;
              messages are opened in the messaging app and the person presses Send; timers and media keys are requests Android does not confirm;
              hands-free listening uses battery and must be restarted by opening the app after a reboot;
              Wi-Fi/Bluetooth/airplane mode can only be opened in Settings, not toggled
```

## 6. Do not claim

- zero errors, until you have a clean build log and lint report;
- that any test "passed on device", until it did;
- an APK, until a build produced one.
