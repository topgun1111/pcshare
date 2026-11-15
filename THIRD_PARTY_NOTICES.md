# Üçüncü taraf bileşenler ve lisanslar — Takipli Oynat

Bu belge, Takipli Oynat paketinde **dağıtılan** ve kullanıcının **ayrıca edinmesi gereken**
bileşenleri ve lisans yükümlülüklerini özetler. Hukuki görüş değildir; dağıtmadan önce her
bileşenin kendi lisans metnini kontrol et. Takipli Oynat kodunun kendi lisansı proje sahibi
tarafından belirlenir (depoda `LICENSE` dosyası yoksa tüm haklar saklıdır).

## 1. Pakette dağıtılanlar

| Bileşen | Lisans | Nasıl dahil | Yükümlülük |
|---|---|---|---|
| **mpv** (`mpv\mpv.exe`, shinchiro Windows derlemesi) | GPL-2.0+ (derlemeye göre GPL-3.0 olabilir; LGPL derlemesi de vardır) | Değiştirilmeden, ayrı çalıştırılabilir; Python ile IPC/Lua üzerinden konuşur (bağlama yok) | Lisans metni ve **kaynak koduna erişim**: bkz. Madde 3. Derleme kimliği `mpv\BUILD_INFO.txt` |
| **Python 3.11** (embeddable, `python\`) | PSF License 2.0 | Değiştirilmeden (yalnız `_pth` yapılandırması) | `python\LICENSE.txt` kalmalı |
| **NumPy** | BSD-3-Clause (tekerlekteki OpenBLAS BSD-3, libgfortran GPL-3 + GCC Runtime Exception) | pip ile `python\Lib\site-packages` | Tekerlekteki `LICENSE.txt` kalmalı |
| **opencv-python-headless** | Sarmalayıcı MIT; OpenCV Apache-2.0; tekerlek FFmpeg (LGPL-2.1+) ve başka kütüphaneler içerir (`LICENSE-3RD-PARTY.txt`) | pip ile | Lisans dosyaları kalmalı; FFmpeg DLL'leri değiştirilebilir (dinamik) bırakılmalı |
| **ONNX Runtime — DirectML** (`onnxruntime-directml`) | MIT; `DirectML.dll` Microsoft yeniden dağıtılabilir bileşeni (tekerlekte `ThirdPartyNotices.txt`) | pip ile | Tekerlekteki bildirimler kalmalı |
| **YOLOX-S modeli** (`models\yolox_s.onnx`, Megvii-BaseDetection/YOLOX) | Apache-2.0 | Derlemede (`scripts/fetch_model.ps1`) resmî sürümden indirilir, değiştirilmeden kurulumla birlikte gelir; böylece kurulumdan sonra elle model kopyalamak gerekmez | Lisans metni `models\YOLOX_LICENSE.txt` olarak pakette kalmalı. COCO verisinin kendi koşulları ayrıca geçerlidir |
| **YOLOv8s-pose modeli** (`models\\yolov8s-pose.onnx`, Ultralytics) | **AGPL-3.0** | Derlemede `scripts/export_pose.py` ile ONNX'e çevrilir (eklem takibi); **kişisel kullanım** — başkalarına dağıtırsan AGPL şartları geçerli olur | Dosyayı dağıtırsan kaynak/lisans yükümlülüğü; bkz. Madde 2 |
| **YOLO11m-pose modeli** (`models\\yolo11m-pose.onnx`, `...-fp16.onnx`; isteğe bağlı, CI'da `scripts/export_pose.py` ile üretilir) | **AGPL-3.0** | `yolov8s-pose` ile aynı koşullar: **kişisel kullanım**; dağıtırsan AGPL | Bkz. Madde 2 |
| **OSNet x0.25 Re-ID modeli** (`models\\osnet_x0_25.onnx`; isteğe bağlı, CI'da `scripts/export_reid.py` ile üretilir) | torchreid kodu MIT; **ağırlık: ImageNet ön eğitimi (ImageNet kullanım koşulları, araştırma/ticari olmayan)** | Derlemede ONNX'e çevrilir; Re-ID verisiyle (Market-1501 vb.) eğitilmemiştir. **Kişisel kullanım**; dağıtmadan önce ağırlık/veri lisansını doğrula | Dosyayı dağıtırsan veri lisansı yükümlülüğü; bkz. Madde 2 |
| **RTMPose-wholebody** (`models\\rtmpose-wholebody.onnx`; isteğe bağlı, CI'da `scripts/export_wholebody.py` ile OpenMMLab'ın hazır ONNX arşivinden indirilir) | MMPose/RTMPose kodu Apache-2.0; **ağırlık: COCO-WholeBody / UBody eğitim verisi koşullarına tabi** | Değiştirilmeden ONNX Runtime ile çalışır; el/yüz/ayak parmağı (133 nokta). **Kişisel kullanım**; dağıtmadan önce veri lisansını doğrula | Apache-2.0 lisans metni/atıf; veri lisansı yükümlülüğü (Madde 2) |
| **ffmpeg** (`ffmpeg\\ffmpeg.exe`, BtbN FFmpeg-Builds win64 **GPL** derlemesi; libx264 içerir) | GPL-2.0+/GPL-3.0 (x264: GPL-2.0+; ffmpeg çekirdeği LGPL-2.1+) | Değiştirilmeden, ayrı çalıştırılabilir; yalnız `export` komutu alt süreç olarak çalıştırır (bağlama yok). `scripts/fetch_ffmpeg.ps1` indirir | Lisans metni `ffmpeg\\FFMPEG_LICENSE.txt`; **kaynak teklifi**: Madde 3. Derleme kimliği `ffmpeg\\BUILD_INFO.txt` |
| **Inno Setup** (yalnız derleme aracı) | Inno Setup License (serbest) | Kurulum programına küçük çalışma zamanı gömülür | Ek yükümlülük yok |

Python paketlerinin tam lisans listesi için paketlenmiş ortamda:
`python\python.exe -m pip list` ve her paketin `*.dist-info\LICENSE*` dosyalarına bak
(`build_embedded.py` bu dosyaları silmez; yalnız önbellek/test/pip kısımlarını ayıklar).

## 2. Diğer modeller (pakette DAĞITILMAZ; kullanıcı isterse kendisi ekler)

**Bu yapı kişisel kullanım içindir.** Pakette YOLOX-S (Apache-2.0) ve eklem takibi için
`yolov8s-pose.onnx` (Ultralytics, **AGPL-3.0**; CI'da `scripts/export_pose.py` ile üretilir) bulunur.
Paketi başkalarına dağıtırsan AGPL şartları geçerli olur (pose modelini çıkar ya da kaynağı aç).
Re-ID/numara modelleri depoda yoktur; lisans sorumluluğu modeli edinen/dağıtana aittir.

**Planlı (ürün amacı: tüm modeller exe ile gelsin)**: Re-ID (OSNet) ağırlıkları, daha güçlü pose (YOLO11-pose, AGPL-3.0) ve tüm-vücut pose (RTMPose-wholebody, O2: CI'da isteğe bağlı eklendi; COCO-WholeBody ağırlık lisansı eğitim verisine bağlı) pakete konursa bu tablo ve Madde 1 **o sürümle birlikte** güncellenmeli; model eklenmeden önce lisansı doğrulanır. Bu yapı kişisel kullanım içindir; tüm modelleri başkalarına dağıtmak AGPL ve veri kümesi koşullarını devreye sokar.

| Model | Lisans | Not |
|---|---|---|
| **YOLOX** (`yolox_s.onnx`, Megvii) — **önerilen, pakette gelir** | Apache-2.0 | Takipli Oynat'ta desteklenir (`model_type=yolox`, dosya adında "yolox" varsa otomatik). Ticari/kapalı dağıtım için uygun yol. COCO verisinin kendi koşulları ayrıca geçerlidir |
| **YOLOv8 / YOLO11** (Ultralytics) | **AGPL-3.0** (ya da ücretli Ultralytics Enterprise lisansı) | Takipli Oynat `ultralytics` paketini kullanmaz/içermez; yalnız dışa aktarılmış ONNX çıktısını okur. Modeli (ve türevlerini) **dağıtırsan** AGPL şartları (kaynak açma) devreye girer; kişisel/okul içi kullanım dağıtım sayılmaz — emin değilsen YOLOX kullan |
| **OSNet** (Re-ID, `osnet_x0_25.onnx`; torchreid) | Kod MIT; **ağırlıklar eğitim verisine bağlıdır** | Market-1501/MSMT17 gibi veri kümeleri araştırma amaçlı/ticari olmayan koşullar taşıyabilir. Dağıtmadan önce ağırlığın kaynağını ve veri lisansını doğrula |
| **Forma numarası modeli** (`jersey_number.onnx`) | Eğiten kişiye bağlı | Projede hazır model yoktur (`tracker/number.py` sözleşmesi) |

## 3. GPL (mpv) kaynak kodu teklifi

mpv, `mpv\mpv.exe` olarak **değiştirilmeden** dağıtılır. Kaynak kodu ve derleme betikleri:
- mpv: https://github.com/mpv-player/mpv
- Derleme: https://github.com/shinchiro/mpv-winbuild-cmake (tam sürüm/etiket `mpv\BUILD_INFO.txt`'de)

Bu paketi dağıtan kişi, paketle birlikte aldığı mpv ikilisinin karşılık gelen kaynak kodunu,
paketi aldıktan sonra **en az üç yıl** boyunca, dağıtım maliyetini aşmayan bir bedelle isteyen
herkese sağlamayı yazılı olarak taahhüt eder (GPL-2.0 madde 3(b)); kolaylık için yukarıdaki
bağlantılar ve BUILD_INFO.txt'deki sürüm bilgisi bu teklifin parçasıdır. Daha güvenli yol: indirilen
mpv sürümünü sabitleyip (`fetch_deps.ps1 -Tag <sürüm>`) kaynak arşivini paketle birlikte yayımlamak.

ffmpeg (ve x264) için de aynı teklif geçerlidir: kaynak kodu https://ffmpeg.org/download.html ve
https://www.videolan.org/developers/x264.html adreslerindedir; derleme betikleri
https://github.com/BtbN/FFmpeg-Builds (tam adres/özet `ffmpeg\\BUILD_INFO.txt`'de).

## 3b. Paket bütünlüğü

`models\\manifest.json` pakette bulunması zorunlu model/araç dosyalarını (boyut, lisans, isteğe bağlı
SHA256) listeler; `scripts/verify_models.py` derlemede denetler, `models\\SHA256SUMS.txt` kurulan
dosyaların özetini verir. Yeni bir model (Re-ID, tüm-vücut pose...) eklenirken **önce** manifest ve bu
belge (lisansıyla) güncellenir.

## 4. Takipli Oynat kodu ile ilişki

- `tracker/` içindeki kod bu projeye özgüdür; Ultralytics/YOLOX/torchreid kodu **kopyalanmamıştır**
  (çözücüler yalnızca modellerin belgelenmiş çıktı biçimini okur).
- AGPL bileşen (Ultralytics modeli) yalnız kullanıcının kendi sağladığı dosyadır; ağ üzerinden
  hizmet olarak sunulmaz.
- Üçüncü taraf ticari markalar (mpv, Python, OpenCV, ONNX, YOLO) ilgili sahiplerine aittir.

## 5. Android sürümü (`android/`)

Android uygulaması (`com.takipli.oynat`) Windows paketinden ayrı bir APK'dır; `make_package.py` `android/` klasörünü
Windows paketine **koymaz**. APK'ya giren bileşenler:

| Bileşen | Lisans | Not |
|---|---|---|
| **ONNX Runtime Android, QNN sürümü** (`com.microsoft.onnxruntime:onnxruntime-android-qnn`) | MIT | CPU + Qualcomm Hexagon (HTP) sağlayıcısı |
| **Qualcomm QNN çalışma zamanı** (`com.qualcomm.qti:qnn-runtime`, ORT-QNN ile gelir) | **Qualcomm'un kendi lisans koşulları** (açık kaynak değil) | Dağıtmadan önce Qualcomm AI Stack lisans metnini oku; yalnız kişisel kullanımda sorun çıkarmaz |
| **OpenCV Android** (`org.opencv:opencv`) | Apache-2.0 | Kamera hareketi (optik akış) için |
| **AndroidX Media3** (ExoPlayer, Transformer, Effect) | Apache-2.0 | Önizleme oynatıcısı ve dışa aktarma (donanım kodlayıcı) |
| **Jetpack Compose, AndroidX core/activity** | Apache-2.0 | Arayüz |
| **Kotlin standart kitaplığı, kotlinx.coroutines, kotlinx.serialization** | Apache-2.0 | |
| **YOLOv8s-pose / YOLO11m-pose** (`assets/models/*.onnx`, Ultralytics; CI'da `scripts/export_pose.py` ile üretilir) | **AGPL-3.0** | **Kişisel kullanım** (karar: `handover-android.md`, Karar günlüğü). APK bu modelleri içerdiği için **herkese açık dağıtılmaz**: CI imzalı APK'yı Release'e yalnız **özel (private)** depoda ekler; herkese açık depoda yalnız workflow artifact olarak kalır. Dağıtmak istersen önce lisansı uygun bir pose modeline geç (kod **ve** ağırlık lisansını doğrula) |

Not: Android sürümü yalnız `arm64-v8a` içindir. Sürüm numarası PC ile ortak `VERSION` dosyasından gelir.
Android arayüzünde (Ana ekran) kısa bir lisans özeti vardır; ayrıntı bu belgededir.
