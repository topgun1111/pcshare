package com.lanshare.app.core.usearch

/** Universal Search, Part 9: names INSIDE archives (`arc:word`). Pure matching (no android.*), tested by `arcTests`. */
object ArcNames {
    /** Query words, folded (Turkish rules, diacritics dropped), no empties. */
    fun words(q: String): List<String> = q.trim().split(Regex("\\s+")).map { Fold.fold(it) }.filter { it.isNotEmpty() }

    /** True when every word is inside the folded entry path ("dir/sub/file.txt"); [ws] empty matches nothing. */
    fun matches(entryPath: String, ws: List<String>): Boolean {
        if (ws.isEmpty()) return false
        val f = Fold.fold(entryPath)
        for (w in ws) if (!f.contains(w)) return false
        return true
    }

    /** Last path segment of an entry. */
    fun leaf(entryPath: String): String = entryPath.substringAfterLast('/')

    /** Splits "arc:word word" -> "word word"; null when [text] is not an archive query. */
    fun strip(text: String): String? {
        val t = text.trim()
        return if (t.length >= 4 && t.substring(0, 4).equals("arc:", ignoreCase = true)) t.substring(4).trim() else null
    }
}
