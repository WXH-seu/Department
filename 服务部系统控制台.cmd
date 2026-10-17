@echo off
set "SCRIPT=%~dp0deploy\control-panel.ps1"
start "服务部系统控制台" "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"
