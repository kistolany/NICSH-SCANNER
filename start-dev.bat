@echo off
rem Starts everything for local dev: Docker (infra + the 3 backend apps), then the admin web.
rem Same as ..\start-dev.bat.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\start-dev.ps1"
pause
