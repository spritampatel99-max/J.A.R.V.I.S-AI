@echo off
REM ============================================================
REM  Start Jarvis (foreground, with visible console/logs)
REM  Closing this window WILL stop Jarvis.
REM  Use start_jarvis_background.bat if you want it to keep
REM  running after you close the window.
REM ============================================================

cd /d "%~dp0"

if not exist "venv\Scripts\python.exe" (
    echo [ERROR] venv not found. Run setup.py first:
    echo     py -3.12 -m venv venv
    echo     venv\Scripts\activate
    echo     python -m pip install -r requirements.txt
    pause
    exit /b 1
)

echo Starting Jarvis...
"venv\Scripts\python.exe" main.py

echo.
echo Jarvis has stopped.
pause
