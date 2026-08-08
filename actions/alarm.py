# actions/alarm.py
#
# A genuine ALARM (distinct from reminder.py's one-shot notification):
#   - Loops an audible sound and shows a blocking "dismiss" dialog that
#     stays on screen until the user closes it (a reminder just beeps
#     once and shows a toast that auto-dismisses).
#   - Accepts relative time ("in 20 minutes") as well as a clock time.
#   - Supports daily recurrence ("every day at 7am").
#
# Windows-only (uses Task Scheduler, same as reminder.py — this project's
# reminder/alarm system isn't cross-platform yet).

import os
import subprocess
import sys
import uuid
from datetime import datetime, timedelta
from xml.sax.saxutils import escape as _xml_escape


def _parse_alarm_time(params: dict) -> datetime | None:
    """
    Accepts either:
      - time: "HH:MM" (next occurrence — today if still ahead, else tomorrow)
      - in_minutes: int (fires N minutes from now)
    Returns None if neither is usable.
    """
    in_minutes = params.get("in_minutes")
    if in_minutes:
        try:
            return datetime.now() + timedelta(minutes=int(in_minutes))
        except (TypeError, ValueError):
            pass

    time_str = params.get("time")
    if time_str:
        try:
            hh, mm = map(int, time_str.strip().split(":"))
            target = datetime.now().replace(hour=hh, minute=mm, second=0, microsecond=0)
            if target <= datetime.now():
                target += timedelta(days=1)
            return target
        except (ValueError, AttributeError):
            pass

    return None


def set_alarm(
    parameters: dict,
    response: str | None = None,
    player=None,
    session_memory=None,
) -> str:
    """
    parameters:
        time:       "HH:MM" (optional if in_minutes given)
        in_minutes: int (optional if time given) — e.g. "wake me in 20 minutes"
        label:      string (optional) — what to call it
        recurring:  bool (optional) — repeat daily at this time
    """
    params = parameters or {}
    target = _parse_alarm_time(params)
    if not target:
        return "Please give me a time (like 7:00) or a duration (like 'in 20 minutes'), sir."

    if sys.platform != "win32":
        return "Alarms are currently only supported on Windows, sir."

    label     = (params.get("label") or "Alarm").strip()[:100]
    recurring = bool(params.get("recurring", False))

    alarm_id     = uuid.uuid4().hex[:8]
    task_name    = f"MARKAlarm_{alarm_id}"
    safe_label   = repr(label)  # safe embedding into the generated script, same
                                 # fix applied here as in reminder.py — avoids the
                                 # backslash/newline/quote breakage bug found there

    temp_dir      = os.environ.get("TEMP", "C:\\Temp")
    notify_script = os.path.join(temp_dir, f"{task_name}.pyw")

    # Blocking dialog + looping beep until dismissed — this is what makes
    # it an ALARM rather than a one-shot reminder toast.
    script_code = f'''import sys, time, threading, ctypes

_stop = threading.Event()

def _beep_loop():
    try:
        import winsound
        while not _stop.is_set():
            winsound.Beep(1000, 400)
            time.sleep(0.3)
    except Exception:
        pass

t = threading.Thread(target=_beep_loop, daemon=True)
t.start()

try:
    ctypes.windll.user32.MessageBoxW(
        0,
        {safe_label} + "\\n\\nClick OK to dismiss the alarm.",
        "MARK Alarm",
        0x1000  # MB_SYSTEMMODAL - stays on top until dismissed
    )
finally:
    _stop.set()

try:
    import os
    os.remove(__file__)
except Exception:
    pass
'''
    with open(notify_script, "w", encoding="utf-8") as f:
        f.write(script_code)

    python_exe = sys.executable
    if python_exe.lower().endswith("python.exe"):
        pythonw = python_exe.replace("python.exe", "pythonw.exe")
        if os.path.exists(pythonw):
            python_exe = pythonw

    trigger_xml = (
        f'<TimeTrigger><StartBoundary>{target.strftime("%Y-%m-%dT%H:%M:%S")}</StartBoundary>'
        f'<Enabled>true</Enabled></TimeTrigger>'
    )
    if recurring:
        trigger_xml = (
            f'<CalendarTrigger><StartBoundary>{target.strftime("%Y-%m-%dT%H:%M:%S")}</StartBoundary>'
            f'<Enabled>true</Enabled><ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay>'
            f'</CalendarTrigger>'
        )

    xml_content = f'''<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>MARK Alarm: {_xml_escape(label)}</Description>
  </RegistrationInfo>
  <Triggers>{trigger_xml}</Triggers>
  <Actions>
    <Exec>
      <Command>{python_exe}</Command>
      <Arguments>"{notify_script}"</Arguments>
    </Exec>
  </Actions>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>true</StartWhenAvailable>
    <WakeToRun>true</WakeToRun>
    <Enabled>true</Enabled>
  </Settings>
  <Principals>
    <Principal>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
</Task>'''

    xml_path = os.path.join(temp_dir, f"{task_name}.xml")
    with open(xml_path, "w", encoding="utf-16") as f:
        f.write(xml_content)

    result = subprocess.run(
        f'schtasks /Create /TN "{task_name}" /XML "{xml_path}" /F',
        shell=True, capture_output=True, text=True,
    )

    try:
        os.remove(xml_path)
    except Exception:
        pass

    if result.returncode != 0:
        err = result.stderr.strip() or result.stdout.strip()
        print(f"[Alarm] ❌ schtasks failed: {err}")
        try:
            os.remove(notify_script)
        except Exception:
            pass
        return "I couldn't set the alarm due to a system error, sir."

    if player:
        player.write_log(f"[alarm] {task_name} set for {target.strftime('%H:%M')}")

    freq = " daily" if recurring else ""
    return f"Alarm '{label}' set for {target.strftime('%B %d at %I:%M %p')}{freq}, sir."


def cancel_alarm(parameters: dict, response=None, player=None, session_memory=None) -> str:
    """Cancels ALL scheduled MARK alarms (task-name prefix MARKAlarm_)."""
    if sys.platform != "win32":
        return "Alarms are currently only supported on Windows, sir."

    result = subprocess.run(
        'schtasks /Query /FO CSV /NH',
        shell=True, capture_output=True, text=True,
    )
    cancelled = 0
    for line in result.stdout.splitlines():
        if "MARKAlarm_" in line:
            task_name = line.split(",")[0].strip('"').lstrip("\\")
            subprocess.run(f'schtasks /Delete /TN "{task_name}" /F', shell=True, capture_output=True)
            cancelled += 1

    if cancelled:
        return f"Cancelled {cancelled} alarm(s), sir."
    return "No active alarms found, sir."
