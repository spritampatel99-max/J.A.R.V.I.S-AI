# Setting up full-control consent + app catalog auto-check

Both features are fully built and independently testable, but — like every
feature in this conversation that needs to run automatically at startup —
actually triggering them when Jarvis launches requires one or two lines in
`main.py`, which I've never seen the content of. Here's exactly what to add
and where (near the top of your startup/entry-point function, before the
voice/task loop begins):

```python
import permissions
from actions.app_catalog import refresh_and_report_changes

# Ask for full-control consent (shows a Yes/No popup every launch)
permissions.request_full_control_consent()

# Check for newly installed/removed apps since last run
app_changes = refresh_and_report_changes()
if app_changes:
    print(f"[Startup] {app_changes}")
    # Optionally also speak it if you have a speak()/TTS function available:
    # speak(app_changes)
```

## Testing each piece independently (no main.py needed)

**Consent dialog:**
```bash
python permissions.py
```
Shows the popup, prints whether you granted it.

**App catalog / new-install detection:**
```bash
python actions/app_catalog.py
```
First run: builds the initial catalog silently (prints "Initial catalog
built: N apps"). Install or uninstall something, run it again: it'll tell
you what changed.

## What "full control" actually changes

This is NOT a bypass for the delete/close confirmation system
(`safety.py`) — that stays active regardless, on purpose. Full control
specifically governs whether the **planner** is allowed to use
`generated_code` — AI-generated Python that gets executed as a subprocess
for tasks that don't fit any of the existing specific tools. Without
consent, this is fully disabled (exactly as before) and the planner falls
back to `web_search` instead. With consent granted for the session, the
planner can use it when it judges a task genuinely needs it, but is still
instructed to prefer a specific tool whenever one fits.

The gate is checked in **two places** (planner.py AND executor.py) as
defense in depth — even if a plan somehow slips through with a
`generated_code` step, the executor refuses to actually run it without
consent.

## A note on "ask every time"

You specifically wanted this asked on every launch, not just once and
remembered — that's exactly how it's built (`_session_full_control` is
in-memory only, reset every process start). Past answers are logged to
`memory/permissions_log.json` purely as an audit trail — that log is never
read to skip asking again.
