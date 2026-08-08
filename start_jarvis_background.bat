@echo off
REM ============================================================
REM  Start Jarvis in the BACKGROUND.
REM  - No console window stays open.
REM  - Closing THIS window (or the terminal you ran it from)
REM    does NOT stop Jarvis - it keeps running.
REM  - Output is logged to logs\jarvis.log
REM  - Use stop_jarvis.bat to actually stop it.
REM ============================================================

cd /d "%~dp0"

if not exist "venv\Scripts\pythonw.exe" (
    echo [ERROR] venv not found. Run setup first - see start_jarvis.bat.
    pause
    exit /b 1
)

if not exist "logs" mkdir "logs"

REM Refuse to start a second copy if one is already running
if exist "jarvis.pid" (
    set /p OLDPID=<jarvis.pid
    tasklist /FI "PID eq %OLDPID%" 2>NUL | find "%OLDPID%" >NUL
    if not errorlevel 1 (
        echo Jarvis appears to already be running as PID %OLDPID%.
        echo Run stop_jarvis.bat first if you want to restart it.
        pause
        exit /b 1
    )
)

REM "start" with a title and no /B fully detaches the new process from
REM this console - closing this window will NOT kill it. pythonw.exe has
REM no console of its own, so nothing pops up on screen for it either.
start "JarvisBackground" /MIN "venv\Scripts\pythonw.exe" main.py 1>>"logs\jarvis.log" 2>>"logs\jarvis_error.log"

REM Give it a moment to spawn, then find and record its PID so
REM stop_jarvis.bat can target it precisely later.
timeout /t 2 /nobreak >NUL

for /f "tokens=2 delims=," %%P in (
    'wmic process where "name='pythonw.exe' and CommandLine like '%%main.py%%'" get ProcessId^,CommandLine /format:csv ^| findstr /I "main.py"'
) do (
    echo %%P> jarvis.pid
)

echo Jarvis started in the background.
echo Logs: logs\jarvis.log  (errors: logs\jarvis_error.log)
if exist jarvis.pid (
    set /p NEWPID=<jarvis.pid
    echo PID: %NEWPID%
) else (
    echo [WARN] Could not confirm PID automatically - check Task Manager for pythonw.exe if stop_jarvis.bat doesn't work later.
)
echo You can close this window now - Jarvis will keep running.
pause
