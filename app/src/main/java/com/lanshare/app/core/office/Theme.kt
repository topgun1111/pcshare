package com.lanshare.app.core.office

/**
 * Part 7: Office theme (word|xl|ppt/theme/theme1.xml): 12 scheme colours + major / minor Latin fonts, and Word / Excel / DrawingML colour maths.
 * Pure Kotlin (no android.*). Colours are "RRGGBB" strings (upper case, no '#').
 *
 * Maths (checked against Word swatches: accent1 4472C4 + themeTint 99 = 8FAADC, + themeShade BF = 2F5496 (rounding: Word shows 2F5597 for lumMod 75)):
 *  - tint  t (0..1, = hex / 255):  HSL lightness L' = L * t + (1 - t)      (Word "Lighter n%")
 *  - shade s (0..1, = hex / 255):  HSL lightness L' = L * s                (Word "Darker n%")
 *  - DrawingML lumMod m / lumOff o (fractions):  L' = L * m + o
 *  - Excel `tint` attribute (-1..1): < 0 -> L' = L * (1 + tint);  > 0 -> L' = L * (1 - tint) + tint
 */
class Theme(private val colors: Map<String, String>, val major: String?, val minor: String?) {

    /** Colour of a scheme name as written in Word (themeColor), DrawingML (schemeClr) or Excel (theme index via [byIndex]); null = unknown. */
    fun color(name: String?): String? {
        if (name == null) return null
        val k = when (name) {
            "dark1", "text1", "tx1" -> "dk1"; "light1", "background1", "bg1" -> "lt1"
            "dark2", "text2", "tx2" -> "dk2"; "light2", "background2", "bg2" -> "lt2"
            "hyperlink" -> "hlink"; "followedHyperlink" -> "folHlink"
            else -> name
        }
        return colors[k]
    }

    /** Excel `theme="n"` index: 0 lt1, 1 dk1, 2 lt2, 3 dk2, 4..9 accent1..6, 10 hlink, 11 folHlink (Excel swaps the first two pairs). */
    fun byIndex(i: Int): String? = color(when (i) { 0 -> "lt1"; 1 -> "dk1"; 2 -> "lt2"; 3 -> "dk2"; 4 -> "accent1"; 5 -> "accent2"; 6 -> "accent3"; 7 -> "accent4"; 8 -> "accent5"; 9 -> "accent6"; 10 -> "hlink"; 11 -> "folHlink"; else -> "" })

    /** Word: themeColor + optional themeTint / themeShade (hex strings "00".."FF") -> RRGGBB; null when the theme has no such colour. */
    fun word(themeColor: String?, tintHex: String? = null, shadeHex: String? = null): String? {
        var c = color(themeColor) ?: return null
        hex255(tintHex)?.let { c = tint(c, it) }
        hex255(shadeHex)?.let { c = shade(c, it) }
        return c
    }

    /** DrawingML: scheme colour (or [rgb] when given) with lumMod / lumOff / tint / shade in 1/1000 percent (e.g. 75000 = 75 %). */
    fun draw(scheme: String?, rgb: String? = null, lumMod: Int? = null, lumOff: Int? = null, tint: Int? = null, shade: Int? = null): String? {
        var c = rgb ?: color(scheme) ?: return null
        if (lumMod != null || lumOff != null) c = lum(c, (lumMod ?: 100000) / 100000.0, (lumOff ?: 0) / 100000.0)
        if (tint != null) c = tint(c, tint / 100000.0)
        if (shade != null) c = shade(c, shade / 100000.0)
        return c
    }

    companion object {
        /** The Office 2013+ default theme (used when a package has no theme part). */
        val DEFAULT = Theme(mapOf("dk1" to "000000", "lt1" to "FFFFFF", "dk2" to "44546A", "lt2" to "E7E6E6", "accent1" to "4472C4", "accent2" to "ED7D31",
            "accent3" to "A5A5A5", "accent4" to "FFC000", "accent5" to "5B9BD5", "accent6" to "70AD47", "hlink" to "0563C1", "folHlink" to "954F72"), "Calibri Light", "Calibri")

        private val NAMES = setOf("dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

        /** Parses a theme part. Returns [DEFAULT] when [x] is null or has no colour scheme; missing single colours fall back to the default theme. */
        fun parse(x: MiniXml?): Theme {
            if (x == null) return DEFAULT
            val cols = HashMap<String, String>(); var major: String? = null; var minor: String? = null
            try {
                var inScheme = false; var slot: String? = null; var mode = 0
                loop@ while (true) when (x.next()) {
                    MiniXml.T.EOF -> break@loop
                    MiniXml.T.START -> {
                        val n = x.name
                        when {
                            n == "clrScheme" -> inScheme = true
                            n == "majorFont" -> mode = 1
                            n == "minorFont" -> mode = 2
                            inScheme && slot == null && n in NAMES -> slot = n
                            slot != null && n == "srgbClr" -> { x.attr("val")?.takeIf { it.length == 6 }?.let { cols[slot!!] = it.uppercase() } }
                            slot != null && n == "sysClr" -> { (x.attr("lastClr") ?: x.attr("val")?.takeIf { it.length == 6 })?.let { cols[slot!!] = it.uppercase() } }
                            n == "latin" -> { val tf = x.attr("typeface")?.takeIf { it.isNotEmpty() && !it.startsWith("+") }; if (mode == 1 && major == null) major = tf else if (mode == 2 && minor == null) minor = tf }
                        }
                    }
                    MiniXml.T.END -> when (x.name) { "clrScheme" -> inScheme = false; "majorFont", "minorFont" -> mode = 0; in NAMES -> slot = null }
                    else -> {}
                }
            } catch (_: Throwable) {}
            if (cols.isEmpty() && major == null && minor == null) return DEFAULT
            for ((k, v) in DEFAULT.colors) if (!cols.containsKey(k)) cols[k] = v
            return Theme(cols, major, minor)
        }

        private fun hex255(h: String?): Double? = h?.trim()?.takeIf { it.length in 1..2 }?.toIntOrNull(16)?.let { it / 255.0 }

        fun tint(rgb: String, t: Double): String = withL(rgb) { it * t + (1 - t) }
        fun shade(rgb: String, s: Double): String = withL(rgb) { it * s }
        fun lum(rgb: String, mod: Double, off: Double): String = withL(rgb) { it * mod + off }
        /** Excel `tint` attribute of a colour (-1 darker .. 1 lighter). */
        fun excelTint(rgb: String, tint: Double): String = if (tint < 0) withL(rgb) { it * (1 + tint) } else withL(rgb) { it * (1 - tint) + tint }

        private fun withL(rgb: String, f: (Double) -> Double): String {
            val v = rgb.toIntOrNull(16) ?: return rgb
            val hsl = toHsl(((v shr 16) and 255) / 255.0, ((v shr 8) and 255) / 255.0, (v and 255) / 255.0)
            val l = f(hsl[2]).coerceIn(0.0, 1.0)
            val rgbOut = fromHsl(hsl[0], hsl[1], l)
            return String.format("%02X%02X%02X", rgbOut[0], rgbOut[1], rgbOut[2])
        }

        private fun toHsl(r: Double, g: Double, b: Double): DoubleArray {
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2
            if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
            val d = mx - mn
            val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            val h = when (mx) { r -> ((g - b) / d + (if (g < b) 6 else 0)); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6
            return doubleArrayOf(h, s, l)
        }

        private fun fromHsl(h: Double, s: Double, l: Double): IntArray {
            if (s == 0.0) { val v = Math.round(l * 255).toInt(); return intArrayOf(v, v, v) }
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s; val p = 2 * l - q
            fun hue(t0: Double): Double { var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1
                return if (t < 1.0 / 6) p + (q - p) * 6 * t else if (t < 0.5) q else if (t < 2.0 / 3) p + (q - p) * (2.0 / 3 - t) * 6 else p }
            return intArrayOf(Math.round(hue(h + 1.0 / 3) * 255).toInt(), Math.round(hue(h) * 255).toInt(), Math.round(hue(h - 1.0 / 3) * 255).toInt())
        }
    }
}
