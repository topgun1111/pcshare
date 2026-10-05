# Native dosya listesi (Seçenek A) — Handover

Tarih: 2026-10-05 · Durum: **Faz 10 (native çerçeve: üst/seçim çubuğu, FAB, dock, snackbar, indirme kartları; derlenmedi) + Faz 9 (native arama kutusu + native Sort/View sayfaları: derlenmedi, cihazda test edilmedi)** + **Faz 5b (anında açılış: yerel başlangıç resmi, derlenmedi) + Faz 5a (anında klasör girişi; derleme 4c ile GEÇTİ, 5a derlenmedi) + Faz 4c (tüm cihazlar + her görünümde arama + sayı yaması + karartma/gölge senkronu) + Faz 4a (en yeni modu) + Faz 1 + Faz 2a (compact) + Faz 2b (büyük küçük resim) + Faz 3a (grid görünümü) + Faz 3b (video galerisi) kodlandı, DERLENMEDİ, cihazda TEST EDİLMEDİ** (bu ortamda Android SDK/kotlinc yok).

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

## 2l. Faz 7a — yol çubuğu + araç satırı native (2026-10-05) — DERLENMEDİ, TEST EDİLMEDİ
`#pathrow` (kırıntılar + depolama yüzdesi hapı) ve `#toolrow` (öğe/boyut özeti, Newest, Sort) artık native çizilir. HTML satırlar **yerinde ve aynen kalır**; native görünüm tam üstlerine opak çizilir (hata olursa altta DOM görünür).
- **Yeni `NlHead.kt`:** `NlHeadData` (veri, `segsOf(path)`), `NlHeadView` (özel çizim: ev/sürücü simgeleri, `›` ayraçları, yatay kaydırma + fling, depolama hapı, özet metni, Newest, Sort; ölçüler `ui.html` CSS'i: kırıntı 34 yüksek/14 sp, özet 13 kalın + 12 sol, Newest 28/12, Sort 32/13, hap 28/12). Dokunma: kırıntı → `nlOn('crumb',i)` (0 kök, 1 sürücü, 2.. klasörler), `newb`, `sort`.
- **`NativeList.kt`:** `HEAD` anahtarı, `head` görünümü overlay içinde; `layout()` JSON'unda `hd:[sol,üst,genişlik,yolYüksekliği,araçYüksekliği]` okunur; `NlOverlay.headRect` (dokunma native, karartma bu bölgeye de biner); `setHead(json)`; **anında klasör girişi:** `headTo()` kırıntıları ve özet metnini satırlarla birlikte hemen günceller (`Built.sumB/sumS`, `NlModel.headSum`). Sayfanın kendi `nlHead` itmesi her `render()`'da gelir ve doğrular/düzeltir.
- **`NlModel.kt`:** `headSum(items, hid)` = `renderTools()` metinleri; `Result.sum`.
- **`MainActivity.kt`:** köprü `nlHead(json)`.
- **`ui.html`:** `nlHeadPush(V)` (render'da `nlRender` öncesi), `nlLay` JSON'una `hd`, `nlOn`'a `crumb/newb/sort`.
- Kapatma: `NativeList.HEAD=false` veya `localStorage.setItem('ls_nlh','0')` → yalnızca HTML satırlar.
- Cihazda bak: iki satırın DOM ile 1 px'e kadar aynı hizada olması (açık/koyu), uzun yolda kırıntıların sona kaydırılması + elle kaydırma, kök/sürücü simgeleri, diğer cihazda cihaz adı, hap yüzdesi, Newest açık/kapalı, Sort etiketi + ok, çekmece/diyalog açıkken karartma ve dokunmanın sayfaya gitmesi, arama kutusu açıkken konum, klasöre girişte yolun listeyle aynı anda değişmesi, hızlı çift dokunuş.

## 2m. Faz 8a — anında yukarı/geri + boyut sıralaması (2026-10-05) — DERLENMEDİ, TEST EDİLMEDİ
- **Anında kırıntı / sistem geri tuşu:** `NativeList.crumbTo(i)`, `backUp()`, `goPath()`. Hedef klasör satırları `built`'ten (≤10 dk) hemen gösterilir, yoksa `warm(urgent)` ile kurulup gösterilir; sayfa `nlOn('goto', yol)` ile aynı yola gider ve doğrular (`navUntil` koruması tapRow ile aynı). `backUp()` yalnızca: yerel + düz klasör + `/` değil + seçim/arama/diyalog/çekmece yok (`sqOn`, `passAll`, `dim`) iken devreye girer; aksi halde `lsBack()` (sayfa) karar verir. `MainActivity.onBackPressed` önce `nl.backUp()` dener.
- **`rememberPage`:** sayfanın `setItems` ile gönderdiği düz yerel klasör listeleri de `built`'e girer → üst klasöre dönüş anında olur (ilk girişte de).
- **`NlModel.build`:** `sort==='size'` artık Kotlin'de kurulur (`Core.local.counts` ile sayılar eklenir = sayfanın `fillCounts` sonrası hali; imza eşit). Yalnızca gerçek dokunuşta (`maxItems==MAX`); boşta ön-kurulum bu modda atlanır (I/O fırtınası olmasın).
- **`ui.html`:** `nlOn('goto', yol)`; `nlLay` JSON `sq` artık `body.srch` açıkken de 1.
- Yapılmayanlar (bilerek): yerel `/api/*` zaten `shouldInterceptRequest`/`InProc` ile süreç içi (TCP yok) → köprüye taşımanın kazancı yok; "newest" modu klasör gezintisi değil; dosya açma (`playVid/viewImg/viewPdf` JSON'u) JS'te kaldı.
- Cihazda bak: kırıntıya dokununca üst klasörün anında gelmesi + kaydırma konumu, geri tuşu (klasörde / diyalogda / seçimde / aramada / çekmecede doğru davranış), size sıralamada klasöre giriş (sayılar ve sıra, titreme yok), hızlı çift dokunuş.
- Geri alma: `head.onAct` içinde `crumbTo(i)` yerine `raw(ev, i)`; `onBackPressed`'ten `nl.backUp()` satırını sil; `NlModel.build` size satırını eski haline getir.

- **Faz 8b — seçim Kotlin'de anında:** `NativeList.emit` içinde `optimisticSel`: uzun basma satırı ekler, seçim modunda dokunma değiştirir (hit/hdr satırları hariç; ui.html `nlOn` ile aynı mantık); işaretler hemen çizilir, sayfa aynı işlemi tekrarlar. Sayfanın `nlSel` itmeleri 400 ms tutulur (`selHold`/`heldSel`/`applyHeld`), son durum sonra uygulanır → hızlı ardışık dokunuşlarda titreme yok. `setSel` = bekletme + `applySel` (eski gövde). Cihazda bak: hızlı çoklu seçim, seçimden çıkış (son öğeyi kaldır), galeri hücresi, aramada hit satırına dokunma (gezinmeli, seçmemeli), üst seçim çubuğu sayacının doğruluğu. Geri alma: `emit` içindeki `optimisticSel` satırını sil.

- **Faz 8c — diğer cihazlar / SMB için önbellekli geri/yukarı/giriş:** `rememberPage`, `childKey`, `crumbTo`, `backUp`, `goPath(dev,..)` artık yerel olmayan cihazlarda da çalışır, ama yalnızca sayfanın daha önce gönderdiği (≤10 dk) satırlarla; Kotlin uzak klasörü KENDİSİ listelemez (çift ağ trafiği + idle ön-listeleme yok: `warmFor`/`warmNeighbours` yalnızca `local`). Önbellekte yoksa eski yol (`emit`/`raw`) aynen. `headTo` artık uzak cihazda da yolu/özeti günceller. Arşiv (`!`) ve newest dışarıda. Cihazda bak: uzak cihazda klasöre gir → geri → anında; kırıntı; eski liste gösterilip ağ cevabıyla düzelmesi (silinen/yeni dosya), cihaz adı kırıntıda doğru kalıyor mu.
- **Yapılmadı (bilerek): sıralama/gizli/görünüm geçişini Kotlin'de önceden yapmak.** Geçiş HTML sayfa menüsünden başlıyor; Kotlin sayfadan önce bilemez, kazanç yok. Gerçek kazanç için native Sort/View sayfası gerekir.

- **Faz 8d — görüntüleyici açılışı Kotlin'de:** `NativeList.openNative(i)` (`emit` içinde, seçim yokken): resim / video / PDF satırına dokununca `PlayerActivity` / `ImageViewerActivity` / `PdfViewerActivity` doğrudan satırlardan kurulan JSON ile açılır (ui.html `nativePlay/nativeImg/nativePdf` ile aynı yük: video = klasördeki tüm videolar + yan `.srt/.vtt/.ass/.ssa`, resim = svg/gif hariç (HEIC için API≥28), PDF; `key` = `dev|yol/ad|boyut` aynı biçim → devam konumları korunur; URL = `Core.url + /api/dl?dev=&path=` ve `encodeURIComponent` eşleniği `encU`). Sayfaya hiç gidilmez. Yapılmayanlar sayfaya düşer: arama açık (`sqOn`), arama sonucu satırı, newest (`N:`), arşiv (`!`), svg/gif/diğer dosyalar. **Satır boyutu:** artık TÜM dosya satırları `s` (boyut) taşır (`ui.html nlBuild` + `NlModel.rows` → imza eşit kalır). Cihazda bak: video/resim/PDF açılışı (liste, compact, grid, galeri), oynatma listesi + altyazı, devam konumu, büyük klasörde açılış hızı, uzak cihaz/SMB'de açılış, boşluk/Türkçe/`+&#%` içeren dosya adları (URL kodlama). Geri alma: `emit` içindeki `openNative` satırını sil.

- **Faz 8e — küçük resim ön yükleme:** `NativeList.preload(down)` (kaydırma dinleyicisinde): kaydırma yönünde ekran dışındaki ~10 satırın (grid'de ×sütun) küçük resmi `loadTh` ile belleğe önceden alınır (en çok 24 eşzamanlı; `preloading` kümesi; başarısızlar `failed`'e girer, kümeden temizlenir). Galeri satırları hariç. Cihazda bak: hızlı kaydırmada boş kutu sayısı, bellek (LruCache = maxMemory/8), pil/IO (3 iş parçacığı havuzu aynı), uzak cihazda ağ yükü (preload uzak küçük resimleri de çeker: gerekirse `r.dev=="local"` şartı ekle).
- **Faz 5–7 (arama süzgeci Kotlin'de, çekerek yenile farkı, soğuk açılış uzak klasör) — yapılmadı:** arama kutusu HTML (`#sq`), yazı sayfada başlıyor (kazanç yok; native arama çubuğu = 8. madde ile birlikte); çekerek yenile zaten `loadMain` içinde içerik eşitse çizmiyor; soğuk açılış her zaman `local` ile başlıyor (splash yeterli).

- **Faz 8f — arama yazarken hızlandırma (ui.html):** `shown()` sıralanmış listeyi önbelleğe alır (`SHC`: items kimliği + uzunluk + hid + sort + asc; `size` sıralaması hariç, sayılar yerinde değişiyor) ve `S.q` süzgecini sıralamadan SONRA uygular (süzgeç sırayı korur → sonuç eskisiyle aynı). `#sq` `oninput` render'ı `requestAnimationFrame` ile birleştirir (kare başına en çok 1). Kotlin'de native arama çubuğu yok: yazı hâlâ HTML'de. Cihazda bak: aramada harf başına gecikme (5000+ dosya), sıralama değişince/dosya silinince/yeniden adlandırınca eski sıra görünmemeli, sonuç sırası eskisiyle aynı, aramayı temizleyince tam liste. Geri alma: `shown()` içinde `SHC` koşulunu `false` yap.

## 2n. Faz 9 — native arama kutusu + native Sort / View (2026-10-06) — DERLENMEDİ, TEST EDİLMEDİ
Yazdırma (PC yazdırma penceresi, önizleme, yazıcı seçimi) bilerek HTML'de kaldı.

**Yeni dosyalar**
- `NlSheets.kt`: `NlSheets` (Sort By iletişim kutusu = `openSort`, alttan View sayfası = `openView`), `NlGlyph` (24'lük yol çizen görünüm; arama satırıyla ortak), `NlRadio`. Durum JSON'u ui.html'den gelir (`nlSheetState()`: view, thumb, sort, asc, hid, palet `p`, sıralama etiketleri `lb`, ikon yolları `ic`). Seçimler `NativeList.pref(o)` ile geri döner: `{sort,asc}`, `{view}`, `{thumb}`, `{aa}`, `{hid}`.
- `NlSearch.kt`: `NlSearchRow` (geri, EditText, temizle; 48 dp, dolgu 0/4, 40 dp düğmeler, 16 sp). HTML `#srow` yerinde ve aynen kalır; native satır tam üstüne konur (dikdörtgen: ui.html `nlSrPush()`). **Katman dışında** ayrı bir view (`MainActivity` kökü): `NlOverlay` liste dışı dokunuşları sayfaya iletiyor; ayrıca "eşleşme yok" durumunda liste gizlense de kutu odağı kaybetmesin.

**Akış — Sort/View**
- ui.html: `openSort()` / `openView()` başında `NLSH` ise `NL.nlSort/nlView(nlSheetState())` ve çıkış. Seçim → Kotlin `pref(o)`: (1) bu telefonun düz klasöründe `NlModel.build` ile satırlar hemen yeniden kurulur (`instantPref`: yeni `cfg`, görünüm anahtarı `view[L]`, `compact/large` bayrakları, başlıkta sıralama etiketi/ok + özet; `navUntil` 2 sn koruma), (2) her durumda `nlPref(json)` ile sayfa aynı tercihi uygular (`setPref` / `toggleHidden` / `S.aa`) ve doğrular (imza eşitse yeniden çizim yok). Anında yol atlanır: seçim açık, arama açık, newest, arşiv (`!`), uzak cihaz, `hid`/`aa` değişikliği.
- `NativeList.sheets.labels` = son gönderilen sıralama etiketleri (başlık düğmesi için).

**Akış — Arama**
- ui.html `nlSrPush()` (150 ms döngü + `nlBurst` + `sOpen`): `body.srch` açıkken `#srow` dikdörtgeni, palet, sayfa/çekmece/diyalog üstünü kapatıyorsa `h:1` (kutu `INVISIBLE`), ve **sayfanın kendi koyduğu sorgu** `x` (ör. `goFind`; `NLSRQ` = native kutuda en son yazılan sorgu, `clearQ()` sıfırlar). Kapalıyken `'0'`.
- Yazı: `NlSearchRow.onText` → `NativeList.onSearchText` → (a) `nlSr('q', metin)` ile sayfa (`S.q`, `render()` rAF, 500 ms'de alt klasör araması; eski `oninput` ile aynı), (b) `filterNow(q)`: filtrelenmemiş satırlardan (`baseRows`, `pageQ` boşken gelen son liste) ui.html `shown()` ile aynı kural (ad küçük harf içerir, sıra korunur) → anında gösterir, `navUntil` 1,5 sn koruma. Sayfanın cevabı aynı satırlar → imza eşit → yeniden çizim yok. Galeri hücresi / sonuç satırı varsa veya 0 sonuç (sayfa "No matches" gösterir) / seçim açıkken anında süzgeç yapılmaz.
- `nlQ(q)` her `nlItems` öncesi gelir: `pageQ`. `setItems` içinde `searchOn && pageQ != curQ` ise **bayat cevap** yok sayılır (kutuda daha yeni sorgu var). Sayfa sorguyu kendi değiştirirse (`x`) Kotlin benimser ve `nlRe()` ile satırları yeniden ister.
- Düzeltme: `rememberPage` artık `pageQ` doluyken çağrılmıyor (süzülmüş liste klasörün satırı gibi önbelleğe girmesin).
- `sOpen(true)`: `NLSR` ise HTML girdisine odak verilmez, `NL.nlSrFocus()` native kutuya odak + klavye verir. `#sback`/`#sclr` yerine native düğmeler `nlSr('back'|'clear')` çağırır.

**Köprü (MainActivity):** `nlSearch, nlQ, nlSrFocus, nlSort, nlView, nlSheets, nlSearchOk`; kökte `nl.search` view'ı (GONE başlar).
**Palet:** `tl` (`--tl`, başlık/seçim turkuazı) eklendi (`NlPal.tl`).

**Kapatma anahtarları:** `NativeList.SEARCH=false` / `localStorage ls_nlq='0'` (HTML girdi), `NativeList.SHEETS=false` / `localStorage ls_nls='0'` (HTML sayfalar).
**Geri alma:** `NlSheets.kt`, `NlSearch.kt` sil; `NativeList`'te Faz 9 bloklarını (`search`, `sheets`, `setSearch…instantPref`, `setItems` bayat koruması) kaldır; `MainActivity`'de 7 köprü + `addView(nl.search…)`; ui.html'de `NLSH/NLSR/nlSheetState/nlPref/nlSrPush/nlSr`, `openSort/openView` başındaki satır, `sOpen`, `clearQ` içindeki `NLSRQ`, `nlRender`'daki `nlQ`.
**Derlemede ilk bakılacak yerler:** `NlSheets.kt` (`radioRow` içindeki `Triple<…, () -> Unit>` tür çıkarımı, `replaceFirstChar`), `NlSearch.kt` (`LayoutParams` kısa adı, `setOnEditorActionListener`), `NativeList.instantPref` (`return@runOnUiThread`), `head.set(headData, true)`.
**Cihazda bak:** Sort düğmesi/menüsü → Sort By kutusu ve View sayfası; seçimde liste anında yeni sırada/görünümde (list↔compact↔grid, küçük↔büyük küçük resim, kaydırma konumu), başlıktaki etiket/ok, "Apply to all folders", "Show hidden files", uzak cihazda (sayfa yolu). Arama: simge → kutu + klavye hemen; harf başına anında süzülme (5000+ dosya), hızlı yazı/silme (bayat cevap titremesi), temizle (X), geri (←) ve sistem geri tuşu (önce klavye, sonra arama), alt klasör sonuçlarının gelmesi, sonuç satırına dokunma, 0 sonuç → yazmaya devam edince liste dönmeli ve kutu odağı kalmalı, "find this file" (`goFind`) kutuya adı yazmalı, çekmece/diyalog açılınca kutu gizlenmeli, açık/koyu tema, yatay mod, döndürmede kutu konumu.

## 2o. Faz 10 — native uygulama çerçevesi: üst çubuk, seçim çubuğu, FAB, alt dock, snackbar, indirme kartları (2026-10-06) — DERLENMEDİ, TEST EDİLMEDİ
Aynı şema (NlHead gibi): HTML öğeler **yerinde ve aynen kalır** (yerleşim, `--dockh`, zamanlayıcılar, tüm işleyiciler onlarda); native görünüm tam üstlerine çizilir, dokunma geri `nlCh(ev, id)` ile aynı HTML öğeye `click()` olarak döner. Davranış kayması olamaz.
- **Yeni `NlChrome.kt`:** `NlCp` (palet), `NlHitView` (ortak taban: dokunma hedefleri, basılı durum; hedef dışı dokunma TÜKETİLMEZ → sayfaya/listeye geçer), `NlBarView` (`.mainb`: menü, başlık, cihaz adı + kalem, ara, görünüm, yenile (dönme animasyonu 800 ms), bölünmüş ekran, ayarlar; `.selb`: iptal, sayaç, tümünü seç; 44 dp), `NlFabView` (`#fab`, elevation 6), `NlDockView` (`#clip` şeridi + `#bar` ızgarası, >5 eylem = 2 satır, üstte 10 dp yumuşak gölge), `NlToastView` (`#toast`: metin, Cancel, ilerleme), `NlDlView` (`#dlw` indirme kartları), `NlChrome` (sahip; `layer` tam ekran, arka plansız, tıklanamaz).
- **Konum/ölçü kaynağı:** her öğenin dikdörtgeni DOM'dan (`getBoundingClientRect`) gelir; iç yerleşim Kotlin'de CSS ölçüleriyle (düğme 40, simge 24, başlık 18/600, cihaz adı 12, dock hücresi 57,4 dp, pill 48x30, şerit 48 dp, snackbar 14/19,6 dp, kart 12/14 dp).
- **`MainActivity.kt`:** köprü `nlChromeOk()`, `nlChrome(json)`; kökte `chrome.layer` (`nl.search`'ten sonra); dokunma `window.nlCh(ev,id)` ile sayfaya gider.
- **`ui.html`:** `nlChPush()` (150 ms döngü + `nlBurst` + `renderBar` sonu; JSON değişmedikçe göndermez), `nlChPal()`, `nlNormPath()` (SVG yollarının yay bayraklarını boşlukla yazar: Android `PathParser` ile tarayıcı aynı okur; tüm kullanılan simgeler node ile denendi), `nlIc()`, `window.nlCh`; `renderBar()` artık `NLACT` (işleyiciler) ve `NLACL` ({k,l,c}) doldurur.
- **Gizleme kuralı:** `#pv` / `#dual` / çekmece (`body.dopen` ya da `#dscrim` görünür) → HEPSİ gizli (`'0'`); `#sheet` / `#dlg` açıkken yalnızca üst çubuk + FAB + dock gizli (sayfanın kendi karartmalı kopyası görünür), snackbar ve indirme kartları kalır (HTML'de de karartmanın üstündeydi).
- **Olay eşlemesi:** `btn` → `document.getElementById(id).click()` (`menub srch tune scan dualb cog nm xsel allsel fab tc`), `act` → `NLACT[i]()`, `paste` → `#clip .tbtn`, `clipx` → `#clip .ibtn`, `dlx` → `DLS[k].cx`.
- **Kapatma:** `NlChrome.ENABLED=false` veya `localStorage.setItem('ls_nlc','0')` → yalnızca HTML çerçeve. Geri alma: `NlChrome.kt` sil; `MainActivity`'de `chrome` alanı + 2 köprü + `addView(chrome.layer…)` + `NlChrome(...)` satırı; ui.html'de `nlChPush…nlCh` bloğu, `nlBurst`/interval/`renderBar` içindeki `nlChPush` çağrıları, `NLACT/NLACL`.
- **Derlemede ilk bakılacak yerler:** `NlChrome.kt` — `NlHitView` içindeki `protected class Hit` (alt sınıflarda kullanım), `Outline.setAlpha` (API 24), `StaticLayout.Builder`, `cv.rotate(360f * t / 800f …)` (Float*Long), `NlChrome.set` içindeki `?.let { … for … }`.
- **Cihazda bak:** (1) açık/koyu temada üst çubuk HTML ile 1 px'e kadar aynı mı (başlık/cihaz adı kısalması, kalem simgesi, sağ düğmeler; bölünmüş ekran düğmesi yalnızca geniş ekranda); (2) menü → çekmece açılırken çubuğun HTML'e geçişi titremiyor mu; (3) seçim modu: sayaç + boyut, iptal, tümünü seç; (4) FAB: etiket (New folder / Extract all), basınca küçülme, gölge; (5) dock: eylem sayısı 1..5 ve >5 (iki satır), pri/dng renkleri, kopyala→yapıştır şeridi ("Paste here", X), `has` olup seçim yokken; (6) snackbar: kısa mesaj, çok satırlı uzun mesaj, ilerleme + Cancel (kopyala/taşı/zip işleri), dock yüksekliği değişince konum; (7) indirme kartları: yüzde, belirsiz çubuk animasyonu, Cancel, bitince kaybolma; (8) bir iletişim kutusu/sayfa açılınca çubuk/FAB/dock'un sayfa karartması altında normal görünmesi; (9) bayat dokunma: seçim değişirken dock eylem dizini (`renderBar` sonunda anında itilir, yine de hızlı çift dokunuşu dene).
- **Hâlâ HTML (sıradaki fazlar):** çekmece (sekmeler, favoriler, cihazlar, yazıcılar), cihaz çipleri şeridi (`#peers`) + `#hint` + `#free` + `#banner`, tüm iletişim kutuları (`dlg`: yeniden adlandır, yeni klasör, sil, ayrıntılar, zip, cihaz seçici), ayarlar, SMB, yazdırma penceresi + önizleme (bilerek), bölünmüş ekran (`#dual`).

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
