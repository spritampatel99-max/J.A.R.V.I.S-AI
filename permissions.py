# permissions.py
#
# Full-device-control consent gate.
#
# On every startup, this can show a Yes/No popup: "Allow Jarvis full
# control of this device this session?"
#   YES -> unlocks broader automation for THIS RUN ONLY (e.g. the planner
#          is allowed to use generated_code / arbitrary system commands
#          when it decides a task needs them).
#   NO  -> Jarvis behaves exactly as it already does: only the predefined,
#          already-built tools (open_app, file_controller, web_search,
#          wifi_bluetooth_control, alarm, etc.) — nothing more.
#
# IMPORTANT: this is a BREADTH-of-capability gate, not a bypass for the
# existing destructive-action confirmations in safety.py. Deleting a file
# or closing an app still asks for confirmation regardless of full-control
# status — full control governs what KINDS of things Jarvis is allowed to
# attempt, not whether it still checks before doing something irreversible.
#
# The decision is asked fresh every launch (not remembered indefinitely)
# and is only ever held in memory for that session — it's also logged
# to memory/permissions_log.json purely as an audit trail of past answers,
# not as a way to skip asking next time.
#
# WIRING: request_full_control_consent() is called from main.py's main()
# function, on the MAIN thread, before the background JarvisLive thread
# starts (this matters — see the comment in main.py for why). Already
# wired in; nothing more to do here.

import json
import sys
import time
from pathlib import Path


def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent


LOG_PATH = _get_base_dir() / "memory" / "permissions_log.json"

_session_full_control: bool | None = None  # None = not asked yet this run


def request_full_control_consent(parent=None) -> bool:
    """
    Shows a Yes/No dialog. Returns True if granted, False otherwise.
    Falls back to False (safe default) if the dialog can't be shown for
    any reason (e.g. no display, PyQt6 missing) rather than silently
    granting broad access.
    """
    global _session_full_control

    try:
        from PyQt6.QtWidgets import QApplication, QMessageBox

        app = QApplication.instance()
        created_app = False
        if app is None:
            app = QApplication(sys.argv)
            created_app = True

        box = QMessageBox(parent)
        box.setWindowTitle("Jarvis — Device Control Permission")
        box.setText(
            "Jarvis is starting up.\n\n"
            "Allow Jarvis FULL CONTROL of this device for this session?\n\n"
            "YES — unlocks broader automation: running arbitrary system "
            "commands and AI-generated code when a task seems to need it.\n\n"
            "NO — Jarvis will only use its existing built-in features "
            "(opening apps, file management, web search, reminders/alarms, "
            "WiFi/Bluetooth, etc.) — nothing beyond what's already built."
        )
        box.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
        box.setDefaultButton(QMessageBox.StandardButton.No)
        choice = box.exec()
        granted = choice == QMessageBox.StandardButton.Yes

        if created_app:
            app.quit()

    except Exception as e:
        print(f"[Permissions] ⚠️ Could not show consent dialog ({e}) — defaulting to NO full control.")
        granted = False

    _session_full_control = granted
    _log_decision(granted)
    print(f"[Permissions] {'✅ Full control GRANTED' if granted else '🔒 Full control DENIED'} for this session.")
    return granted


def has_full_control() -> bool:
    """
    Check anywhere in the codebase whether full control was granted this
    session. Returns False (safe default) if consent was never asked —
    e.g. if this module is used before request_full_control_consent() has
    run, or main.py hasn't been wired to call it yet.
    """
    return bool(_session_full_control)


def set_full_control_for_testing(value: bool) -> None:
    """Test/debug helper only — do not call this from real request-handling code."""
    global _session_full_control
    _session_full_control = value


def _log_decision(granted: bool) -> None:
    try:
        LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        history = []
        if LOG_PATH.exists():
            try:
                history = json.loads(LOG_PATH.read_text(encoding="utf-8"))
            except Exception:
                history = []
        history.append({"granted": granted, "timestamp": time.time()})
        history = history[-50:]
        LOG_PATH.write_text(json.dumps(history, indent=2), encoding="utf-8")
    except Exception as e:
        print(f"[Permissions] ⚠️ Could not log consent decision: {e}")


if __name__ == "__main__":
    result = request_full_control_consent()
    print(f"Granted: {result}")
    print(f"has_full_control() now returns: {has_full_control()}")
