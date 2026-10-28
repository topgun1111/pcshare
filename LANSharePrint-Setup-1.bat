@echo off
setlocal EnableExtensions
title LANShare Print - setup
cd /d "%~dp0"
set "LSBAT=%~f0"
if not exist "%~dp0pcprint.py" (
  echo pcprint.py was not found next to this file. Put both files in the same folder and run this again.
  pause
  exit /b 1
)
call :find
if defined PYX goto run
echo Python is not installed - installing it now (internet needed)...
for %%R in (1 2) do if not defined PYX call :sources %%R
if defined PYX goto run
echo.
echo Python could not be installed automatically (no internet, a blocked download, or the installer was refused).
echo Install it from https://www.python.org/downloads/windows/ (tick "Add python.exe to PATH") and run this file again.
start "" "https://www.python.org/downloads/windows/"
pause
exit /b 1

:sources
rem round %1: winget only in round 1, then the python.org installers (3 versions, each downloaded with retries and checked)
if "%~1"=="2" (
  echo Trying again in a few seconds...
  ping -n 8 127.0.0.1 >nul
)
if "%~1"=="1" call :winget
if defined PYX exit /b
for %%V in (3.12.8 3.12.10 3.12.7) do if not defined PYX call :getpy %%V
exit /b

:winget
where winget >nul 2>nul
if errorlevel 1 exit /b
for %%N in (1 2) do if not defined PYX (
  echo Installing Python with winget, try %%N ...
  winget install -e --id Python.Python.3.12 --source winget --scope user --silent --accept-source-agreements --accept-package-agreements
  call :find
  if not defined PYX ping -n 6 127.0.0.1 >nul
)
exit /b

:getpy
echo Downloading Python %~1 from python.org ...
set "LSV=%~1"
set "LSOUT=%TEMP%\lanshare-python-%~1.exe"
set "LSARCH=%PROCESSOR_ARCHITECTURE%"
if defined PROCESSOR_ARCHITEW6432 set "LSARCH=%PROCESSOR_ARCHITEW6432%"
if /i "%LSARCH%"=="AMD64" set "LSARCH=amd64"
if /i "%LSARCH%"=="ARM64" set "LSARCH=arm64"
if /i "%LSARCH%"=="x86" set "LSARCH=x86"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$l = Get-Content -LiteralPath $env:LSBAT | Where-Object { $_.StartsWith('::PS:') } | ForEach-Object { $_.Substring(5) }; Invoke-Expression ([string]::Join([Environment]::NewLine, $l))"
if errorlevel 1 (
  echo Download of Python %~1 failed or the file was not the real installer.
  if exist "%LSOUT%" del /q "%LSOUT%" >nul 2>nul
  exit /b 1
)
echo Installing Python %~1 ...
start /wait "" "%LSOUT%" /quiet InstallAllUsers=0 PrependPath=1 Include_launcher=1 Include_tcltk=1 Include_pip=1 Include_test=0
del /q "%LSOUT%" >nul 2>nul
call :find
exit /b

:find
set "PYX="
py -3 -c "import sys" >nul 2>nul && set "PYX=py -3"
if defined PYX exit /b
python -c "import sys" >nul 2>nul && set "PYX=python"
if defined PYX exit /b
rem a fresh install is not on this window's PATH yet: look in the usual places
for %%P in ("%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" "%ProgramFiles%\Python312\python.exe" "%ProgramFiles%\Python313\python.exe") do if not defined PYX if exist "%%~P" set PYX="%%~P"
exit /b

:run
%PYX% "%~dp0pcprint.py"
if errorlevel 1 (
  echo.
  echo pcprint.py stopped with an error. See the message above or the "Pcprint logs" folder.
  pause
)
exit /b

rem ---- PowerShell download part (read by :getpy; every line below starts with ::PS: and is never run by cmd) ----
::PS:[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
::PS:$ProgressPreference = 'SilentlyContinue'
::PS:$v = $env:LSV; $arch = $env:LSARCH; $out = $env:LSOUT
::PS:if ($arch -eq 'x86') { $n = "python-$v.exe" } else { $n = "python-$v-$arch.exe" }
::PS:$u = "https://www.python.org/ftp/python/$v/$n"
::PS:for ($i = 1; $i -le 3; $i++) {
::PS:  try { Invoke-WebRequest -Uri $u -OutFile $out -UseBasicParsing -TimeoutSec 300 } catch { }
::PS:  if (-not (Test-Path $out) -or (Get-Item $out).Length -lt 15MB) { try { & curl.exe -L --fail --retry 2 -o $out $u 2>$null } catch { } }
::PS:  if ((Test-Path $out) -and (Get-Item $out).Length -gt 15MB) {
::PS:    $s = Get-AuthenticodeSignature $out
::PS:    if ($s.Status -eq 'Valid' -and $s.SignerCertificate.Subject -like '*Python Software Foundation*') { exit 0 }
::PS:    Write-Host 'The downloaded file is not signed by the Python Software Foundation.'
::PS:  }
::PS:  Remove-Item $out -ErrorAction SilentlyContinue
::PS:  if ($i -lt 3) { Write-Host "Download failed, retrying ($i of 3)..."; Start-Sleep -Seconds ($i * 5) }
::PS:}
::PS:exit 1
