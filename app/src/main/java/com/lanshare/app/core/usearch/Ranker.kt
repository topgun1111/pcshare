package com.lanshare.app.core.usearch

import java.util.PriorityQueue

/** Match quality of one plain term against a folded name (chapter 16.7 Part 2). Smaller = better. */
object Ranker {
    /** 0 exact name (or name without its extension), 1 name starts with the term, 2 term starts a word, 3 substring, 4 not in the name. */
    fun tier(foldedName: String, term: String): Int {
        if (term.isEmpty()) return 3
        if (foldedName == term) return 0
        val dot = foldedName.lastIndexOf('.')
        if (dot > 0 && foldedName.length - dot - 1 <= 12 && foldedName.substring(0, dot) == term) return 0
        if (foldedName.startsWith(term)) return 1
        var i = foldedName.indexOf(term)
        if (i < 0) return 4
        while (i >= 0) {
            if (i == 0 || !Character.isLetterOrDigit(foldedName[i - 1])) return 2
            i = foldedName.indexOf(term, i + 1)
        }
        return 3
    }
}

class CancelToken {
    @Volatile var cancelled = false
    fun cancel() { cancelled = true }
}

enum class SortKey { BEST, NAME, PATH, SIZE, DATE }

/**
 * Natural direction: BEST best first, NAME and PATH A to Z, SIZE and DATE biggest / newest first; reverse flips it.
 * NAME and PATH compare the folded text (Turkish letters sort as their plain letters). Ties end in index order.
 */
class SortSpec(val key: SortKey = SortKey.BEST, val reverse: Boolean = false, val foldersFirst: Boolean = false)

/** ids = the requested page of entry ids (NameIndex ids), total = all hits, complete = false when cancelled (ids and total are then partial). */
class SearchResult(val ids: IntArray, val total: Int, val complete: Boolean, val ms: Long, val notes: List<String>)

object Search {
    const val CHUNK = 8192
    /** offset + limit is capped at this many results (the top of the ranking is kept in a bounded heap). */
    const val MAX_K = 20000

    private class Cand(val id: Int) {
        var rank = 0
        var depth = -1
        var mt = 0L
        var sz = 0L
        var nm: String? = null
        var pt: String? = null
        var dir = false
    }

    private fun depthOf(ix: NameIndex, id: Int): Int {
        var d = 0
        var x = ix.parentOf(id)
        while (x >= 0 && d < 4096) { d++; x = ix.parentOf(x) }
        return d
    }

    private fun depth(a: Cand, ix: NameIndex): Int {
        if (a.depth < 0) a.depth = depthOf(ix, a.id)
        return a.depth
    }

    // negative = a comes first
    private fun compare(a: Cand, b: Cand, s: SortSpec, ix: NameIndex): Int {
        if (s.foldersFirst && a.dir != b.dir) return if (a.dir) -1 else 1
        var r = when (s.key) {
            SortKey.BEST -> {
                var x = a.rank.compareTo(b.rank)
                if (x == 0) x = depth(a, ix).compareTo(depth(b, ix))
                if (x == 0) x = b.mt.compareTo(a.mt)
                x
            }
            SortKey.NAME -> a.nm!!.compareTo(b.nm!!)
            SortKey.PATH -> a.pt!!.compareTo(b.pt!!)
            SortKey.SIZE -> b.sz.compareTo(a.sz)
            SortKey.DATE -> b.mt.compareTo(a.mt)
        }
        if (s.reverse) r = -r
        if (r == 0) r = a.id.compareTo(b.id)
        return r
    }

    /**
     * Runs the name expression of [q] over the index. Scans in chunks of CHUNK slots and checks [cancel] between chunks.
     * A query without a name expression (empty, or only content: ocr: ai:) returns no ids.
     */
    fun run(
        ix: NameIndex,
        q: Query,
        opt: MatchOptions = MatchOptions(),
        sort: SortSpec = SortSpec(),
        offset: Int = 0,
        limit: Int = 100,
        cancel: CancelToken = CancelToken()
    ): SearchResult {
        val t0 = System.nanoTime()
        val notes = ArrayList<String>(q.notes)
        val node = q.name
        if (node == null) return SearchResult(IntArray(0), 0, true, 0L, notes)
        val m = Matcher(node, opt)
        notes.addAll(m.notes)
        val off = offset.coerceIn(0, 1000000)
        val lim = limit.coerceIn(0, 100000)
        val k = minOf(MAX_K.toLong(), off.toLong() + lim.toLong()).toInt()
        val cmp = Comparator<Cand> { a, b -> compare(a, b, sort, ix) }
        val heap = PriorityQueue<Cand>(maxOf(2, k + 1), Comparator<Cand> { a, b -> compare(b, a, sort, ix) })   // head = worst kept
        val c = Ctx(ix)
        val slots = ix.slotCount()
        val buf = IntArray(CHUNK)
        var total = 0
        var from = 0
        var complete = true
        while (from < slots) {
            if (cancel.cancelled) { complete = false; break }
            val to = minOf(slots, from + CHUNK)
            var bn = 0
            ix.scanRange(from, to, m.prefilter) { id -> buf[bn] = id; bn++ }
            for (i in 0 until bn) {
                val id = buf[i]
                c.reset(id)
                if (!ix.isLive(id)) continue
                if (!m.test(c)) continue
                total++
                if (k > 0) {
                    val cd = Cand(id)
                    if (sort.foldersFirst) cd.dir = c.isDir()
                    when (sort.key) {
                        SortKey.BEST -> { cd.rank = m.rank(c); cd.mt = c.mtime() }
                        SortKey.NAME -> cd.nm = c.fold()
                        SortKey.PATH -> cd.pt = c.pathFold(false)
                        SortKey.SIZE -> cd.sz = c.size()
                        SortKey.DATE -> cd.mt = c.mtime()
                    }
                    heap.add(cd)
                    if (heap.size > k) heap.poll()
                }
            }
            from = to
        }
        val list = ArrayList<Cand>(heap)
        list.sortWith(cmp)
        val start = minOf(off, list.size)
        val end = minOf(list.size, off + lim)
        val ids = IntArray(maxOf(0, end - start)) { list[start + it].id }
        return SearchResult(ids, total, complete, (System.nanoTime() - t0) / 1000000L, notes)
    }
}
