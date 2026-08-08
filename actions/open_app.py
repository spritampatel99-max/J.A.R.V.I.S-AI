# actions/open_app.py
# MARK XXV — Cross-Platform App Launcher

import time
import subprocess
import platform
import shutil
import os
import difflib
from pathlib import Path

try:
    import psutil
    _PSUTIL = True
except ImportError:
    _PSUTIL = False

_APP_ALIASES = {
    "whatsapp":           {"Windows": "WhatsApp",               "Darwin": "WhatsApp",            "Linux": "whatsapp"},
    "chrome":             {"Windows": "chrome",                 "Darwin": "Google Chrome",       "Linux": "google-chrome"},
    "google chrome":      {"Windows": "chrome",                 "Darwin": "Google Chrome",       "Linux": "google-chrome"},
    "firefox":            {"Windows": "firefox",                "Darwin": "Firefox",             "Linux": "firefox"},
    "spotify":            {"Windows": "Spotify",                "Darwin": "Spotify",             "Linux": "spotify"},
    "vscode":             {"Windows": "code",                   "Darwin": "Visual Studio Code",  "Linux": "code"},
    "visual studio code": {"Windows": "code",                   "Darwin": "Visual Studio Code",  "Linux": "code"},
    "discord":            {"Windows": "Discord",                "Darwin": "Discord",             "Linux": "discord"},
    "telegram":           {"Windows": "Telegram",               "Darwin": "Telegram",            "Linux": "telegram"},
    "instagram":          {"Windows": "Instagram",              "Darwin": "Instagram",           "Linux": "instagram"},
    "tiktok":             {"Windows": "TikTok",                 "Darwin": "TikTok",              "Linux": "tiktok"},
    "notepad":            {"Windows": "notepad.exe",            "Darwin": "TextEdit",            "Linux": "gedit"},
    "calculator":         {"Windows": "calc.exe",               "Darwin": "Calculator",          "Linux": "gnome-calculator"},
    "terminal":           {"Windows": "cmd.exe",                "Darwin": "Terminal",            "Linux": "gnome-terminal"},
    "cmd":                {"Windows": "cmd.exe",                "Darwin": "Terminal",            "Linux": "bash"},
    "explorer":           {"Windows": "explorer.exe",           "Darwin": "Finder",              "Linux": "nautilus"},
    "file explorer":      {"Windows": "explorer.exe",           "Darwin": "Finder",              "Linux": "nautilus"},
    "paint":              {"Windows": "mspaint.exe",            "Darwin": "Preview",             "Linux": "gimp"},
    "word":               {"Windows": "winword",                "Darwin": "Microsoft Word",      "Linux": "libreoffice --writer"},
    "excel":              {"Windows": "excel",                  "Darwin": "Microsoft Excel",     "Linux": "libreoffice --calc"},
    "powerpoint":         {"Windows": "powerpnt",               "Darwin": "Microsoft PowerPoint","Linux": "libreoffice --impress"},
    "vlc":                {"Windows": "vlc",                    "Darwin": "VLC",                 "Linux": "vlc"},
    "zoom":               {"Windows": "Zoom",                   "Darwin": "zoom.us",             "Linux": "zoom"},
    "slack":              {"Windows": "Slack",                  "Darwin": "Slack",               "Linux": "slack"},
    "steam":              {"Windows": "steam",                  "Darwin": "Steam",               "Linux": "steam"},
    "task manager":       {"Windows": "taskmgr.exe",            "Darwin": "Activity Monitor",    "Linux": "gnome-system-monitor"},
    "settings":           {"Windows": "ms-settings:",           "Darwin": "System Preferences",  "Linux": "gnome-control-center"},
    "powershell":         {"Windows": "powershell.exe",         "Darwin": "Terminal",            "Linux": "bash"},
    "edge":               {"Windows": "msedge",                 "Darwin": "Microsoft Edge",      "Linux": "microsoft-edge"},
    "brave":              {"Windows": "brave",                  "Darwin": "Brave Browser",       "Linux": "brave-browser"},
    "obsidian":           {"Windows": "Obsidian",               "Darwin": "Obsidian",            "Linux": "obsidian"},
    "notion":             {"Windows": "Notion",                 "Darwin": "Notion",              "Linux": "notion"},
    "blender":            {"Windows": "blender",                "Darwin": "Blender",             "Linux": "blender"},
    "capcut":             {"Windows": "CapCut",                 "Darwin": "CapCut",              "Linux": "capcut"},
    "postman":            {"Windows": "Postman",                "Darwin": "Postman",             "Linux": "postman"},
    "figma":              {"Windows": "Figma",                  "Darwin": "Figma",               "Linux": "figma"},
}


def _normalize(raw: str) -> str:
    system = platform.system()
    key    = raw.lower().strip()
    if key in _APP_ALIASES:
        return _APP_ALIASES[key].get(system, raw)
    for alias_key, os_map in _APP_ALIASES.items():
        if alias_key in key or key in alias_key:
            return os_map.get(system, raw)
    return raw


# --------------------------------------------------------------------- #
# FEATURE: dynamic installed-app discovery.
#
# The ~30 hardcoded aliases above only cover common apps. Anything else
# ("open Adobe Premiere", "open my invoice tool", any niche/less-common
# software) had no way to be found or launched at all. This uses
# app_scanner.py (shared with app_catalog.py) to see what's actually
# installed, builds a name index once (cached), and fuzzy-matches against
# it — so basically anything installed can be opened by approximate name,
# not just the hardcoded list.
# --------------------------------------------------------------------- #

from actions.app_scanner import scan_installed_apps

_APP_INDEX_CACHE: dict[str, str] | None = None


def _get_app_index() -> dict[str, str]:
    global _APP_INDEX_CACHE
    if _APP_INDEX_CACHE is None:
        _APP_INDEX_CACHE = scan_installed_apps()
        print(f"[open_app] 📇 Indexed {len(_APP_INDEX_CACHE)} installed apps")
    return _APP_INDEX_CACHE


def refresh_app_index() -> int:
    """Force a re-scan (e.g. after installing new software). Returns count found."""
    global _APP_INDEX_CACHE
    _APP_INDEX_CACHE = None
    return len(_get_app_index())


def _fuzzy_find_app(app_name: str) -> str | None:
    """Returns a launchable path for app_name via fuzzy match, or None."""
    index = _get_app_index()
    if not index:
        return None

    key = app_name.lower().strip()
    if key in index:
        return index[key]

    matches = difflib.get_close_matches(key, index.keys(), n=1, cutoff=0.6)
    if matches:
        return index[matches[0]]

    for name, path in index.items():
        if key in name or name in key:
            return path

    return None


def _launch_found_path(path: str) -> bool:
    """Launches a path found via _fuzzy_find_app, using the right method per OS."""
    system = platform.system()
    try:
        if system == "Windows":
            os.startfile(path)  # noqa: this attr only exists on Windows
            return True
        elif system == "Darwin":
            subprocess.run(["open", path], capture_output=True, timeout=8)
            return True
        elif system == "Linux":
            desktop_id = Path(path).stem
            subprocess.run(["gtk-launch", desktop_id], capture_output=True, timeout=5)
            return True
    except Exception as e:
        print(f"[open_app] ⚠️ Could not launch discovered path {path}: {e}")
    return False


def list_running_apps(limit: int = 30) -> str:
    """FEATURE: lists currently running applications/processes by name."""
    if not _PSUTIL:
        return "psutil is not installed, sir — cannot list running apps. Run: pip install psutil"

    seen: set[str] = set()
    names: list[str] = []
    try:
        for proc in psutil.process_iter(["name"]):
            try:
                name = proc.info.get("name")
                if name and name.lower() not in seen:
                    seen.add(name.lower())
                    names.append(name)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception as e:
        return f"Could not list running apps, sir: {e}"

    names.sort(key=str.lower)
    shown = names[:limit]
    more  = f" (+{len(names) - limit} more)" if len(names) > limit else ""
    return f"{len(names)} processes running, sir. Top ones: " + ", ".join(shown) + more


def close_app(app_name: str) -> str:
    """FEATURE: closes a running app by (approximate) name."""
    if not _PSUTIL:
        return "psutil is not installed, sir — cannot close apps. Run: pip install psutil"

    key    = app_name.lower().replace(" ", "").replace(".exe", "")
    killed = []
    try:
        for proc in psutil.process_iter(["name", "pid"]):
            try:
                pname = (proc.info.get("name") or "").lower().replace(" ", "").replace(".exe", "")
                if key and (key in pname or pname in key):
                    proc.terminate()
                    killed.append(proc.info.get("name"))
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception as e:
        return f"Could not close {app_name}, sir: {e}"

    if killed:
        return f"Closed {', '.join(set(killed))}, sir."
    return f"{app_name} doesn't appear to be running, sir."


def _is_running(app_name: str) -> bool:
    if not _PSUTIL:
        return True
    app_lower = app_name.lower().replace(" ", "").replace(".exe", "")
    try:
        for proc in psutil.process_iter(["name"]):
            try:
                proc_name = proc.info["name"].lower().replace(" ", "").replace(".exe", "")
                if app_lower in proc_name or proc_name in app_lower:
                    return True
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception:
        pass
    return False


def _launch_windows(app_name: str) -> bool:
    try:
        import pyautogui
        pyautogui.PAUSE = 0.1
        pyautogui.press("win")
        time.sleep(0.6)
        pyautogui.write(app_name, interval=0.05)
        time.sleep(0.8)
        pyautogui.press("enter")
        time.sleep(3.0)
        return True
    except Exception as e:
        print(f"[open_app] ⚠️ Windows launch failed: {e}")
        return False

def _launch_macos(app_name: str) -> bool:
    try:
        result = subprocess.run(["open", "-a", app_name], capture_output=True, timeout=8)
        if result.returncode == 0:
            time.sleep(1.0)
            return True
    except Exception:
        pass

    try:
        result = subprocess.run(["open", "-a", f"{app_name}.app"], capture_output=True, timeout=8)
        if result.returncode == 0:
            time.sleep(1.0)
            return True
    except Exception:
        pass

    try:
        import pyautogui
        pyautogui.hotkey("command", "space")
        time.sleep(0.6)
        pyautogui.write(app_name, interval=0.05)
        time.sleep(0.8)
        pyautogui.press("enter")
        time.sleep(1.5)
        return True
    except Exception as e:
        print(f"[open_app] ⚠️ macOS Spotlight failed: {e}")
        return False



def _launch_linux(app_name: str) -> bool:
    binary = (
        shutil.which(app_name) or
        shutil.which(app_name.lower()) or
        shutil.which(app_name.lower().replace(" ", "-"))
    )
    if binary:
        try:
            subprocess.Popen([binary], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            time.sleep(1.0)
            return True
        except Exception:
            pass

    try:
        subprocess.run(["xdg-open", app_name], capture_output=True, timeout=5)
        return True
    except Exception:
        pass

    try:
        desktop_name = app_name.lower().replace(" ", "-")
        subprocess.run(["gtk-launch", desktop_name], capture_output=True, timeout=5)
        return True
    except Exception:
        pass

    return False


_OS_LAUNCHERS = {
    "Windows": _launch_windows,
    "Darwin":  _launch_macos,
    "Linux":   _launch_linux,
}


def open_app(
    parameters=None,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    params = parameters or {}
    action = params.get("action", "open").lower().strip()

    # FEATURE: "is any app currently running / what's open" was previously
    # impossible to ask — _is_running() existed but nothing exposed it as a
    # queryable action. Same for closing an app.
    if action == "list_running":
        return list_running_apps(limit=params.get("limit", 30))

    app_name = params.get("app_name", "").strip()
    if not app_name:
        return "Please specify which application to open, sir."

    if action == "close":
        # FEATURE: closing an app can lose unsaved work, so this is now
        # gated the same way as file deletion — see safety.py.
        import safety
        if safety.confirmation_required():
            result = safety.request_confirmation(f"close '{app_name}'", close_app, app_name)
        else:
            result = close_app(app_name)
        if player:
            player.write_log(f"[open_app] close {app_name}")
        return result

    system   = platform.system()
    launcher = _OS_LAUNCHERS.get(system)

    if launcher is None:
        return f"Unsupported OS: {system}"

    normalized = _normalize(app_name)

    # BUGFIX: _is_running() was fully implemented (psutil process scan) but
    # never actually called from anywhere — every "open X" request relaunched
    # the app via keystroke-simulation even if it was already open, which at
    # best wastes a few seconds and at worst opens a second window/instance.
    if _is_running(normalized) or _is_running(app_name):
        msg = f"{app_name} is already open, sir."
        print(f"[open_app] ℹ️ {msg}")
        if player:
            player.write_log(f"[open_app] {app_name} already running")
        return msg

    print(f"[open_app] 🚀 Launching: {app_name} → {normalized} ({system})")

    if player:
        player.write_log(f"[open_app] {app_name}")

    try:
        # FEATURE: try the dynamic installed-app index first (Start Menu /
        # Applications / .desktop scan). This launches the actual
        # executable/shortcut directly instead of gambling on Windows
        # Search or Spotlight finding the right thing from typed text —
        # and it works for anything installed, not just the ~30 apps in
        # _APP_ALIASES.
        found_path = _fuzzy_find_app(app_name)
        if found_path and _launch_found_path(found_path):
            time.sleep(1.0)
            return f"Opened {app_name} successfully, sir."

        success = launcher(normalized)

        if success:
            return f"Opened {app_name} successfully, sir."

        if normalized != app_name:
            success = launcher(app_name)
            if success:
                return f"Opened {app_name} successfully, sir."

        return (
            f"I tried to open {app_name}, sir, but couldn't confirm it launched. "
            f"It may still be loading or might not be installed."
        )

    except Exception as e:
        print(f"[open_app] ❌ {e}")
        return f"Failed to open {app_name}, sir: {e}"