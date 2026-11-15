# Takipli Oynat — mpv Tabanlı Nesne Takipli Otomatik Zoom

Oynatma sırasında seçilen bir kişiyi, vücut parçasını (ayak, bacak…) ya da herhangi bir nesneyi takip edip `video-zoom` / `video-pan` ile otomatik yakınlaştıran Windows video oynatıcı paketi.

## Hedef
- Ekranda seçilen kişiyi **ya da vücut parçasını** (ayak, bacak, el…) takip et, kameraman hissiyle zoom/pan uygula
- Tek `setup.exe` ile kurulum, internetsiz çalışma
- Açık kaynak bileşenler üzerine kurulu, mpv çekirdeğine dokunmadan

## Amaç ve kapsam
Takipli Oynat **spora özel değildir**. Amaç: videoda **insanları ve özellikle vücut parçalarını (ayak, bacak, el, baş… ya da kişinin kendisini) aynı anda takip edip yakınlaştırmak**. Futbol gibi sporlar yalnız bir örnektir; takım rengi/forma numarası gibi sinyaller isteğe bağlı ek araçlardır.

Hedef davranışlar:
- **Hızlı hareket**: kişi ya da vücut parçası hızlı hareket etse de hedef kaybedilmez.
- **Kare dışına çıkıp geri gelme**: hedef kareden çıkınca takip bırakılmaz; hedef (kişi ya da parça) geri girince **kaldığı yerden** sürer.
- **Offline iş akışı**: videoyu açıp önce **noktayı/parçayı seç** → program videoyu tarar, nesneyi takip eder → sonucu **ayrı bir takipli/yakınlaştırılmış video olarak kaydeder**.
- **Her şey exe ile gelir**: gerekli tüm model ve uzantılar kurulum paketinin içindedir; kurulumdan sonra elle bir şey indirmek gerekmez.

### Hedef ↔ mevcut durum (kodla karşılaştırıldı)
| Hedef | Şu anki durum | Yol haritası |
|---|---|---|
| Kişi + vücut parçası takibi | Kişi: ByteTrack. Parça: yalnız **canlı** modda, YOLOv8s-pose (COCO-17: ayak bileği, diz, bilek…) + **O2: `rtmpose-wholebody.onnx` varsa el/parmak, yüz ve ayak parmağı noktaları da (133 nokta; canlı, sağ tık)** | B1–B3 (Faz 2/5; B3: canlı ön ayarlar — **Parça** menüsü / `G` tuşu: baş, gövde, kollar, bacaklar, ayaklar, eller; kişiye sağ tık seçili parçayı izler, hedefken parça yeniden taramadan değişir); offline tüm-vücut AÇIK |
| Aynı anda birden çok hedef | Tek hedef | T1 (Faz 5), T2 |
| Hızlı hareket | Kalman + öngörü; parça kayması yalnız görünüm EMA'sıyla yumuşatılır; gerçek videoda doğrulanmadı | V1 (ölç), B2 (One-Euro, kayıp zinciri) |
| Kare dışı → geri girişte devam | Nesne/dikdörtgen: kamera telafili `WorldPointTracker` kare dışında ilerletir, geri girince şablonla bulur. Eklem (canlı): kamera telafili öngörü `kp_hold_s` (1.5 sn) sürer, kişi geri girince yeniden bağlanır (B2). Kişi: ByteTrack ID + isteğe bağlı Re-ID. Gerçek videoda doğrulanmadı | B2 (kare dışı süreklilik), G1, G3 |
| Offline: noktayı seç → tara → takip et | Offline tarama yalnız **kişi** içindir (`--target-id` ya da `--click` ile kişi); nokta/dikdörtgen/parça ile offline yok; sonuç `track.json` + `views.json` (mpv'de `o` ile oynar) | A1 (+B1), A2, A3 — Faz 3 |
| Sonucu **video olarak kaydet** | **Var (E1)**: Dosya → «Takip kamerası dışa aktar…» / `export VIDEO` | E2 (boyut/oran, PiP, kuyruk) |
| Tüm model/uzantılar exe ile | `yolox_s.onnx` + `yolov8s-pose.onnx` + `ffmpeg\\ffmpeg.exe` (video dışa aktarma) pakette; `models\\manifest.json` + `scripts/verify_models.py` CI'da denetler (I1 tamam). **Re-ID (OSNet), forma numarası modeli pakette değil** (hazır ONNX yok; G3 CI'da dışa aktaracak); tüm-vücut poz (RTMPose-wholebody) CI'da isteğe bağlı üretilir (O2) | ~~I1~~ ✅, ~~G3~~ ✅ (OSNet CI'da üretilir, isteğe bağlı; `reid_live=auto`), ~~G2~~ ✅ (`single_pose`, `pose-bench`; karar Windows ölçümünde), ~~O2~~ ✅ (canlı; `wholebody=auto`) — Faz 4 |

Öncelik sırası ve parça numaraları (B1, B2, A1, E1, I1, G3…) `handover.md` → «Ürün amacı ve öncelikler» ve «Yol haritası → Parts» bölümündedir.

## Neden mpv (VLC değil)
- Runtime property'ler: `video-zoom` (log2), `video-pan-x`, `video-pan-y`
- IPC (Windows named pipe) + Lua + JSON ile derlemesiz genişletme
- VLC'de aynı iş C/C++ `video_filter` modülü + OpenCV contrib build gerektirir; ağır inference video thread'ini bloklar, A/V senkronu bozulur

## Mimari
- **Detector**: YOLOX (Apache-2.0, önerilen) veya YOLOv8/YOLO11 (AGPL-3.0) person modeli, ONNX
- **Tracker**: ByteTrack (kendi uygulaması, `bytetrack.py`; BoT-SORT yok), kalıcı ID
- **Re-ID**: OSNet embedding ile örtüşme sonrası ID kurtarma
- **Ek sinyaller**: takım rengi (kümeleme), forma numarası (isteğe bağlı sınıflandırıcı model; OCR değil)
- **Sınıfsız izleyici**: sağ tıkla herhangi bir nesne (Lucas-Kanade + şablon, kamera hareketi telafili); detector gerekmez
- **Eklem takibi**: YOLOv8s-pose ile ayak bileği/diz/bilek vb. (isteğe bağlı model)
- **Smoothing**: EMA/Kalman, dead-zone, hız yönünde lookahead
- **Kayıp yönetimi**: son konumda bekle → kademeli zoom-out
- **Hedef seçimi**: sağ tuş (`MBTN_RIGHT`): tık = nesne/eklem (`track-point`), sürükle = dikdörtgen (`track-rect`); koordinat `osd-dimensions` ile video-normalize edilir. Sol tuş ekrandaki düğmeler içindir; ayrıca zoom açıkken **sol tuşu basılı tutup sürükleyerek videoyu serbestçe kaydırırsın** (pan; hedefe kilitliyken ya da offline açıkken kapalı, önce `X` ile hedefi bırak)
- **Çıkış**: IPC ile `set_property video-zoom/pan-x/pan-y`

## Çalışma Modları
- **Offline (önerilen)**: video bir kez taranır → `track.json` (frame → bbox, ID) → Lua `time-pos` ile JSON'dan zoom/pan uygular. A/V senkronu bozulmaz, hedef sonradan düzeltilebilir
- **Canlı**: mpv açılınca `track.lua` Python süreci `tracker live --wait 30`'u (konsolsuz, `pythonw.exe`) kendiliğinden başlatır. Süreç kareleri mpv'den `screenshot-raw` ile alır (en çok `live_fps`, varsayılan 10), kendi ONNX detector + ByteTrack hattından geçirir, sonucu IPC ile `video-zoom`/`video-pan` olarak yazar. Gecikme Kalman hızıyla ileri tahminle (`lookahead_s`) telafi edilir. Ultralytics kullanılmaz. Sağ tık nesne/eklem takibinde detector çalışmaz (optik akış + şablon)

## Dosya Yapısı
```
TakipliOynat/
├─ mpv/                          # portable mpv (mpv.exe + portable_config/)
│  └─ portable_config/
│     ├─ mpv.conf                # input-ipc-server, keep-open, osd-level
│     ├─ input.conf              # tuş bağları (t, r, x, o, k, +/-, Ctrl+…, F10, Ctrl+O, sağ/çift tık)
│     ├─ script-modules/trackmenu.lua  # üst menü çubuğu motoru (M1; saf mantık)
│     ├─ script-modules/trackfiles.lua # Dosya menüsü yardımcıları (M2; saf mantık, PowerShell diyalogları)
│     └─ scripts/track.lua       # sağ tık/sürükleme seçimi, OSD/HUD, zoom kontrolü, menü bağlama, Python'u başlatma
├─ tracker/                      # Python paketi (python -m tracker ...)
│  ├─ main.py, __main__.py       # CLI: ping, view, listen, detect, live, offline, offline-view, bench
│  ├─ ipc.py, clicks.py, frames.py, geometry.py, smooth.py, config.py, settings.py
│  ├─ detector.py                # YOLOv8/YOLOX ONNX çözücüleri + cihaz seçimi
│  ├─ bytetrack.py, pipeline.py  # ByteTrack + detector birleşimi
│  ├─ point.py                   # sınıfsız nesne izleyici (sağ tık; optik akış + şablon + kamera telafisi)
│  ├─ export.py                  # takip kamerası videosu (kırp+ölçekle → ffmpeg x264, ses aynen)
│  ├─ scan.py                    # hedefle aç + arka plan taraması (seçim çözümü, ilerleme, sürdürme)
│  ├─ gmc.py                     # kamera hareketi telafisi (LK + RANSAC; ByteTrack, offline, nokta/eklem)
│  ├─ pose.py                    # eklem (vücut parçası) takibi (YOLOv8-pose)
│  ├─ reid.py, team.py, number.py  # OSNet Re-ID, takım rengi, forma numarası
│  ├─ live.py                    # canlı mod (hedef seçimi, kayıp yönetimi, HUD)
│  ├─ offline.py                 # offline tarama → track.json / views.json
│  ├─ bench.py                   # kıyaslama aracı (P23)
│  └─ single.py                  # tek takip programı kilidi (tracker.lock)
├─ models/                       # ONNX modeller (MODELLER.txt; yolox_s/pose pakette gelir)
├─ launcher/                     # Baslat.bat (yalnız mpv), Offline.bat (sürükle-bırak offline)
├─ scripts/                      # make_package.py, build_embedded.py, fetch_deps.ps1, fetch_model.ps1, export_pose.py
├─ tests/                        # pytest + texlua ile Lua harness testleri
├─ .github/workflows/            # ci.yml (ruff + pytest, ubuntu/windows), build.yml (paket + installer)
├─ installer.iss                 # Inno Setup
├─ VERSION, requirements.txt, requirements-dev.txt, pyproject.toml
├─ THIRD_PARTY_NOTICES.md        # üçüncü taraf lisanslar + GPL kaynak teklifi
└─ handover.md                   # parça parça ilerleme notu + yol haritası
```
(Pakete `tests/`, `scripts/` ve `.github/` girmez; `make_package.py` bkz. `scripts/make_package.py`.)

## Paketleme
- **Installer**: Inno Setup / NSIS → tek `setup.exe`, kısayol "Takipli Oynat" (mpv + Python süreci birlikte)
- **Boyut**: PyTorch ~2–3 GB; ONNX Runtime + ONNX model ~300–500 MB
- **GPU**: `onnxruntime-directml` (NVIDIA/AMD/Intel evrensel); NVIDIA için `onnxruntime-gpu` (+~1 GB CUDA/cuDNN)
- **Kullanılan yol**: gömülü Python (`scripts/build_embedded.py`) + Inno Setup; PyInstaller kullanılmıyor

## Lisans
Ayrıntılı denetim: **`THIRD_PARTY_NOTICES.md`** (pakete ve Başlat menüsüne de konur; hukuki görüş değildir).
- **Dağıtılanlar**: mpv (GPL-2.0+, değiştirilmeden; kaynak teklifi + `mpv\BUILD_INFO.txt`), Python (PSF), NumPy (BSD), OpenCV (Apache-2.0; FFmpeg LGPL), ONNX Runtime-DirectML (MIT; DirectML Microsoft bileşeni).
- **Modeller dağıtılmaz** (`installer.iss` `models\*.onnx` hariç):
  - **YOLOX** (Apache-2.0) — önerilen, dağıtıma uygun; **kurulum paketine CI'da (`scripts/fetch_model.ps1`) konur**. Dosya adında "yolox" varsa otomatik seçilir (`model_type`: auto | yolov8 | yolox).
  - **YOLOv8/YOLO11** (Ultralytics, AGPL-3.0) — kişisel/okul içi kullanımda sorun yok; modeli başkalarına dağıtırsan AGPL şartları geçerli.
  - **OSNet** ağırlıkları eğitim verisinin (Market-1501 vb.) koşullarına tabidir; dağıtmadan önce doğrula.
- Takipli Oynat kodunun kendi lisansını proje sahibi seçer (depoda `LICENSE` yoksa tüm haklar saklıdır; `make_package.py` `LICENSE` varsa pakete koyar).
- Kodda `ultralytics` içe aktarılmaz; çözücüler yalnız ONNX çıktı biçimini okur.

## Kod Boyutu (gerçek)
- `tracker/*.py`: ~4100 satır (en büyükleri `live.py` ~590, `main.py` ~440, `point.py` ~380, `detector.py` ~370, `reid.py` ~340)
- Lua: `track.lua` + `trackmenu.lua` + `trackfiles.lua`
- `tests/`: 230 test (Python + `texlua` ile Lua harness; `texlua`/`lua` yoksa Lua testleri atlanır — CI'da şu an yüklü değil)

## Bilinen Riskler
- Benzer görünümlü kişilerin (ör. aynı formalı) kesişmesinde ID kayması → Re-ID (+ isteğe bağlı renk/numara sinyali); Re-ID modeli henüz pakette yok
- Hızlı hareket / kare dışına çıkış → mevcut telafiler gerçek videoda doğrulanmadı (bkz. «Hedef ↔ mevcut durum»)
- Küçük hedef (30–60 px) → tam çözünürlük inference
- Ani kamera pan'ı → bbox sıçraması → Kalman + dead-zone
- CPU-only makinelerde canlı mod düşük fps → offline mod

## Yol Haritası
Ayrıntılı plan ve sıra `handover.md` → "Yol haritası" bölümündedir (Faz 0–6; yapıldı: P23, M1, M2, V1 (CI kısmı), B1, B2 (canlı), G1, A1, E1, A3, A2, I1, G3, G2, O2 (canlı tüm-vücut poz), G4 (çekim kesmesi algılama); sıradaki: **B3** vücut parçası ön ayarları + menü; V1'in Windows gerçek-klip doğrulaması açık). Tamamlanan: **Parça 1–15** + ek parçalar 16–22 (düğme paneli, sağ tık/zoom kontrolü, nesne ve eklem takibi, dikdörtgen seçimi) + P23, M1, M2; sürüm dosyaları **1.2.0**. Parça 1–15: (iskelet, tıklama, detector, ByteTrack, canlı mod, hedef seçimi, Kalman + lookahead, kayıp yönetimi, offline mod, Lua offline oynatma, Re-ID, takım rengi + isteğe bağlı forma numarası, canlı ayar tuşları + durum etiketi,  Python + tam installer, lisans denetimi + YOLOX alternatifi + release).

### Sağ tık: herhangi bir nesneyi takip (Parça 18)
`tracker/point.py`: sağ tıklanan nokta için kutu çıkarılır (aynı renkteki bitişik bölge; olmazsa karenin %6'sı kadar kare), sonra her karede Lucas-Kanade optik akışı kutuyu taşır, şablon benzerliği (NCC) yanlış şeye kaymayı yakalar, tutmazsa son konum çevresinde şablon aranır; bulunamazsa "hedef kayıp" (bekle → kademeli zoom-out) ve nesne geri gelirse takip sürer. Kamera hareketi telafisi (Parça 19, `WorldPointTracker`): arka plan köşelerinden kareden kareye kamera dönüşümü birikir; hedef dünya koordinatında tutulur, kare dışına çıksa/örtülse bile sönümlü hızla ilerletilir ve geri gelince şablonla yeniden bulunur. Detector çalışmaz (CPU'da daha hızlı); `models\` boşsa bile sağ tık çalışır. Ayarlar (`config.json`): `point_box` (0.06), `point_refine` (true), `point_min_score` (0.45; yükselirse kayıp daha erken ilan edilir), `point_max_side` (640). Zoom miktarı `+`/`−` (doluluk) ile ayarlanır. Takip edilen noktanın üstünde küçük bir **artı (nişangâh)** gösterilir: tıklar tıklamaz sarı (tıklama alındı), takip programı doğrulayınca beyaz; 2 sn içinde doğrulanmazsa ekranda uyarı çıkar (Python her karede merkezi `track-dot` ile yollar, Lua mpv'nin anlık zoom/pan'ına göre çizer); hedef kayıpsa ya da bırakılınca artı kaybolur — çalışıp çalışmadığını buradan görürsün.
Sınırlar: tek tıklamadan nesnenin boyutu kesin bilinemez (küçük/dokusuz nesnede kutu fazla büyük ya da küçük olabilir → `+`/`−`, `point_box`); çok benzer nesnelerin iç içe geçtiği, nesnenin tamamen örtüldüğü ya da kareyi terk ettiği durumlarda kayıp ilan edilir (yeniden sağ tık). Offline mod (`offline --click`) hâlâ yalnız oyuncu içindir.

**Tek takip programı**: mpv her açılışında takip programını başlatır; aynı anda birden çok süreç kalmaması için `%APPDATA%\\TakipliOynat\\tracker.lock` kullanılır (son başlayan devralır, eskisi ≤ 1 sn'de kapanır). Ek ayarlar (`config.json`): `kp_hold` (6; eklem/kişi görünmeyince tolere edilen adım), `dot_lead_s` (0.08 sn; artıyı hız yönünde ileri çizer, 0 = kapalı). Konsolsuz çalışırken masaüstündeki `takipli-oynat-log.txt` 5 sn'de bir hız/gecikme/hedef satırı yazar.

## Offline mod (Parça 9)
```
python -m tracker offline video.mp4                       # tespit+izleme → video.mp4.track.json, en uzun izleri listeler
python -m tracker offline video.mp4 --target-id 7         # hedefle birlikte görünüm anahtar karelerini de yazar
python -m tracker offline video.mp4 --click 12.5 0.43 0.61  # 12.5. sn'de (nx, ny) noktasındaki kişi
python -m tracker offline-view video.mp4.track.json --list
python -m tracker offline-view video.mp4.track.json --target-id 3   # tespit YOK, saniyeler sürer
```
Şema `tracker/offline.py` başındadır (`views`: `[t, zoom, pan_x, pan_y]`).

### Kıyaslama (Parça 23)
```
python -m tracker bench video.mp4 --max-seconds 60 --out rapor.json   # tespit + süre ölçümü (ort/p50/p95/maks ms, fps)
python -m tracker bench video.mp4.track.json                          # yalnız analiz (tespit yok)
```
Ölçütler: kimlik sayısı, kısa izler, olası ID değişimi, hedefin görülme/kayıp yüzdesi, canlı-benzetimi ile offline görünüm arasındaki sapma ve titreme (`--target-id` yoksa en uzun iz). Diğer ortak bayraklar: `--model`, `--device`, `--fps`. Canlı modda ek bayraklar: `--wait SN` (mpv IPC'sini bekle), `--stats SN` (fps/gecikme satırı).

### Eklem (vücut parçası) takibi (Parça 22)
**Takip kamerası dışa aktarma (E1)**: `python -m tracker export VIDEO [--views F] [--out F] [--no-audio] [--crf 18] [--ffmpeg yol]` (ya da Dosya → «Takip kamerası dışa aktar…») `<video>.views.json` anahtar karelerini her kareye uygular (zoom = 2^z, merkez = 0.5 − pan, kenar boşluğu yok), kareleri ffmpeg'e ham BGR olarak verir (`libx264 -crf 18 -preset veryfast`, `yuv420p`, `+faststart`) ve özgün sesi aynen ekler. Çıktı `<video>.takipli.mp4`, boyut kaynakla aynı (çift). ffmpeg sırası: `--ffmpeg`, `TAKIPLI_FFMPEG`, `ffmpeg\\ffmpeg.exe` (python klasörünün yanı), PATH; yoksa sessiz yedek (`cv2.VideoWriter` mp4v, uyarı). İlerleme `<çıktı>.export.json`; `<çıktı>.export.cancel` iptal eder (yarım çıktı silinir). Hedefli aç + tara (A1) → dışa aktar = iki menü adımı.

**Hedefle aç + arka plan taraması (A1)**: Dosya → «Hedefle video aç + tara…» videoyu açıp duraklatır; ilk sağ tık (nokta) ya da sağ tuşla sürükleme (alan) canlı takibi hemen başlatır ve arka planda `python -m tracker offline VIDEO --select-point SN NX NY` (ya da `--select-rect SN NX1 NY1 NX2 NY2`) taramasını başlatır. Hedef tarama o ana varınca çözülür: nokta bir eklemin yakınındaysa **eklem**, değilse noktadaki **kişi**; alan kişiyi büyük ölçüde kapsıyorsa kişi, küçük alan içinde eklem varsa o eklem (pose modeli varsa taramaya otomatik eklenir). Sonuç `<video>.track.json` + `<video>.views.json`; bitince `o` tuşu offline takibi açar. İlerleme `<video>.track.json.scan.json`'a (durum, %, hedef) yazılır, her 100 örnekte `.partial.json` kesinti noktası alınır: `offline VIDEO --resume` kaldığı yerden sürdürür; `<çıktı>.scan.cancel` dosyası oluşturulursa tarama durur. Konsolsuz çalışırken günlük masaüstünde `takipli-tarama-log.txt`. Not: sınıfsız nesneler (kişi/eklem olmayan) offline taranmaz; seçim en yakın kişiye çözülür, yoksa hata.

**Tarama önbelleği (A3)**: `track.json` video imzasını (yol+boyut+değişme zamanı), örnek hızını ve model dosyalarını saklar; aynı video+ayarla `offline VIDEO --select-*` yeniden çalıştırılırsa tarama atlanır, hedef kayıtlı izlerden çözülür. Baştan taramak için `--rescan` ya da Dosya → «Yeniden tara (önbelleği yok say)». Başka videoya ait `.partial.json` sürdürülmez, başka videoya ait `.views.json` dışa aktarmada reddedilir. Oynatma açıkken tarama `scan_throttle_s` (0.15 sn/örnek) ile kısılır.

**Otomatik geçiş (A2)**: Takip → «Kaynak: Otomatik» (varsayılan) iken canlı takip son 5 sn'de yavaşsa (hız < `live_fps`×0.6), gecikmeliyse (>300 ms) ya da hedefi sık kaybediyorsa ve aynı videonun taranmış izi (`.views.json`, tarama bitmiş) hazırsa görünüm 0.6 sn'de yumuşakça taranmış ize geçer (OSD'de sebep). «Kaynak: Canlı» geçişi engeller, «Taranmış iz» offline'ı elle açar. Eşikler: `auto_window_s`, `auto_fps_ratio`, `auto_lag_ms`, `auto_lost_frac` (`auto_window_s=0` kapatır).

**Kamera hareketi telafisi (G1)**: `tracker/gmc.py` (`CameraMotion`) arka plan köşelerinden kareden kareye kamera dönüşümünü kestirir; ByteTrack tahmini (Kalman konum/boy/hız) bu dönüşümle kaydırılır, böylece kamera hızlı kayarken kişilerin kimliği kopmaz. Canlıda ve `offline` taramada açıktır; kişi kutuları köşe seçiminden dışlanır, kestirilemezse ya da çekim kesmesinde (kayma > kare genişliğinin %50'si) dönüşüm kimlik sayılır. Ayarlar (`config.json`): `gmc` (true; `false` eski davranış), `gmc_side` (480). Ek maliyet: karede ~birkaç ms (`live --stats` gecikmesine bak).

`models/yolov8s-pose.onnx` varsa yüklenir (yoksa sessizce kapalı). Sağ tık bir eklemin (`kp_tol`: kişi boyunun 0.12'si) yakınına yapılırsa o eklem izlenir (HUD: «Hedef: sol ayak bileği»), uzaksa nesne takibi. Ayarlar (`config.json`): `pose_model`, `pose_conf` (0.3), `kp_tol`, `kp_min_conf` (0.35), `kp_box` (0.10), `kp_hold` (6), `kp_hold_s` (1.5), `kp_world`, `kp_filter`, `kp_reacq_single`. Canlıda çalışır; offline için `offline VIDEO --pose` eklemleri `track.json`'a (sürüm 2) yazar, `--part "sol ayak bileği"` (ya da 0–16) görünümü o eklemden üretir (B1).

### Offline oynatma (Parça 10)
`offline ... --target-id N` (veya `offline-view`) `video.mp4.views.json` dosyasını da yazar. Bu dosya
videonun yanında dururken mpv'de **`o`** tuşu görünümü Python olmadan, `time-pos`'a göre sürer
(seek/duraklatmada da çalışır). `t` (canlı) açılınca offline kapanır ve tersi; `r` ikisini de sıfırlar.
`--out` ile farklı yola yazdıysan `*.views.json` dosyasını `<video dosya adı>.views.json` yap.

### Re-ID (Parça 11)
OSNet ONNX modelini `models/osnet_x0_25.onnx` olarak koy (torchreid ile dışa aktarılır; girdi 1x3x256x128 veya dinamik batch).
```
python -m tracker live --reid                      # hedef kaybolup ByteTrack yeni ID verirse görünüme göre geri bağlar
python -m tracker offline video.mp4 --reid         # izlerin ilk/son embedding'ini track.json'a yazar
python -m tracker offline-view video.mp4.track.json --target-id 7          # kayıtlı embedding varsa ID değişimlerini zincirler
python -m tracker offline-view video.mp4.track.json --target-id 7 --no-link
```
Ayarlar: `reid_thres` (0.6), `reid_margin` (0.05), `reid_link_gap_s` (20). Benzer formalı oyuncular karışabilir; belirsizse (marj yetmezse) bağlama yapılmaz.

### Takım rengi ve forma numarası (Parça 12)
```
python -m tracker live --reid --team                 # farklı takım renkli adaylar Re-ID'de elenir
python -m tracker live --reid --team --number        # + hedefle farklı numaralı adaylar elenir
python -m tracker offline video.mp4 --reid --team    # izlerin takımı track.json'a yazılır, zincirleme takımı gözetir
```
Takım yalnızca eleme yapar; etiket belirsizse aday elenmez. `--number` için `models/jersey_number.onnx` gerekir (girdi [N,C,H,W], çıktı [N,101]: 0–99 numara, 100 = yok; ayrıntı `tracker/number.py`). Ayarlar: `team_margin` (0.15), `team_min_samples` (40), `number_min_votes` (3).

### Canlı ayarlar ve durum etiketi (Parça 13)
Canlı mod çalışırken mpv'de: `Ctrl+↑/↓` doluluk, `Ctrl+→/←` tepki hızı, `Ctrl+PgUp/PgDn` öngörü, `Ctrl+0` varsayılanlar, `Ctrl+S` ayarları kaydet (`--config` ya da `tracker_config.json`), `h` durum özeti, `Ctrl+H` sol üst bilgi bloğunu gizle/göster. Kayıtlı ayarla başlamak için: `python -m tracker --config tracker_config.json live ...`.

### Sol üst bilgi bloğu ve çift tık
Takip açıkken sol üstte küçük harflerle tüm takip bilgisi görünür: durum (`Takip: ID 5`), **model** (`yolox_s.onnx (yolox, 640x640) · DirectML`), Re-ID/takım/numara ve poz modelleri (açıksa), kare hızı ve gecikme, hedef, kişi sayısı, tepki/öngörü/doluluk ayarları, zoom ve döndürme. `Ctrl+H` gizler/gösterir (`hud: false` config ile kalıcı kapatır). **Videoya çift sol tık** oynat/duraklat yapar (mpv'nin varsayılan çift tık = tam ekran yerine; tam ekran `F`).

### Üst menü çubuğu (M1)
Fareyi/parmağı oynatınca üstte menü çubuğu görünür (3 sn sonra gizlenir); **F10** de açar. **K** videoyu 90° döndürür (takip için önce 0°'ye getir): **Dosya · Görünüm · Takip · Araçlar · Ayarlar · Yardım**. Çalışan işlevler (takip, bırak, sıfırla, offline, zoom, HUD, tepki/öngörü/doluluk, kaydet, durum, çıkış) menüden de kullanılır; kısayolları yanında yazar, açık olanlar • ile işaretlidir. Henüz yazılmamış öğeler (Takip kamerası dışa aktar, Tam ekran menü öğesi, Otomatik tarama, Vücut parçası, Araçlar menüsündeki tarama/kıyaslama/modeller, Ayar penceresi, Kısayollar, Hakkında) gri görünür ve sonraki parçalarda (`yol haritası (handover.md)`) açılır; tam ekran şimdilik `F` tuşuyla. Açıkken ←/→/↑/↓, Enter, Esc ile gezilir; dışarı tıklamak kapatır.

### Dosya menüsü (M2)
**Dosya** menüsü ya da **Ctrl+O**: **Video aç…** (Windows dosya diyaloğu, son klasörde açılır), **Son açılanlar…** (son 10, listeden çift tık/Enter), **Klasör aç…** (klasör oynatma listesi olur), **Adres (URL) aç…**, **Görünümü kaydet / yükle** (videonun yanına `<video>.view.json`: o anki zoom/pan/döndürme; takip/offline kapalıyken yüklenir), **Videoyu kapat**, **Çıkış**. Dışa aktarma (E1) gridir. Son açılanlar ve son klasör `%APPDATA%\\TakipliOynat\\recent.json`'da tutulur. Diyaloglar PowerShell+WinForms ile açılır (mpv donmaz; gömülü Python'da tkinter yoktur). Yalnız Windows.

### Ekran üstü düğmeler (Parça 16)
Düğme paneli **varsayılan kapalıdır** (`track.lua` içinde `TRACK_SHOW_PANEL=true` ile açılır; kapalıyken menüdeki «Düğme paneli sabit» öğesi gri görünür). Açıkken mpv penceresinde fareyi/parmağı oynatınca sol üstte çıkar (3 sn sonra gizlenir; **Sabit** düğmesi ya da `Ctrl+B` ile sabitlenir): **Takip AÇIK/KAPALI**, **Bırak**, **Sıfırla**, **Offline**, **Zoom -/+**, **Tepki -/+**, **Öngörü -/+**, **Kaydet**, **Durum**. Zoom düğmeleri ayrı bir **ekran üstü zoom kontrolüne** taşındı (aşağıda). Sol tuşla oyuncu seçme Parça 21'de kaldırıldı; sol tuş yalnız imleç ekran üstü düğmenin üzerindeyken yakalanır. Klavye kısayolları aynen çalışır.

### Sağ tık ile takip noktası ve ekran üstü zoom kontrolü (Parça 17)
- **Sağ tık**: tıklanan nokta/**nesne** takip edilir — oyuncu olmak zorunda değil (top, araba, tahtadaki bir şekil...); takip kapalıysa kendiliğinden açılır (önce `t` basmak gerekmez). Nesnenin üstüne tekrar sağ tık bırakır, başka yere sağ tık hedefi değiştirir. (mpv'nin sağ tık = duraklat davranışı yerine geçer; duraklatma Boşluk tuşuyla.) Bu mod YOLO'ya ihtiyaç duymaz (bkz. Parça 18). **Sağ tuşu basılı tutup sürüklemek** (≥6 px) kırmızı çerçeveli bir **dikdörtgen** seçer (Parça 20): seçimin merkezi takip edilir, ekrandaki +/− seçili alana yakınlaşır; dikdörtgen her zaman nesne takibidir. Eski oyuncu-kimliği seçimi (sol tık, `track-click`) arayüzden kaldırıldı (Parça 21); Python tarafı mesajı hâlâ işler.
- **Zoom kontrolü**: pencerenin sağ kenarında ortada **+** / anlık zoom (ör. `x1.5`) / **−**. Fare/dokunma hareketinde, takip açıkken ya da zoom yapılmışken görünür (zoom varken panel gizlense de kalır). `+` ve `-` tuşları aynı işi yapar.
  - Hedef kilitliyken +/− hedefin kareyi doldurma oranını ayarlar (zoom'u takip sürer; değer etiketi canlı güncellenir).
  - Hedef yokken ya da takip kapalıyken +/− doğrudan video zoom'unu 1x–16x arasında 0.25 (log2) adımla değiştirir.
  - Offline oynatma açıkken zoom sabittir (uyarı verir).

### Kurulum ve başlatıcılar (Parça 14)
Installer (`TakipliOynat-Setup-x.y.z.exe`, Windows 10 1903+) gömülü Python, mpv, DirectML ve **YOLOX-S modeliyle (Apache-2.0)** gelir: kurduktan sonra elle bir şey kurmak ya da kopyalamak gerekmez, kurulum bitince "şimdi başlat" ile çalışır. Eklem (vücut parçası) takibi için `yolov8s-pose.onnx` (AGPL, yalnız kişisel kullanım) de pakete CI'da konur; sağ tık bir eklemin (ayak bileği, diz, bilek...) yakınına yapılırsa o eklem izlenir, uzaksa nesne izlenir. Re-ID/numara modelleri isteğe bağlıdır (`models\MODELLER.txt`); bunlar şimdilik yalnız komut satırından (`live --reid …`) ve `Offline.bat` ile kullanılır — mpv'nin otomatik başlattığı takip programı bayrak vermez.

**Güncelleme**: yeni `setup.exe`'yi çalıştırmak yeter; AppId aynı olduğundan eski kurulumun üstüne (aynı klasör/yetki) sorusuz kurulur, açık mpv/python kapatılır, eski artıklar (`python\`, `tracker\`, Lua betikleri) temizlenip yenisi yazılır. Ayarlar (`%APPDATA%\TakipliOynat\config.json`) ve `models\` içine kendi eklediğin dosyalar korunur. Sessiz: `TakipliOynat-Setup-x.y.z.exe /VERYSILENT /NORESTART`.
- **Takipli Oynat (canlı)** kısayolu (doğrudan `mpv.exe`) / `Baslat.bat` (yalnız mpv'yi açar; video sürükleyebilirsin): takip programını mpv içindeki `track.lua` kendiliğinden başlatır, mpv kapanınca biter; aynı anda tek takip programı çalışır (`tracker.lock`).
- **Offline** kısayolu / `Offline.bat`: videoyu üstüne sürükle → işlenir (`models\osnet_x0_25.onnx` varsa `--reid --team` ile) → oyuncu ID'sini yaz → mpv'de `o`.
- Ayarlar `%APPDATA%\TakipliOynat\config.json` dosyasına kaydedilir.
Paket derleme (CI): `python scripts/make_package.py` → `python scripts/build_embedded.py` → `scripts/fetch_deps.ps1` → `iscc installer.iss`.

### Release (Parça 15)
0. `VERSION` ile `tracker/__init__.py::__version__` aynı olmalı (`tests/test_license.py` ikisini de 1.2.0'a sabitler; sürümü yükseltirken testi de güncelle).
1. `main`'e push → CI (`ci.yml`) ve `build.yml` (ayrı artifact: **TakipliOynat-Setup** = `TakipliOynat-Setup-x.y.z.exe`, **TakipliOynat-Portable** = `TakipliOynat-Portable-x.y.z.zip`).
2. Derleme günlüğündeki `SHA256 python-...` ve `SHA256 mpv-...` satırlarını doğrulayıp `scripts/build_embedded.py` (`PY_SHA256`) içine yaz; mpv için `fetch_deps.ps1 -Tag <sürüm>` ile sürümü sabitle.
3. Temiz Windows'ta kur (model pakette gelir) → `Baslat.bat` ile dene. Üzerine yeni sürüm kurup ayarların/kısayolların korunduğunu da dene.
4. `git tag v1.0.0 && git push origin v1.0.0` → GitHub Release otomatik oluşur (`generate_release_notes`).

İlk gerçek denemede kontrol: `python -m tracker detect kare.jpg --model models\yolox_s.onnx --out sonuc.jpg` (kutular doğru yerde mi, `provider=DmlExecutionProvider`); `live --stats 2` (fps/gecikme); `o` ile offline oynatma; Ctrl+S ayar kaydı.
