package com.lanshare.app.core

import com.lanshare.app.core.usearch.CancelToken
import com.lanshare.app.core.usearch.MatchOptions
import com.lanshare.app.core.usearch.Query
import com.lanshare.app.core.usearch.QueryParser
import com.lanshare.app.core.usearch.Search
import com.lanshare.app.core.usearch.SortKey
import com.lanshare.app.core.usearch.SortSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal Search, Part 4: the one place that runs a name query for the screen ([com.lanshare.app.UsActivity]) and for
 * GET /api/usearch. Parse, [Search.run], then the filters that cannot be done while indexing (the Hide list, D9).
 * Results carry real paths; [virt] turns one into the file manager's virtual path (null = another volume, e.g. an SD card).
 */
object UsSearch {
    class Row(val id: Int, val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)

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

    /** File manager virtual path of the real path [abs] (main storage only); null for another volume. */
    fun virt(abs: String): String? {
        val root = try { Core.local.root.path.trimEnd('/') } catch (_: Throwable) { return null }
        if (abs == root) return "/"
        return if (abs.startsWith("$root/")) abs.substring(root.length) else null
    }

    fun toJson(o: Out): JSONObject {
        val a = JSONArray()
        for (r in o.rows) a.put(JSONObject().put("id", r.id).put("name", r.name).put("path", virt(r.path) ?: r.path)
            .put("dir", r.dir).put("size", r.size).put("mtime", r.mtime))
        val st = IndexManager.status()
        return JSONObject().put("total", o.total).put("approx", o.approx).put("complete", o.complete).put("ms", o.ms)
            .put("notes", JSONArray(o.notes)).put("items", a)
            .put("index", JSONObject().put("state", st.optString("state")).put("complete", st.optBoolean("complete")).put("pct", st.optInt("pct", -1))
                .put("entries", st.optInt("entries")))
    }

    fun optsOf(case: Boolean, word: Boolean, path: Boolean, diac: Boolean, regex: Boolean) = MatchOptions(case, word, path, diac, regex)

}
