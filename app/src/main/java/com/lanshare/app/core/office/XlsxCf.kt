package com.lanshare.app.core.office

/**
 * Part 9: conditional formatting of one sheet, evaluated on the Kotlin side (no JavaScript in the viewer). Pure Kotlin, no android.*.
 * Supported rule types: cellIs, expression (comparisons, + - * / &, AND OR NOT IF MOD ROW COLUMN ISBLANK ISNUMBER ISTEXT ISEVEN ISODD ABS LEN INT; refs relative to the first
 * cell of the range; no ranges, no other sheets), top10 (rank / percent / bottom), aboveAverage, duplicateValues, uniqueValues, containsText / notContainsText / beginsWith /
 * endsWith, containsBlanks / notContainsBlanks, containsErrors / notContainsErrors, colorScale (2 / 3 colours), dataBar (min / max / num / percent / percentile, positive style).
 * NOT supported (rule is ignored): iconSet, timePeriod, stdDev, formulas with ranges or functions not listed. Only cells that exist in [XSheet.rows] are evaluated
 * (a rule never colours a cell that has no value and no own fill / border).
 * Priority: the lowest priority number wins per property (fill, colour, bold ...); `stopIfTrue` stops later rules for that cell.
 */
class CfOut {
    var fill: String? = null; var color: String? = null
    var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null; var strike: Boolean? = null
    var bar = -1.0          // data bar length 0..1, < 0 = none
    var barColor: String? = null
}

private typealias Fn = (XlsxCf.Ctx) -> Any?

object XlsxCf {
    fun key(r: Int, c: Int): Long = r.toLong() * 100000L + c

    private class Unsupported : RuntimeException()
    internal class Ctx(val cells: Map<Long, XCell>, val r: Int, val c: Int, val r0: Int, val c0: Int)

    /** (row, col) key -> effect, for every cell some rule applied to. Empty map when the sheet has no rules. */
    fun evaluate(sh: XSheet, styles: XStyles?): Map<Long, CfOut> {
        if (sh.cf.isEmpty()) return emptyMap()
        val cells = HashMap<Long, XCell>()
        for ((r, cs) in sh.rows) for (c in cs) cells[key(r, c.col)] = c
        val out = HashMap<Long, CfOut>(); val stopped = HashSet<Long>()
        for (rule in sh.cf.sortedBy { it.priority }) {
            try { applyRule(rule, sh, cells, styles, out, stopped) } catch (_: Unsupported) { } catch (_: Exception) { }
        }
        return out
    }

    // ------------------------------------------------------------------ values
    private fun cellValue(x: XCell?): Any? {
        if (x == null) return null
        return when (x.kind) {
            XCell.Kind.EMPTY -> null
            XCell.Kind.NUMBER, XCell.Kind.DATE -> x.raw.toDoubleOrNull() ?: x.text
            XCell.Kind.BOOL -> x.raw == "1"
            else -> x.text
        }
    }
    private fun num(x: XCell): Double? = if (x.kind == XCell.Kind.NUMBER || x.kind == XCell.Kind.DATE) x.raw.toDoubleOrNull() else null

    private fun rank(v: Any): Int = when (v) { is Double -> 0; is String -> 1; else -> 2 }
    private fun cmp(a: Any?, b: Any?): Int {
        var x = a; var y = b
        if (x == null) x = when (y) { is String -> ""; is Boolean -> false; else -> 0.0 }
        if (y == null) y = when (x) { is String -> ""; is Boolean -> false; else -> 0.0 }
        val rx = rank(x); val ry = rank(y)
        if (rx != ry) return rx.compareTo(ry)
        return when (x) {
            is Double -> { val q = y as Double; if (x < q) -1 else if (x > q) 1 else 0 }
            is String -> x.compareTo(y as String, true)
            else -> (x as Boolean).compareTo(y as Boolean)
        }
    }
    private fun toNum(v: Any?): Double = when (v) { null -> 0.0; is Double -> v; is Boolean -> if (v) 1.0 else 0.0; is String -> v.trim().toDoubleOrNull() ?: throw Unsupported(); else -> throw Unsupported() }
    private fun truthy(v: Any?): Boolean = when (v) { null -> false; is Boolean -> v; is Double -> v != 0.0; else -> false }
    private fun str(v: Any?): String = when (v) { null -> ""; is Double -> NumFmt.general(v); is Boolean -> if (v) "TRUE" else "FALSE"; else -> v.toString() }

    // ------------------------------------------------------------------ formula mini-parser
    private val compiled = java.util.WeakHashMap<String, Fn>()
    private fun compile(f: String): Fn {
        synchronized(compiled) { compiled[f]?.let { return it } }
        val fn = Parser(f.trimStart('=')).parse()
        synchronized(compiled) { compiled[f] = fn }
        return fn
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun parse(): Fn { val f = cmpE(); ws(); if (i < s.length) throw Unsupported(); return f }

        fun cmpE(): Fn {
            var l = concatE()
            while (true) {
                ws()
                val op = when {
                    s.startsWith("<>", i) -> "<>"; s.startsWith("<=", i) -> "<="; s.startsWith(">=", i) -> ">="
                    peek() == '=' -> "="; peek() == '<' -> "<"; peek() == '>' -> ">"
                    else -> return l
                }
                i += op.length
                val a = l; val b = concatE()
                l = { c: Ctx -> val k = cmp(a(c), b(c)); when (op) { "=" -> k == 0; "<>" -> k != 0; "<" -> k < 0; ">" -> k > 0; "<=" -> k <= 0; else -> k >= 0 } }
            }
        }
        fun concatE(): Fn {
            var l = addE()
            while (true) { ws(); if (peek() != '&') return l; i++; val a = l; val b = addE(); l = { c: Ctx -> str(a(c)) + str(b(c)) } }
        }
        fun addE(): Fn {
            var l = mulE()
            while (true) {
                ws(); val ch = peek(); if (ch != '+' && ch != '-') return l
                i++; val a = l; val b = mulE()
                l = if (ch == '+') { c: Ctx -> toNum(a(c)) + toNum(b(c)) } else { c: Ctx -> toNum(a(c)) - toNum(b(c)) }
            }
        }
        fun mulE(): Fn {
            var l = unary()
            while (true) {
                ws(); val ch = peek(); if (ch != '*' && ch != '/') return l
                i++; val a = l; val b = unary()
                l = if (ch == '*') { c: Ctx -> toNum(a(c)) * toNum(b(c)) } else { c: Ctx -> val d = toNum(b(c)); if (d == 0.0) throw Unsupported(); toNum(a(c)) / d }
            }
        }
        fun unary(): Fn {
            ws()
            if (peek() == '-') { i++; val a = unary(); return { c: Ctx -> -toNum(a(c)) } }
            if (peek() == '+') { i++; return unary() }
            return primary()
        }
        fun primary(): Fn {
            ws(); val ch = peek()
            if (ch == '(') { i++; val e = cmpE(); ws(); if (peek() != ')') throw Unsupported(); i++; return e }
            if (ch == '"') {
                i++; val sb = StringBuilder()
                while (true) {
                    if (i >= s.length) throw Unsupported()
                    if (s[i] == '"') { if (i + 1 < s.length && s[i + 1] == '"') { sb.append('"'); i += 2; continue }; i++; break }
                    sb.append(s[i++])
                }
                val v = sb.toString(); return { _: Ctx -> v }
            }
            if (ch.isDigit() || ch == '.') {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || ((s[i + 1] == '+' || s[i + 1] == '-') && i + 2 < s.length && s[i + 2].isDigit()))) { i += 2; while (i < s.length && s[i].isDigit()) i++ }
                var v = s.substring(st, i).toDoubleOrNull() ?: throw Unsupported()
                if (peek() == '%') { i++; v /= 100.0 }
                return { _: Ctx -> v }
            }
            if (ch.isLetter() || ch == '$' || ch == '_') {
                val st = i
                if (ch == '$') i++
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$' || s[i] == '_' || s[i] == '.')) i++
                val word = s.substring(st, i)
                ws()
                if (peek() == '(') { i++; return call(word.uppercase()) }
                if (peek() == '!' || peek() == ':') throw Unsupported()   // other sheet / range
                if (word.equals("TRUE", true)) return { _: Ctx -> true }
                if (word.equals("FALSE", true)) return { _: Ctx -> false }
                return refFn(word)
            }
            throw Unsupported()
        }

        class Ref(val row: Int, val col: Int, val absR: Boolean, val absC: Boolean)
        fun parseRef(w: String): Ref {
            val m = REF.matchEntire(w) ?: throw Unsupported()
            val col = XRef.parse(m.groupValues[2] + "1")?.get(0) ?: throw Unsupported()
            return Ref((m.groupValues[4].toIntOrNull() ?: throw Unsupported()) - 1, col, m.groupValues[3] == "$", m.groupValues[1] == "$")
        }
        fun refFn(w: String): Fn {
            val rf = parseRef(w)
            return { c: Ctx ->
                val rr = if (rf.absR) rf.row else rf.row + (c.r - c.r0); val cc = if (rf.absC) rf.col else rf.col + (c.c - c.c0)
                if (rr < 0 || cc < 0) throw Unsupported()
                cellValue(c.cells[key(rr, cc)])
            }
        }

        fun call(name: String): Fn {
            ws()
            if (name == "ROW" || name == "COLUMN") {
                val isRow = name == "ROW"
                if (peek() == ')') { i++; return { c: Ctx -> (if (isRow) c.r + 1 else c.c + 1).toDouble() } }
                val st = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$')) i++
                val rf = parseRef(s.substring(st, i)); ws(); if (peek() != ')') throw Unsupported(); i++
                return { c: Ctx -> if (isRow) (if (rf.absR) rf.row else rf.row + (c.r - c.r0)).toDouble() + 1 else (if (rf.absC) rf.col else rf.col + (c.c - c.c0)).toDouble() + 1 }
            }
            val args = ArrayList<Fn>()
            ws()
            if (peek() == ')') i++ else while (true) {
                args.add(cmpE()); ws()
                if (peek() == ',') { i++; continue }
                if (peek() == ')') { i++; break }
                throw Unsupported()
            }
            fun a(k: Int): Fn = args.getOrNull(k) ?: throw Unsupported()
            return when (name) {
                "AND" -> { c: Ctx -> args.isNotEmpty() && args.all { truthy(it(c)) } }
                "OR" -> { c: Ctx -> args.any { truthy(it(c)) } }
                "NOT" -> { c: Ctx -> !truthy(a(0)(c)) }
                "IF" -> { c: Ctx -> if (truthy(a(0)(c))) a(1)(c) else if (args.size > 2) a(2)(c) else false }
                "MOD" -> { c: Ctx -> val d = toNum(a(1)(c)); if (d == 0.0) throw Unsupported(); val n = toNum(a(0)(c)); n - d * Math.floor(n / d) }
                "ISBLANK" -> { c: Ctx -> a(0)(c) == null }
                "ISNUMBER" -> { c: Ctx -> a(0)(c) is Double }
                "ISTEXT" -> { c: Ctx -> a(0)(c) is String }
                "ISEVEN" -> { c: Ctx -> Math.floor(toNum(a(0)(c))).toLong() % 2L == 0L }
                "ISODD" -> { c: Ctx -> Math.floor(toNum(a(0)(c))).toLong() % 2L != 0L }
                "ABS" -> { c: Ctx -> Math.abs(toNum(a(0)(c))) }
                "INT" -> { c: Ctx -> Math.floor(toNum(a(0)(c))) }
                "LEN" -> { c: Ctx -> str(a(0)(c)).length.toDouble() }
                else -> throw Unsupported()
            }
        }
        companion object { val REF = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7})") }
    }

    // ------------------------------------------------------------------ rules
    private fun applyRule(rule: XCfRule, sh: XSheet, cells: Map<Long, XCell>, styles: XStyles?, out: MutableMap<Long, CfOut>, stopped: MutableSet<Long>) {
        val targets = ArrayList<XCell>()
        for (rg in rule.ranges) {
            if (rg[3] < rg[1]) continue
            for ((_, cs) in sh.rows.subMap(rg[1], true, rg[3], true)) for (c in cs) if (c.col >= rg[0] && c.col <= rg[2]) targets.add(c)
        }
        if (targets.isEmpty()) return
        val r0 = rule.ranges[0][1]; val c0 = rule.ranges[0][0]
        val dx = styles?.dxfs?.getOrNull(rule.dxfId)
        fun ctx(x: XCell) = Ctx(cells, x.row, x.col, r0, c0)
        fun hit(x: XCell) {
            val k = key(x.row, x.col)
            val o = out.getOrPut(k) { CfOut() }
            if (dx != null) {
                if (o.fill == null) o.fill = dx.fill
                if (o.color == null) o.color = dx.color
                if (o.bold == null) o.bold = dx.bold
                if (o.italic == null) o.italic = dx.italic
                if (o.underline == null) o.underline = dx.underline
                if (o.strike == null) o.strike = dx.strike
            }
            if (rule.stopIfTrue) stopped.add(k)
        }
        fun live() = targets.filter { key(it.row, it.col) !in stopped }
        fun nums(): List<Double> = targets.mapNotNull { num(it) }

        when (rule.type) {
            "cellIs" -> {
                val op = rule.op ?: throw Unsupported()
                val f1 = compile(rule.formulas.getOrNull(0) ?: throw Unsupported())
                val f2 = if (op == "between" || op == "notBetween") compile(rule.formulas.getOrNull(1) ?: throw Unsupported()) else null
                for (x in live()) {
                    val cx = ctx(x); val v = cellValue(x); val a = f1(cx)
                    val ok = when (op) {
                        "equal" -> cmp(v, a) == 0; "notEqual" -> cmp(v, a) != 0
                        "greaterThan" -> cmp(v, a) > 0; "greaterThanOrEqual" -> cmp(v, a) >= 0
                        "lessThan" -> cmp(v, a) < 0; "lessThanOrEqual" -> cmp(v, a) <= 0
                        "between" -> cmp(v, a) >= 0 && cmp(v, f2!!(cx)) <= 0
                        "notBetween" -> cmp(v, a) < 0 || cmp(v, f2!!(cx)) > 0
                        else -> throw Unsupported()
                    }
                    if (ok) hit(x)
                }
            }
            "expression" -> {
                val f = compile(rule.formulas.getOrNull(0) ?: throw Unsupported())
                for (x in live()) if (truthy(f(ctx(x)))) hit(x)
            }
            "top10" -> {
                val v = nums().sortedDescending(); if (v.isEmpty()) return
                val n = if (rule.percent) maxOf(1, Math.floor(v.size * rule.rank / 100.0).toInt()) else rule.rank
                val k = minOf(maxOf(n, 1), v.size)
                val thr = if (rule.bottom) v[v.size - k] else v[k - 1]
                for (x in live()) { val d = num(x) ?: continue; if (if (rule.bottom) d <= thr else d >= thr) hit(x) }
            }
            "aboveAverage" -> {
                val v = nums(); if (v.isEmpty()) return
                val avg = v.sum() / v.size
                for (x in live()) { val d = num(x) ?: continue
                    val ok = if (rule.above) d > avg || (rule.equalAvg && d == avg) else d < avg || (rule.equalAvg && d == avg)
                    if (ok) hit(x) }
            }
            "duplicateValues", "uniqueValues" -> {
                val cnt = HashMap<String, Int>()
                fun kk(x: XCell): String? = if (x.kind == XCell.Kind.EMPTY) null else x.kind.name.take(1) + ":" + (if (num(x) != null) num(x).toString() else x.text.lowercase())
                for (x in targets) kk(x)?.let { cnt[it] = (cnt[it] ?: 0) + 1 }
                val dup = rule.type == "duplicateValues"
                for (x in live()) { val k = kk(x) ?: continue; val n = cnt[k] ?: 0; if (if (dup) n > 1 else n == 1) hit(x) }
            }
            "containsText", "notContainsText", "beginsWith", "endsWith" -> {
                val t = (rule.text ?: throw Unsupported()).lowercase()
                for (x in live()) {
                    val s = x.text.lowercase()
                    val ok = when (rule.type) { "containsText" -> s.contains(t); "notContainsText" -> !s.contains(t); "beginsWith" -> s.startsWith(t); else -> s.endsWith(t) }
                    if (ok) hit(x)
                }
            }
            "containsBlanks" -> for (x in live()) if (x.text.isBlank()) hit(x)
            "notContainsBlanks" -> for (x in live()) if (x.text.isNotBlank()) hit(x)
            "containsErrors" -> for (x in live()) if (x.kind == XCell.Kind.ERROR) hit(x)
            "notContainsErrors" -> for (x in live()) if (x.kind != XCell.Kind.ERROR) hit(x)
            "colorScale" -> {
                val n = rule.vos.size
                if ((n != 2 && n != 3) || rule.colors.size < n || rule.colors.take(n).any { it == null }) throw Unsupported()
                val sorted = nums().sorted(); if (sorted.isEmpty()) return
                val stops = DoubleArray(n) { resolve(rule.vos[it], sorted, ctx(targets[0])) }
                val cols = rule.colors.take(n).map { rgb(it!!) }
                for (x in live()) {
                    val d = num(x) ?: continue
                    val o = out.getOrPut(key(x.row, x.col)) { CfOut() }
                    if (o.fill != null) continue
                    o.fill = scaleColor(d, stops, cols)
                    if (rule.stopIfTrue) stopped.add(key(x.row, x.col))
                }
            }
            "dataBar" -> {
                if (rule.vos.size < 2) throw Unsupported()
                val sorted = nums().sorted(); if (sorted.isEmpty()) return
                val lo = resolve(rule.vos[0], sorted, ctx(targets[0])); val hi = resolve(rule.vos[1], sorted, ctx(targets[0]))
                val col = rule.colors.getOrNull(0) ?: "#638EC6"
                for (x in live()) {
                    val d = num(x) ?: continue
                    val o = out.getOrPut(key(x.row, x.col)) { CfOut() }
                    if (o.bar >= 0) continue
                    val p = if (hi > lo) ((d - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 1.0
                    o.bar = (rule.minLen + p * (rule.maxLen - rule.minLen)) / 100.0; o.barColor = col
                }
            }
            else -> throw Unsupported()   // iconSet, timePeriod ...
        }
    }

    private fun resolve(v: XCfvo, sorted: List<Double>, cx: Ctx): Double = when (v.type) {
        "min" -> sorted.first()
        "max" -> sorted.last()
        "num" -> v.value?.toDoubleOrNull() ?: throw Unsupported()
        "percent" -> sorted.first() + (sorted.last() - sorted.first()) * ((v.value?.toDoubleOrNull() ?: throw Unsupported()) / 100.0)
        "percentile" -> percentile(sorted, (v.value?.toDoubleOrNull() ?: throw Unsupported()) / 100.0)
        "formula" -> toNum(compile(v.value ?: throw Unsupported())(cx))
        else -> throw Unsupported()
    }
    private fun percentile(s: List<Double>, p: Double): Double {
        val pos = p.coerceIn(0.0, 1.0) * (s.size - 1); val lo = Math.floor(pos).toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
    private fun rgb(c: String): IntArray { val v = c.trimStart('#').toInt(16); return intArrayOf((v shr 16) and 255, (v shr 8) and 255, v and 255) }
    private fun scaleColor(d: Double, t: DoubleArray, c: List<IntArray>): String {
        val last = t.size - 1
        val a: IntArray; val b: IntArray; val f: Double
        if (d <= t[0]) { a = c[0]; b = c[0]; f = 0.0 }
        else if (d >= t[last]) { a = c[last]; b = c[last]; f = 0.0 }
        else {
            var k = 0; while (k < last - 1 && d > t[k + 1]) k++
            a = c[k]; b = c[k + 1]; f = if (t[k + 1] > t[k]) (d - t[k]) / (t[k + 1] - t[k]) else 0.0
        }
        fun m(i: Int) = Math.round(a[i] + (b[i] - a[i]) * f).toInt().coerceIn(0, 255)
        return String.format("#%02x%02x%02x", m(0), m(1), m(2))
    }
}
