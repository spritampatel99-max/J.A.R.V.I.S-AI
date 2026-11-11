# Automations (Phase 7)

Status: rules and runner are in `:core` and unit-tested offline. The screen and the scheduler hookup are in `:app` and **have never been compiled or run**.

An automation is "at this time, or this often, do these things". It is deliberately small.

## What an automation can do

Only these four tools, and only while each is LOW risk and valid in the live tool registry:

`add_task`, `add_note`, `show_notification`, `notify_overview`

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
- Next-run recomputation when the time zone changes.
