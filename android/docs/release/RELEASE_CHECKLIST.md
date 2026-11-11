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
- [ ] "Call Asha" shows the exact name and number, and **nothing** dials until the Call button is tapped; voice cannot approve it
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

## 3. Local AI (blocked)

The on-device engine does not exist. Do not tick "works offline" for AI chat until a model actually answers a prompt in airplane mode on a real phone.
Blocked on the LiteRT-LM version, the Kotlin version it requires, and its cancel API (see `PROJECT_AUDIT.md` §6).

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
J.A.R.V.I.S.-AI for Android  <version>
Verified on: <phone model, Android version>, <date>
Works: <list only items ticked in section 2>
Not built: on-device AI chat, wake word, light theme, web research, document search, agent knowledge sources
Known limits: reminders can be minutes late without the Alarms & reminders access; calls can only be started, not confirmed as connected;
              Wi-Fi/Bluetooth/airplane mode can only be opened in Settings, not toggled
```

## 6. Do not claim

- zero errors, until you have a clean build log and lint report;
- that any test "passed on device", until it did;
- an APK, until a build produced one.
