# YOLOX-S person modelini (Apache-2.0, Megvii) indirip -Target klasorune koyar; kurulumla birlikte gelir,
# kullanici elle model kopyalamaz. Apache-2.0 geregi lisans metni de models\YOLOX_LICENSE.txt olarak konur.
# -Sha256 verilirse (ya da YOLOX_S_SHA256 ortam degiskeni) dosya ozeti dogrulanir; verilmezse ozet gunluge yazilir
# (dogrulayip workflow'daki YOLOX_S_SHA256'ya yapistir). Indirme hatasinda derleme DURUR (model olmadan setup.exe cikmaz).
param(
  [string]$Target = "dist/TakipliOynat/models",
  [string]$Url = "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_s.onnx",
  [string]$LicenseUrl = "https://raw.githubusercontent.com/Megvii-BaseDetection/YOLOX/main/LICENSE",
  [string]$Sha256 = $env:YOLOX_S_SHA256
)
$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force $Target | Out-Null
$dst = Join-Path $Target "yolox_s.onnx"
Write-Host "Indiriliyor: $Url"
Invoke-WebRequest $Url -OutFile $dst -Headers @{ "User-Agent" = "takipli-oynat-ci" }
$size = (Get-Item $dst).Length
# yolox_s.onnx ~35 MB; HTML hata sayfasi/bos dosya gelirse durdur
if ($size -lt 10MB) { throw "yolox_s.onnx beklenenden kucuk ($size bayt): indirme bozuk ya da adres degismis ($Url)" }
$sha = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLower()
Write-Host "SHA256 yolox_s.onnx: $sha ($([math]::Round($size / 1MB, 1)) MB)"
if ($Sha256 -and $sha -ne $Sha256.ToLower()) { throw "yolox_s.onnx SHA256 uyusmuyor (beklenen $Sha256, gelen $sha)" }
Invoke-WebRequest $LicenseUrl -OutFile (Join-Path $Target "YOLOX_LICENSE.txt") -Headers @{ "User-Agent" = "takipli-oynat-ci" }
Write-Host "Model hazir: $dst"
