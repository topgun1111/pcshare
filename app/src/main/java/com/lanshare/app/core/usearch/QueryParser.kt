package com.lanshare.app.core.usearch

import java.util.Calendar
import java.util.TimeZone
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Query language of universal search (chapter 16.5), Part 2. Pure JVM, no android imports, no java.time (minSdk 24).
 * parse() never throws: half-typed input gives the best query it can plus entries in Query.notes.
 */

/** "Now" and the time zone used to resolve dm:today and friends. Tests pass fixed values. */
class ParseContext(val nowSec: Long = System.currentTimeMillis() / 1000L, val tz: TimeZone = TimeZone.getDefault())

sealed class QNode

/**
 * One word or phrase. phrase = it was typed in quotes. regex = typed with re:. The three Boolean? fields are per-term overrides
 * (case: nocase: ww: wholeword: diac: nodiac:); null = use the option of the search box.
 */
class QText(
    val text: String,
    val phrase: Boolean = false,
    val regex: Boolean = false,
    val caseSens: Boolean? = null,
    val wholeWord: Boolean? = null,
    val diacritics: Boolean? = null
) : QNode() {
    /** Contains * or ? (and is not a regex). Wildcard terms must match the whole name (or whole path when the term has a slash). */
    val wild: Boolean = !regex && (text.indexOf('*') >= 0 || text.indexOf('?') >= 0)
}

class QFilter(val f: Filter) : QNode()
class QNot(val n: QNode) : QNode()
class QAnd(val list: List<QNode>) : QNode()
class QOr(val list: List<QNode>) : QNode()

sealed class Filter
class ExtFilter(val exts: List<String>) : Filter()                 // lower-case, no dot, sorted, distinct
class KindFilter(val kinds: List<Kind>) : Filter()                 // sorted by enum order, distinct
class SizeFilter(val min: Long, val max: Long) : Filter()          // inclusive bytes; NO_MIN / NO_MAX = open; folders never match
class DateFilter(val ranges: List<LongArray>) : Filter()           // each [from, to) in epoch seconds; Long.MIN_VALUE / MAX_VALUE = open
class PathFilter(val text: String) : Filter()                      // the full path contains the text
class InFilter(val dir: String) : Filter()                         // the entry is the folder or lies below it
class DepthFilter(val min: Int, val max: Int) : Filter()           // 0 = a volume root, 1 = its children; max Int.MAX_VALUE = open

/** content: / ocr: / ai: sub-query. Terms are QText or QNot(QText); words are ANDed (ai: joins them into one sentence later). */
class ScopeQuery(val terms: List<QNode>)

/**
 * name = expression for file / folder names (null = no name search: empty query, or only content: ocr: ai: terms).
 * filters = the positive filters at the top level of the expression; later parts apply them to content and picture results too.
 */
class Query(
    val name: QNode?,
    val content: ScopeQuery?,
    val ocr: ScopeQuery?,
    val ai: ScopeQuery?,
    val filters: List<Filter>,
    val notes: List<String>
) {
    val isEmpty: Boolean get() = name == null && content == null && ocr == null && ai == null && filters.isEmpty()

    /** Positive plain words of the top-level AND chain (what "Search everywhere" would hand to the other sections). */
    fun plainWords(): List<String> {
        val n = name ?: return emptyList()
        val conj: List<QNode> = if (n is QAnd) n.list else listOf(n)
        val out = ArrayList<String>()
        for (x in conj) if (x is QText && !x.regex && !x.wild) out.add(x.text)
        return out
    }

    /** Canonical text form, used by the fixture test. */
    fun dump(): String {
        val sb = StringBuilder("N=")
        val n = name
        sb.append(if (n == null) "-" else Dump.node(n))
        if (content != null) sb.append(" C=[").append(Dump.scope(content)).append(']')
        if (ocr != null) sb.append(" O=[").append(Dump.scope(ocr)).append(']')
        if (ai != null) sb.append(" A=[").append(Dump.scope(ai)).append(']')
        if (n == null && filters.isNotEmpty()) sb.append(" F=[").append(filters.joinToString(",") { Dump.filter(it) }).append(']')
        if (notes.isNotEmpty()) sb.append(" notes=").append(notes.size)
        return sb.toString()
    }
}

internal object Dump {
    fun scope(s: ScopeQuery): String = s.terms.joinToString(",") { node(it) }

    fun node(n: QNode): String = when (n) {
        is QText -> {
            val fl = ArrayList<String>()
            if (n.phrase) fl.add("p")
            if (n.wild) fl.add("w")
            if (n.regex) fl.add("r")
            if (n.caseSens != null) fl.add(if (n.caseSens) "c" else "nc")
            if (n.wholeWord != null) fl.add(if (n.wholeWord) "ww" else "nww")
            if (n.diacritics != null) fl.add(if (n.diacritics) "d" else "nd")
            "t:" + n.text + (if (fl.isEmpty()) "" else "[" + fl.joinToString(",") + "]")
        }
        is QFilter -> filter(n.f)
        is QNot -> "!" + node(n.n)
        is QAnd -> "AND(" + n.list.joinToString(",") { node(it) } + ")"
        is QOr -> "OR(" + n.list.joinToString(",") { node(it) } + ")"
    }

    private fun lng(v: Long, open: Long): String = if (v == open) "" else v.toString()

    fun filter(f: Filter): String = when (f) {
        is ExtFilter -> "ext(" + f.exts.joinToString(",") + ")"
        is KindFilter -> "kind(" + f.kinds.joinToString(",") { it.name.lowercase() } + ")"
        is SizeFilter -> "size[" + lng(f.min, QueryParser.NO_MIN) + ".." + lng(f.max, QueryParser.NO_MAX) + "]"
        is DateFilter -> "dm" + f.ranges.joinToString("+") { "[" + lng(it[0], Long.MIN_VALUE) + ".." + lng(it[1], Long.MAX_VALUE) + ")" }
        is PathFilter -> "path(" + f.text + ")"
        is InFilter -> "in(" + f.dir + ")"
        is DepthFilter -> "depth[" + f.min + ".." + (if (f.max == Int.MAX_VALUE) "" else f.max.toString()) + "]"
    }
}

object QueryParser {
    const val NO_MIN = Long.MIN_VALUE
    const val NO_MAX = Long.MAX_VALUE

    private val FILTER_KEYS = setOf("ext", "kind", "size", "dm", "path", "in", "depth")
    // In these the pipe belongs to the value (kind:image|video, dm:today|yesterday, re:a|b), everywhere else it is the OR operator.
    private val KEEP_PIPE = Regex("^(?:(?:case|nocase|ww|wholeword|diac|nodiac):)*(?:ext|kind|dm|re|regex):", RegexOption.IGNORE_CASE)
    private val MODIFIERS = arrayOf("nocase:", "case:", "wholeword:", "ww:", "nodiac:", "diac:", "regex:", "re:")
    private val OPS = arrayOf(">=", "<=", ">", "<", "=")
    private val SIZE_NUM = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(b|k|kb|m|mb|g|gb|t|tb)?$")
    private val YMD = Regex("^(\\d{4})(?:[-/.](\\d{1,2})(?:[-/.](\\d{1,2}))?)?$")
    private val DMY = Regex("^(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})$")
    private val LASTN = Regex("^last(\\d{1,4})days$")

    private class Tok(val text: String, val quoted: Boolean, val startsQuoted: Boolean, val neg: Boolean, val or: Boolean)

    fun parse(input: String, ctx: ParseContext = ParseContext()): Query {
        val notes = ArrayList<String>()
        val toks = tokenize(input, notes)
        val groups = ArrayList<List<QNode>>()
        var cur = ArrayList<QNode>()
        val scopes = arrayOf(ArrayList<QNode>(), ArrayList<QNode>(), ArrayList<QNode>())   // content, ocr, ai
        var scope = -1
        for (t in toks) {
            if (t.or) {
                if (cur.isNotEmpty()) groups.add(cur)
                cur = ArrayList()
                scope = -1
                continue
            }
            val low = t.text.lowercase()
            val colon = low.indexOf(':')
            if (!t.startsQuoted && colon > 0) {
                val key = low.substring(0, colon)
                val value = t.text.substring(colon + 1)
                val si = when (key) { "content" -> 0; "ocr" -> 1; "ai" -> 2; else -> -1 }
                if (si >= 0) {
                    scope = si
                    if (value.isNotEmpty()) addScope(scopes[si], value, t.quoted, t.neg)
                    continue
                }
                if (FILTER_KEYS.contains(key)) {
                    val f = parseFilter(key, value, ctx, notes)
                    if (f != null) { val fn = QFilter(f); cur.add(if (t.neg) QNot(fn) else fn) }
                    continue
                }
            }
            // plain word or phrase, with optional modifiers in front
            var text = t.text
            var cs: Boolean? = null
            var ww: Boolean? = null
            var dc: Boolean? = null
            var re = false
            if (!t.startsQuoted) {
                var again = true
                while (again) {
                    again = false
                    val l = text.lowercase()
                    for (m in MODIFIERS) {
                        if (l.startsWith(m)) {
                            when (m) {
                                "case:" -> cs = true
                                "nocase:" -> cs = false
                                "ww:", "wholeword:" -> ww = true
                                "diac:" -> dc = true
                                "nodiac:" -> dc = false
                                else -> re = true
                            }
                            text = text.substring(m.length)
                            again = true
                            break
                        }
                    }
                }
            }
            if (text.isEmpty()) continue
            if (re) {
                try { Pattern.compile(text) } catch (e: PatternSyntaxException) { notes.add("bad regex: " + text) }
            }
            val q = QText(text, t.quoted, re, cs, ww, dc)
            if (scope >= 0) scopes[scope].add(if (t.neg) QNot(q) else q)
            else cur.add(if (t.neg) QNot(q) else q)
        }
        if (cur.isNotEmpty()) groups.add(cur)

        var tree: QNode? = null
        if (groups.isNotEmpty()) {
            val conj = ArrayList<QNode>()
            for (g in groups) conj.add(if (g.size == 1) g[0] else QAnd(g))
            tree = if (conj.size == 1) conj[0] else QOr(conj)
        }
        val content = if (scopes[0].isEmpty()) null else ScopeQuery(scopes[0])
        val ocr = if (scopes[1].isEmpty()) null else ScopeQuery(scopes[1])
        val ai = if (scopes[2].isEmpty()) null else ScopeQuery(scopes[2])
        val filters = topFilters(tree)
        if ((content != null || ocr != null || ai != null) && tree != null && !hasText(tree)) tree = null   // only filters + scopes: no name listing
        return Query(tree, content, ocr, ai, filters, notes)
    }

    private fun addScope(list: ArrayList<QNode>, text: String, phrase: Boolean, neg: Boolean) {
        val q = QText(text, phrase)
        list.add(if (neg) QNot(q) else q)
    }

    private fun hasText(n: QNode): Boolean = when (n) {
        is QText -> true
        is QFilter -> false
        is QNot -> hasText(n.n)
        is QAnd -> n.list.any { hasText(it) }
        is QOr -> n.list.any { hasText(it) }
    }

    private fun topFilters(n: QNode?): List<Filter> {
        if (n == null) return emptyList()
        val conj: List<QNode> = if (n is QAnd) n.list else listOf(n)
        val out = ArrayList<Filter>()
        for (x in conj) if (x is QFilter) out.add(x.f)
        return out
    }

    // ---------------------------------------------------------------- tokenizer
    private fun tokenize(s: String, notes: MutableList<String>): List<Tok> {
        val out = ArrayList<Tok>()
        val n = s.length
        var i = 0
        while (i < n) {
            if (s[i].isWhitespace()) { i++; continue }
            var neg = false
            while (i < n && s[i] == '!') { neg = !neg; i++ }
            val sb = StringBuilder()
            var quoted = false
            var startsQuoted = false
            var first = true
            var pipe = false
            while (i < n) {
                val c = s[i]
                if (c == '"') {
                    if (first) startsQuoted = true
                    quoted = true
                    first = false
                    val j = s.indexOf('"', i + 1)
                    if (j < 0) { sb.append(s, i + 1, n); i = n; notes.add("open quote") }
                    else { sb.append(s, i + 1, j); i = j + 1 }
                    continue
                }
                if (c.isWhitespace()) break
                if (c == '|' && !KEEP_PIPE.containsMatchIn(sb)) { pipe = true; break }
                sb.append(c)
                i++
                first = false
            }
            if (sb.isNotEmpty()) out.add(Tok(sb.toString(), quoted, startsQuoted, neg, false))
            if (pipe) { out.add(Tok("", false, false, false, true)); i++ }
        }
        return out
    }

    // ---------------------------------------------------------------- filters
    private fun parseFilter(key: String, v: String, ctx: ParseContext, notes: MutableList<String>): Filter? {
        if (v.isEmpty()) return null   // half typed ("ext:"): ignore quietly
        when (key) {
            "ext" -> {
                val l = v.split(';', ',', '|').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
                return if (l.isEmpty()) null else ExtFilter(l)
            }
            "kind" -> {
                val set = HashSet<Kind>()
                for (p in v.split(';', ',', '|')) {
                    if (p.isBlank()) continue
                    val k = Kinds.parse(p)
                    if (k == null) notes.add("unknown kind: " + p.trim()) else set.add(k)
                }
                return if (set.isEmpty()) null else KindFilter(set.sortedBy { it.ordinal })
            }
            "size" -> {
                val f = parseSize(v)
                if (f == null) notes.add("bad size: " + v)
                return f
            }
            "dm" -> {
                val f = parseDate(v, ctx)
                if (f == null) notes.add("bad date: " + v)
                return f
            }
            "path" -> return PathFilter(v)
            "in" -> return InFilter(v)
            "depth" -> {
                val f = parseDepth(v)
                if (f == null) notes.add("bad depth: " + v)
                return f
            }
        }
        return null
    }

    /** Units are binary: 1kb = 1024 bytes. A decimal comma is accepted (1,5mb). */
    fun parseSize(v0: String): SizeFilter? {
        val v = v0.trim().lowercase()
        if (v == "empty") return SizeFilter(0L, 0L)
        val dd = v.indexOf("..")
        if (dd >= 0) {
            val a = v.substring(0, dd).trim()
            val b = v.substring(dd + 2).trim()
            val lo = if (a.isEmpty()) NO_MIN else (sizeNum(a) ?: return null)
            val hi = if (b.isEmpty()) NO_MAX else (sizeNum(b) ?: return null)
            return SizeFilter(lo, hi)
        }
        var op = ""
        var rest = v
        for (o in OPS) if (v.startsWith(o)) { op = o; rest = v.substring(o.length); break }
        val x = sizeNum(rest.trim()) ?: return null
        return when (op) {
            ">" -> SizeFilter(x + 1, NO_MAX)
            ">=" -> SizeFilter(x, NO_MAX)
            "<" -> SizeFilter(NO_MIN, x - 1)
            "<=" -> SizeFilter(NO_MIN, x)
            else -> SizeFilter(x, x)
        }
    }

    private fun sizeNum(s: String): Long? {
        val m = SIZE_NUM.find(s) ?: return null
        val num = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "k", "kb" -> 1024.0
            "m", "mb" -> 1048576.0
            "g", "gb" -> 1073741824.0
            "t", "tb" -> 1099511627776.0
            else -> 1.0
        }
        return (num * mult).toLong()
    }

    private fun parseDepth(v: String): DepthFilter? {
        val s = v.trim()
        val dd = s.indexOf("..")
        if (dd >= 0) {
            val a = s.substring(0, dd)
            val b = s.substring(dd + 2)
            val lo = if (a.isEmpty()) 0 else (a.toIntOrNull() ?: return null)
            val hi = if (b.isEmpty()) Int.MAX_VALUE else (b.toIntOrNull() ?: return null)
            return DepthFilter(lo, hi)
        }
        var op = ""
        var rest = s
        for (o in OPS) if (s.startsWith(o)) { op = o; rest = s.substring(o.length); break }
        val x = rest.trim().toIntOrNull() ?: return null
        if (x < 0 || x > 100000) return null
        return when (op) {
            ">" -> DepthFilter(x + 1, Int.MAX_VALUE)
            ">=" -> DepthFilter(x, Int.MAX_VALUE)
            "<" -> DepthFilter(0, x - 1)
            "<=" -> DepthFilter(0, x)
            else -> DepthFilter(x, x)
        }
    }

    // ---------------------------------------------------------------- dates
    fun parseDate(v: String, ctx: ParseContext): DateFilter? {
        val ranges = ArrayList<LongArray>()
        for (alt in v.split('|', ';', ',')) {
            val a = alt.trim().lowercase()
            if (a.isEmpty()) continue
            val r = oneDate(a, ctx) ?: return null
            ranges.add(r)
        }
        return if (ranges.isEmpty()) null else DateFilter(ranges)
    }

    private fun oneDate(a: String, ctx: ParseContext): LongArray? {
        val dd = a.indexOf("..")
        if (dd >= 0) {
            val x = a.substring(0, dd).trim()
            val y = a.substring(dd + 2).trim()
            val lo = if (x.isEmpty()) Long.MIN_VALUE else ((period(x, ctx) ?: return null)[0])
            val hi = if (y.isEmpty()) Long.MAX_VALUE else ((period(y, ctx) ?: return null)[1])
            return longArrayOf(lo, hi)
        }
        var op = ""
        var rest = a
        for (o in OPS) if (a.startsWith(o)) { op = o; rest = a.substring(o.length); break }
        val per = period(rest.trim(), ctx) ?: return null
        return when (op) {
            ">" -> longArrayOf(per[1], Long.MAX_VALUE)
            ">=" -> longArrayOf(per[0], Long.MAX_VALUE)
            "<" -> longArrayOf(Long.MIN_VALUE, per[0])
            "<=" -> longArrayOf(Long.MIN_VALUE, per[1])
            else -> per
        }
    }

    private fun dayStart(ctx: ParseContext, shiftDays: Int): Calendar {
        val c = Calendar.getInstance(ctx.tz)
        c.timeInMillis = ctx.nowSec * 1000L
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (shiftDays != 0) c.add(Calendar.DAY_OF_MONTH, shiftDays)
        return c
    }

    private fun sec(c: Calendar): Long = c.timeInMillis / 1000L

    private fun span(a: Calendar, field: Int, n: Int): LongArray {
        val b = a.clone() as Calendar
        b.add(field, n)
        return longArrayOf(sec(a), sec(b))
    }

    /** [from, to) of one date word or date, in epoch seconds; null when it is not a date. */
    private fun period(s: String, ctx: ParseContext): LongArray? {
        if (s.isEmpty()) return null
        when (s) {
            "today" -> return span(dayStart(ctx, 0), Calendar.DAY_OF_MONTH, 1)
            "yesterday" -> return span(dayStart(ctx, -1), Calendar.DAY_OF_MONTH, 1)
            "thisweek", "lastweek" -> {
                val a = dayStart(ctx, 0)
                val back = (a.get(Calendar.DAY_OF_WEEK) + 5) % 7      // Monday = 0
                a.add(Calendar.DAY_OF_MONTH, -back - (if (s == "lastweek") 7 else 0))
                return span(a, Calendar.DAY_OF_MONTH, 7)
            }
            "thismonth", "lastmonth" -> {
                val a = dayStart(ctx, 0)
                a.set(Calendar.DAY_OF_MONTH, 1)
                if (s == "lastmonth") a.add(Calendar.MONTH, -1)
                return span(a, Calendar.MONTH, 1)
            }
            "thisyear", "lastyear" -> {
                val a = dayStart(ctx, 0)
                a.set(Calendar.MONTH, 0)
                a.set(Calendar.DAY_OF_MONTH, 1)
                if (s == "lastyear") a.add(Calendar.YEAR, -1)
                return span(a, Calendar.YEAR, 1)
            }
        }
        val ln = LASTN.find(s)
        if (ln != null) {
            val n = ln.groupValues[1].toInt()
            if (n < 1) return null
            return longArrayOf(sec(dayStart(ctx, -(n - 1))), sec(dayStart(ctx, 1)))
        }
        var y = 0
        var mo = 0
        var d = 0
        val m1 = YMD.find(s)
        if (m1 != null) {
            y = m1.groupValues[1].toInt()
            if (m1.groupValues[2].isNotEmpty()) { mo = m1.groupValues[2].toInt(); if (mo == 0) return null }
            if (m1.groupValues[3].isNotEmpty()) { d = m1.groupValues[3].toInt(); if (d == 0) return null }
        } else {
            val m2 = DMY.find(s) ?: return null
            d = m2.groupValues[1].toInt()
            mo = m2.groupValues[2].toInt()
            y = m2.groupValues[3].toInt()
        }
        if (y < 1970 || y > 2200) return null
        return try {
            val c = Calendar.getInstance(ctx.tz)
            c.isLenient = false
            c.clear()
            if (mo == 0) { c.set(y, 0, 1); span(c, Calendar.YEAR, 1) }
            else if (d == 0) { c.set(y, mo - 1, 1); span(c, Calendar.MONTH, 1) }
            else { c.set(y, mo - 1, d); span(c, Calendar.DAY_OF_MONTH, 1) }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
