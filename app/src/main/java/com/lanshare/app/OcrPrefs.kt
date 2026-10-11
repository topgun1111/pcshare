package com.lanshare.app

import android.content.Context

/** Setting "Text in pictures" (Settings screen in ui.html, also reachable by a long press on T in the image viewer). */
object OcrPrefs {
    const val ALWAYS = 0       // read pictures in the background as soon as they are shown
    const val CHARGING = 1     // ... only while the charger is connected
    const val OFF = 2          // only the picture the user pressed T on

    fun mode(c: Context): Int = c.getSharedPreferences("viewer", Context.MODE_PRIVATE).getInt("ocr_auto", ALWAYS).coerceIn(0, 2)
    fun setMode(c: Context, m: Int) { c.getSharedPreferences("viewer", Context.MODE_PRIVATE).edit().putInt("ocr_auto", m.coerceIn(0, 2)).apply() }
}
