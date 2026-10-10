@echo off
echo started %date% %time% > "%~dp0phone-logs-run.txt"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0phone-logs.ps1" >> "%~dp0phone-logs-run.txt" 2>&1
