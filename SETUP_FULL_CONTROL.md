<<<<<<< HEAD
# Full-control consent + app catalog — now fully wired

Unlike earlier in this project's history, these are now actually connected
to `main.py`'s startup — no manual wiring needed.

## What happens on launch

1. `JarvisUI` window is created.
2. **On the main thread**, before anything else starts: a Yes/No popup asks
   "Allow Jarvis full control of this device this session?" This blocks
   briefly until you answer — by design, so the answer is settled before
   any tool can run.
3. The background thread starts, waits for your API key setup (if this is
   first run), then silently checks for apps installed/removed since the
   last launch and logs it if anything changed.
4. The voice session begins.

## Testing each piece independently
```bash
python permissions.py          # shows the consent popup standalone
python actions/app_catalog.py  # runs the new-app check standalone
```

## What "full control" changes

Governs whether the planner (`agent/planner.py`) is allowed to use
`generated_code` — AI-generated Python executed as a subprocess for tasks
that don't fit any specific tool. Denied by default; granted only for
that session if you say yes. Does **not** bypass the delete/close
confirmation system (`safety.py`) — that's a separate, permanent safety
floor regardless of this setting. Checked in two places (planner.py and
executor.py) as defense in depth.
=======
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
>>>>>>> 67c8ea33789aa1eeedffa001e492817756b17244
