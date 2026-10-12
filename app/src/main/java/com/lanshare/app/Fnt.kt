package com.lanshare.app

import android.content.Context
import android.graphics.Typeface

/** App font: Figtree Medium / Semibold (OFL), Latin + Latin-Extended (Turkish) subset in assets/fonts. Falls back to the system font if the files are missing. */
object Fnt {
    @Volatile private var m: Typeface? = null
    @Volatile private var s: Typeface? = null

    fun init(c: Context) {
        if (m != null && s != null) return
        try { m = Typeface.createFromAsset(c.assets, "fonts/figtree-medium.ttf") } catch (_: Throwable) { }
        try { s = Typeface.createFromAsset(c.assets, "fonts/figtree-semibold.ttf") } catch (_: Throwable) { }
    }

    fun med(): Typeface = m ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun semi(): Typeface = s ?: Typeface.create("sans-serif", Typeface.BOLD)
}
