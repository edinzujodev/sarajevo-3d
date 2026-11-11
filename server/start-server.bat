@echo off
REM Starts the Sarajevo 3D server on port 8000 (reachable from your phone on the same Wi-Fi).
cd /d "%~dp0"
if not exist .venv (
  python -m venv .venv
  .venv\Scripts\pip install -r requirements.txt
)
echo.
echo Your PC's address for the phone app (use the IPv4 address, port 8000):
ipconfig | findstr /i "IPv4"
echo Example: http://192.168.1.20:8000
echo.
.venv\Scripts\python -m uvicorn server:app --host 0.0.0.0 --port 8000
pause
