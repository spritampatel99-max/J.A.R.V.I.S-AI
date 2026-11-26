# Automations (Phase 7)

Status: rules and runner are in `:core` and unit-tested offline. The screen and the scheduler hookup are in `:app`; they compile in GitHub Actions and have **never been run on a phone**.

An automation is "at this time, or this often, do these things". It is deliberately small.

## What an automation can do

Only these five tools, and only while each is LOW risk and valid in the live tool registry:

`add_task`, `add_note`, `show_notification`, `notify_overview`, `notify_routine` (shows a saved routine's steps as a notification; added 2026-11-26 for routine reminders, see `ROUTINES_DATA_SHORTCUTS.md`)

Nothing else can be put in an automation: no calls, messages, apps, links, files, flashlight, alarms, no shell, Python or Kotlin, no free-form
"AI decides". A request such as "call Asha every morning" is refused with the reason, not quietly turned into something else.

## Triggers

| Trigger | Rule |
|---|---|
| Daily | At a clock time. |
| Weekly | At a clock time on chosen days. |
| Every N minutes | 15 minutes to 24 hours. |

Limits: 30 automations, 5 actions each, 20 run-history entries each.

## Safety layers

1. **At save time** (`AutomationRules`): every action must be on the allow-list, the tool must exist and be LOW risk in the live registry, and its arguments must validate.
2. **Before every run** (`AutomationRunner`): the stored actions are validated again against the *full* registry, so a hand-edited or old stored file cannot smuggle an action in.
3. **During the run:** the actions go through an `Executor` built on a *filtered* registry (allow-list only) with a confirmation requester that **denies everything** and no re-planner.

Layers 2 and 3 are backup. Mutation testing could not distinguish them from layer 1 because layer 1 already stops everything those layers would stop. They survived deliberately and are **not independently tested**. This is stated in the test report.

## When they run

Via the same single `AlarmManager` wake as reminders (see `MEMORY_AND_PLANNER.md`). Android decides the real moment (minutes late is normal without the exact-alarm access, and longer under battery restrictions).
A run that was missed (phone off) executes **once** on the next opportunity and then reschedules strictly after now; it does not replay every missed occurrence.
Pausing an automation clears its next run. "Run now" runs it immediately through the same three layers.

## Not built

- Editing an existing automation (delete and recreate it).
- Triggers for location, Wi-Fi, charging, an app opening or a received message. Android only allows some of these to background apps, and none were built.


## Reliability rules (2026-11-24)
- Each wake-up: reminders, then the next wake-up is requested, then automations, then the nightly review, then the next wake-up is requested again. A failing stage never stops the next one.
- A run is claimed and saved before it starts. After a restart, a run found unfinished is shown as **interrupted** and is not repeated, because it may already have added the task or shown the notification.
- "Every N minutes" is on a fixed grid by default (09:00, 09:30, 10:00 ...). Missed slots are skipped; one run happens when the phone is back.
- If the phone cannot write its storage, the screen says the data is in memory only and each wake-up tries again. A damaged file is kept as `<name>.damaged`.
- Not covered: WorkManager, and automations longer than a few seconds.

## Clock and time-zone changes (2026-11-26)
- When Android announces that the clock was set or the time zone changed (`TIME_SET`, `TIMEZONE_CHANGED`), the next run of each **enabled, not running, not yet due** automation is worked out again from now, in the zone the phone is in now. "Every day at 8:00" stays 8:00 local after you fly to another zone.
- "Every N minutes" keeps its rhythm but is never left more than one interval away, so a clock set backwards does not leave it asleep for hours.
- An automation that is already due is left alone and runs once (a clock set forwards does not make a due run vanish). A paused automation stays paused and has no next run.
- Compiled in CI and tested in `:core` (the rule and the wake-up path); the broadcasts themselves have never been received on a phone. Some phone makers do not deliver these broadcasts to a stopped app; the next ordinary wake-up still runs anything that is due.
