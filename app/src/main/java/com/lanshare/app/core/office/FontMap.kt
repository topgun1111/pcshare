package com.lanshare.app.core.office

/**
 * Office fidelity Part 3: maps the font a document asks for to a bundled, metric-compatible font and a CSS fallback stack.
 * Pure Kotlin (no android.*), JVM-testable (tools/office_selftest/Main.kt --fonts).
 *
 * Bundled (assets/fonts/office/, all SIL OFL 1.1, unmodified): Carlito = Calibri, Caladea = Cambria,
 * Liberation Sans = Arial / Helvetica, Liberation Serif = Times New Roman, Liberation Mono = Courier New.
 * "Metric-compatible" = same glyph widths, so line breaks match Microsoft Office.
 */
object FontMap {
    /** The WebView page is loaded with this base URL (same origin as the font URLs, so no CORS check applies). */
    const val HOST = "appassets.androidplatform.net"
    const val BASE = "https://$HOST/"
    const val FONT_URL = "https://$HOST/office-fonts/"
    const val PATH = "/office-fonts/"
    const val ASSET_DIR = "fonts/office/"

    /** bundled family -> file stem (files are `<stem>-Regular|Bold|Italic|BoldItalic.ttf`) */
    private val STEM = linkedMapOf(
        "Carlito" to "Carlito", "Caladea" to "Caladea",
        "Liberation Sans" to "LiberationSans", "Liberation Serif" to "LiberationSerif", "Liberation Mono" to "LiberationMono")
    private val STYLES = listOf("Regular" to (400 to "normal"), "Bold" to (700 to "normal"), "Italic" to (400 to "italic"), "BoldItalic" to (700 to "italic"))

    /** requested family (lower case) -> bundled family */
    private val ALIAS = mapOf(
        "calibri" to "Carlito", "calibri light" to "Carlito", "carlito" to "Carlito",
        "cambria" to "Caladea", "caladea" to "Caladea",
        "arial" to "Liberation Sans", "helvetica" to "Liberation Sans", "liberation sans" to "Liberation Sans",
        "times new roman" to "Liberation Serif", "times" to "Liberation Serif", "liberation serif" to "Liberation Serif",
        "courier new" to "Liberation Mono", "courier" to "Liberation Mono", "liberation mono" to "Liberation Mono")

    /** CSS stack after the bundled name: the real name first (a device that has it is fine too), then a generic. */
    private val TAIL = mapOf(
        "Carlito" to "Calibri,Arial,sans-serif", "Caladea" to "Cambria,'Times New Roman',serif",
        "Liberation Sans" to "Arial,Helvetica,sans-serif", "Liberation Serif" to "'Times New Roman',Times,serif",
        "Liberation Mono" to "'Courier New',Courier,monospace")

    private val SERIF = Regex("(times|georgia|garamond|palatino|book antiqua|bookman|century|constantia|baskerville|didot|minion|serif)", RegexOption.IGNORE_CASE)
    private val MONO = Regex("(courier|consolas|monaco|menlo|lucida console|mono|typewriter|code)", RegexOption.IGNORE_CASE)

    /** "  'Calibri'  " / "Calibri, sans-serif" / null -> clean family name ("Calibri"), or null when nothing usable is left. */
    fun clean(requested: String?): String? {
        val first = requested?.substringBefore(',') ?: return null
        val n = first.replace(Regex("[^\\p{L}\\p{N} _.+-]"), "").trim().replace(Regex("\\s+"), " ")
        return n.takeIf { it.isNotEmpty() && it.length <= 64 }
    }

    /** The bundled family that stands in for [requested] (case-insensitive, quotes ignored), or null when it has no bundled twin. */
    fun bundled(requested: String?): String? = clean(requested)?.let { ALIAS[it.lowercase()] }

    /** Generic CSS family for an unknown font name. */
    fun generic(name: String): String = when {
        MONO.containsMatchIn(name) -> "monospace"
        SERIF.containsMatchIn(name) && !name.contains("sans", true) -> "serif"
        else -> "sans-serif"
    }

    /**
     * Value for CSS `font-family`. Bundled twin first, so a line breaks the same on every phone:
     * Calibri -> `Carlito,Calibri,Arial,sans-serif`. Unknown fonts keep their own name first, then a generic.
     * Null / empty -> the Word default (Calibri). Never contains a double quote (safe inside style="...").
     */
    fun stack(requested: String?): String {
        val n = clean(requested) ?: return stack("Calibri")
        val b = ALIAS[n.lowercase()]
        if (b != null) return q(b) + "," + TAIL.getValue(b)
        return q(n) + "," + generic(n)
    }

    /** Part 5: natural single-line height of a font as a multiple of its size (what Word calls "single" spacing); CSS `line-height:normal` equals it, a numeric multiple of Word needs it. */
    fun lineFactor(requested: String?): Double = when (bundled(requested ?: "Calibri")) {
        "Carlito" -> 1.2207; "Caladea" -> 1.1719; "Liberation Sans", "Liberation Serif" -> 1.1499; "Liberation Mono" -> 1.1328; else -> 1.15
    }

    private fun q(n: String) = if (n.contains(' ')) "'$n'" else n

    /** `@font-face` rules for every bundled face (the WebView downloads a face only when text really uses it). [urlBase] ends with '/'. */
    fun fontFaceCss(urlBase: String = FONT_URL): String {
        val sb = StringBuilder(4096)
        for ((fam, stem) in STEM) for ((suffix, ws) in STYLES)
            sb.append("@font-face{font-family:'").append(fam).append("';font-weight:").append(ws.first).append(";font-style:").append(ws.second)
                .append(";src:url(").append(urlBase).append(stem).append('-').append(suffix).append(".ttf) format('truetype')}")
        return sb.toString()
    }

    /** All bundled file names (e.g. "Carlito-Bold.ttf"). */
    val files: Set<String> = STEM.values.flatMap { s -> STYLES.map { "$s-${it.first}.ttf" } }.toSet()

    /** Asset path for a requested file name, or null when it is not one of the bundled fonts (no path tricks). */
    fun assetPath(fileName: String?): String? = if (fileName != null && fileName in files) ASSET_DIR + fileName else null
}
