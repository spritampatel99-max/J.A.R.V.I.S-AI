# actions/app_scanner.py
#
# Shared installed-app discovery logic. Scans what's actually on the
# machine (Start Menu shortcuts on Windows, /Applications on macOS,
# .desktop files on Linux) and returns {lowercased name: launchable path}.
#
# Used by both open_app.py (fuzzy-match & launch by name) and
# app_catalog.py (persistent tracking of what's installed, so Jarvis can
# notice when something new shows up between runs).

import os
import platform
from pathlib import Path


def scan_installed_apps_windows() -> dict[str, str]:
    index = {}
    roots = [
        Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
        Path(os.environ.get("PROGRAMDATA", "")) / "Microsoft/Windows/Start Menu/Programs",
    ]
    for root in roots:
        if not root.exists():
            continue
        try:
            for lnk in root.rglob("*.lnk"):
                index[lnk.stem.lower()] = str(lnk)
        except Exception:
            continue
    return index


def scan_installed_apps_macos() -> dict[str, str]:
    index = {}
    roots = [Path("/Applications"), Path.home() / "Applications"]
    for root in roots:
        if not root.exists():
            continue
        try:
            for app in root.glob("*.app"):
                index[app.stem.lower()] = str(app)
        except Exception:
            continue
    return index


def scan_installed_apps_linux() -> dict[str, str]:
    index = {}
    roots = [
        Path("/usr/share/applications"),
        Path("/usr/local/share/applications"),
        Path.home() / ".local/share/applications",
    ]
    for root in roots:
        if not root.exists():
            continue
        try:
            for desktop_file in root.glob("*.desktop"):
                index[desktop_file.stem.lower()] = str(desktop_file)
        except Exception:
            continue
    return index


def scan_installed_apps() -> dict[str, str]:
    """Scans using whichever method matches the current OS."""
    system = platform.system()
    if system == "Windows":
        return scan_installed_apps_windows()
    elif system == "Darwin":
        return scan_installed_apps_macos()
    elif system == "Linux":
        return scan_installed_apps_linux()
    return {}
