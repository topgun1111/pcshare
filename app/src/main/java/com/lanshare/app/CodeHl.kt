package com.lanshare.app

/**
 * Small, dependency-free syntax highlighter (no WebView, no library) for the text editor and the Markdown preview's code blocks.
 * [scan] returns packed triples (start, end, kind) of the coloured tokens only; plain text gets no entry.
 * Not a real parser: comments, strings, numbers, keywords, Types, calls, @annotations, $variables, tags / attributes, "key": and key= lines.
 */
object CodeHl {
    const val COMMENT = 0
    const val STRING = 1
    const val NUMBER = 2
    const val KEYWORD = 3
    const val TYPE = 4
    const val FUNC = 5
    const val ATTR = 6
    const val META = 7

    private const val MAX_TOKENS = 40_000

    private val LIGHT = intArrayOf(0xFF6E7781.toInt(), 0xFF0A7B3E.toInt(), 0xFFB35900.toInt(), 0xFFCF222E.toInt(),
        0xFF0550AE.toInt(), 0xFF6F42C1.toInt(), 0xFF953800.toInt(), 0xFF8250DF.toInt())
    private val DARK = intArrayOf(0xFF8B949E.toInt(), 0xFF7EE787.toInt(), 0xFFFFA657.toInt(), 0xFFFF7B72.toInt(),
        0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFFE3B341.toInt(), 0xFFFF9BCE.toInt())

    fun color(kind: Int, dark: Boolean): Int = (if (dark) DARK else LIGHT)[kind.coerceIn(0, 7)]

    private class Lang(
        val kw: Set<String> = emptySet(),
        val line: Array<String> = emptyArray(),
        val block: Boolean = false,       // /* ... */
        val quotes: String = "\"'",
        val tags: Boolean = false,        // html / xml
        val json: Boolean = false,        // "key": value
        val keyLine: Boolean = false,     // yaml / ini / toml / css: key at the start of a line
        val md: Boolean = false,
        val triple: Boolean = false,      // python """ ... """
        val dollar: Boolean = false,      // $variable (php, shell)
        val php: Boolean = false,
        val ci: Boolean = false,          // keywords are case-insensitive (sql)
        val dash: Boolean = false,        // '-' is part of identifiers (css)
        val hex: Boolean = false          // #rrggbb colours (css)
    )

    private fun w(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val JAVAISH = w("package import class interface object fun val var if else when for while do return break continue in is as try catch finally throw null true false this super override open abstract final private public protected internal data sealed enum companion lateinit by init constructor suspend inline static void new extends implements instanceof int long double float boolean byte short char String throws synchronized volatile const typealias namespace using var async await readonly string bool foreach struct")
    private val JS = w("var let const function return if else for while do switch case default break continue new delete typeof instanceof in of this class extends super import export from as async await yield try catch finally throw null undefined true false void static get set interface type enum implements public private protected readonly abstract declare namespace")
    private val PY = w("def class return if elif else for while in not and or is import from as with try except finally raise pass break continue lambda yield None True False global nonlocal assert del async await self")
    private val PHP = w("function class extends implements interface trait namespace use new return if elseif else foreach for while do switch case default break continue try catch finally throw public private protected static final abstract const var null true false array isset unset empty require require_once include include_once as instanceof global fn match and or xor echo print")
    private val SH = w("if then else elif fi for while do done case esac in function return exit echo export local readonly set unset source alias cd")
    private val SQL = w("select from where and or not insert into values update set delete create table alter drop index view join left right inner outer full on as group by order having limit offset union all distinct null is in like between exists case when then else end primary key foreign references default unique check constraint begin commit rollback true false asc desc")
    private val GO = w("package import func var const type struct interface map chan go defer return if else for range switch case default break continue fallthrough select nil true false")
    private val RUST = w("fn let mut pub struct enum impl trait use mod crate self Self super match if else for while loop return break continue as in ref move const static type where async await dyn unsafe true false")
    private val C = w("if else for while do switch case default break continue return goto struct enum union typedef static const volatile extern sizeof void int char short long float double unsigned signed auto register inline class public private protected virtual new delete this namespace using template typename try catch throw true false null nullptr NULL bool")
    private val RUBY = w("def class module end if elsif else unless while until for in do begin rescue ensure return yield self nil true false and or not require include extend attr_accessor puts then case when")
    private val LUA = w("function end if then else elseif for while do repeat until return break local nil true false and or not in")
    private val DATA = w("true false null yes no on off")

    private val LANGS: Map<String, Lang> = mapOf(
        "java" to Lang(JAVAISH, arrayOf("//"), block = true),
        "js" to Lang(JS, arrayOf("//"), block = true, quotes = "\"'`"),
        "py" to Lang(PY, arrayOf("#"), triple = true),
        "php" to Lang(PHP, arrayOf("//", "#"), block = true, dollar = true, php = true),
        "sh" to Lang(SH, arrayOf("#"), dollar = true),
        "sql" to Lang(SQL, arrayOf("--"), block = true, quotes = "\"'`", ci = true),
        "go" to Lang(GO, arrayOf("//"), block = true, quotes = "\"'`"),
        "rust" to Lang(RUST, arrayOf("//"), block = true, quotes = "\""),
        "c" to Lang(C, arrayOf("//"), block = true),
        "ruby" to Lang(RUBY, arrayOf("#")),
        "lua" to Lang(LUA, arrayOf("--")),
        "css" to Lang(emptySet(), emptyArray(), block = true, keyLine = true, dash = true, hex = true),
        "json" to Lang(DATA, emptyArray(), quotes = "\"", json = true),
        "yaml" to Lang(DATA, arrayOf("#"), keyLine = true),
        "ini" to Lang(DATA, arrayOf("#", ";"), keyLine = true),
        "html" to Lang(tags = true),
        "md" to Lang(md = true)
    )

    private val ALIAS: Map<String, String> = HashMap<String, String>().apply {
        fun a(k: String, names: String) { for (n in names.split(' ')) put(n, k) }
        a("java", "java kt kts kotlin gradle cs csharp scala dart swift groovy")
        a("js", "js mjs cjs jsx ts tsx javascript typescript")
        a("py", "py pyw python")
        a("php", "php phtml")
        a("sh", "sh bash zsh shell console bat cmd ps1")
        a("sql", "sql")
        a("go", "go golang")
        a("rust", "rs rust")
        a("c", "c h cpp cc cxx hpp hh cplusplus")
        a("ruby", "rb ruby")
        a("lua", "lua")
        a("css", "css scss less")
        a("json", "json jsonc json5 geojson")
        a("yaml", "yml yaml")
        a("ini", "ini cfg conf properties toml env")
        a("html", "html htm xhtml xml svg xaml plist vue")
        a("md", "md markdown mdx")
    }

    /** Language key for a fence tag or a file extension, null = unknown. */
    fun key(s: String): String? = ALIAS[s.trim().lowercase()]

    fun keyForFile(name: String): String? {
        val n = name.substringAfterLast('/')
        if (!n.contains('.')) return null
        return key(n.substringAfterLast('.'))
    }

    private class Ints {
        var a = IntArray(1024)
        var n = 0
        fun add(s: Int, e: Int, k: Int) {
            if (e <= s) return
            if (n + 3 > a.size) a = a.copyOf(a.size * 2)
            a[n] = s; a[n + 1] = e; a[n + 2] = k; n += 3
        }
        fun full() = n >= MAX_TOKENS * 3
        fun done(): IntArray = a.copyOf(n)
    }

    fun scan(t: String, key: String): IntArray? {
        val l = LANGS[key] ?: return null
        val out = Ints()
        when {
            l.md -> scanMd(t, out)
            l.tags -> scanTags(t, out)
            else -> scanGeneric(t, l, out, 0)
        }
        return out.done()
    }

    private fun scanGeneric(t: String, l: Lang, out: Ints, off: Int) {
        val n = t.length
        var i = 0
        var atLine = true
        fun identStart(c: Char) = c.isLetter() || c == '_' || (c == '$' && !l.dollar)
        fun ident(c: Char) = c.isLetterOrDigit() || c == '_' || (c == '$' && !l.dollar) || (c == '-' && l.dash)
        while (i < n && !out.full()) {
            val c = t[i]
            if (c == '\n') { atLine = true; i++; continue }
            if (atLine) {
                if (c == ' ' || c == '\t' || c == '\r') { i++; continue }
                atLine = false
                if (l.keyLine) {
                    var p = i
                    if (c == '-' && p + 1 < n && t[p + 1] == ' ') { p += 2; while (p < n && t[p] == ' ') p++ }   // yaml list item
                    if (p < n && t[p] == '[' && !l.dash) {   // ini / toml [section]
                        val e = t.indexOf(']', p)
                        val nl = t.indexOf('\n', p)
                        if (e > 0 && (nl < 0 || e < nl)) { out.add(off + p, off + e + 1, META); i = e + 1; continue }
                    }
                    var j = p
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '.' || t[j] == '-')) j++
                    if (j > p) {
                        var k = j
                        while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                        if (k < n && (t[k] == '=' || (t[k] == ':' && (k + 1 >= n || t[k + 1] == ' ' || t[k + 1] == '\n' || t[k + 1] == '\r')))) {
                            out.add(off + p, off + j, ATTR); i = j; continue
                        }
                    }
                }
            }
            // comments
            if (l.block && c == '/' && i + 1 < n && t[i + 1] == '*') {
                val e = t.indexOf("*/", i + 2)
                val end = if (e < 0) n else e + 2
                out.add(off + i, off + end, COMMENT); i = end; continue
            }
            var lineComment = false
            for (p in l.line) if (t.startsWith(p, i)) { lineComment = true; break }
            if (lineComment) {
                val e = t.indexOf('\n', i)
                val end = if (e < 0) n else e
                out.add(off + i, off + end, COMMENT); i = end; continue
            }
            if (l.php && (t.startsWith("<?php", i) || t.startsWith("<?=", i) || t.startsWith("?>", i))) {
                val len = if (t.startsWith("<?php", i)) 5 else if (t.startsWith("<?=", i)) 3 else 2
                out.add(off + i, off + i + len, META); i += len; continue
            }
            // strings
            if (l.quotes.indexOf(c) >= 0) {
                if (l.triple && (t.startsWith("\"\"\"", i) || t.startsWith("'''", i))) {
                    val q = t.substring(i, i + 3)
                    val e = t.indexOf(q, i + 3)
                    val end = if (e < 0) n else e + 3
                    out.add(off + i, off + end, STRING); i = end; continue
                }
                var j = i + 1
                while (j < n) {
                    val ch = t[j]
                    if (ch == '\\') { j += 2; continue }
                    if (ch == c) { j++; break }
                    if (ch == '\n' && c != '`') break
                    j++
                }
                if (j > n) j = n
                var kind = STRING
                if (l.json) {
                    var k = j
                    while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                    if (k < n && t[k] == ':') kind = ATTR
                }
                out.add(off + i, off + j, kind); i = j; continue
            }
            // numbers / colours
            if (c in '0'..'9' && (i == 0 || !ident(t[i - 1]))) {
                var j = i + 1
                while (j < n && (t[j].isLetterOrDigit() || t[j] == '.' || t[j] == '_')) j++
                out.add(off + i, off + j, NUMBER); i = j; continue
            }
            if (l.hex && c == '#') {
                var j = i + 1
                while (j < n && t[j].isLetterOrDigit()) j++
                val len = j - i - 1
                var ok = len == 3 || len == 4 || len == 6 || len == 8
                if (ok) for (q in i + 1 until j) { val h = t[q]; if (!(h in '0'..'9' || h in 'a'..'f' || h in 'A'..'F')) { ok = false; break } }
                if (ok) { out.add(off + i, off + j, NUMBER); i = j; continue }
                i++; continue
            }
            // @annotation / @at-rule
            if (c == '@' && i + 1 < n && t[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (t[j].isLetterOrDigit() || t[j] == '_' || t[j] == '.' || (t[j] == '-' && l.dash))) j++
                out.add(off + i, off + j, META); i = j; continue
            }
            // $variable
            if (l.dollar && c == '$') {
                var j = i + 1
                if (j < n && t[j] == '{') {
                    val e = t.indexOf('}', j)
                    j = if (e < 0 || e - i > 80) j else e + 1
                } else {
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                }
                if (j > i + 1) out.add(off + i, off + j, ATTR)
                i = maxOf(j, i + 1); continue
            }
            // identifiers
            if (identStart(c)) {
                var j = i + 1
                while (j < n && ident(t[j])) j++
                val word = t.substring(i, j)
                val isKw = if (l.ci) l.kw.contains(word.lowercase()) else l.kw.contains(word)
                if (isKw) out.add(off + i, off + j, KEYWORD)
                else if (c.isUpperCase() && word.length > 1) out.add(off + i, off + j, TYPE)
                else {
                    var k = j
                    while (k < n && (t[k] == ' ' || t[k] == '\t')) k++
                    if (k < n && t[k] == '(') out.add(off + i, off + j, FUNC)
                }
                i = j; continue
            }
            i++
        }
    }

    private fun scanTags(t: String, out: Ints) {
        val n = t.length
        var i = 0
        while (i < n && !out.full()) {
            val lt = t.indexOf('<', i)
            if (lt < 0) break
            i = lt
            if (t.startsWith("<!--", i)) {
                val e = t.indexOf("-->", i + 4)
                val end = if (e < 0) n else e + 3
                out.add(i, end, COMMENT); i = end; continue
            }
            var j = i + 1
            if (j < n && (t[j] == '/' || t[j] == '!' || t[j] == '?')) j++
            val ns = j
            while (j < n && (t[j].isLetterOrDigit() || t[j] == ':' || t[j] == '-' || t[j] == '_' || t[j] == '.')) j++
            if (j == ns) { i++; continue }   // a lone '<' (e.g. "a < b")
            val name = t.substring(ns, j).lowercase()
            val closing = t[i + 1] == '/'
            out.add(i, j, KEYWORD)
            while (j < n && t[j] != '>' && t[j] != '<') {
                val ch = t[j]
                if (ch == '"' || ch == '\'') {
                    val e = t.indexOf(ch, j + 1)
                    val end = if (e < 0) n else e + 1
                    out.add(j, end, STRING); j = end
                } else if (ch.isLetter() || ch == '_' || ch == ':' || ch == '@') {
                    val s = j
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '-' || t[j] == '_' || t[j] == ':' || t[j] == '.' || t[j] == '@')) j++
                    out.add(s, j, ATTR)
                } else j++
            }
            if (j < n && t[j] == '>') {
                out.add(if (j > 0 && t[j - 1] == '/') j - 1 else j, j + 1, KEYWORD)
                j++
                if (!closing && (name == "script" || name == "style")) {   // embedded JS / CSS
                    val endTag = "</$name"
                    val e = t.indexOf(endTag, j, ignoreCase = true)
                    val stop = if (e < 0) n else e
                    if (stop > j) scanGeneric(t.substring(j, stop), LANGS[if (name == "script") "js" else "css"]!!, out, j)
                    j = stop
                }
            }
            i = maxOf(j, i + 1)
        }
    }

    private val MD_CODE = Regex("`[^`\\n]+`")

    private fun scanMd(t: String, out: Ints) {
        var pos = 0
        var fence = false
        val n = t.length
        while (pos <= n && !out.full()) {
            val nl = t.indexOf('\n', pos)
            val end = if (nl < 0) n else nl
            val ln = t.substring(pos, end)
            val s = ln.trimStart()
            if (s.startsWith("```") || s.startsWith("~~~")) {
                out.add(pos, end, META); fence = !fence
            } else if (!fence) {
                if (s.startsWith("#")) out.add(pos, end, KEYWORD)
                else if (s.startsWith(">")) out.add(pos, end, COMMENT)
                else for (m in MD_CODE.findAll(ln)) out.add(pos + m.range.first, pos + m.range.last + 1, STRING)
            }
            if (nl < 0) break
            pos = nl + 1
        }
    }
}
