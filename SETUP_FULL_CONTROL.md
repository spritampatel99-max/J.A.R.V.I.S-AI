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
