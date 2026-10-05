# Native dosya listesi (Seçenek A) — Handover

Tarih: 2026-10-05 · Durum: **Faz 5b (anında açılış: yerel başlangıç resmi, derlenmedi) + Faz 5a (anında klasör girişi; derleme 4c ile GEÇTİ, 5a derlenmedi) + Faz 4c (tüm cihazlar + her görünümde arama + sayı yaması + karartma/gölge senkronu) + Faz 4a (en yeni modu) + Faz 1 + Faz 2a (compact) + Faz 2b (büyük küçük resim) + Faz 3a (grid görünümü) + Faz 3b (video galerisi) kodlandı, DERLENMEDİ, cihazda TEST EDİLMEDİ** (bu ortamda Android SDK/kotlinc yok).

## 1. Amaç ve kararlar
- Gezinme gecikmesi WebView katmanından geliyor (daha önceki native deneme çok hızlıydı). Ama ayrı ekran olan `BrowserActivity` görünümü bozmuştu → **kaldırıldı, geri getirilmeyecek**.
- Karar: **Seçenek A** — sayfa (üst bar, yol çubuğu, çekmece, diyaloglar, FAB, toast, yazdırma, ayarlar) HTML kalır; yalnızca **dosya satırları** WebView'in ÜSTÜNE yerleştirilen bir `RecyclerView` ile çizilir. Satır ölçüleri/renkleri `ui.html` CSS'inin birebir karşılığı.
- Kural: **görünüm ve diğer işlevler değişmeyecek.** Şüphede kalınan her durumda DOM satırlarına geri düşülür (aşağıdaki "kapı koşulları").

## 2. Faz 1'de yapılanlar
| Dosya | Değişiklik |
|---|---|
| `app/src/main/java/com/lanshare/app/NativeList.kt` (**yeni**, ~360 satır) | `NlOverlay` (WebView üstü katman, dokunma yönlendirme, "delik" ve karartma), `NlRowView` (özel çizimli satır), `NlPal` (renk paleti), `NlIcons` (ui.html `IC` yollarının birebir kopyası), `NativeList` (RecyclerView + SwipeRefresh + küçük resim yükleyici + kaydırma konumu hafızası) |
| `MainActivity.kt` | `FrameLayout{WebView, nl.overlay}`; `Bridge`: `nlAvail, nlPalette, nlItems(key,items,sel), nlSel, nlLayout, nlHide, nlDone` |
| `assets/ui.html` | `render()` içine kapı (`nlUse`) + `NL...` blokları (`nlRender, nlLay, nlPal, nlOn, nlSyncSel, nlRe`); `renderBar()` ve klasör sayısı güncellemesine birer satır |
| `HANDOVER.md` | başa kısa not |

### Veri akışı
- **JS → Kotlin:** palet (CSS değişkenleri `getComputedStyle` ile okunur, açık/koyu tema otomatik uyar), satırlar (`{n,k,a,b,d,g,gc,u,p,s,t}`: ad, tür, **önceden biçimlendirilmiş** boyut metni `a` ve tarih metni `b`, klasör mü, rozet, duplicate, küçük resim yolu/boyut/mtime), seçili indeksler, yerleşim `{l,t,w,h,v,o,dm,ps}`.
- **Kotlin → JS:** `nlOn('tap'|'long'|'down'|'refresh', index)`. `tap` ve `long` eski `r.onclick` / `holdMenu` mantığının aynısını çağırır (`go, playVid, viewImg, viewPdf, openFile, renderBar`). `down` = klasör ön yükleme (`prefetch`). `refresh` = eski pull-to-refresh gövdesi.
- Yerleşim: liste alanı = `#list` üst kenarı ile `innerHeight - --dockh` arası. `#fab, #toast, #dlw` için **delik** (`holes`): o bölgede katman çizmez, dokunmayı WebView'e iletir (FAB piksel piksel aynı kalır). `#sheet/#dlg` ve çekmece açıkken `dm` (karartma) + `ps` (tüm dokunma sayfaya). `#pv`/`#dual` açıkken liste gizlenir. 150 ms'lik `setInterval` yalnızca native aktifken çalışır, değişiklik yoksa köprüyü çağırmaz.

### Kapı koşulları (`nlUse`) — hepsi sağlanırsa native, değilse eski DOM
Android uygulaması + `LSAndroid.nlAvail()` + `localStorage.ls_nl!=='0'` + liste boş değil + `S.dev==='local'` + arşiv içinde değil + video galerisi yok + arama sonucu yok (`!S.sr`) + `!S.newest` + `S.view==='list'` + `S.thumb!=='l'` (büyük küçük-resim modu değil).

### Kapatma anahtarları
- `NativeList.ENABLED = false` (Kotlin) → tamamen eski davranış.
- `localStorage.setItem('ls_nl','0')` (JS) → aynı.
- Geri alma: `NativeList.kt`'yi sil, `MainActivity`'deki `nl*` köprüleri + FrameLayout'u eski `setContentView(web)`'e çevir, `ui.html`'de `/* ---- native file list` bloğunu ve `render()/renderBar()/sayı` satırlarını kaldır.

## 2b. Faz 2a — compact görünüm (2026-10-05, bu oturum)
- `NativeList.kt`: `NlRowView.compact` + `NativeList.compact`; `bind(r, pl, sel, compact)`. Compact ölçüler (`ui.html` `#list.v-compact`): satır 44, dolgu 2/14/2/8, lead 36×36 (x=8), boşluk 10, ikon kutusu/seçim dairesi 30 (ikon 20), klasör 34×29, rozet 15 (ikon 11, alt −2), küçük resim 28×30, ad 15 + sağda küçük metin 12 **tek satırda** (ad esner, küçük metin sağa yaslı). `duplicate?` rozeti adın hemen sonrasında. Satır yüksekliği `onMeasure` içinde (44/60); adapter `LayoutParams` artık `WRAP_CONTENT`.
- `ui.html`: `nlUse` artık `S.view==='list'||'compact'`; `nlRender` compact'ta `a` = (`S.sort==='date'` ve tarih varsa tarih (saatsiz), yoksa boyut/öğe sayısı), `b=''`; liste anahtarı `dev|view|yol` (görünüm başına ayrı kaydırma konumu); `nlLay` JSON'una `cp` (1=compact) eklendi; Kotlin `layout()` değişince `notifyDataSetChanged`.
- Cihazda bak: compact'ta satır 44 dp, ad/tarih hizası (1 px fark olabilir), seçim dairesi 30, küçük resim 28×30, video süre rozeti, ad uzun + `duplicate?`, Görünüm menüsünden list↔compact geçişi (kaydırma konumu korunmalı).

## 2c. Faz 2b — büyük küçük resim (`S.thumb==='l'`, list görünümü) (2026-10-05, bu oturum)
- `NativeList.kt`: `NlRowView.large` + `NativeList.large`; `bind(r, pl, sel, compact, large)`. Ölçüler (`ui.html` `#list.t-lg:not(.v-grid):not(.v-compact)`): satır 84, lead 76×68 (x=10 → merkez 48), ikon kutusu/seçim dairesi 64 (ikon 36), klasör 68×58, küçük resim 68×62 (lead −8/−6), metin başlangıcı x=98; ad/alt satır boyutları list ile aynı (16/13). Rozet ve süre rozeti ölçüleri list ile aynı bırakıldı (CSS'te değişiklik yok varsayıldı).
- `ui.html`: `nlUse` içindeki `S.thumb!=='l'` koşulu kaldırıldı; `nlLay` JSON'una `lg` (1 = list + büyük küçük resim) eklendi; liste anahtarına büyük modda `L` eki (ayrı kaydırma konumu). Compact'ta `t-lg` CSS'i zaten uygulanmıyor → `lg` yalnızca `S.view==='list'` iken 1.
- Cihazda bak: satır 84 dp, küçük resim köşe yarıçapı/boyutu, klasör ikonu 68×58 ve rozet konumu (CSS ile 1–2 px fark olabilir), seçim dairesi 64, video süre rozeti konumu, Görünüm menüsünden küçük↔büyük küçük resim geçişi.

## 2d. Faz 3a — grid görünümü (2026-10-05, bu oturum)
- `NativeList.kt`: yeni `NlGridView` (kart: `--bg` zemin, 1px `--bd` kenarlık, yarıçap 12, dolgu 6/6/8; kare lead = kart genişliği − 14; klasör lead'in %40'ı, dosya ikon kutusu %40 (köşe 8, ikon kutuyu doldurur), küçük resim tüm kare (köşe 8) + süre rozeti; ad 13 (satır 16.25, en çok 2 satır, ortalı, sığmazsa `…`), alt metinler 12 (satır 16.8: boyut + tarih), seçim dairesi sağ-üst 5/5, 24 px, beyaz halka; seçim modunda seçilmemişlerde boş halka). Kart yüksekliği içeriğe göre (CSS `align-items:start` gibi).
- `NativeList`: `ensureMgr(grid)` — liste = `LinearLayoutManager`, grid = `GridLayoutManager` (2 sütun, genişlik ≥600 dp → 4); boşluk 8 = rv dolgusu 4 + öğe ofseti 4. Grid bilgisi liste anahtarından (`dev|grid|yol`) okunur, böylece `nlItems` ile layout JSON'u arasındaki sıra sorun çıkarmaz. Adapter `getItemViewType` ile iki görünüm tipi; seçim modu açılıp kapanınca grid'de tüm kartlar yenilenir (boş halka).
- `ui.html`: `nlUse` artık `S.view==='grid'` kabul ediyor; palete `bg` eklendi (`NlPal.bg`). Başka JS değişikliği yok (grid'de `a`=boyut, `b`=tarih+saat, list ile aynı).
- Bilinen farklar / cihazda bak: kart yüksekliği (satır aralığı 1.25/1.4 tahmini, 1–2 px fark olabilir), `duplicate?` rozeti (adın son satırına sığarsa yanına, sığmazsa ve ad tek satırsa altına; ad 2 satırsa ve sığmazsa **çizilmez**), seçim halkasının gölgesi yaklaşık, grid'de basılı (pressed) vurgu yok (CSS'te de yok), küçük resim kare kırpma, yatay modda 4 sütun, 5000+ dosyada akıcılık.

## 2e. Faz 3b — video galerisi (`.gal`) (2026-10-05, bu oturum)
- `ui.html`: `nlUse` artık `!GV.length` şartı aramıyor; `render()` native'e `LIST.concat(GV)` yolluyor, `nlRender(LIST,P0,GN)`: indeks `>= GN` olan satırlara `v:1` (galeri hücresi) eklenir. `NLGN` global'i `nlRe()` için saklanır. Dokunma/seçim indeksleri birleşik dizi üzerinden çalışır (eski `tap/long` mantığı aynı: video = `playVid`).
- `NativeList.kt`: `NlRow.gal`; yeni `NlGalView` (adapter satırı başına 3 hücre, ≥600 dp'de 5; kare hücre, yarıçap 8, 4 dp boşluk/dolgu, ortada video ikonu (36, %70) → küçük resim gelince tüm kare, üstte ad gradyanı (11), sol-altta oynat noktası (22), sağ-altta süre hapı, sağ-üstte seçim dairesi (grid kartıyla aynı), basılıyken %15 karartma, son satırın altında 1 px `--bd` çizgisi). Dokunmayı `NlGalView` kendisi çözer (`GestureDetector`; tek hücre vurma/uzun basma → `emit(tap/long, birleşikIndeks)`).
- `NativeList`: `galStart` (ilk galeri hücresi), `gs` (3/5), adapter `getItemCount = galStart + ceil(galeriHücre/gs)`, `getItemViewType` 0/1/2, `posOf(i)` (seçim değişince doğru adapter satırını yeniler), grid modunda `SpanSizeLookup` galeri satırına tam genişlik verir (öğe ofseti 0). Küçük resim yükleme `loadTh(...)` ortak fonksiyonuna taşındı (liste, grid, galeri aynı önbellek/`failed` kümesini kullanır).
- Cihazda bak: galeri listenin ALTINDA mı (DOM ile aynı sıra), hücre boyutu (3/5 sütun), ad gradyanı, süre hapı, seçim modunda halka + ad sağ boşluğu (34), uzun basma → seçim, seçili hücrede küçük resim KARARMIYOR (yalnızca tik), yatay modda 5 sütun, grid görünümünde galerinin tam genişlik olması, galeri varken pull-to-refresh.

## 2f. Faz 4a — "en yeni" modu (`S.newest`) (2026-10-05, bu oturum)
- `ui.html`: `nlUse` içinden `!S.newest` kaldırıldı (DOM'da da satırlar aynı; yalnızca `shown()` listesi tarihe göre düz sıralı). Liste anahtarı `dev|view|` + `N:` + yol (kaydırma konumu normal listeden ayrı; Kotlin yalnızca anahtarın 2. parçasını okuyor, grid ayrımı bozulmaz). Kotlin değişikliği YOK.
- Cihazda bak: "Newest" düğmesi açıkken native liste, kapatınca eski sıraya ve kaydırma konumuna dönüş, seçim/uzun basma, list/compact/grid üçünde.
- Kalan Faz 4: arama sonuçları (`S.sr`), arşiv içi (`!`), diğer cihazlar/SMB.


## 2g. Faz 4b — arşiv içi + arama sonuçları (2026-10-05)
- Derleme (6b4dffb, `isSel`) başarılı. `ui.html`: `nlUse(n,SR)` artık arşiv içini (`!AR`) ve arama durumunu (`!S.sr`) elemiyor. Arşivde küçük resim yok (`!inArc()`), tıklama mantığı aynı (`isArcF` → `go(..'!')`).
- Alt klasör arama sonuçları (`renderSR`): `srRows()` başlık + sonuç satırlarını üretir; native listeye `LIST + sonuçlar + galeri` sırasıyla gider (`GN` = LIST+sonuç). Sonuç satırı: `_h` (alt metin = üst klasör yolu, seçilemez, tıklama `go`/`goFind`), başlık: `_hd` (`k:'hdr'`). Arama sonuçları yalnızca **list** görünümünde native; compact/grid + sonuç varken DOM'a düşer.
- `NativeList.kt`: `NlRowView` `k=="hdr"` için metin-only başlık satırı (38 dp, 13 sp, `mut`). Başka Kotlin değişikliği yok.
- Diğer cihazlar (`S.dev!=='local'`) bilerek DOM'da: Kotlin küçük resim yükleyici yalnızca yerel `File`/`Source` kullanıyor; native yapınca uzak küçük resimler kaybolurdu.
- Cihazda bak: arşiv içi gezinme/açma, aramada "In subfolders" başlığı + sonuç tıklama (klasöre git / dosyayı bul), arama kutusu temizlenince normal liste, seçim modunda sonuç satırı, compact/grid'de aramada DOM fallback.

## 2h. Faz 4c — kalan her şey (2026-10-05)
- **Diğer cihazlar / SMB native:** `nlUse` artık `S.dev==='local'` istemiyor. Satırlar `NlRow.dev` taşır (`nlItems` anahtarının ilk parçası). Küçük resimler `core/RemoteThumbs.kt` ile üretilir: `Jobs.ep(dev).open(path)` → resim geçici dosyaya (≤30 MB) akıtılıp `Thumbs.makeUncached` ile JPEG, video `VideoThumbs.make(Source)` (aralıklı okuma); disk önbelleği `rthumbcache/` (dev|yol|boyut|mtime). Bellek önbelleği anahtarına `dev` eklendi. Yerel yol değişmedi.
- **Arama sonuçları compact/grid'de de native:** `nlUse(n)` görünüm kısıtı yok (list/compact/grid). Grid'de `hdr` satırı tam genişlik (`SpanSizeLookup`) ve `NlRowView` ile çizilir (`getItemViewType`).
- **Klasör sayısı yaması:** `fillCounts` → `nlPatch(Set)` → `LSAndroid.nlPatch([[idx,metin]])` → `NativeList.patch` yalnızca ilgili satırı yeniler (tüm listeyi yeniden göndermez). `nlA()` metin hesabını ortaklaştırır.
- **Karartma geçişi:** `nlBurst(500)` — tık/dokunma/transition/animation olaylarında 500 ms boyunca her karede `nlLay()`; çekmece/diyalog solması 150 ms gecikmesiz izlenir. `layout()` yalnızca geometri değişince `LayoutParams` günceller (karartma-only değişiklikte yeniden ölçüm yok).
- **Başlık gölgesi:** `RecyclerView` kaydırma dinleyicisi → `nlOn('el',0|1)` → `#top.el`; native liste kapanınca sayfa kaydırmasına göre yeniden hesaplanır.
- Kalan (bilerek): yok. Açık riskler cihazda doğrulanacak: uzak klasörde çok sayıda küçük resim/ağ yükü (havuz 3 iş parçacığı), SMB'de büyük video küçük resmi gecikmesi, arama sonuçlarının grid'de kart görünümü, `hdr` satırı x konumu (24 dp), kaydırınca gölge.
- Geri alma: yalnızca uzak cihazları DOM'a döndürmek için `nlUse`'a `&&S.dev==='local'` ekle; her şey için `ls_nl='0'` / `NativeList.ENABLED=false`.

## 2i. Faz 5a — anında klasör girişi (2026-10-05) — YERİNE 2k GEÇTİ: JS `nlStash` düzeneği kaldırıldı, aşağıdaki JS/Kotlin stash ayrıntıları tarihsel
Amaç: klasöre dokununca satırların JS gidiş-dönüşünü (`go()` → `render` → JSON → köprü) beklemeden görünmesi. Biçimlendirme kopyalanmadı: satırları yine JS üretir, Kotlin yalnızca saklar.
- **JS (`ui.html`):** `nlRender` ikiye bölündü: `nlBuild(LIST,P0,GN)` (yan etkisiz, satır dizisi) + `nlRender`. `nlStash(dev,path,{items})` bir klasörün satırlarını (geçici `S.items`/`DUPS` değişimiyle, mevcut ayarlarla) üretip `NL.nlStash(key,json,cfg)` ile Kotlin'e yollar. Çağrı noktaları: `prefetch()` bitişi (dokunma-başı ve boşta) ve `idlePre()` (ilk **6** alt klasör; LSC'de varsa doğrudan). `nlRender` her seferinde `NL.nlCfg(cfg)` yollar (`cfg` = sort,asc,hid,gal,view,thumb). Atlananlar: yalnızca yerel cihaz, arama açıkken, newest, `sort==='size'` (sayılar sonradan gelir, sıra bozulur), arşiv yolları, >2000 satır, boş klasör.
- **Kotlin (`NativeList.kt`, `MainActivity.kt`):** `stashed` (en çok 24, anahtar = `dev|görünüm|yol`; `cfg` değişince temizlenir), `tapRow(i)`: seçim yok + düz klasör satırı (`NlRow.hit` değil, `hdr` değil) + saklı satır varsa `applyItems` ile hemen gösterir, ardından `raw("tap")` ile sayfa normal `go()` yapar ve doğrular (satırlar aynıysa `sig` eşit → yeniden çizim yok). Sayfa cevap verene kadar (en çok 2 sn, `navUntil`) yeni dokunuşlar yutulur: eski `NLL` ile yeni satır dizinleri karışmasın. `setItems` (köprüden gelen) bunu sıfırlar. Yeni köprü: `nlCfg`, `nlStash`.
- Kapatma: `NL.nlStash` yoksa/çağrılmazsa hiçbir şey değişmez (davranış eskisi). Tam kapatma için `nlStash` içinde ilk satıra `return` koy.
- Cihazda bak: ilk dokunuşta klasörün anında açılması (özellikle yeni girilen klasörün alt klasörleri), üst çubuğun yolu birkaç on ms sonra güncellemesi, hızlı çift dokunuşta yanlış satırın açılmaması, boş/yeni klasörde hata olmaması, ayar (sıralama/görünüm) değişince eski sıranın görünmemesi.

## 2j. Faz 5b — anında açılış (2026-10-05)
Soğuk başlangıçta WebView `ui.html`'i yüklerken son ekran yerel çizilir. NOT compiled / NOT device-tested.
- **Yeni `NlSplash.kt`:** `onPause` → `save()`: görünen klasörün ilk 120 satırı (küçük resim yolları `p/s/t` ÇIKARILIR; Core hazır olmayabilir), palet, liste geometrisi ve sayfanın liste dışı iki şeridi (üst: başlık+yol çubuğu, alt: dock) `PixelCopy` ile (API 26+; altında yalnızca liste) `filesDir/nlsplash/{state.json,top.jpg,bot.jpg}`. Yalnızca düz, boşta, **yerel** native liste varken (seçim/çekmece/diyalog/newest/arşiv/arama kutusu açıkken kaydedilmez; `lay.sq`). `start()` (yalnızca `savedInstanceState==null`): iş parçacığında okur, WebView ilk yerleşimden sonra (`web.width>0`) palet + satırlar + geometri (FAB/toast delikleri temizlenir) + iki resim ekranda. Boyut/dpi/uygulama sürümü (`lastUpdateTime`) değiştiyse atlanır.
- **Kaldırma:** sayfanın ilk canlı cevabı (`nlLayout`, `nlHide`, yeni `nlBoot` = ilk `load()` bitişi, 8 sn bekçi, sunucu hata sayfası) → `dismiss()`: WebView boyadıktan sonra (`postVisualStateCallback`) resimler gider; sayfa native liste kullanmadıysa (`live=false`) eski satırlar `nl.hide()` ile kalkar.
- **`MainActivity`:** `splash` alanı, `nlItems` içinde `splash.noteItems`, `nlBoot` köprüsü, `onPause`. `loadWhenReady`: sunucu bekleme yoklaması **25 ms** (eskiden önce 300 ms uyuyup 300 ms'de bir bakıyordu: 0,6 sn'ye kadar boş bekleme).
- **`ui.html`:** `load()` → `loadMain()` sarmalayıcı (`finally nlBoot()`), `nlLay` JSON'una `sq`.
- Kapatma: `NativeList.ENABLED=false` hepsini kapatır; yalnızca açılış resmi için `MainActivity.onCreate`'ta `splash.start()` satırını sil.
- Cihazda bak: soğuk açılışta ilk karede son klasör + üst/alt çubuk görünmeli; canlı sayfa gelince sıçrama/yanıp sönme olmamalı; boş klasör/başka cihaz/tema değişimi/yön değişimi; uygulama güncellenince resim kullanılmamalı; ilk saniyede dokunuşların zarar vermemesi.

## 2k. Faz 6a — liste hattı Kotlin'de (2026-10-05) — DERLENMEDİ, TEST EDİLMEDİ
Amaç: klasör girişi için JS gidiş-dönüşünü (`go` → `render` → JSON → köprü) ve JS'in önceden kurduğu "stash" düzeneğini kaldırmak.
- **Yeni `NlModel.kt`:** `ui.html` hattının yerel cihaz için Kotlin ikizi: `Core.local.ls(path,false)` → sunucu sırası (klasör önce, küçük harf ad) → gizli dosya süzgeci → sıralama (`android.icu` Collator, numeric + PRIMARY = `Intl.Collator numeric/base`) → `findDups` → galeri ayrımı → satır metinleri (`fmt`, `nlA`, tarih/saat `yMMMd`/`jjmm`, `BADGE`, küçük resim kuralı). `NlModel.sig(rows)` = satır İÇERİĞİ imzası (eskiden JSON metninin hash'i): JS ve Kotlin aynı satırları kurarsa imza eşit → yeniden çizim yok.
- **`NativeList.kt`:** `Stash`/`stashed`/`stashCfg`/`stash()`/`cfgOk()` kaldırıldı. Yerine `built` (en çok 24 klasör, 30 sn ömür, `cfg` değişince temizlenir), `warm()` (arka plan: parmak basınca acil, yükleme sonrası 400 ms'de ilk 6 alt klasör boşta; >2000 öğe boşta atlanır), `childKey()`, `warmFor()`, `warmNeighbours()`. `tapRow`: hazırsa satırları hemen gösterir, değilse kurulunca gösterir; her durumda `raw("tap")` ile sayfa normal `go()` yapar ve doğrular. Sayfa cevap verene kadar (en çok 2 sn) yeni dokunuş yutulur (artık `tapRow` başında da).
- **`MainActivity.kt`:** `nlStash` köprüsü kaldırıldı; `nlItems` imzayı `NlModel.sig(rows)` ile hesaplar.
- **`ui.html`:** `nlStash()` ve `prefetch`/`idlePre` içindeki çağrıları kaldırıldı (JS `LSC` ısıtması kalır). `nlCfg()` her `nlRender`'da gitmeye devam eder.
- Kotlin'in kurmadığı durumlar (sayfa karar verir): `sort==='size'` (klasör sayıları sonradan gelir), newest, arşiv (`!`), diğer cihazlar/SMB, arama sonuçları, seçim açıkken, boş/hepsi gizli klasör, `view` list/compact/grid dışı.
- Bilinen fark: Kotlin satırlarında klasör sayısı yok ("Folder"); sayfanın `LSC`'sinde sayı varsa ilk cevapta yalnızca o metin değişir. Tarih/saat biçimi ICU ile WebView'in `Intl`'i arasında 1 karakter (ör. U+202F) farklı olabilir → sayfa satırları değiştirir, işlev bozulmaz.
- Cihazda bak: klasöre ilk dokunuşta satırların anında gelmesi (daha önce hiç girilmemiş klasörde de), hızlı iki dokunuş, sıralama/gizli/galeri/compact/grid ayarı değişince eski sıranın görünmemesi, sayfa cevabı sonrası titreme/yeniden çizim olmaması (imza eşitliği), büyük klasör (5000+) girişi, geri dönüşte kaydırma konumu.
- Geri alma: `NativeList.tapRow` içinde `childKey(i)` yerine `null` döndür → eski davranış (yalnızca sayfa). Tamamen `NativeList.ENABLED=false`.

## 3. Derlemede ilk bakılacak yerler (tahmini risk)
1. `NativeList.kt`: `PathParser.createPathFromPathData` (androidx.core 1.13.1'de var), `Region.Op.DIFFERENCE` ile `clipRect` (kullanımdan kalkmış uyarısı normal), `pool.submit(Runnable { })`, `LruCache` alt sınıfı, `lm.onSaveInstanceState()` dönüş tipi.
2. `Core.local.real(path)`, `Core.local.open(path)`, `Thumbs.make(File)`, `VideoThumbs.make(Source): Pair<ByteArray, Long>` (süre **ms** varsayıldı; ui.html `X-Duration/1000` yapıyor).
2b. `NlModel.kt`: `android.icu.text.Collator/RuleBasedCollator.setNumericCollation`, `DateFormat.getInstanceForSkeleton`, `Comparator<Item>` etiketi `return@Comparator`, `Result` iç sınıfı.
3. `MainActivity`: `nl` alanı `web`'den sonra oluşturuluyor; `recreate()` sonrası yeniden kuruluyor (sorun beklenmez).
4. Yeni bağımlılık yok (`recyclerview`, `swiperefreshlayout` zaten `app/build.gradle.kts` içinde).

## 4. Cihazda doğrulama listesi (görünüm eşleşmesi öncelikli)
1. Aynı klasörde `ls_nl='0'` ile ve açıkken ekran görüntüsü al → satır yüksekliği (60), ikon kutusu (40, ikon 24), klasör ikonu (40×34), ad (16, 500 ağırlık), alt satır (13), boşluklar, ayraç çizgisi, **açık ve koyu tema** karşılaştır. En olası fark: 1 px dikey metin hizası (Roboto metrikleri).
2. Küçük resimler (resim/video, video süre rozeti), `duplicate?` rozeti, DCIM/Download/Movies/Music/Pictures/Documents klasör rozetleri.
3. Dokunma: klasöre gir, geri dön (kaydırma konumu geri gelmeli), video/resim/PDF/diğer dosya açma.
4. Uzun basma → seçim modu, çoklu seçim, seçilince onay dairesi, üst seçim çubuğu, alt dock.
5. Pull-to-refresh, çekmece açma (karartma + katman altında kalmama), diyaloglar, FAB'a dokunma, toast, ilerleme çubuğu.
6. Yatay mod, farklı dock yüksekliği, 5000+ dosyalı klasörde kaydırma akıcılığı.

## 5. Bilinen eksikler / sonraki fazlar (öncelik sırasıyla)
1. **Faz 2 tamam (kodlandı, test yok):** ~~`compact`~~ (2b), ~~`S.thumb==='l'`~~ (2c). Video galerisi 3b'de kodlandı (bkz. 2e).
2. **Faz 3 — grid görünümü** ~~(yapıldı, bkz. 2d)~~; video galerisi (3b) kodlandı, bkz. 2e.
3. **Faz 4 — kapsam: TAMAMLANDI** (bkz. 2f, 2g, 2h). Sıradaki iş: derleme + cihaz doğrulaması.
4. Çekmece/diyalog karartması şimdilik `dm=0.42` sabit tahmini (`#dscrim` opaklığı okunuyor); geçiş animasyonunda 150 ms gecikme olabilir. İstenirse `transitionend` ile tetiklenir.
5. Seçimdeyken küçük resim gizleme, `row:active` ve seçili satır renk öncelikleri CSS'e göre uyarlandı; ayrıntılar cihazda gözle doğrulanmalı.
6. Klasör sayısı (`i.n`) geç geldiğinde `nlRe()` tüm satır metinlerini yeniden gönderiyor (hash değişince `notifyDataSetChanged`). Çok büyük klasörlerde yalnızca ilgili satırı güncelleyen `nlPatch(idx,text)` daha ucuz olur.
7. Başlık/yol çubuğu ile **kaydırma senkronu**: şimdilik gerekmiyor (sayfa kaydırmıyor, `#top` sabit); yapışkan başlığın küçülmesi/gölgesi (`#top.el`) liste kaydırmasına bağlanmadı.

## 6. Ücretsiz haklarla devam etme önerisi
- Her oturumda **tek küçük adım** iste: önce "derleme hatalarını düzelt" (GitHub Actions log'unu yapıştır), sonra "Faz 2 compact görünüm" gibi.
- Yükle: `pcshare.zip` (veya yalnızca `NativeList.kt`, `MainActivity.kt`, `ui.html`, bu dosya) + varsa derleme log'u / ekran görüntüleri (açık: `ls_nl=0`, kapalı: native).
- Sıradaki tek adım önerisi: **ÖNCE derleme** (Actions log'u) ve cihazda list/compact/büyük küçük resim doğrulaması; sorun yoksa **Faz 4** (arama sonuçları, arşiv içi, SMB/diğer cihazlar). Grid için de cihazda doğrulama (bkz. 2d).
- Örnek ilk istek: "HANDOVER_NATIVE_LIST.md'yi oku. Actions derleme hatalarını düzelt, başka hiçbir şeyi değiştirme. Log: …"
