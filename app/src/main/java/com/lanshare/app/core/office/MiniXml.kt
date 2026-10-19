package com.lanshare.app.core.office

/**
 * Tiny, dependency-free XML pull parser (no android.util.Xml, so it also runs in plain JVM unit tests).
 * Good enough for OOXML / ODF parts: elements, attributes, text, CDATA, entities, comments, PIs, DOCTYPE (skipped).
 * Names are returned WITHOUT namespace prefix (local name) - `w:p` -> `p`, `r:id` -> `id`.
 * Use [rawName]/[rawAttr] when the prefix matters (e.g. `r:id` vs `id`).
 */
class MiniXml(private val s: String) {
    enum class T { START, END, TEXT, EOF }

    private var i = 0
    var type = T.EOF; private set
    var name = ""; private set
    var rawName = ""; private set
    var text = ""; private set
    var depth = 0; private set
    private var pendingEnd = false
    private val attrs = ArrayList<Pair<String, String>>(8)   // rawName -> value

    constructor(b: ByteArray) : this(decode(b))

    fun attr(local: String): String? {
        for (a in attrs) if (local(a.first) == local) return a.second
        return null
    }
    fun rawAttr(raw: String): String? { for (a in attrs) if (a.first == raw) return a.second; return null }
    fun attrCount() = attrs.size
    /** True right after a START event of a self-closing tag (`<c r="A1"/>`): the next event is its END. */
    fun isEmptyElement(): Boolean = pendingEnd

    /** Advance to the next START / END / TEXT event. */
    fun next(): T {
        if (pendingEnd) { pendingEnd = false; depth--; type = T.END; return type }
        while (i < s.length) {
            if (s[i] != '<') {
                val e = s.indexOf('<', i).let { if (it < 0) s.length else it }
                val raw = s.substring(i, e); i = e
                if (depth == 0 && raw.isBlank()) continue
                text = unescape(raw); type = T.TEXT; return type
            }
            if (s.startsWith("<!--", i)) { val e = s.indexOf("-->", i + 4); i = if (e < 0) s.length else e + 3; continue }
            if (s.startsWith("<![CDATA[", i)) {
                val e = s.indexOf("]]>", i + 9).let { if (it < 0) s.length else it }
                text = s.substring(i + 9, e); i = minOf(s.length, e + 3); type = T.TEXT; return type
            }
            if (s.startsWith("<?", i)) { val e = s.indexOf("?>", i + 2); i = if (e < 0) s.length else e + 2; continue }
            if (s.startsWith("<!", i)) { skipDecl(); continue }
            if (s.startsWith("</", i)) {
                val e = s.indexOf('>', i).let { if (it < 0) s.length else it }
                rawName = s.substring(i + 2, e).trim(); name = local(rawName); i = minOf(s.length, e + 1)
                depth--; type = T.END; return type
            }
            return readStart()
        }
        type = T.EOF; return type
    }

    /** Text of the current element up to its matching END (nested text included). Leaves the parser ON the END. */
    fun readText(): String {
        val d = depth; val sb = StringBuilder()
        if (type == T.START && pendingEnd) { next(); return "" }
        while (true) {
            when (next()) {
                T.TEXT -> sb.append(text)
                T.END -> if (depth < d) return sb.toString()
                T.EOF -> return sb.toString()
                else -> {}
            }
        }
    }

    /** Skip the subtree of the current START element. */
    fun skip() {
        val d = depth
        if (pendingEnd) { next(); return }
        while (true) { val t = next(); if (t == T.EOF || (t == T.END && depth < d)) return }
    }

    private fun readStart(): T {
        var k = i + 1
        val n0 = k
        while (k < s.length && !s[k].isWhitespace() && s[k] != '>' && s[k] != '/') k++
        rawName = s.substring(n0, k); name = local(rawName); attrs.clear()
        while (k < s.length) {
            while (k < s.length && s[k].isWhitespace()) k++
            if (k >= s.length) break
            val c = s[k]
            if (c == '>') { k++; break }
            if (c == '/') { pendingEnd = true; k = s.indexOf('>', k).let { if (it < 0) s.length else it + 1 }; break }
            val a0 = k
            while (k < s.length && s[k] != '=' && !s[k].isWhitespace() && s[k] != '>' && s[k] != '/') k++
            val an = s.substring(a0, k)
            while (k < s.length && s[k].isWhitespace()) k++
            var v = ""
            if (k < s.length && s[k] == '=') {
                k++; while (k < s.length && s[k].isWhitespace()) k++
                if (k < s.length && (s[k] == '"' || s[k] == '\'')) {
                    val q = s[k]; val e = s.indexOf(q, k + 1).let { if (it < 0) s.length else it }
                    v = unescape(s.substring(k + 1, e)); k = minOf(s.length, e + 1)
                }
            }
            if (an.isNotEmpty()) attrs.add(an to v)
        }
        i = k; depth++; type = T.START; return type
    }

    private fun skipDecl() {
        var lvl = 0; var k = i
        while (k < s.length) {
            val c = s[k]
            if (c == '[') lvl++ else if (c == ']') lvl-- else if (c == '>' && lvl <= 0) { k++; break }
            k++
        }
        i = k
    }

    companion object {
        fun local(n: String): String { val c = n.indexOf(':'); return if (c < 0) n else n.substring(c + 1) }

        /** UTF-8 / UTF-16 (BOM) aware decode. */
        fun decode(b: ByteArray): String {
            if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
            if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
            val off = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
            return String(b, off, b.size - off, Charsets.UTF_8)
        }

        fun unescape(t: String): String {
            if (t.indexOf('&') < 0) return t
            val sb = StringBuilder(t.length)
            var k = 0
            while (k < t.length) {
                val c = t[k]
                if (c != '&') { sb.append(c); k++; continue }
                val e = t.indexOf(';', k)
                if (e < 0 || e - k > 10) { sb.append(c); k++; continue }
                val ent = t.substring(k + 1, e)
                val r: String? = when {
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "amp" -> "&"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { cp(it) }
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { cp(it) }
                    else -> null
                }
                if (r == null) { sb.append(c); k++ } else { sb.append(r); k = e + 1 }
            }
            return sb.toString()
        }
        private fun cp(c: Int): String? = if (c in 0..0x10FFFF) String(Character.toChars(c)) else null

        /** OOXML `_x000D_` escapes used in shared strings / inline strings. */
        fun unescapeOoxml(t: String): String {
            if (t.indexOf("_x") < 0) return t
            val sb = StringBuilder(t.length); var k = 0
            while (k < t.length) {
                if (t[k] == '_' && k + 6 < t.length && t[k + 1] == 'x' && t[k + 6] == '_') {
                    val h = t.substring(k + 2, k + 6).toIntOrNull(16)
                    if (h != null) { sb.append(h.toChar()); k += 7; continue }
                }
                sb.append(t[k]); k++
            }
            return sb.toString()
        }
    }
}
