# Personal Routines, Mobile Data Monitor and home shortcuts (2026-11-26)

Three features that were "planned" in the catalogue and are now written. **Core logic is tested offline (see the test count in `STATUS.md`). The Android screens and the Android data readers are compiled by GitHub Actions at most; none of it has been run on a phone.** Read the "Not verified" lines before relying on any of it.

## Personal Routines

A routine is a named list of steps in your own words, for example "Morning: drink water; check the calendar; plan the day".

- Where: Prefs > Routines (screen), or by voice / typing.
- Say: "start my morning routine", "read my evening routine", "what is in my gym routine", "list my routines". Steps are read to you; nothing is run for you.
- Limits: 20 routines, 12 steps each, 120 characters per step, names 2 to 40 characters.
- A routine **does not do anything by itself**. It is a checklist that is shown or read out. It cannot press, call, send or open anything. To do something for you, say it as a normal request.
- **Optional daily reminder:** a time of day. The reminder is a normal automation (see `AUTOMATIONS.md`) that runs one allowed action, `notify_routine`, which shows the routine's steps as an Android notification. Automations remain limited to low-risk actions; the routine reminder was added to that allow-list as a fifth action and nothing else was.
- Privacy: routine steps are personal. Reading a routine back is a private result: it is spoken or shown on the phone, **not written to the action log and not sent to an AI provider**. Saving goes through the same tool pipeline as every other action (arguments checked, action recorded without the step text).
- If you have two routines with similar names, JARVIS asks which one instead of guessing.
- Deleting a routine also removes its reminder.
- Damaged file: backed up with a `.damaged` ending (for example `routines.json.damaged`); the screen says so. A failed write is kept in memory, reported, and retried at every wake-up.

**Not verified:** the Routines screen and the notification reminder have never been shown on a phone. Whether the reminder arrives on time depends on the exact-alarm access and the phone maker's battery rules (the same limits as automations).

## Mobile Data Monitor

"How much mobile data have I used today / this week / this month?" with a per-app breakdown (largest apps first, the rest added up as "Other apps").

- Where: Prefs > Data monitor (screen), or by voice / typing ("how much mobile data did I use this week").
- It counts **mobile data only** (not Wi-Fi), from Android's own counters (`NetworkStatsManager`). Today starts at midnight, the week on Monday, the month on the 1st, all in the phone's time zone.
- It needs **Usage access** (Settings > Apps > Special app access > Usage access). Without it, JARVIS says so and offers the Settings page; it does not guess a number. The screen has "Open Usage access" and "Check again".
- Sizes are written the way Android's own settings write them (1 KB = 1000 bytes), so they should match what the Settings app shows to within rounding.
- The answer includes app names, which are your information: it is a private result (not logged, not sent to an AI provider).
- It reads. It does not limit, block or switch off data for any app, and cannot.

**Not verified:** the numbers have never been compared with the phone's Settings. Android keeps per-app history for a limited time and may count work done by one app on behalf of another differently. Treat the figures as Android's, not as an invoice. The code asks Android for all mobile data on the phone at once (no SIM is picked, so a two-SIM phone is added together); that is how the Android documentation describes a missing subscriber id, but I could not try it on a real phone, and if Android refuses, JARVIS says it could not read the usage rather than showing zero. Apps this app is not allowed to see by name appear as "Other app", never guessed. The edges of "today" can be off by one short Android time slice.

## Home shortcuts ("Adaptive Dashboard")

The home screen can offer your most frequent requests as one-tap shortcuts.

- A request becomes a shortcut only after it has **worked twice**. A request that failed, was refused, was turned down, or whose answer was private (messages, screen, notifications) is never counted.
- Anything that looks sensitive is never kept: numbers of five or more digits, e-mail addresses, links, and words such as password, PIN, OTP, bank, card number, token, API key. Looking sensitive is enough; a missed shortcut costs nothing.
- Kept on this phone only, up to 40, six shown. A shortcut not used for 60 days stops being offered.
- Tapping one sends exactly the same words as typing them, so every permission check and confirmation still applies. A shortcut can never skip a confirmation.
- Switch it off ("Stop learning") or clear it ("Clear", tapped twice) on the home card.

**Not verified:** the card layout on different screen sizes and in the light theme has not been seen.

## What these features do not do

- No routine, shortcut or data reading is sent anywhere by JARVIS itself. (If you ask the AI a question, your typed words go to your provider as always.)
- None of this is an on-device AI feature; planning and chat still need a cloud key.
