@echo off
rem Takipli Oynat - offline mod. Video dosyasini bu dosyanin ustune surukle.
chcp 65001 >nul
setlocal
cd /d "%~dp0"
set "PY=%~dp0python\python.exe"
if "%~1"=="" (
  echo Kullanim: video dosyasini Offline.bat uzerine surukle birak.
  pause
  exit /b 1
)
set "HASMODEL="
for %%M in (yolov8n yolox_s yolox_m yolox_tiny yolox_nano) do if exist "models\%%M.onnx" set "HASMODEL=1"
if not defined HASMODEL (
  echo HATA: models klasorunde person modeli yok - yolox_s.onnx onerilir. models\MODELLER.txt dosyasini oku.
  pause
  exit /b 1
)
set "EXTRA="
if exist "models\osnet_x0_25.onnx" set "EXTRA=--reid --team"

echo Video isleniyor (uzun surebilir)...
"%PY%" -m tracker offline "%~1" %EXTRA%
if errorlevel 1 (
  pause
  exit /b 1
)
echo.
set /p TID=Takip edilecek oyuncunun ID numarasi: 
"%PY%" -m tracker offline-view "%~1.track.json" --target-id %TID%
if errorlevel 1 (
  pause
  exit /b 1
)
start "" "%~dp0mpv\mpv.exe" "%~1"
echo mpv acildi: o tusuna basarak offline takibi ac.
pause
