@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\package-release.ps1" %*
set "CCR_RELEASE_EXIT=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %CCR_RELEASE_EXIT%
