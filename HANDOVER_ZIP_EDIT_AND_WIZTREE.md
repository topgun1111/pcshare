# HANDOVER - ZIP editing + WizTree view (2026-10-17)

Hiçbiri derlenmedi / cihazda denenmedi (Android SDK yok). Önce push -> Actions derle -> hata logunu yapıştır.

## 1. ZIP düzenleme (bitti, mantığı test edildi)
- `core/ZipEdit.kt` (YENİ): yerinde ekleme (eski merkezi dizinin yerine yazar, hata olursa geri alır), silme (kalanları ham kopyalar, geçici dosya + rename), aynı isim = önce yeni eklenir sonra eski silinir. ZIP64 / RAR desteklenmez.
- `core/ArchiveFs.kt`: `ArcEp.write/mkdir/remove/removeMany` sadece bu telefondaki .zip/.cbz için çalışır.
- `core/Jobs.kt`: arşive "kes" = kopyala; toplu silme tek yeniden yazımla (`removeMany`).
- `core/Routes.kt`: `/api/addzip`.
- `assets/ui.html`: açık ZIP'te Delete, Paste, "Edit ZIP" menüsü (Add files picker, New folder, Paste, Extract all).
- Test: Kotlin ile ZipEdit tek başına derlendi; ekle/değiştir/sil/hatalı ekleme geri alma, `unzip -t` temiz.

## 2. WizTree görünümü (YAZILDI, entegre edildi, derlenmedi)
- `StorageWiz.kt` (YENİ): siyah tema; Tree View (+/- açılır, Percent/Size/Files), File View (filtre: `*.jpg`, `a|b`, `!x`, `>10mb`, `<1gb`, yol için `/`; Max 1000/5000/20000), Types (uzantı rengi, %, boyut, dosya sayısı; dokun = treemap'te kırmızı vurgu, uzun bas = File View'da filtrele), dosya düzeyinde "cushion" treemap (uzantıya göre renk, tek dokun seç, çift dokun klasöre gir, uzun bas detay), durum çubuğu, CSV dışa aktarma (sekmeye göre). Yatay ekranda liste solda treemap sağda.
- `StorageActivity.kt`: `wiz`/`wizHost`, varsayılan açık (`storage_ui` prefs `wiz`). Header'daki "Classic" ile eski ekrana, eski ekrandaki "WizTree" düğmesiyle geri. Silme (details diyaloğu) sonrası `wiz.refresh()`, Back = üst klasör.
- Bilinen eksikler: "Allocated" boyutu yok; File View yalnızca boyuta göre sıralı; çok büyük taramalarda "small files" (agg) düğümleri Types'ta tek satır; açık/koyu tema sabit koyu.
- Sonraki adım: derleme hatalarını düzelt, cihazda treemap hızı (MAXR=7000 karo) ve Types renklerini kontrol et.
