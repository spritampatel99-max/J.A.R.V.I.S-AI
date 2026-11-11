# Offline report and security review (Phases 8 and 9)

Status: both reports are computed in `:core` and unit-tested offline. The two screens (Prefs → Offline, Prefs → Security) and the data they read from the phone are in `:app` and **have never been compiled or run**.

## Offline readiness (Prefs → Offline)

`OfflineReadiness.assess` takes a snapshot and returns one line per capability with a state. It does not guess:

| Capability | Offline state |
|---|---|
| Built-in commands | Works (flashlight, alarms, battery and storage, calls; opening YouTube or a page still needs internet once it opens). |
| Planner, notes and reminders; Memory | Works; "Checked just now" after the self-test passes, "unavailable" if it fails. |
| Reminders and automations | Works if Android notifications are on, otherwise Limited. |
| AI chat without internet | **Not built** while the on-device engine does not exist, even if a model file is installed (`localEngineBuilt` is a hard-coded `false` in `AppContainer`, and the report says why). |
| AI chat with a cloud provider | Unavailable with no key or no connection; Limited when Android does not say if you are online. |
| Voice input | Works only if Android says the recogniser can run without internet; Limited otherwise; Unavailable if there is no recogniser. |
| Spoken replies | Works if a text-to-speech voice is installed. |
| Web research, search your own documents | **Not built.** |

**Self-test:** "Run offline self-test" really writes a scratch file for the planner store and the memory store in the app cache folder, reads each back, compares, and
deletes it. It reports pass or fail per check with the time taken. It does **not** test the network, speech or any AI.

## Security review (Prefs → Security)

`SecurityReview.review` returns findings and two lists that are **always shown**:

- **Checked:** permissions granted to this app and whether anything uses them; cloud keys saved (counted, never shown); saved chat history, memory notes, agents and automations; confirmation history for sensitive actions; whether saving to storage works.
- **Not checked:** other apps on the phone; Android's own security settings and updates; the network you are on; what a cloud provider does with messages once it receives them.

Findings it can raise: permissions nothing uses, cloud keys saved, chat history size, memory notes that look like keys, agents that share memory with a cloud provider, agents holding a tool they should not have, automations that no longer pass the rules, approvals that did not match the action, failed saves, and notifications being off while reminders exist.

It never says "secure". API keys are only **counted**, never read out or shown. The "needed permissions" set is the permissions of tools that exist plus permissions of
Access entries that the app actually uses now, so unused permissions (like call-phone before a call is made) are not flagged as missing.

## What is *not* covered by any of this

- No penetration test, no static analysis tool, no dependency vulnerability scan, no Android Lint run and no instrumented test has been run (none are possible in the sandbox).
- The earlier-listed protections (confirmation bound to the prepared action, https only, redaction, untrusted-content wrapper) are described in `SECURITY.md` and tested at the unit level, not on a device.
