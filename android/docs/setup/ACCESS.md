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
| Microphone | Runtime | No: voice input is not built |
| Contacts | Runtime | No: calling is not built |
| Phone calls | Runtime | No: calling is not built; every call would still ask first |
| Exact alarms and reminders | Settings page | No: reminders are not built (alarms are handed to the Clock app) |
| Usage access | Settings page | No: the data monitor is not built |
| Read notifications | Not available | Android lists an app on that switch page only once it has a notification-listener service |

Each row shows what it reaches, how to turn it off, and an honest **Used by the app now / Not used yet** tag. Allowing something that is
not used yet is harmless, but the screen does not pretend the feature exists.

## Details

- The state shown is read from Android each time the screen resumes, so changes made in Settings appear when you come back.
- After two denials Android stops showing a permission dialog. The app remembers that it has asked and, when Android will no longer ask,
  the button becomes **Open Settings** (the app's own page).
- The manifest declares only permissions with a row above, plus `INTERNET`, `ACCESS_NETWORK_STATE`, `SET_ALARM` and the foreground-service ones.
- Not requested and not promised: Wi-Fi, Bluetooth and airplane-mode control (Android does not allow apps to switch them silently),
  accessibility service, device admin, "all files access".
