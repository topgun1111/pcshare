package com.lanshare.app.core.usearch

/**
 * Matching of a `content:` sub-query against ONE stored text (Universal Search Part 5). Pure JVM.
 * The stored text is pre-folded with [Fold.fold] (Turkish lower-case, diacritics dropped), so plain words are folded the same way;
 * match case and match diacritics therefore do not apply to content (the search notes say so). Regex terms run on the folded text, case-insensitive.
 * Contains = substring; Whole word = the characters before and after the match are not letters, digits or underscore (no tokenizer involved).
 */
class CTerm(val needle: String, val not: Boolean, val whole: Boolean, val re: Regex?)
class CHit(val pos: Int, val len: Int, val line: Int, val snip: String)

object ContentMatch {
    private fun isW(c: Char) = Character.isLetterOrDigit(c) || c == '_'

    /** Terms of a content: scope (QText or QNot(QText)). Notes explain what is ignored or wrong. */
    fun compile(nodes: List<QNode>, opt: MatchOptions, notes: MutableList<String>): List<CTerm> {
        val out = ArrayList<CTerm>()
        var warned = false
        for (n in nodes) {
            val neg = n is QNot
            val t = (if (n is QNot) n.n else n) as? QText ?: continue
            val whole = t.wholeWord ?: opt.wholeWord
            var re: Regex? = null
            var needle = ""
            if (t.regex || opt.regex) {
                re = try { Regex(t.text, RegexOption.IGNORE_CASE) } catch (_: Throwable) { notes.add("content: invalid regular expression: " + t.text); continue }
            } else if (t.wild) {
                val sb = StringBuilder()
                for (ch in Fold.fold(t.text)) when (ch) { '*' -> sb.append("[^\\n]*?"); '?' -> sb.append("[^\\n]"); else -> sb.append(Regex.escape(ch.toString())) }
                re = Regex(sb.toString())
            } else needle = Fold.fold(t.text)
            if (re == null && needle.isEmpty()) continue
            if (!warned && (t.caseSens == true || opt.matchCase || t.diacritics == true || opt.diacritics)) { notes.add("content: match case / diacritics are not applied inside files"); warned = true }
            out.add(CTerm(needle, neg, whole, re))
        }
        return out
    }

    /** Plain positive needles: usable as a SQL instr() prefilter. */
    fun needles(terms: List<CTerm>): List<String> = terms.filter { !it.not && it.re == null }.map { it.needle }

    private fun findOne(text: String, t: CTerm): IntArray? {   // [pos, len]
        val re = t.re
        if (re != null) {
            var m = re.find(text)
            while (m != null) {
                val s = m.range.first; val e = m.range.last + 1
                if (e > s && (!t.whole || ((s == 0 || !isW(text[s - 1])) && (e >= text.length || !isW(text[e]))))) return intArrayOf(s, e - s)
                m = m.next()
            }
            return null
        }
        var from = 0
        val nl = t.needle.length
        while (true) {
            val i = text.indexOf(t.needle, from)
            if (i < 0) return null
            if (!t.whole || ((i == 0 || !isW(text[i - 1])) && (i + nl >= text.length || !isW(text[i + nl])))) return intArrayOf(i, nl)
            from = i + 1
        }
    }

    /** All positive terms present and no negative term present -> the hit at the first positive term; else null. */
    fun find(text: String, terms: List<CTerm>): CHit? {
        var first: IntArray? = null
        for (t in terms) {
            val r = findOne(text, t)
            if (t.not) { if (r != null) return null } else { if (r == null) return null; if (first == null) first = r }
        }
        val pos = first?.get(0) ?: 0
        val len = first?.get(1) ?: 0
        var line = 1
        for (i in 0 until minOf(pos, text.length)) if (text[i] == '\n') line++
        return CHit(pos, len, line, snippet(text, pos, len))
    }

    fun snippet(text: String, pos: Int, len: Int, width: Int = 70): String {
        val a = maxOf(0, pos - width / 2)
        val b = minOf(text.length, maxOf(pos + len, pos) + width)
        val s = text.substring(a, b).replace('\n', ' ').replace('\t', ' ').trim()
        return (if (a > 0) "\u2026" else "") + s + (if (b < text.length) "\u2026" else "")
    }
}
