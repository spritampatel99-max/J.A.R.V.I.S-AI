# actions/system_inventory.py
#
# Rich system inventory reports (installed apps with SIZE and INSTALL
# DATE, running-apps snapshot, startup apps) via the Windows Registry —
# migrated and adapted from an older assistant's system_agent.py.
#
# This is complementary to app_catalog.py, not a replacement:
#   - app_catalog.py: Start-Menu-shortcut scan, tracks NEW/REMOVED apps
#     between runs (no size/date metadata available from shortcuts)
#   - system_inventory.py (this file): Registry scan, gives size + install
#     date + startup entries + live running-process reports (no
#     new/removed diffing — that's app_catalog's job)
#
# SAFETY NOTE: the original system_agent.py had an UNGATED kill_app() that
# force-killed processes with zero confirmation. That is NOT carried over
# here — "close/kill an app" already exists as open_app.py's close_app,
# which IS gated behind safety.py's confirmation system. This module
# deliberately does not add a second, ungated way to kill processes.

import sys
import time
import threading

try:
    import psutil
    _PSUTIL = True
except ImportError:
    _PSUTIL = False

_installed_apps: list[dict] = []
_running_apps:   list[dict] = []
_startup_apps:   list[dict] = []
_ready = threading.Event()
_started = False

_EXCLUDED_PROCESSES = {
    "system", "svchost.exe", "csrss.exe", "wininit.exe", "services.exe",
    "lsass.exe", "smss.exe", "winlogon.exe", "fontdrvhost.exe", "dwm.exe",
    "registry", "memcompression",
}


def _scan_installed_apps_registry() -> list[dict]:
    """Windows-only. Returns [] gracefully on other platforms."""
    if sys.platform != "win32":
        return []
    apps = []
    try:
        import winreg
        roots = [
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"),
            (winreg.HKEY_CURRENT_USER,  r"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
        ]
        for hive, path in roots:
            try:
                key = winreg.OpenKey(hive, path)
                for i in range(winreg.QueryInfoKey(key)[0]):
                    try:
                        sub_name = winreg.EnumKey(key, i)
                        sub_key  = winreg.OpenKey(key, sub_name)
                        name = winreg.QueryValueEx(sub_key, "DisplayName")[0]
                        try:
                            size_kb = winreg.QueryValueEx(sub_key, "EstimatedSize")[0]
                        except Exception:
                            size_kb = 0
                        try:
                            install_loc = winreg.QueryValueEx(sub_key, "InstallLocation")[0]
                        except Exception:
                            install_loc = ""
                        try:
                            install_date = winreg.QueryValueEx(sub_key, "InstallDate")[0]
                        except Exception:
                            install_date = ""
                        if name and not name.startswith("KB"):
                            apps.append({
                                "name": name, "path": install_loc,
                                "size_mb": round(size_kb / 1024, 1) if size_kb else 0,
                                "install_date": install_date,
                            })
                    except Exception:
                        continue
            except Exception:
                continue
    except Exception as e:
        print(f"[SystemInventory] ⚠️ Registry scan failed: {e}")

    seen, unique = set(), []
    for a in apps:
        if a["name"] not in seen:
            seen.add(a["name"])
            unique.append(a)
    return sorted(unique, key=lambda x: x["size_mb"], reverse=True)


def _scan_startup_apps() -> list[dict]:
    if sys.platform != "win32":
        return []
    try:
        import winreg
        entries = []
        for hive, path in [
            (winreg.HKEY_CURRENT_USER,  r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"),
            (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows\CurrentVersion\Run"),
        ]:
            try:
                key = winreg.OpenKey(hive, path)
                for i in range(winreg.QueryInfoKey(key)[1]):
                    name, value, _ = winreg.EnumValue(key, i)
                    entries.append({"name": name, "command": value})
            except Exception:
                continue
        return entries
    except Exception as e:
        print(f"[SystemInventory] ⚠️ Startup scan failed: {e}")
        return []


def _get_running_snapshot() -> list[dict]:
    if not _PSUTIL:
        return []
    out = []
    try:
        for p in psutil.process_iter(["name", "memory_info", "cpu_percent"]):
            try:
                info = p.info
                name = info.get("name")
                if not name or not name.lower().endswith(".exe"):
                    continue
                if name.lower() in _EXCLUDED_PROCESSES:
                    continue
                mem_mb = (info["memory_info"].rss / (1024 * 1024)) if info.get("memory_info") else 0
                out.append({
                    "name": name.replace(".exe", ""),
                    "mem_mb": round(mem_mb, 1),
                    "cpu": info.get("cpu_percent", 0) or 0,
                })
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                continue
    except Exception as e:
        print(f"[SystemInventory] ⚠️ Running snapshot failed: {e}")
    return out


def _build_inventory():
    global _installed_apps, _startup_apps
    _installed_apps = _scan_installed_apps_registry()
    _startup_apps   = _scan_startup_apps()
    _ready.set()
    print(f"[SystemInventory] 📇 {len(_installed_apps)} installed apps (registry), {len(_startup_apps)} startup entries")


def _refresh_running_loop():
    global _running_apps
    while True:
        _running_apps = _get_running_snapshot()
        time.sleep(5)


def ensure_started():
    global _started
    if not _started:
        _started = True
        threading.Thread(target=_build_inventory, daemon=True, name="SystemInventory-Build").start()
        threading.Thread(target=_refresh_running_loop, daemon=True, name="SystemInventory-Running").start()


# --------------------------------------------------------------------- #
# Reports
# --------------------------------------------------------------------- #

def full_report() -> str:
    ensure_started()
    _ready.wait(timeout=10)
    n_installed = len(_installed_apps)
    n_running   = len(set(a["name"] for a in _running_apps))
    n_startup   = len(_startup_apps)
    top_size    = sorted(_installed_apps, key=lambda x: x["size_mb"], reverse=True)[:3]
    top_names   = ", ".join(f"{a['name']} ({a['size_mb']:.0f}MB)" for a in top_size if a["size_mb"] > 0)
    return (
        f"System overview, sir: {n_installed} applications installed, "
        f"{n_running} currently running, {n_startup} set to launch at startup. "
        f"Largest installed apps: {top_names or 'unavailable'}."
    )


def running_report() -> str:
    ensure_started()
    apps = sorted(_running_apps, key=lambda x: x["mem_mb"], reverse=True)[:8]
    if not apps:
        return "Could not read running applications, sir."
    names = ", ".join(f"{a['name']} ({a['mem_mb']:.0f}MB)" for a in apps)
    return f"Currently running, sir: {names}."


def top_resource_hogs() -> str:
    ensure_started()
    apps = sorted(_running_apps, key=lambda x: x["mem_mb"], reverse=True)[:5]
    if not apps:
        return "Could not read resource usage, sir."
    lines = [f"  {a['name']}: {a['mem_mb']:.0f}MB RAM, {a['cpu']:.0f}% CPU" for a in apps]
    return "Top resource consumers, sir:\n" + "\n".join(lines)


def storage_wasters() -> str:
    ensure_started()
    _ready.wait(timeout=10)
    large = [a for a in _installed_apps if a["size_mb"] > 500]
    large = sorted(large, key=lambda x: x["size_mb"], reverse=True)[:10]
    if not large:
        return "No unusually large applications detected, sir."
    lines = [f"  {a['name']}: {a['size_mb']:.0f}MB" for a in large]
    return "Large installed applications, sir (review if still needed):\n" + "\n".join(lines)


def startup_report() -> str:
    ensure_started()
    _ready.wait(timeout=10)
    if not _startup_apps:
        return "No applications are currently set to launch at startup, sir."
    names = ", ".join(a["name"] for a in _startup_apps[:10])
    return f"{len(_startup_apps)} application(s) launch at startup, sir: {names}."


def is_installed(app_name: str) -> bool:
    ensure_started()
    _ready.wait(timeout=10)
    q = app_name.lower()
    return any(q in a["name"].lower() for a in _installed_apps)


def system_inventory(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        report: "full" | "running" | "top_resources" | "storage_wasters" |
                "startup" (optional, default: "full")
    """
    if sys.platform != "win32":
        return "Detailed system inventory currently requires Windows (registry-based), sir."

    report = (parameters or {}).get("report", "full").lower().strip()

    handlers = {
        "full": full_report,
        "running": running_report,
        "top_resources": top_resource_hogs,
        "storage_wasters": storage_wasters,
        "startup": startup_report,
    }
    handler = handlers.get(report, full_report)
    result  = handler()

    if player:
        player.write_log(f"[system_inventory] {report}: {result[:60]}")

    return result
