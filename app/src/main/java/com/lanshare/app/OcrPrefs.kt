package com.lanshare.app

import android.content.Context

/** Setting "Text in pictures" (Settings screen in ui.html, also reachable by a long press on T in the image viewer). */
object OcrPrefs {
    const val ALWAYS = 0       // read pictures in the background as soon as they are shown
    const val CHARGING = 1     // ... only while the charger is connected
    const val OFF = 2          // only the picture the user pressed T on

    fun mode(c: Context): Int = c.getSharedPreferences("viewer", Context.MODE_PRIVATE).getInt("ocr_auto", ALWAYS).coerceIn(0, 2)
    fun setMode(c: Context, m: Int) { c.getSharedPreferences("viewer", Context.MODE_PRIVATE).edit().putInt("ocr_auto", m.coerceIn(0, 2)).apply() }

    // ---- which text recognition model reads the pictures (Settings -> Text recognition model)
    const val ENG_AUTO = 0     // PaddleOCR when its model files are on the phone, else ML Kit
    const val ENG_PADDLE = 1   // PaddleOCR (downloads its model on Wi-Fi; ML Kit reads until it is there)
    const val ENG_MLKIT = 2    // ML Kit only (Google Play Services)

    fun engine(c: Context): Int = c.getSharedPreferences("viewer", Context.MODE_PRIVATE).getInt("ocr_engine", ENG_AUTO).coerceIn(0, 2)

    /** Saves the choice. Text saved by the other model is dropped (cacheDir/ocr) so pictures are read again with the new one. */
    fun setEngine(c: Context, m: Int) {
        val v = m.coerceIn(0, 2)
        if (v == engine(c)) return
        c.getSharedPreferences("viewer", Context.MODE_PRIVATE).edit().putInt("ocr_engine", v).apply()
        try { java.io.File(c.cacheDir, "ocr").listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }
}
