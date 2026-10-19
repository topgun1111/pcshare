package com.lanshare.app.core.office

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/** Excel number-format engine: General / 0.00 / #,##0 / % / scientific / dates & times / text / [Red] sections. Pure Kotlin, no java.time (minSdk 24). */
object NumFmt {
    private val BUILTIN = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        12 to "# ?/?", 13 to "# ??/??", 14 to "m/d/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "m/d/yyyy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)", 45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@"
    )
    private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    fun code(id: Int, custom: Map<Int, String>): String = custom[id] ?: BUILTIN[id] ?: "General"

    /** Built-in date/time ids (14-22, 45-47) plus any custom code that contains date tokens. */
    fun isDate(id: Int, code: String): Boolean {
        if (id in 14..22 || id in 45..47) return true
        if (id in 0..13 || id in 37..44 || id == 48 || id == 49) return false
        return isDateCode(code)
    }

    fun isDateCode(code: String): Boolean {
        val sec = splitSections(code)[0]
        var k = 0; val sb = StringBuilder()
        while (k < sec.length) {
            val c = sec[k]
            when {
                c == '"' -> { val e = sec.indexOf('"', k + 1); k = if (e < 0) sec.length else e + 1 }
                c == '[' -> { val e = sec.indexOf(']', k); val inner = if (e < 0) "" else sec.substring(k + 1, e).lowercase()
                    if (inner.startsWith("h") || inner.startsWith("m") || inner.startsWith("s")) sb.append(inner[0]); k = if (e < 0) sec.length else e + 1 }
                c == '\\' || c == '_' || c == '*' -> k += 2
                else -> { sb.append(c.lowercaseChar()); k++ }
            }
        }
        val t = sb.toString()
        return t.contains('y') || t.contains('d') || t.contains('h') || t.contains('s') || (t.contains('m') && !t.contains('0') && !t.contains('#'))
    }

    fun format(v: Double, code0: String, date1904: Boolean = false): String {
        if (v.isNaN() || v.isInfinite()) return "#NUM!"
        val code = if (code0.isEmpty()) "General" else code0
        if (code.equals("General", true)) return general(v)
        val secs = splitSections(code)
        // pick section: [cond] aware (only the simple 1/2/3-section positive;negative;zero form + basic conditions)
        var sec = secs[0]; var neg = false
        if (secs.size >= 2 && v < 0) { sec = secs[1]; neg = true }
        if (secs.size >= 3 && v == 0.0) sec = secs[2]
        val useAbs = if (secs.size >= 2 && v < 0) true else false
        val x = if (useAbs) abs(v) else v
        val clean = stripDirectives(sec)
        if (isDateCode(sec)) return fmtDate(x, clean, date1904)
        val r = fmtNumber(x, clean)
        return if (neg && secs.size < 2) "-$r" else r
    }

    fun general(v: Double): String {
        if (v == floor(v) && abs(v) < 1e11) return v.toLong().toString()
        var s = String.format(java.util.Locale.ROOT, "%.10G", v)
        if (s.contains('E')) {
            val m = s.substringBefore('E').trimEnd('0').trimEnd('.'); val e = s.substringAfter('E').toInt()
            return m + "E" + (if (e < 0) "-" else "+") + abs(e).toString().padStart(2, '0')
        }
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return s
    }

    private fun splitSections(code: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false; var k = 0
        while (k < code.length) {
            val c = code[k]
            if (c == '"') q = !q
            if (c == '\\' && k + 1 < code.length) { sb.append(c).append(code[k + 1]); k += 2; continue }
            if (c == ';' && !q) { out.add(sb.toString()); sb.setLength(0) } else sb.append(c)
            k++
        }
        out.add(sb.toString()); return out
    }

    private fun stripDirectives(sec: String): String {
        val sb = StringBuilder(); var k = 0
        while (k < sec.length) {
            val c = sec[k]
            if (c == '[') {
                val e = sec.indexOf(']', k); if (e < 0) { sb.append(c); k++; continue }
                val inner = sec.substring(k + 1, e); val l = inner.lowercase()
                if (l.startsWith("h") || l.startsWith("m") || l.startsWith("s")) sb.append(sec, k, e + 1)       // elapsed [h] [mm] [ss]
                else if (inner.startsWith("$")) { val cur = inner.substring(1).substringBefore('-'); sb.append('"').append(cur).append('"') }
                k = e + 1
            } else { sb.append(c); k++ }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- numbers
    private fun fmtNumber(x: Double, f: String): String {
        if (f.contains('@') && !f.contains('0') && !f.contains('#')) return f.replace("@", general(x))
        // tokenise into literals and the numeric pattern
        val pat = StringBuilder(); val pre = StringBuilder(); val post = StringBuilder()
        var seen = false; var ended = false; var pct = 0; var k = 0; var sci = false
        while (k < f.length) {
            val c = f[k]
            val lit: String? = when {
                c == '"' -> { val e = f.indexOf('"', k + 1).let { if (it < 0) f.length else it }; val s = f.substring(k + 1, e); k = minOf(f.length, e + 1); s }
                c == '\\' && k + 1 < f.length -> { val s = f[k + 1].toString(); k += 2; s }
                c == '_' -> { k += 2; " " }
                c == '*' -> { k += 2; "" }
                c == '%' -> { pct++; k++; "%" }
                else -> null
            }
            if (lit != null) { if (!seen) pre.append(lit) else { ended = true; post.append(lit) }; continue }
            if ((c == 'E' || c == 'e') && seen && k + 1 < f.length && (f[k + 1] == '+' || f[k + 1] == '-')) { sci = true; pat.append(c).append(f[k + 1]); k += 2; continue }
            if (c in "0#?,." && !ended) { pat.append(c); seen = true; k++; continue }
            if (c in "0#?" && ended) { post.append(c); k++; continue }
            if (!seen) pre.append(c) else { ended = true; post.append(c) }; k++
        }
        val v = if (pct == 0) x else x * 100.0.pow(pct)
        var p = pat.toString()
        if (p.isEmpty()) return pre.toString() + post.toString()
        val body = if (sci) fmtSci(v, p) else fmtPlain(v, p)
        return pre.toString() + body + post.toString()
    }

    private fun fmtSci(v: Double, p: String): String {
        val mant = p.substringBefore('E').substringBefore('e'); val expPat = p.substring(mant.length + 2)
        val dec = if (mant.contains('.')) mant.substringAfter('.').count { it in "0#?" } else 0
        val intDigits = mant.substringBefore('.').count { it in "0#?" }.coerceAtLeast(1)
        if (v == 0.0) return String.format(java.util.Locale.ROOT, "%.${dec}f", 0.0) + "E+" + "0".repeat(expPat.length.coerceAtLeast(2))
        var e = floor(Math.log10(abs(v))).toInt() - (intDigits - 1)
        var m = v / 10.0.pow(e)
        if (abs(m) >= 10.0.pow(intDigits) - 5e-12) { m /= 10; e++ }
        val sign = if (e < 0) "-" else if (p.contains("E+") || p.contains("e+")) "+" else ""
        return String.format(java.util.Locale.ROOT, "%.${dec}f", m) + "E" + sign + abs(e).toString().padStart(expPat.length.coerceAtLeast(1), '0')
    }

    private fun fmtPlain(v0: Double, p0: String): String {
        var p = p0
        // fractions "# ?/?" - rare; approximate with decimals
        val dot = p.indexOf('.')
        val intPat = if (dot < 0) p else p.substring(0, dot)
        val decPat = if (dot < 0) "" else p.substring(dot + 1)
        val grouping = intPat.contains(',')
        val trailingCommas = intPat.reversed().takeWhile { it == ',' }.length   // scaling by 1000 each
        var v = v0
        repeat(trailingCommas) { v /= 1000.0 }
        val decPlaces = decPat.count { it in "0#?" }
        val minDec = decPat.count { it == '0' }
        val scale = 10.0.pow(decPlaces)
        val rounded = if (decPlaces == 0) v.roundHalfUp() else (v * scale).roundHalfUp() / scale
        val digits = String.format(java.util.Locale.ROOT, "%.${decPlaces}f", rounded)
        var ip = digits.substringBefore('.'); var fp = if (digits.contains('.')) digits.substringAfter('.') else ""
        // trim optional decimals (#) from the right
        var keep = fp.length
        while (keep > minDec && fp[keep - 1] == '0') keep--
        fp = fp.substring(0, keep)
        val minInt = intPat.count { it == '0' }
        if (ip == "0" && minInt == 0 && intPat.any { it == '#' || it == '?' }) ip = if (fp.isEmpty()) "0" else ""
        if (ip.length < minInt) ip = ip.padStart(minInt, '0')
        if (grouping) ip = ip.reversed().chunked(3).joinToString(",").reversed()
        return if (decPat.isNotEmpty() && (fp.isNotEmpty() || minDec > 0)) "$ip.$fp" else ip
    }
    private fun Double.roundHalfUp(): Double = if (this >= 0) floor(this + 0.5) else -floor(-this + 0.5)

    // ---------------------------------------------------------------- dates
    /** Days since 1970-01-01 for an Excel serial (1900 system with its fake 1900-02-29, or the 1904 system). */
    fun epochDays(serial: Long, date1904: Boolean): Long = when {
        date1904 -> serial - 24107L
        serial > 60 -> serial - 25569L
        else -> serial - 25568L
    }

    /** Serial -> (y, m, d). */
    fun civil(serial: Long, date1904: Boolean): IntArray {
        if (!date1904 && serial == 60L) return intArrayOf(1900, 2, 29)
        val z = epochDays(serial, date1904) + 719468; val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097; val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400; val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt(); val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return intArrayOf((if (m <= 2) y + 1 else y).toInt(), m, d)
    }
    /** 0 = Sunday. */
    fun dayOfWeek(serial: Long, date1904: Boolean): Int = (((epochDays(serial, date1904) + 4) % 7 + 7) % 7).toInt()

    private fun fmtDate(x: Double, f: String, date1904: Boolean): String {
        val whole = floor(x).toLong(); var frac = x - whole
        var secTotal = (frac * 86400.0).roundToLong()
        var dayAdj = whole
        if (secTotal >= 86400) { secTotal -= 86400; dayAdj++ }
        val ymd = civil(dayAdj, date1904)
        val hh = (secTotal / 3600).toInt(); val mi = ((secTotal % 3600) / 60).toInt(); val ss = (secTotal % 60).toInt()
        val ampm = f.contains("AM/PM", true) || f.contains("A/P", true)
        val low = f.lowercase()
        val sb = StringBuilder(); var k = 0
        while (k < f.length) {
            val c = f[k]; val l = low[k]
            when {
                c == '"' -> { val e = f.indexOf('"', k + 1).let { if (it < 0) f.length else it }; sb.append(f, k + 1, e); k = minOf(f.length, e + 1) }
                c == '\\' && k + 1 < f.length -> { sb.append(f[k + 1]); k += 2 }
                c == '_' -> { sb.append(' '); k += 2 }
                c == '*' -> k += 2
                l == 'y' -> { val n = run(low, k, 'y'); sb.append(if (n <= 2) (ymd[0] % 100).toString().padStart(2, '0') else ymd[0].toString().padStart(4, '0')); k += n }
                l == 'd' -> { val n = run(low, k, 'd')
                    sb.append(when (n) { 1 -> ymd[2].toString(); 2 -> ymd[2].toString().padStart(2, '0'); 3 -> DAYS[dayOfWeek(dayAdj, date1904)].take(3); else -> DAYS[dayOfWeek(dayAdj, date1904)] }); k += n }
                l == 'm' -> { val n = run(low, k, 'm')
                    // minutes if directly after h / before s, else month
                    val prevH = prevToken(low, k) == 'h'; val nextS = nextToken(low, k + n) == 's'
                    if (prevH || nextS) sb.append(if (n >= 2) mi.toString().padStart(2, '0') else mi.toString())
                    else sb.append(when (n) { 1 -> ymd[1].toString(); 2 -> ymd[1].toString().padStart(2, '0'); 3 -> MONTHS[ymd[1] - 1].take(3); 4 -> MONTHS[ymd[1] - 1]; else -> MONTHS[ymd[1] - 1].take(1) })
                    k += n }
                l == 'h' -> { val n = run(low, k, 'h'); var h = hh; if (ampm) { h %= 12; if (h == 0) h = 12 }
                    sb.append(if (n >= 2) h.toString().padStart(2, '0') else h.toString()); k += n }
                l == 's' -> { val n = run(low, k, 's'); sb.append(if (n >= 2) ss.toString().padStart(2, '0') else ss.toString()); k += n
                    if (k < f.length && f[k] == '.' && k + 1 < f.length && f[k + 1] == '0') { var z = 0; while (k + 1 + z < f.length && f[k + 1 + z] == '0') z++
                        val ms = ((frac * 86400.0) % 1.0); sb.append('.').append(String.format(java.util.Locale.ROOT, "%.${z}f", ms).substringAfter('.')); k += 1 + z } }
                f.startsWith("AM/PM", k, true) -> { sb.append(if (hh < 12) "AM" else "PM"); k += 5 }
                f.startsWith("A/P", k, true) -> { sb.append(if (hh < 12) "A" else "P"); k += 3 }
                c == '[' -> { val e = f.indexOf(']', k).let { if (it < 0) f.length - 1 else it }; val t = low.substring(k + 1, e)
                    val tot = (x * 24.0 * 3600.0).roundToLong()
                    sb.append(when { t.startsWith("h") -> (tot / 3600).toString().padStart(t.length, '0'); t.startsWith("m") -> (tot / 60).toString().padStart(t.length, '0'); else -> tot.toString().padStart(t.length, '0') }); k = e + 1 }
                else -> { sb.append(c); k++ }
            }
        }
        return sb.toString()
    }
    private fun run(s: String, k: Int, c: Char): Int { var n = 0; while (k + n < s.length && s[k + n] == c) n++; return n }
    private fun prevToken(s: String, k: Int): Char? { var j = k - 1; while (j >= 0) { val c = s[j]; if (c in "hmsyd") return c; if (c == '"' || c == ']') { j--; continue }; j-- }; return null }
    private fun nextToken(s: String, k: Int): Char? { var j = k; while (j < s.length) { val c = s[j]; if (c in "hmsyd") return c; j++ }; return null }
}
