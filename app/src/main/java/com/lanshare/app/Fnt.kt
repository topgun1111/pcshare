package com.lanshare.app

import android.content.Context
import android.graphics.Typeface

/** App font (Times New Roman look-alike: Liberation Serif Regular / Bold, Latin + Turkish subset in assets/fonts). Falls back to the system font if the files are missing. */
object Fnt {
    @Volatile private var m: Typeface? = null
    @Volatile private var s: Typeface? = null

    fun init(c: Context) {
        if (m != null && s != null) return
        try { m = Typeface.createFromAsset(c.assets, "fonts/tnr-regular.ttf") } catch (_: Throwable) { }
        try { s = Typeface.createFromAsset(c.assets, "fonts/tnr-bold.ttf") } catch (_: Throwable) { }
    }

    fun med(): Typeface = m ?: Typeface.create("serif", Typeface.NORMAL)
    fun semi(): Typeface = s ?: Typeface.create("serif", Typeface.BOLD)
}
