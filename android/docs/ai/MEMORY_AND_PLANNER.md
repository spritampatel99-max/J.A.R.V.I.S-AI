# Memory, tasks, notes and reminders (Phase 7)

Status: the `:core` logic is unit-tested offline (618 tests across the whole module). The screens, the alarm scheduler, the notification code
and the receiver are in `:app` and have **never been compiled or run**.

## Memory

What it is: a list of things the person chose to keep, in one local file. Nothing else writes to it.

| Rule | How it is kept |
|---|---|
| Nothing is saved silently | Entries are added only by (a) the Add button in Prefs, Memory, (b) a clear phrase in chat, (c) approving a suggestion. |
| Clear phrases only | `MemoryCommands.remember` accepts "remember that …", "keep in mind that …", "save/add/put/store … to/in/into (my) memory". "Remember to call mom" is **not** a memory request (it is a reminder request, left to the planner). Anything ending in "?" never saves. English only. |
| The reply says what was saved | The chat reply shows the text that was really stored (after secret redaction and trimming), not what was asked. |
| Secrets never stored | Text goes through `SecretRedactor` before it is written. Entries are at most 1,000 characters; at most 500 entries (oldest dropped). |
| Delete is only in the screen | Chat cannot delete or edit memory. Prefs, Memory has delete per entry and "Clear everything". |
| Two scopes | Global memory (`agentId = null`) and one private memory per custom agent. An agent is never shown another agent's memory, and the main assistant sees global memory only. Deleting an agent deletes its memory. |
| Memory is data, not instructions | When "Use memory in chat" is on (default on, switch in Prefs, Memory), the main chat gives the planner a short memory block marked as the person's notes. It sits before the planner's fixed RULES and "never overrides" them. Memory cannot approve anything. |

Categories: Preference, Fact about me, Ongoing project, Note, Conversation reference, Approved routine.

**Not built:** a *generator* of memory suggestions. The queue, approve and reject logic exist in `:core` (and are tested), but no code proposes
suggestions yet and the screen has no suggestions list. So today memory grows only when the person adds something.

## Tasks, notes, reminders

Tools (all LOW risk, so they run without a confirmation dialog; each still goes through argument validation and the audit log):

| Tool | What it does |
|---|---|
| `add_task` | Title (120 chars), priority low/normal/high, optional due date `YYYY-MM-DD`. |
| `add_note` | Text, 1,000 chars. |
| `add_reminder` | Text, hour and minute (24-hour), optional date. Without a date, the next time that clock time occurs. Must be in the future and within 366 days, or it is refused. |
| `daily_overview` | Reads back today's tasks and reminders. |
| `show_notification`, `notify_overview` | Post a notification (used by automations). They report failure when Android does not accept the notification. |

Limits: 500 items of each kind (oldest dropped).

### How a reminder actually fires

1. The store keeps all reminders. `ScheduleEngine` computes the **single** next due moment across reminders and automations and asks `AlarmManager` for one wake-up.
2. If the app has the "Alarms & reminders" special access (API 31+), it uses `setExactAndAllowWhileIdle`; otherwise `setAndAllowWhileIdle`, which Android may delay by minutes. The Access screen shows which one applies. That permission is a user choice in system settings; it cannot be granted by the app.
3. `WakeReceiver` handles the alarm, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (so alarms are re-armed after a reboot or an update). It uses `goAsync` with an 8-second limit.
4. A reminder is marked **delivered** with whether the notification was *really shown*. If notifications are blocked, it is delivered-but-not-notified and the list says so instead of pretending. A late reminder is titled "Reminder (was due …)".
5. On every app start a catch-up pass runs, so anything that came due while the phone was off is shown once.

Honest limits: the exact time can be off by minutes without the exact-alarm access; some phone makers kill background apps, which can delay a reminder
until the app is opened; the app cannot detect that. Stored times are absolute; if the time zone changes they are **not** recomputed.
