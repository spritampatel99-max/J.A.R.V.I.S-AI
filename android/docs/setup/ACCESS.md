# Access (permissions)

The **Access** tab, and the Permissions step of setup, ask Android for real access and show what Android actually says.

## "Allow all" is a walk-through, not a master switch

Android has no setting that grants every permission at once, and an app cannot switch on special access by itself. **Allow all**
therefore goes through the missing items one after another: Android's own dialog for each normal permission, and the matching Settings
page for each special one. Every step can be declined. Nothing is granted without your tap.

## What can be requested

| Access | Kind | Used by the app today? |
|---|---|---|
| Notifications | Runtime (Android 13+) | **Yes**: model download progress |
| Run in the background (battery optimisation) | Settings page | **Yes**: keeps a long download alive |
| Microphone | Runtime | **Yes** (written, uncompiled): push-to-talk and the hands-free "Jarvis" service |
| Contacts | Runtime | **Yes** (written, uncompiled): calls and messages look up a name |
| Phone calls, Answer phone calls | Runtime | **Yes** (written, uncompiled): call, answer, end. A typed or AI-planned call asks for a tap; a spoken one is read back and dialled after 3 seconds unless you say stop |
| Exact alarms and reminders | Settings page | **Yes**: reminders and automations (alarms and timers are handed to the Clock app) |
| Display over other apps | Settings page | **Yes**: lets the app start another app from the background, and shows the hands-free overlay |
| Accessibility service | Settings page | **Yes**: read the visible screen text, scroll, press Back / Home / Recents / lock / screenshot. Pressing a labelled button, typing, and pressing Send in WhatsApp are extra switches in Prefs > Phone control, off until you turn them on. Screen text is not logged or given to a model |
| Notification access | Settings page | **Yes**: read and clear notifications. Text stays on the phone and is not logged or given to a model |
| Usage access | Settings page | **Yes**: the Mobile Data Monitor reads how much mobile data each app used (byte counts only: never what was sent, which sites, or screen time). Not used for anything else. See `docs/ROUTINES_DATA_SHORTCUTS.md` |

Android may grey out the Accessibility and Notification-access switches for an app that was installed outside the Play Store. If so: Settings > Apps > JARVIS 3.0 > three dots > "Allow restricted settings", then try again.

Each row shows what it reaches, how to turn it off, and an honest **Used by the app now / Not used yet** tag. Allowing something that is
not used yet is harmless, but the screen does not pretend the feature exists.

## Details

- The state shown is read from Android each time the screen resumes, so changes made in Settings appear when you come back.
- After two denials Android stops showing a permission dialog. The app remembers that it has asked and, when Android will no longer ask,
  the button becomes **Open Settings** (the app's own page).
- The manifest declares only permissions with a row above, plus `INTERNET`, `ACCESS_NETWORK_STATE`, `SET_ALARM`, `RECEIVE_BOOT_COMPLETED` and the foreground-service ones (`dataSync` for model downloads, `microphone` for hands-free).
- Messages (SMS and WhatsApp) need **no** SMS permission: the app opens the messaging app and you press Send (WhatsApp: unless you switched on "Press Send for me", which needs the Accessibility service).
- Photos use Android's photo picker: no photo permission, and the app only gets the photos you select.
- Not requested and not promised: Wi-Fi, Bluetooth and airplane-mode control (Android does not allow apps to switch them silently),
  tapping or typing inside other apps, device admin, "all files access", reading your SMS.
