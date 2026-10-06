package com.lanshare.app

/**
 * Palette of the native screen = ui.html's CSS variables (:root and :root[data-theme=dark]) as ARGB, in the JSON shape NlPal reads.
 * Generated from ui.html; keep in sync if the CSS colours change.
 */
object NlTheme {
    private const val LIGHT = "{\"card\":4294572537,\"bg\":4294243572,\"fg\":4279966491,\"mut\":4285229931,\"bd\":4292072403,\"sel\":4291813623,\"onsel\":4278856277,\"hov\":251658240,\"ac\":4279922641,\"onac\":4294967295,\"warn\":4294552320,\"cont\":4293717228,\"tl\":4278225275,\"k\":{\"folder\":[4287388928,4294959006],\"img\":[4279528494,4291096272],\"vid\":[4285933480,4293975039],\"aud\":[4289925739,4294957290],\"pdf\":[4289930782,4294565596],\"zip\":[4284301367,4293647312],\"apk\":[4278217052,4290965736],\"doc\":[4278933456,4292076541],\"file\":[4282664774,4292994017]}}"
    private const val DARK = "{\"card\":4279900698,\"bg\":4279374354,\"fg\":4293322470,\"mut\":4288322202,\"bd\":4281545523,\"sel\":4280499038,\"onsel\":4292273403,\"hov\":352321535,\"ac\":4287280376,\"onac\":4278921819,\"warn\":4294825571,\"cont\":4280558628,\"tl\":4283283116,\"k\":{\"folder\":[4294959006,4284236544],\"img\":[4291096272,4279194147],\"vid\":[4293975039,4284161666],\"aud\":[4294957290,4286189385],\"pdf\":[4294565596,4287372568],\"zip\":[4293647312,4283315246],\"apk\":[4290965736,4278210634],\"doc\":[4292076541,4278731424],\"file\":[4292994017,4282664774]}}"

    fun pal(dark: Boolean): NlPal = NlPal(org.json.JSONObject(if (dark) DARK else LIGHT))
    /** ui.html --bg, --fg, --mut, --ac, --bd, --sel, --cont as ARGB for the plain widgets of FilesActivity. */
    class Cols(val bg: Int, val fg: Int, val mut: Int, val ac: Int, val onac: Int, val bd: Int, val cont: Int, val sel: Int, val warn: Int)
    fun cols(dark: Boolean): Cols = if (dark) Cols(-15592942, -1644826, -6645094, -7686920, -16045477, -13421773, -14408668, -14468258, -141725) else Cols(-723724, -15000805, -9737365, -15044655, -1, -2894893, -1250068, -3153673, -414976)
}
