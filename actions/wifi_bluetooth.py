# actions/wifi_bluetooth.py
# WiFi and Bluetooth status/control (Windows).
#
# WiFi: uses `netsh wlan` — fully supported, no admin rights required for
#   status, admin rights ARE required to toggle it on/off (enable/disable
#   the adapter).
#
# Bluetooth: Windows has no equivalent single CLI tool. This uses
#   PowerShell's Get-PnpDevice / Enable-PnpDevice / Disable-PnpDevice
#   against the Bluetooth radio device class. Toggling requires the app
#   (or the PowerShell it spawns) to run as Administrator — status checks
#   do not.

import platform
import subprocess


def _run(cmd: list[str], timeout: int = 10) -> tuple[int, str, str]:
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout,
            creationflags=subprocess.CREATE_NO_WINDOW if platform.system() == "Windows" else 0,
        )
        return result.returncode, result.stdout.strip(), result.stderr.strip()
    except FileNotFoundError:
        return 1, "", "command not found"
    except subprocess.TimeoutExpired:
        return 1, "", "command timed out"
    except Exception as e:
        return 1, "", str(e)


# --------------------------------------------------------------------- #
# WiFi
# --------------------------------------------------------------------- #

def wifi_status() -> str:
    if platform.system() != "Windows":
        return "WiFi status checking is currently only implemented for Windows, sir."

    code, out, err = _run(["netsh", "wlan", "show", "interfaces"])
    if code != 0 or not out:
        return "Could not read WiFi status, sir. Is a WiFi adapter present?"

    if "State" not in out:
        return "No WiFi interface found, sir."

    state = "disconnected"
    ssid  = None
    signal = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("State"):
            state = line.split(":", 1)[1].strip()
        elif line.startswith("SSID") and not line.startswith("BSSID"):
            ssid = line.split(":", 1)[1].strip()
        elif line.startswith("Signal"):
            signal = line.split(":", 1)[1].strip()

    if state.lower() == "connected" and ssid:
        return f"WiFi is connected to '{ssid}', sir. Signal strength: {signal or 'unknown'}."
    return f"WiFi is {state}, sir."


def wifi_set(enabled: bool) -> str:
    """Requires Administrator privileges to actually change adapter state."""
    if platform.system() != "Windows":
        return "WiFi control is currently only implemented for Windows, sir."

    # Find the WiFi adapter's interface name first (usually "Wi-Fi").
    code, out, _ = _run(["netsh", "interface", "show", "interface"])
    iface_name = "Wi-Fi"
    if code == 0:
        for line in out.splitlines():
            if "Wi-Fi" in line or "Wireless" in line:
                parts = line.split()
                if parts:
                    iface_name = " ".join(parts[3:]) if len(parts) > 3 else iface_name
                break

    action = "enabled" if enabled else "disabled"
    code, out, err = _run(["netsh", "interface", "set", "interface", iface_name, action])

    if code == 0:
        return f"WiFi {'enabled' if enabled else 'disabled'}, sir."
    if "denied" in (err or "").lower() or "access" in (err or "").lower():
        return "I need Administrator privileges to change WiFi state, sir. Please run as admin."
    return f"Could not {'enable' if enabled else 'disable'} WiFi, sir: {err or out}"


# --------------------------------------------------------------------- #
# Bluetooth
# --------------------------------------------------------------------- #

_BT_STATUS_PS = (
    "Get-PnpDevice -Class Bluetooth | "
    "Where-Object {$_.FriendlyName -match 'radio|adapter'} | "
    "Select-Object -First 1 -ExpandProperty Status"
)

_BT_TOGGLE_PS = (
    "Get-PnpDevice -Class Bluetooth | "
    "Where-Object {{$_.FriendlyName -match 'radio|adapter'}} | "
    "{action}-PnpDevice -Confirm:$false"
)


def bluetooth_status() -> str:
    if platform.system() != "Windows":
        return "Bluetooth status checking is currently only implemented for Windows, sir."

    code, out, err = _run(["powershell", "-NoProfile", "-Command", _BT_STATUS_PS], timeout=15)
    if code != 0 or not out:
        return "Could not read Bluetooth status, sir. Is a Bluetooth radio present?"

    status = out.strip().lower()
    if status == "ok":
        return "Bluetooth is on, sir."
    if status == "error":
        return "Bluetooth is off, sir."
    return f"Bluetooth status: {out.strip()}, sir."


def bluetooth_set(enabled: bool) -> str:
    """Requires Administrator privileges — Enable/Disable-PnpDevice needs elevation."""
    if platform.system() != "Windows":
        return "Bluetooth control is currently only implemented for Windows, sir."

    action = "Enable" if enabled else "Disable"
    ps_cmd = _BT_TOGGLE_PS.format(action=action)
    code, out, err = _run(["powershell", "-NoProfile", "-Command", ps_cmd], timeout=15)

    if code == 0:
        return f"Bluetooth {'enabled' if enabled else 'disabled'}, sir."
    if "denied" in (err or "").lower() or "administrator" in (err or "").lower():
        return "I need Administrator privileges to change Bluetooth state, sir. Please run as admin."
    return f"Could not {'enable' if enabled else 'disable'} Bluetooth, sir: {err or out}"


# --------------------------------------------------------------------- #
# Dispatcher
# --------------------------------------------------------------------- #

def wifi_bluetooth_control(
    parameters: dict,
    response=None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        target: "wifi" | "bluetooth" (required)
        action: "status" | "on" | "off" (required)
    """
    params = parameters or {}
    target = params.get("target", "").lower().strip()
    action = params.get("action", "status").lower().strip()

    if target not in ("wifi", "bluetooth"):
        return "Please specify 'wifi' or 'bluetooth', sir."

    if action == "status":
        result = wifi_status() if target == "wifi" else bluetooth_status()
    elif action in ("on", "enable", "enabled"):
        result = wifi_set(True) if target == "wifi" else bluetooth_set(True)
    elif action in ("off", "disable", "disabled"):
        result = wifi_set(False) if target == "wifi" else bluetooth_set(False)
    else:
        return f"Unknown action '{action}' — use status, on, or off, sir."

    if player:
        player.write_log(f"[wifi_bluetooth] {target} {action}: {result[:60]}")

    return result
