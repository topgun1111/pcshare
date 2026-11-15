; Takipli Oynat - tam kurulum (gömülü Python + mpv + DirectML + YOLOX-S modeli). Derleme: iscc /DAppVersion=x installer.iss
; Önkoşul: dist\TakipliOynat içinde python\ (scripts/build_embedded.py), mpv\mpv.exe (fetch_deps.ps1)
; models\yolox_s.onnx (fetch_model.ps1), models\yolov8s-pose.onnx (export_pose.py), ffmpeg\ffmpeg.exe (fetch_ffmpeg.ps1). Kurulumdan sonra elle bir şey kurmak/kopyalamak gerekmez.
;
; GÜNCELLEME: yeni setup.exe'yi çalıştırmak yeter. AppId aynı olduğundan eski kurulumun üstüne (aynı klasör,
; aynı yetki, aynı Başlat menüsü grubu) sorusuz kurulur; açık mpv/python kendiliğinden kapatılır; eski
; sürümü önce kaldırmak gerekmez. Sessiz güncelleme: TakipliOynat-Setup-x.y.z.exe /VERYSILENT /NORESTART
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#define AppName "Takipli Oynat"

[Setup]
AppId={{6F3A2C1E-8B47-4D5A-9E21-7C0D4B8A1F55}
AppName={#AppName}
AppVersion={#AppVersion}
DefaultDirName={autopf}\TakipliOynat
DefaultGroupName={#AppName}
OutputDir=dist\installer
OutputBaseFilename=TakipliOynat-Setup-{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; DirectML için Windows 10 sürüm 1903 (18362) ve üstü
MinVersion=10.0.18362
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
UninstallDisplayName={#AppName}
; Güncelleme = eski kurulumun üstüne: önceki klasör/yetki/grup yeniden kullanılır, bu sayfalar atlanır
UsePreviousAppDir=yes
UsePreviousGroup=yes
UsePreviousPrivileges=yes
DisableDirPage=auto
DisableProgramGroupPage=auto
; Çalışan mpv.exe / python.exe dosyaları kilitler: kapatılır (sessiz kurulumda da)
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "tr"; MessagesFile: "compiler:Languages\Turkish.isl"

[Files]
; models\yolox_s.onnx, models\yolov8s-pose.onnx (eklem takibi; AGPL: yalnız kişisel kullanım) ve ffmpeg\ffmpeg.exe
; (dışa aktarma; GPL) pakete CI'da konur (models\manifest.json + scripts\verify_models.py denetler).
Source: "dist\TakipliOynat\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[InstallDelete]
; Güncellemede eski sürümden artakalan dosyalar çalışmayı bozmasın (kullanıcı verisi %APPDATA%'da, models\ ve
; mpv\portable_config\watch_later korunur)
Type: filesandordirs; Name: "{app}\python"
Type: filesandordirs; Name: "{app}\tracker"
Type: filesandordirs; Name: "{app}\ffmpeg"
Type: filesandordirs; Name: "{app}\mpv\portable_config\scripts"
Type: filesandordirs; Name: "{app}\mpv\portable_config\script-modules"

[Dirs]
; Modeller kullanıcı tarafından eklenir; kaldırmada silinmesin
Name: "{app}\models"; Flags: uninsneveruninstall

[Icons]
Name: "{group}\{#AppName} (canlı)"; Filename: "{app}\mpv\mpv.exe"; Parameters: "--player-operation-mode=pseudo-gui"; WorkingDir: "{app}"
Name: "{group}\{#AppName} (offline, video sürükle)"; Filename: "{app}\Offline.bat"; WorkingDir: "{app}"; IconFilename: "{app}\mpv\mpv.exe"
Name: "{group}\Modeller klasörü"; Filename: "{app}\models"
Name: "{group}\Üçüncü taraf lisanslar"; Filename: "notepad.exe"; Parameters: """{app}\THIRD_PARTY_NOTICES.md"""
Name: "{group}\Kaldır"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\mpv\mpv.exe"; Parameters: "--player-operation-mode=pseudo-gui"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Masaüstü kısayolu oluştur"

[Run]
Filename: "{app}\mpv\mpv.exe"; Parameters: "--player-operation-mode=pseudo-gui"; WorkingDir: "{app}"; Description: "Takipli Oynat'ı şimdi başlat"; Flags: postinstall nowait skipifsilent

[UninstallDelete]
; Çalışma sırasında oluşan .pyc önbellekleri
Type: filesandordirs; Name: "{app}\tracker\__pycache__"
Type: filesandordirs; Name: "{app}\python"
