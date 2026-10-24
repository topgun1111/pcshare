package com.lanshare.app.core.usearch

/**
 * Shared text folding for universal search (decision D5). Pure JVM, no android imports.
 *
 * Rules (always): lower-case with Turkish rules, so "I", dotted capital I (U+0130) and dotless i (U+0131) all become plain "i".
 * Rule (only when keepDiacritics is false, the default = forgiving, Everything-like): the Turkish letters
 * c-cedilla, g-breve, o-umlaut, s-cedilla, u-umlaut and the circumflex vowels a, i, u lose their mark.
 * Every other character is lower-cased with Character.toLowerCase and otherwise left alone (no full Unicode decomposition).
 *
 * Length preserving: each UTF-16 unit maps to exactly one unit, so a match position in the folded text is the same position in the original text (needed for highlighting).
 */
object Fold {
    fun fold(s: String, keepDiacritics: Boolean = false): String {
        val n = s.length
        var i = 0
        while (i < n) {   // fast path: nothing to change (plain lower-case ASCII)
            val c = s[i]
            if (c.code >= 128 || (c >= 'A' && c <= 'Z')) break
            i++
        }
        if (i == n) return s
        val out = s.toCharArray()
        while (i < n) {
            out[i] = foldChar(out[i], keepDiacritics)
            i++
        }
        return String(out)
    }

    fun foldChar(c: Char, keepDiacritics: Boolean): Char {
        val code = c.code
        if (code < 128) return if (c >= 'A' && c <= 'Z') (code + 32).toChar() else c
        return when (code) {
            0x130, 0x131 -> 'i'                                          // dotted capital I, dotless i
            0xC7, 0xE7 -> if (keepDiacritics) '\u00E7' else 'c'          // C / c with cedilla
            0x11E, 0x11F -> if (keepDiacritics) '\u011F' else 'g'        // G / g with breve
            0xD6, 0xF6 -> if (keepDiacritics) '\u00F6' else 'o'          // O / o with umlaut
            0x15E, 0x15F -> if (keepDiacritics) '\u015F' else 's'        // S / s with cedilla
            0xDC, 0xFC -> if (keepDiacritics) '\u00FC' else 'u'          // U / u with umlaut
            0xC2, 0xE2 -> if (keepDiacritics) '\u00E2' else 'a'          // A / a with circumflex
            0xCE, 0xEE -> if (keepDiacritics) '\u00EE' else 'i'          // I / i with circumflex
            0xDB, 0xFB -> if (keepDiacritics) '\u00FB' else 'u'          // U / u with circumflex
            else -> Character.toLowerCase(c)
        }
    }
}
