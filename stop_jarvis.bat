@echo off
REM ============================================================
REM  Stop a Jarvis instance started via start_jarvis_background.bat
REM ============================================================

cd /d "%~dp0"

if exist "jarvis.pid" (
    set /p PID=<jarvis.pid
    tasklist /FI "PID eq %PID%" 2>NUL | find "%PID%" >NUL
    if not errorlevel 1 (
        echo Stopping Jarvis (PID %PID%)...
        taskkill /PID %PID% /T /F
        del jarvis.pid
        echo Stopped.
        pause
        exit /b 0
    ) else (
        echo Recorded PID %PID% is not running - cleaning up stale pid file.
        del jarvis.pid
    )
)

echo No PID file found ^(or it was stale^). Searching for a running instance...
for /f "tokens=2 delims=," %%P in (
    'wmic process where "name='pythonw.exe' and CommandLine like '%%main.py%%'" get ProcessId^,CommandLine /format:csv ^| findstr /I "main.py"'
) do (
    echo Found Jarvis running as PID %%P - stopping it.
    taskkill /PID %%P /T /F
)

echo Done. If nothing printed above, Jarvis was not running.
pause
