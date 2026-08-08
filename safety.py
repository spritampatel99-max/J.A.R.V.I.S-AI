# safety.py
#
# Confirmation gate for destructive actions (delete, close app, etc.).
#
# HOW IT WORKS:
#   1. A destructive action (e.g. file_controller's "delete") doesn't run
#      immediately. Instead it calls request_confirmation(), which stores
#      the actual operation (as a deferred function call) under a short
#      token, and returns a message asking the user to confirm.
#   2. Whatever's driving the conversation (Gemini Live in main.py, or the
#      planner/executor) relays that message to the user and, on the next
#      turn, calls the confirm_action tool with the token.
#   3. confirm_action -> safety.confirm(token) actually runs the deferred
#      operation and returns its real result.
#
# This works the same way regardless of which code path triggered the
# original request (direct voice tool-call vs. a planned multi-step task),
# because both paths call the same action functions — the gate lives in
# those functions, not in the orchestration layer.
#
# Tokens expire after CONFIRM_TTL seconds so a stale "yes" minutes later
# doesn't accidentally confirm an old, forgotten request.

import time
import uuid
import threading
from typing import Callable

CONFIRM_TTL = 120  # seconds a pending confirmation stays valid

_pending: dict[str, dict] = {}
_lock = threading.Lock()


def _cfg_confirm_enabled() -> bool:
    """Reads config/api_keys.json's 'confirm_destructive_actions' flag.
    Defaults to True (safer) if the key is missing or the file can't be read."""
    try:
        import json
        import sys
        from pathlib import Path

        def _base_dir() -> Path:
            if getattr(sys, "frozen", False):
                return Path(sys.executable).parent
            return Path(__file__).resolve().parent

        cfg_path = _base_dir() / "config" / "api_keys.json"
        cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        return bool(cfg.get("confirm_destructive_actions", True))
    except Exception:
        return True


def confirmation_required() -> bool:
    """Public check other modules can call before gating an action."""
    return _cfg_confirm_enabled()


def cleanup_expired() -> None:
    now = time.time()
    with _lock:
        expired = [t for t, e in _pending.items() if now - e["created"] > CONFIRM_TTL]
        for t in expired:
            del _pending[t]


def request_confirmation(description: str, execute_fn: Callable, *args, **kwargs) -> str:
    """
    Registers a deferred action and returns a message the assistant should
    speak/display asking the user to confirm. `execute_fn(*args, **kwargs)`
    is what actually runs if/when the user confirms.
    """
    cleanup_expired()
    token = uuid.uuid4().hex[:6]
    with _lock:
        _pending[token] = {
            "description": description,
            "execute_fn":  execute_fn,
            "args":        args,
            "kwargs":      kwargs,
            "created":     time.time(),
        }
    return (
        f"⚠️ Confirmation needed, sir: {description}. "
        f"Say \"confirm {token}\" to proceed, or \"cancel {token}\" to abort. "
        f"This expires in {CONFIRM_TTL} seconds."
    )


def confirm(token: str) -> str:
    token = (token or "").strip()
    with _lock:
        entry = _pending.pop(token, None)

    if not entry:
        return "That confirmation token is invalid or has already expired, sir."

    if time.time() - entry["created"] > CONFIRM_TTL:
        return "That confirmation request expired, sir. Please ask again."

    try:
        result = entry["execute_fn"](*entry["args"], **entry["kwargs"])
        return result or f"Done: {entry['description']}"
    except Exception as e:
        return f"Confirmed action failed, sir: {e}"


def cancel(token: str) -> str:
    token = (token or "").strip()
    with _lock:
        entry = _pending.pop(token, None)

    if not entry:
        return "That confirmation token is invalid or was already handled, sir."
    return f"Cancelled, sir: {entry['description']}"
