package com.lanshare.app

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/**
 * App font. Default: Figtree Medium / Semibold (OFL, assets/fonts). Settings -> Interface -> Font picks one of [LIST] (Google Fonts, OFL, Latin + Latin-Extended
 * subset incl. Turkish, files assets/fonts/ff_<id>.ttf, most are variable fonts: weight 500 / 600 are set through the variation axis on Android 8+).
 * The same id is applied to the WebView page by ui.html (served as /appfont/<id>.ttf by MainActivity). Falls back to the system font if a file is missing.
 */
object Fnt {
    @Volatile private var m: Typeface? = null
    @Volatile private var s: Typeface? = null
    @Volatile private var cur: String = ""

    /** id, display name. "figtree" = built-in default, "system" = the phone's own font. */
    @JvmField val LIST: List<Pair<String, String>> = listOf(
        "figtree" to "Figtree (default)", "system" to "System font",
        "anton" to "Anton",
        "archivo" to "Archivo",
        "barlow" to "Barlow",
        "bebasneue" to "Bebas Neue",
        "bitter" to "Bitter",
        "cabin" to "Cabin",
        "caveat" to "Caveat",
        "comfortaa" to "Comfortaa",
        "crimsontext" to "Crimson Text",
        "dancingscript" to "Dancing Script",
        "dmsans" to "DM Sans",
        "exo2" to "Exo 2",
        "heebo" to "Heebo",
        "ibmplexsans" to "IBM Plex Sans",
        "inter" to "Inter",
        "josefinsans" to "Josefin Sans",
        "jost" to "Jost",
        "karla" to "Karla",
        "lexend" to "Lexend",
        "librebaskerville" to "Libre Baskerville",
        "lora" to "Lora",
        "manrope" to "Manrope",
        "merienda" to "Merienda",
        "montserrat" to "Montserrat",
        "mulish" to "Mulish",
        "nunito" to "Nunito",
        "opensans" to "Open Sans",
        "oswald" to "Oswald",
        "outfit" to "Outfit",
        "overpass" to "Overpass",
        "pacifico" to "Pacifico",
        "playfairdisplay" to "Playfair Display",
        "poppins" to "Poppins",
        "ptserif" to "PT Serif",
        "quicksand" to "Quicksand",
        "raleway" to "Raleway",
        "redhatdisplay" to "Red Hat Display",
        "rubik" to "Rubik",
        "sora" to "Sora",
        "sourcesans3" to "Source Sans 3",
        "spacegrotesk" to "Space Grotesk",
        "titilliumweb" to "Titillium Web",
        "varelaround" to "Varela Round",
        "worksans" to "Work Sans",
        "zillaslab" to "Zilla Slab"
    )

    fun valid(id: String) = LIST.any { it.first == id }

    fun init(c: Context) {
        val id = UiScale.font(c).let { if (valid(it)) it else "figtree" }
        if (m != null && s != null && cur == id) return
        load(c, id)
    }

    /** Re-read the chosen font (after Settings changed it). */
    fun reload(c: Context) { load(c, UiScale.font(c).let { if (valid(it)) it else "figtree" }) }

    private fun load(c: Context, id: String) {
        var mm: Typeface? = null; var ss: Typeface? = null
        when (id) {
            "figtree" -> {
                try { mm = Typeface.createFromAsset(c.assets, "fonts/figtree-medium.ttf") } catch (_: Throwable) { }
                try { ss = Typeface.createFromAsset(c.assets, "fonts/figtree-semibold.ttf") } catch (_: Throwable) { }
            }
            "system" -> { mm = Typeface.create("sans-serif-medium", Typeface.NORMAL); ss = Typeface.create("sans-serif", Typeface.BOLD) }
            else -> {
                val path = "fonts/ff_" + id + ".ttf"
                mm = weight(c, path, 500); ss = weight(c, path, 600)
            }
        }
        m = mm; s = ss; cur = id
    }

    private fun weight(c: Context, path: String, w: Int): Typeface? {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val t = Typeface.Builder(c.assets, path).setFontVariationSettings("'wght' " + w).build()
                if (t != null) return t
            }
        } catch (_: Throwable) { }
        return try {
            val t = Typeface.createFromAsset(c.assets, path)
            if (w >= 600) Typeface.create(t, Typeface.BOLD) else t       // static font (or Android 7): bold style = synthetic / real bold
        } catch (_: Throwable) { null }
    }

    fun med(): Typeface = m ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun semi(): Typeface = s ?: Typeface.create("sans-serif", Typeface.BOLD)
}
