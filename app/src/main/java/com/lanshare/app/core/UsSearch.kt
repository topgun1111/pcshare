package com.lanshare.app.core

import com.lanshare.app.core.usearch.CancelToken
import com.lanshare.app.core.usearch.ContentMatch
import com.lanshare.app.core.usearch.Ctx
import com.lanshare.app.core.usearch.HiddenFilter
import com.lanshare.app.core.usearch.Matcher
import com.lanshare.app.core.usearch.NameIndex
import com.lanshare.app.core.usearch.QAnd
import com.lanshare.app.core.usearch.QFilter
import com.lanshare.app.core.usearch.QNode
import com.lanshare.app.core.usearch.TextExtractor
import com.lanshare.app.core.usearch.MatchOptions
import com.lanshare.app.core.usearch.Query
import com.lanshare.app.core.usearch.QueryParser
import com.lanshare.app.core.usearch.Search
import com.lanshare.app.core.usearch.SortKey
import com.lanshare.app.core.usearch.SortSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Universal Search, Part 4: the one place that runs a name query for the screen ([com.lanshare.app.UsActivity]) and for
 * GET /api/usearch. Parse, [Search.run], then the filters that cannot be done while indexing (the Hide list, D9).
 * Results carry real paths; [virt] turns one into the file manager's virtual path (null = another volume, e.g. an SD card).
 */
object UsSearch {
    class Row(val id: Int, val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long, val snippet: String = "", val line: Int = 0)

    class Out(val rows: List<Row>, val total: Int, val complete: Boolean, val ms: Long, val notes: List<String>, val approx: Boolean)

    /** Extra hits fetched per page so that hidden ones can be dropped without a second scan. */
    private const val SLACK = 200

    fun sortOf(s: String?, reverse: Boolean, foldersFirst: Boolean = false): SortSpec = SortSpec(when ((s ?: "").lowercase()) {
        "name" -> SortKey.NAME
        "path" -> SortKey.PATH
        "size" -> SortKey.SIZE
        "date", "mtime" -> SortKey.DATE
        else -> SortKey.BEST
    }, reverse, foldersFirst)

    /** [kind] = "" (all) or a `kind:` value (file, folder, image, video, audio, doc ...); it is added to the query text. */
    fun run(text: String, kind: String, opt: MatchOptions, sort: SortSpec, offset: Int, limit: Int, cancel: CancelToken): Out? {
        val ix = IndexManager.index() ?: return null
        val full = if (kind.isEmpty()) text else (text.trim() + " kind:" + kind)
        val q: Query = QueryParser.parse(full)
        val hf = IndexManager.hiddenFilter()
        val hid = try { com.lanshare.app.NlModel.hidden.isNotEmpty() } catch (_: Throwable) { false }
        if (q.content != null) return runContent(ix, q, opt, sort, offset.coerceAtLeast(0), limit.coerceIn(1, 2000), cancel, hf, hid)
        val off = offset.coerceAtLeast(0)
        val lim = limit.coerceIn(1, 2000)
        // no Hide list: the page is exact. With one: fetch the top (offset + limit + slack), drop hidden ones, slice.
        val r = if (!hid) Search.run(ix, q, opt, sort, off, lim, cancel)
                else Search.run(ix, q, opt, sort, 0, minOf(Search.MAX_K, off + lim + SLACK), cancel)
        val ids = r.ids
        val rows = ArrayList<Row>(minOf(ids.size, lim))
        var dropped = 0
        var seen = 0
        for (id in ids) {
            val p = ix.pathOf(id)
            if (hid && hf.isHidden(p)) { dropped++; continue }
            if (hid) { seen++; if (seen <= off) continue; if (rows.size >= lim) continue }
            if (!ix.isLive(id)) continue
            rows.add(Row(id, ix.nameOf(id), p, ix.isDir(id), ix.sizeOf(id), ix.mtimeOf(id)))
        }
        // with the Hide list the total is only exact when everything that matched was fetched
        val approx = hid && r.total > ids.size
        val total = if (hid) (if (approx) r.total else r.total - dropped) else r.total
        return Out(rows, total.coerceAtLeast(0), r.complete, r.ms, r.notes, approx)
    }

    /**
     * content: query (Part 5). Candidates = stored text files that contain the words; then the name expression and the filters of the query
     * (ext: size: dm: in: ...) and the Hide list are applied through the name index. BEST sort = newest first. Snippets of the returned page
     * are re-read from the real file (so they keep their case and accents) when the file has not changed since indexing.
     */
    private fun runContent(ix: NameIndex, q: Query, opt: MatchOptions, sort: SortSpec, off: Int, lim: Int, cancel: CancelToken, hf: HiddenFilter, hid: Boolean): Out {
        val t0 = System.currentTimeMillis()
        val notes = ArrayList<String>(q.notes)
        val store = IndexManager.content() ?: return Out(emptyList(), 0, true, 0L, notes + "Content search is off (Settings > Search index)", false)
        val terms = ContentMatch.compile(q.content!!.terms, opt, notes)
        if (terms.none { !it.not }) return Out(emptyList(), 0, true, 0L, notes + "content: needs at least one word", false)
        val extra = ArrayList<QNode>()
        q.name?.let { extra.add(it) }
        for (f in q.filters) extra.add(QFilter(f))
        val m = if (extra.isEmpty()) null else Matcher(QAnd(extra), opt)
        val ctx = Ctx(ix)
        val (found, complete) = store.search(ContentMatch.needles(terms), { txt -> ContentMatch.find(txt, terms) }, 5000, t0 + 8000L, { cancel.cancelled })
        class Cand(val id: Int, val f: ContentStore.Found)
        val cands = ArrayList<Cand>()
        for (f in found) {
            val id = ix.find(f.path)
            if (id == NameIndex.NONE || !ix.isLive(id)) continue
            if (m != null) { ctx.reset(id); if (!m.test(ctx)) continue }
            if (hid && hf.isHidden(f.path)) continue
            cands.add(Cand(id, f))
        }
        val key = sort.key
        val cmp: Comparator<Cand> = when (key) {
            SortKey.NAME -> compareBy<Cand> { ix.nameOf(it.id).lowercase() }
            SortKey.PATH -> compareBy<Cand> { it.f.path.lowercase() }
            SortKey.SIZE -> compareByDescending<Cand> { it.f.size }
            else -> compareByDescending<Cand> { it.f.mtime }
        }
        val sorted = cands.sortedWith(if (sort.reverse) cmp.reversed() else cmp)
        val rows = ArrayList<Row>()
        for (c in sorted.drop(off).take(lim)) {
            var snip = c.f.hit.snip
            val isDoc = com.lanshare.app.core.office.DocExtractor.isDoc(c.f.path.substringAfterLast('.', ""))
            if (!isDoc) try {   // real text for the snippet when the file is unchanged
                val file = File(c.f.path)
                if (file.length() == c.f.size && file.lastModified() / 1000 == c.f.mtime) {
                    val r = TextExtractor.extract(file, c.f.path.substringAfterLast('.', "").lowercase(), c.f.size + 1)
                    if (r != null && r.text.length > c.f.hit.pos) snip = ContentMatch.snippet(r.text, c.f.hit.pos, c.f.hit.len)
                }
            } catch (_: Throwable) {}
            if (isDoc) rows.add(Row(c.id, ix.nameOf(c.id), c.f.path, false, c.f.size, c.f.mtime, (if (c.f.label.isNotEmpty()) c.f.label + ": " else "") + snip, 0))   // documents: no line number, snippet is the stored (folded) text
            else rows.add(Row(c.id, ix.nameOf(c.id), c.f.path, false, c.f.size, c.f.mtime, snip, c.f.hit.line))
        }
        if (!complete) notes.add("content: showing the first part of the matches (time / count cap) - add words or filters to narrow")
        if (!IndexManager.isComplete()) notes.add("The name index is still being built")
        return Out(rows, cands.size, complete, System.currentTimeMillis() - t0, notes, !complete)
    }

    /** File manager virtual path of the real path [abs] (main storage only); null for another volume. */
    fun virt(abs: String): String? {
        val root = try { Core.local.root.path.trimEnd('/') } catch (_: Throwable) { return null }
        if (abs == root) return "/"
        return if (abs.startsWith("$root/")) abs.substring(root.length) else null
    }

    fun toJson(o: Out): JSONObject {
        val a = JSONArray()
        for (r in o.rows) a.put(JSONObject().put("id", r.id).put("name", r.name).put("path", virt(r.path) ?: r.path)
            .put("dir", r.dir).put("size", r.size).put("mtime", r.mtime).put("snippet", r.snippet).put("line", r.line))
        val st = IndexManager.status()
        return JSONObject().put("total", o.total).put("approx", o.approx).put("complete", o.complete).put("ms", o.ms)
            .put("notes", JSONArray(o.notes)).put("items", a)
            .put("index", JSONObject().put("state", st.optString("state")).put("complete", st.optBoolean("complete")).put("pct", st.optInt("pct", -1))
                .put("entries", st.optInt("entries")))
    }

    fun optsOf(case: Boolean, word: Boolean, path: Boolean, diac: Boolean, regex: Boolean) = MatchOptions(case, word, path, diac, regex)

}
