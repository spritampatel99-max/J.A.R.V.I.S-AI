# actions/app_catalog.py
#
# Persistent "what apps does Jarvis know about" catalog, distinct from
# open_app.py's in-memory index (which resets every run and doesn't track
# history). This module:
#   - Saves the full scanned app list to disk (memory/app_catalog.json)
#   - On each scan, diffs against the previous saved list to detect apps
#     installed (or removed) since last time
#   - Lets Jarvis proactively say "I noticed you installed X" instead of
#     silently only discovering it the next time you ask to open it
#
<<<<<<< HEAD
# Call refresh_and_report_changes() once at startup (already wired into
# main.py's runner() function, on the background thread — safe there
# since this is pure file I/O with no Qt/GUI calls involved) and/or
# whenever the user asks "check for new apps" (wired as a voice tool too).
=======
# Call refresh_and_report_changes() once at startup (see main.py wiring
# note in the readme) and/or whenever the user asks "check for new apps".
>>>>>>> 67c8ea33789aa1eeedffa001e492817756b17244

import json
import sys
import time
from pathlib import Path

from actions.app_scanner import scan_installed_apps


def _get_base_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


CATALOG_PATH = _get_base_dir() / "memory" / "app_catalog.json"


def _load_catalog() -> dict:
    if not CATALOG_PATH.exists():
        return {}
    try:
        return json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"[AppCatalog] ⚠️ Could not read catalog: {e}")
        return {}


def _save_catalog(catalog: dict) -> None:
    try:
        CATALOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CATALOG_PATH.write_text(json.dumps(catalog, indent=2, ensure_ascii=False), encoding="utf-8")
    except Exception as e:
        print(f"[AppCatalog] ⚠️ Could not save catalog: {e}")


def refresh_and_report_changes() -> str | None:
    """
    Scans installed apps, diffs against the last saved catalog, updates
    the catalog on disk, and returns a human-readable message about what
    changed — or None if this is the first run ever (nothing to compare
    against yet) or nothing changed.
    """
    current = scan_installed_apps()
    previous = _load_catalog()

    now = time.time()
    new_catalog = {}
    newly_installed = []
    for name, path in current.items():
        if name in previous:
            new_catalog[name] = previous[name]
            new_catalog[name]["last_seen"] = now
        else:
            new_catalog[name] = {"path": path, "first_seen": now, "last_seen": now}
            newly_installed.append(name)

    removed = [name for name in previous if name not in current]

    _save_catalog(new_catalog)

    is_first_run = not previous
    if is_first_run:
        print(f"[AppCatalog] 📇 Initial catalog built: {len(current)} apps")
        return None

    if not newly_installed and not removed:
        return None

    parts = []
    if newly_installed:
        shown = ", ".join(sorted(newly_installed)[:10])
        more  = f" (+{len(newly_installed)-10} more)" if len(newly_installed) > 10 else ""
        parts.append(f"I noticed {len(newly_installed)} new app(s) installed: {shown}{more}")
    if removed:
        shown = ", ".join(sorted(removed)[:10])
        more  = f" (+{len(removed)-10} more)" if len(removed) > 10 else ""
        parts.append(f"{len(removed)} app(s) seem to have been removed: {shown}{more}")

    return ", sir. ".join(parts) + ", sir."


def list_known_apps(limit: int = 30) -> str:
    catalog = _load_catalog()
    if not catalog:
        return "I haven't built an app catalog yet, sir — it builds automatically on first run."

    names = sorted(catalog.keys())
    shown = names[:limit]
    more  = f" (+{len(names)-limit} more)" if len(names) > limit else ""
    return f"I know about {len(names)} installed apps, sir. Including: " + ", ".join(shown) + more


def app_catalog(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        action: "check_new" | "list" (optional, default: check_new)
    """
    action = (parameters or {}).get("action", "check_new").lower().strip()

    if action == "list":
        return list_known_apps()

    result = refresh_and_report_changes()
    if player and result:
        player.write_log(f"[app_catalog] {result[:80]}")

    return result or "No new apps since last check, sir."


if __name__ == "__main__":
    print(refresh_and_report_changes() or "No changes (or first run).")
