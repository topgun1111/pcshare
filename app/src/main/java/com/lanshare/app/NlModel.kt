package com.lanshare.app

import android.icu.text.Collator
import android.icu.text.DateFormat
import android.icu.text.RuleBasedCollator
import com.lanshare.app.core.Core
import com.lanshare.app.core.Item
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date
import java.util.Objects

/**
 * Kotlin twin of ui.html's list pipeline for the LOCAL device: listing -> hidden filter -> sort -> dups -> gallery split -> row texts.
 * Used for instant folder entry (NativeList.tapRow): the next folder's rows are built here, without a trip through the page.
 * The page stays authoritative: it revalidates after every tap and its rows replace these if they differ (NlModel.sig compares content).
 * Keep in sync with ui.html: shown(), findDups(), kind(), fmt(), nlA(), nlBuild(), BADGE, COL.
 */
object NlModel {
    class Cfg(val sort: String, val asc: Boolean, val hid: Boolean, val gal: Boolean, val view: String, val thumb: String)

    /** rows == null: not buildable here (page decides); count = number of entries read (for the "too big" memo). */
    class Result(val rows: List<NlRow>?, val count: Int, val sum: Pair<String, String>? = null)

    /** ui.html nlCfg(): sort,asc,hid,gal,view,thumb */
    fun parseCfg(s: String): Cfg? {
        val p = s.split(',')
        if (p.size < 6) return null
        return Cfg(p[0], p[1] == "1", p[2] == "1", p[3] == "1", p[4], p[5])
    }

    private fun col(rgb: Int): Int = (0xFF000000L or rgb.toLong()).toInt()

    private val EXT: Map<String, String> = HashMap<String, String>().also { m ->
        listOf(
            "img" to "jpg jpeg png gif webp bmp heic svg",
            "vid" to "mp4 mkv mov avi webm 3gp m4v mpg mpeg flv ogv m2ts mts",
            "aud" to "mp3 wav m4a ogg flac aac opus",
            "pdf" to "pdf",
            "zip" to "zip rar 7z tar gz cbz cbr",
            "apk" to "apk",
            "doc" to "txt md rtf doc docx odt xls xlsx csv ppt pptx"
        ).forEach { (k, s) -> s.split(' ').forEach { m[it] = k } }
    }

    private class Badge(val icon: String, val color: Int)
    private val BADGE: Map<String, Badge> = mapOf(
        "dcim" to Badge("camera", col(0x333333)), "download" to Badge("download", col(0x2f9bd8)), "downloads" to Badge("download", col(0x2f9bd8)),
        "movies" to Badge("vid", col(0xb3261e)), "music" to Badge("aud", col(0x0f8a6d)), "pictures" to Badge("img", col(0x2e7d32)),
        "documents" to Badge("doc", col(0x1a6fd1))
    )

    // ui.html kind(): the extension is whatever follows the last '.', or the whole name when there is no dot (same quirk)
    fun kind(name: String, dir: Boolean): String = if (dir) "folder" else (EXT[name.substringAfterLast('.').lowercase()] ?: "file")

    private fun extOf(n: String): String { val p = n.lastIndexOf('.'); return if (p > 0) n.substring(p + 1).lowercase() else "" }

    /** ui.html fmt(): 1 decimal from KB up, exact binary value rounded half up like Number.toFixed. */
    fun fmt(n0: Long): String {
        var n = n0.toDouble(); var i = 0
        while (n >= 1024 && i < 4) { n /= 1024; i++ }
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        return (if (i > 0) BigDecimal(n).setScale(1, RoundingMode.HALF_UP).toPlainString() else n0.toString()) + " " + u[i]
    }

    private val DUP_RE = Regex("(\\s*\\(\\d+\\)|\\s*-\\s*copy(\\s*\\(\\d+\\))?|\\s+copy(\\s*\\d+)?|_\\d+)$", RegexOption.IGNORE_CASE)

    /** ui.html findDups(): same size (>0), same extension and the same name once a copy suffix is removed. Over 5000 entries: none. */
    private fun findDups(items: List<Item>): Set<String> {
        if (items.size > 5000) return emptySet()
        val g = HashMap<String, MutableList<String>>()
        for (i in items) {
            if (i.dir || i.size <= 0) continue
            val e = extOf(i.name)
            val b = DUP_RE.replaceFirst(if (e.isNotEmpty()) i.name.dropLast(e.length + 1) else i.name, "").lowercase()
            g.getOrPut(i.size.toString() + "|" + e + "|" + b) { ArrayList() }.add(i.name)
        }
        val out = HashSet<String>()
        for (v in g.values) if (v.size > 1) out.addAll(v)
        return out
    }

    private val NOTHUMB = if (android.os.Build.VERSION.SDK_INT >= 28) Regex("\\.svg$", RegexOption.IGNORE_CASE) else Regex("\\.(svg|heic|heif)$", RegexOption.IGNORE_CASE)   // HEIC/HEIF decode natively from API 28

    /** List this phone's folder [path] and build the rows exactly as ui.html would. Never throws. [maxItems]: give up on bigger folders. */
    fun build(path: String, cfgS: String, maxItems: Int = Int.MAX_VALUE): Result {
        try {
            val cfg = parseCfg(cfgS) ?: return Result(null, 0)
            if (path.contains('!')) return Result(null, 0)
            if (cfg.sort == "size" && maxItems != Int.MAX_VALUE) return Result(null, 0)   // size order needs every sub-folder's count: only built on a real tap, never as an idle pre-build
            if (cfg.view != "list" && cfg.view != "compact" && cfg.view != "grid") return Result(null, 0)
            if (Core.url == null) return Result(null, 0)
            var items = Core.local.ls(path, false)
            if (items.size > maxItems) return Result(null, items.size)
            if (cfg.sort == "size") {   // the page's final state: counts filled in (fillCounts) and the list re-sorted by them
                val c = Core.local.counts(path)
                items = items.map { if (it.dir) (c[it.name]?.let { n -> it.copy(n = n) } ?: it) else it }
            }
            return Result(rows(items, path, cfg), items.size, headSum(items, cfg.hid))
        } catch (_: Throwable) { return Result(null, 0) }
    }

    /** ui.html renderTools(): "12 items . 3.4 MB" (bold) and "2 folders . 10 files" (small, only when both kinds exist) of the visible entries. */
    fun headSum(items: List<Item>, hid: Boolean): Pair<String, String> {
        var n = 0; var fo = 0; var fi = 0; var sz = 0L
        for (i in items) {
            if (!hid && (i.hid || (i.name.isNotEmpty() && i.name[0] == '.'))) continue
            n++; if (i.dir) fo++ else { fi++; sz += i.size }
        }
        val b = n.toString() + (if (n == 1) " item" else " items") + (if (fi > 0) " \u00b7 " + fmt(sz) else "")
        val s = if (fo > 0 && fi > 0) fo.toString() + (if (fo == 1) " folder" else " folders") + " \u00b7 " + fi + (if (fi == 1) " file" else " files") else ""
        return Pair(b, s)
    }

    fun rows(items0: List<Item>, p0: String, cfg: Cfg): List<NlRow>? {
        // Routes "ls" hands the page its items dir-first, then by lower-case name: same start order here (sort "none" and ties depend on it)
        val items = items0.map { it to it.name.lowercase() }.sortedWith(compareBy<Pair<Item, String>>({ !it.first.dir }, { it.second })).map { it.first }
        val dups = findDups(items)
        val coll = Collator.getInstance()                          // Intl.Collator(undefined,{numeric:true,sensitivity:'base'})
        (coll as? RuleBasedCollator)?.setNumericCollation(true)
        coll.setStrength(Collator.PRIMARY)
        val vis = if (cfg.hid) items else items.filter { !it.hid && (it.name.isEmpty() || it.name[0] != '.') }
        val d = if (cfg.asc) 1 else -1
        val num = { i: Item -> if (cfg.sort == "date") i.mtime else if (i.dir) (i.n ?: 0).toLong() else i.size }
        val v: List<Item> = if (cfg.sort == "none") vis else vis.sortedWith(Comparator<Item> { a, b ->
            if (a.dir != b.dir) return@Comparator if (a.dir) -1 else 1
            val c = when (cfg.sort) {
                "name" -> coll.compare(a.name, b.name)
                "type" -> if (a.dir) 0 else coll.compare(extOf(a.name), extOf(b.name))
                else -> java.lang.Long.compare(num(a), num(b))
            }
            if (c != 0) c * d else coll.compare(a.name, b.name) * (if (cfg.sort == "name") d else 1)
        })
        val isVid = { i: Item -> !i.dir && kind(i.name, false) == "vid" }
        val gv = if (cfg.gal) v.filter(isVid) else emptyList()
        val list = if (gv.isNotEmpty()) v.filterNot(isVid) else v
        if (list.size + gv.size == 0) return null                   // empty folder: the page shows its empty state

        val cp = cfg.view == "compact"
        val df = DateFormat.getInstanceForSkeleton("yMMMd")
        val tf = DateFormat.getInstanceForSkeleton("jjmm")
        val dateOnly = { i: Item -> if (i.mtime != 0L) df.format(Date(i.mtime * 1000)) else "" }
        val out = ArrayList<NlRow>(list.size + gv.size)
        fun add(i: Item, isGal: Boolean) {
            val k = kind(i.name, i.dir)
            val dt = if (cp) dateOnly(i) else ""
            val sz = if (i.dir) (if (i.n == null) "Folder" else i.n.toString() + (if (i.n == 1) " item" else " items")) else fmt(i.size)
            val a = if (cp) (if (cfg.sort == "date" && dt.isNotEmpty()) dt else sz) else sz
            val b = if (cp || i.mtime == 0L) "" else df.format(Date(i.mtime * 1000)) + ", " + tf.format(Date(i.mtime * 1000))
            var badge: String? = null; var badgeCol = 0; var dup = false
            var path: String? = null; var size = 0L; var mt = 0L
            if (i.dir) {
                BADGE[i.name.lowercase()]?.let { badge = it.icon; badgeCol = it.color }
            } else {
                if (i.name in dups) dup = true
                size = i.size                                   // every file row carries its size (viewers need it); ui.html nlBuild sends `s` for all files too
                if ((k == "img" || k == "vid") && !NOTHUMB.containsMatchIn(i.name) && (k == "vid" || i.size < 30_000_000L)) {
                    path = (if (p0 == "/") "" else p0) + "/" + i.name; size = i.size; mt = i.mtime
                }
            }
            out.add(NlRow(i.name, i.dir, k, a, b, badge, badgeCol, dup, path, size, mt, isGal, "local", false, nlIsNew(i.mtime)))
        }
        for (i in list) add(i, false)
        for (i in gv) add(i, true)
        return out
    }

    /** Content signature of a row list: equal rows = equal signature, whoever built them (page JSON or this object). */
    fun sig(rows: List<NlRow>): Int {
        var h = 1
        for (r in rows) h = 31 * h + Objects.hash(r.nm, r.dir, r.k, r.a, r.b, r.badge, r.badgeCol, r.dup, r.path, r.size, r.mtime, r.gal, r.dev, r.hit, r.fresh)
        return h
    }
}
