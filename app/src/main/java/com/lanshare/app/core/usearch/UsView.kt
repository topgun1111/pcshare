package com.lanshare.app.core.usearch

/**
 * Universal Search, Part 8: pure logic of the results screen (no android.*), tested by tools/usearch_selftest (`viewTests`).
 * [UsHistory] = recent queries + saved searches (text form for SharedPreferences), [UsSections] = "Search everywhere" plan
 * and the folder scope ("Search here").
 */
object UsHistory {
    const val MAX_RECENT = 30
    const val MAX_SAVED = 50

    class Saved(val name: String, val query: String)

    /** [q] goes to the front; blank or 1-char queries are ignored; duplicates (ignoring case) are merged; at most [MAX_RECENT]. */
    fun push(list: List<String>, q: String): List<String> {
        val t = q.trim()
        if (t.length < 2) return list
        val out = ArrayList<String>()
        out.add(t)
        for (x in list) if (!x.equals(t, ignoreCase = true) && out.size < MAX_RECENT) out.add(x)
        return out
    }

    fun remove(list: List<String>, q: String): List<String> = list.filter { !it.equals(q, ignoreCase = true) }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun encode(list: List<String>): String = list.joinToString("\n") { esc(it) }

    fun decode(s: String?): List<String> =
        if (s.isNullOrEmpty()) emptyList() else s.split('\n').filter { it.isNotEmpty() }.map { unesc(it) }.filter { it.isNotBlank() }.take(MAX_RECENT)

    /** Same name (ignoring case) replaces the old entry; newest first; at most [MAX_SAVED]. */
    fun save(list: List<Saved>, name: String, query: String): List<Saved> {
        val n = name.trim().ifEmpty { query.trim() }
        if (query.isBlank()) return list
        val out = ArrayList<Saved>()
        out.add(Saved(n, query.trim()))
        for (x in list) if (!x.name.equals(n, ignoreCase = true) && out.size < MAX_SAVED) out.add(x)
        return out
    }

    fun encodeSaved(list: List<Saved>): String = list.joinToString("\n") { esc(it.name) + "\t" + esc(it.query) }

    fun decodeSaved(s: String?): List<Saved> {
        if (s.isNullOrEmpty()) return emptyList()
        val out = ArrayList<Saved>()
        for (line in s.split('\n')) {
            val t = line.indexOf('\t')
            if (t <= 0) continue
            val q = unesc(line.substring(t + 1))
            if (q.isBlank()) continue
            out.add(Saved(unesc(line.substring(0, t)), q))
        }
        return out.take(MAX_SAVED)
    }
}

object UsSections {
    class Sec(val id: String, val title: String, val query: String)

    /** True when the text already picks one engine (a content: / ocr: / ai: token) - then there is only one section. */
    fun hasEngine(text: String): Boolean = text.split(' ', '\t').any {
        val l = it.lowercase().trimStart('!', '-')
        l.startsWith("content:") || l.startsWith("ocr:") || l.startsWith("ai:")
    }

    /**
     * Sections of "Search everywhere" (D4): Names, Contents, Pictures (text), Pictures (meaning). A section query is the typed text
     * after an engine prefix, so `ext:` / `size:` / `in:` words in the text still filter every section.
     * Not everywhere, a blank text or an text with its own engine prefix = one section holding the text as typed.
     */
    fun plan(text: String, everywhere: Boolean, content: Boolean, images: Boolean): List<Sec> {
        val t = text.trim()
        if (t.isEmpty() || !everywhere || hasEngine(t)) return listOf(Sec("names", "Results", t))
        val l = ArrayList<Sec>()
        l.add(Sec("names", "Names", t))
        if (content) l.add(Sec("content", "Contents", "content: $t"))
        if (images) { l.add(Sec("ocr", "Pictures \u00B7 text in image", "ocr: $t")); l.add(Sec("ai", "Pictures \u00B7 meaning", "ai: $t")) }
        return l
    }

    fun title(s: Sec, n: Int, approx: Boolean): String = s.title + "  \u00B7  " + n + (if (approx) "+" else "")

    /** The query with an extra `in:` folder filter. Used for Query objects, see [scope]. */
    fun scope(q: Query, dir: String): Query {
        if (dir.isBlank()) return q
        val f = InFilter(dir)
        val node = QFilter(f)
        val name: QNode = if (q.name == null) node else QAnd(listOf(q.name, node))
        return Query(name, q.content, q.ocr, q.ai, q.filters + f, q.notes)
    }
}
