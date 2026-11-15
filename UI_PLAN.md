# UI_PLAN.md — Takipli Oynat: MPC-HC tarzı arayüz planı

Free Claude hesabı, hafta içi limitleri için bölünmüş plan. Her oturumun başında bu dosyayı + `handover.md` oku, **yalnız sıradaki parçayı** yap.

## Karar
- Hedef görünüm: **MPC-HC (klasik, koyu)**. Üst menü çubuğu + seek çubuğu + alt düğme şeridi. Mevcut `trackmenu.lua` ile aynı yapı.
- VLC/MPC-HC kodu kopyalanamaz (Qt/MFC C++, GPL; mpv Lua'sı değil). Yalnız görünüm/yerleşim taklit edilir.
- uosc vb. hazır Lua arayüz **alınmaz**: fareyi ve menüyü ele geçirir, sağ tık takibi / zoom +/- / menü çubuğuyla çakışır.
- Teknik: mpv ASS overlay (`mp.create_osd_overlay("ass-events")`). Yeni bağımlılık yok.

## Bozulmaması gerekenler (her parçada kontrol listesi)
1. Sağ tık = nesne takibi, sağ sürükle = dikdörtgen (`MBTN_RIGHT script-binding track/pick`)
2. Sol tık yalnız bir düğmenin üstündeyken yakalanır; başka yerde mpv/track.lua davranışı aynı kalır
3. Çift sol tık = oynat/duraklat (`MBTN_LEFT_DBL track/dblclick`)
4. Sağ kenardaki zoom `+ / -` düğmeleri ve beyaz nokta/HUD (`track.lua`)
5. F10 menü, Ctrl+O aç, tüm `input.conf` kısayolları
6. `osc=no` kalır; alt çubuğu yalnız `mpcbar.lua` çizer
7. Kural: bir ASS olayında yalnız **ilk `\pos`** geçerli → çok parçalı çizimi `"\n"` ile ayrı satırlara böl

## Oturum kuralları (free hesap)
- 1 parça = 1 oturum. ≤3 dosya, ≤~300 değişen satır.
- Dosyaların **tamamını okuma**: `grep -n` + `sed -n` ile yalnız ilgili aralık.
- Yalnız **değişen dosyaları** zip'le, klasör yapısı korunarak (`mpv/portable_config/...`).
- Parça sonunda `handover.md`'ye 3–5 satır ekle. Bitiremeyeceğin parçaya başlama; token biterse son çalışan noktada dur, nereye kadar geldiğini yaz.
- Gerçek mpv'de doğrulama kullanıcıdadır; Claude yalnız Lua testini (`texlua`) ve gerekirse ASS önizlemesini çalıştırır.

## Durum özeti
| Parça | Konu | Durum |
|---|---|---|
| U0 | Alt çubuk MPC-HC cilası, `vlcbar.lua` silindi | Kod yapıldı; Windows'ta denenmedi; handover notu/zip teyit edilecek |
| U1 | Windows doğrulama + çakışma düzeltmeleri | Sırada |
| U2 | Çubuk testleri (Lua harness) | |
| U3 | Seek çubuğu geliştirmeleri | |
| U4 | Üst menü çubuğu görünümü | |
| U5 | Ses ve hız açılır kontrolleri | |
| U6 | Oynatma listesi paneli | |
| U7 | Ses/altyazı/bölüm menüleri | |
| U8 | Tema ve ölçek ayarı (`mpcbar.conf`) | |
| U9 | Bildirim (OSD) stili + son temizlik, sürüm | |

---

## U0 — Alt çubuk cilası (yapıldı, teyit)
- Dosyalar: `scripts/mpcbar.lua`, `mpv.conf` (yorum), `scripts/vlcbar.lua` (silindi), `handover.md`
- İçerik: büyük simgeler, grup ayırıcıları, önbellek ve bölüm işaretleri, dosya adı durum metni, Liste düğmesi, çok parçalı simge hatası düzeltmesi.
- Yapılacak (küçük): `handover.md` notunun yazıldığını ve zip'te `vlcbar.lua` olmadığını kontrol et. Eski kurulumda `vlcbar.lua` kalmışsa elle silinmeli (zip silme yapmaz).

## U1 — Windows doğrulama + çakışma düzeltmeleri
Kullanıcı mpv'de açar, sorunları kısa yazar. Claude yalnız bulunanları düzeltir.
- Bak: (1) alt çubuk görünüyor mu, tam ekranda 2,5 sn sonra gizleniyor mu; (2) simgeler tam mı (duraklat iki çubuk); (3) alt çubuk ile sağ kenardaki zoom `+/-` ve sol üst HUD üst üste biniyor mu; (4) sol tık seek/ses sürüklemesi, takip tıklamasını bozuyor mu; (5) 4K / akıllı tahtada boyut (`u = H/700`, 0.9–2.2).
- Dosyalar: `mpcbar.lua` (+ gerekirse `track.lua` yalnız z-sırası).
- Bitti: kullanıcı «sorun yok» dedi ya da bulunanlar kapandı.

## U2 — Çubuk için Lua testi
- Dosyalar: `tests/lua_harness_bar.lua`, `tests/test_lua_bar.py` (mevcut `lua_mp_stub.lua` kullanılır).
- Test: düğme çarpışma kutuları (play, seek, vol), seek oranı, ses sürgüsü sınırı, hover'da sol tık bağı kurulması / kalkması, tam ekran gizleme.
- Bitti: `pytest -q` yeşil; `texlua` yoksa atlanır (diğer Lua testleri gibi).

## U3 — Seek çubuğu geliştirmeleri
- Dosya: `mpcbar.lua`.
- Ekle: bölüm adı ipucu (zaman + bölüm), A-B döngü işaretleri (`ab-loop-a/b`).
- Kapsam dışı: küçük resim önizleme (mpv'de ağır).
- Bitti: işaretler doğru konumda, tıklama/sürükleme aynı çalışıyor.

## U4 — Üst menü çubuğu görünümü (yalnız çizim)
- Dosya: `script-modules/trackmenu.lua` → yalnız `e:render()`. Menü tablosu, kısayol, durum mantığı **dokunulmaz**.
- Yap: alt çubukla aynı palet (`262626` zemin, mavi seçili), ince alt çizgi, ayırıcı çizgisi, onay işareti (✓), gri pasif öğe, gölge, Tahoma yazı tipi.
- Bitti: `tests/test_lua_menu.py` ve `test_lua_files.py` yeşil.

## U5 — Ses ve hız açılır kontrolleri
- Dosya: `mpcbar.lua`.
- Ekle: ses sürgüsü yanında `%NN` yazısı (sürüklerken), hız düğmesine tıkla → hızlı seçim şeridi (0.5 / 1 / 1.5 / 2), tekerlek ile ses (çubuk üstünde).
- Dikkat: tekerlek bağını yalnız fare çubuğun üstündeyken kur (yoksa mpv ses/zoom tuşları bozulur).
- Bitti: bağlar fare ayrılınca kalkıyor.

## U6 — Oynatma listesi paneli
- Dosya: `mpcbar.lua` ya da yeni `scripts/playlist.lua` (≤150 satır).
- Yap: «Liste» düğmesi `show-text ${playlist}` yerine ASS panel: satır listesi, geçerli öğe vurgulu, tıkla = oynat, tekerlek ile kaydır, Esc kapatır.
- Bitti: panel açıkken sağ tık takibi ve çift tık davranışı aynı.
- Büyükse böl: U6a panel çizimi, U6b tıklama/kaydırma.

## U7 — Ses / altyazı / bölüm menüleri
- Dosyalar: `trackmenu.lua` (yalnız menü verisi), `track.lua` (yalnız eylemler).
- Yap: Oynat menüsü: Ses ► (iz listesi), Altyazı ► (iz listesi + Kapat), Bölümler ►. Seçili iz işaretli (✓). Alt menü zor olursa tek seviyeli açılır liste.
- Dikkat: menü öğesi sayısı testleri (`test_lua_menu.py`) güncellenir.
- Bitti: izler `track-list` ile dolduruluyor; iz yoksa pasif.

## U8 — Tema ve ölçek ayarı
- Dosyalar: `script-opts/mpcbar.conf` (yeni), `mpcbar.lua` (`mp.options.read_options`).
- Seçenekler: `scale` (0.8–2.5), `theme=dark|light`, `hide_seconds`, `seek_step`, `show_title`, `accent` (RRGGBB).
- Bitti: dosya yokken varsayılanlar bugünkü görünümle aynı.

## U9 — OSD bildirimi + son temizlik
- Dosyalar: `mpcbar.lua`, `README.md`, `handover.md`, `VERSION` (+ `tracker/__init__.py` + `tests/test_license.py` birlikte).
- Yap: «Hız: 1.5x», «Ses: 80%» mesajlarını sağ üstte ASS bildirimine çevir (yalnız bu çubuğun mesajları); README'ye arayüz bölümü; sürüm yükseltilecekse üç yeri birlikte güncelle.
- Bitti: `pytest -q` yeşil, `git grep '<<<<<<<'` boş, tam proje zip'i.

---

## Önerilen sıra
U0 teyit + U1 → U2 → U4 → U3 → U5 → U6 → U7 → U8 → U9.
Önce gerçek ekranda doğrula, sonra test ağı, sonra görünüm, en son ayar ve cila. U6 ve U7 en büyükleri; token azsa ikiye böl.

## Kapsam dışı
Küçük resim önizleme, ağ/DLNA, ekolayzır arayüzü, özel pencere kenarlığı, VLC tarzı yuvarlak tema.
