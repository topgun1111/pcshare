@echo off
setlocal EnableExtensions
title LANShare Print - setup
cd /d "%~dp0"
set "PYX="
py -3 -c "import sys" >nul 2>nul && set "PYX=py -3"
if not defined PYX python -c "import sys" >nul 2>nul && set "PYX=python"
if defined PYX goto run
echo Python is not installed - installing it now (internet needed)...
where winget >nul 2>nul && winget install -e --id Python.Python.3.12 --source winget --scope user --silent --accept-source-agreements --accept-package-agreements
call :find
if defined PYX goto run
echo Downloading Python from python.org ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://www.python.org/ftp/python/3.12.8/python-3.12.8-amd64.exe' -OutFile \"$env:TEMP\py-setup.exe\"; Start-Process \"$env:TEMP\py-setup.exe\" -ArgumentList '/quiet','InstallAllUsers=0','PrependPath=1','Include_launcher=1' -Wait"
call :find
if defined PYX goto run
echo.
echo Python could not be installed automatically.
echo Install it from https://www.python.org/downloads/ (tick "Add python.exe to PATH") and run this file again.
pause
exit /b 1
:find
for %%P in ("%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" "%ProgramFiles%\Python312\python.exe") do if exist %%P set PYX="%%~P"
exit /b
:run
%PYX% "%~dp0pcprint.py"
