# actions/window_control.py
#
# Window focus/switching + generic keyboard injection for whatever app is
# currently focused. Migrated and adapted from an older assistant's
# app_control_agent.py (Tier B: works on ANY open window, not just apps
# with dedicated tool support).
#
# This is genuinely new relative to open_app.py, which only opens/closes/
# lists apps by process — it has no concept of window focus/switching or
# of typing/pressing keys into whatever's currently active.

import subprocess
import time

try:
    import pyautogui
    pyautogui.FAILSAFE = True
    _PYAUTO = True
except ImportError:
    _PYAUTO = False

try:
    import pygetwindow as gw
    _PYGW = True
except ImportError:
    _PYGW = False


_KEY_MAP = {
    "enter": "enter", "escape": "esc", "tab": "tab", "backspace": "backspace",
    "save": ["ctrl", "s"], "copy": ["ctrl", "c"], "paste": ["ctrl", "v"],
    "cut": ["ctrl", "x"], "undo": ["ctrl", "z"], "redo": ["ctrl", "y"],
    "select all": ["ctrl", "a"], "new tab": ["ctrl", "t"], "close tab": ["ctrl", "w"],
    "find": ["ctrl", "f"], "new": ["ctrl", "n"], "print": ["ctrl", "p"],
    "refresh": "f5", "fullscreen": "f11",
}


def focus_window(app_name: str) -> bool:
    if not _PYGW:
        return False
    try:
        wins = gw.getWindowsWithTitle(app_name)
        if not wins:
            all_titles = gw.getAllTitles()
            match = next((t for t in all_titles if app_name.lower() in t.lower()), None)
            if match:
                wins = gw.getWindowsWithTitle(match)
        if wins:
            w = wins[0]
            if w.isMinimized:
                w.restore()
            w.activate()
            time.sleep(0.3)
            return True
    except Exception:
        pass
    return False


def minimize_all() -> str:
    try:
        subprocess.run(
            ["powershell", "-command", "(New-Object -ComObject shell.application).minimizeall()"],
            capture_output=True, timeout=5,
        )
        return "All windows minimized, sir."
    except Exception as e:
        return f"Could not minimize windows, sir: {e}"


def close_window(app_name: str) -> str:
    """
    Graceful window close (asks the app to close its window, like clicking
    the X) — distinct from open_app.py's close_app, which force-terminates
    the process. This one is gentler and lets apps prompt to save unsaved
    work; open_app's close is the harder guarantee-it's-gone option.
    """
    if not _PYGW:
        return "Window management isn't available, sir (pygetwindow not installed)."
    try:
        wins = gw.getWindowsWithTitle(app_name)
        if wins:
            wins[0].close()
            return f"Closed the {app_name} window, sir."
        return f"No open window found for {app_name}, sir."
    except Exception as e:
        return f"Could not close that window, sir: {e}"


def type_text(text: str) -> str:
    if not _PYAUTO:
        return "Text input isn't available, sir (pyautogui not installed)."
    try:
        time.sleep(0.3)
        pyautogui.write(text, interval=0.02)
        return f"Typed: {text[:60]}, sir."
    except Exception as e:
        return f"Could not type that, sir: {e}"


def press_key(key: str) -> str:
    if not _PYAUTO:
        return "Key presses aren't available, sir (pyautogui not installed)."
    k = _KEY_MAP.get(key.lower().strip(), key.lower().strip())
    try:
        if isinstance(k, list):
            pyautogui.hotkey(*k)
        else:
            pyautogui.press(k)
        return f"Pressed {key}, sir."
    except Exception as e:
        return f"Could not press that key, sir: {e}"


def window_control(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        action: "switch_to" | "minimize_all" | "close_window" | "type" | "press" (required)
        app_name: string (required for switch_to/close_window)
        text: string (required for type)
        key: string (required for press) — e.g. "save", "copy", "enter"
    """
    params = parameters or {}
    action = params.get("action", "").lower().strip()

    if action == "switch_to":
        app_name = params.get("app_name", "").strip()
        if not app_name:
            return "Which app should I switch to, sir?"
        result = f"Switched to {app_name}, sir." if focus_window(app_name) else f"Could not find an open window for {app_name}, sir."

    elif action == "minimize_all":
        result = minimize_all()

    elif action == "close_window":
        app_name = params.get("app_name", "").strip()
        if not app_name:
            return "Which window should I close, sir?"
        result = close_window(app_name)

    elif action == "type":
        text = params.get("text", "")
        if not text:
            return "What should I type, sir?"
        result = type_text(text)

    elif action == "press":
        key = params.get("key", "")
        if not key:
            return "Which key should I press, sir?"
        result = press_key(key)

    else:
        return f"Unknown window_control action: '{action}', sir."

    if player:
        player.write_log(f"[window_control] {action}: {result[:60]}")

    return result
