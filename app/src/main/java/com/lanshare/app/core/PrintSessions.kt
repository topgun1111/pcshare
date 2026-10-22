package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Part 13a (Print space "Recent prints" keep everything): a SESSION is a full snapshot of one print made from the Print space editor, so the editor can be
 * reopened later with the exact layout, the exact pictures and the same printer options - and the same sheets printed again without drawing them.
 *
 * Storage: "<root>/.LANShare Print/.sessions/<sid>/" =
 *   session.json  (spec, plan, mode, only, rev, printer [to, toName, opts], names, pictures [id, name, copies, rot, from], page list, flags) - written LAST, so a folder without it is unfinished and ignored
 *   pics/<picid>/<name>  copies of every picture the layout uses (the Print space copy may be removed or cleared later)
 *   prev/<n>.jpg         small previews of the first sheets, drawn by [PrintSpaceRender.page]
 *   print.pdf            the PDF that was sent to the printer (a PDF added to Print space became pictures; this is the finished result)
 *
 * Restore = put the pictures back into the Print space under their OLD ids ([PrintSpace.adopt]) so the saved plan finds them unchanged, then the page opens
 * the editor with the stored spec / plan. Print again = send print.pdf ([pdfPath]) to a printer with the existing print pipeline (no render, no pictures needed).
 * Limits: newest [keep] sessions and [mb] MB in total (oldest go first, the newest is always kept). Saving runs in a background thread; list() only shows finished ones.
 * The folder is inside the print space folder, so [PrintSpace.inSpace] already keeps it out of "add" and the gallery.
 * NOT compiled / NOT device-tested.
 */
object PrintSessions {
    private const val DIR = ".sessions"
    private const val META = "session.json"
    private const val PICS = "pics"
    private const val PREV = "prev"
    private const val PDF = "print.pdf"
    private const val SET = "settings.json"
    const val DEF_KEEP = 12
    const val MAX_KEEP = 50
    const val DEF_MB = 500
    const val MAX_MB = 102400
    private const val PREV_W = 360
    private const val PREV_PAGES = 8
    private val SID = Regex("[0-9a-z]{6,24}")

    private val lock = Any()
    private var keep = DEF_KEEP
    private var mb = DEF_MB
    private var loaded = false
    private val working = HashSet<String>()

    private fun dir(): File = File(File(Core.local.root, PrintSpace.NAME), DIR)

    private fun box(sid: String): File {
        if (!SID.matches(sid)) throw BadReq("bad print id")
        return File(dir(), sid)
    }

    // ------------------------------------------------------------------ settings

    private fun load() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val f = File(dir(), SET)
            if (!f.isFile) return
            try {
                val o = JSONObject(f.readText())
                keep = o.optInt("keep", DEF_KEEP).coerceIn(1, MAX_KEEP)
                mb = o.optInt("mb", DEF_MB).coerceIn(0, MAX_MB)
            } catch (_: Exception) { /* damaged: defaults */ }
        }
    }

    private fun saveSettings() {
        val d = dir()
        d.mkdirs()
        val tmp = File(d, "$SET.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("keep", keep).put("mb", mb).toString())
        val dst = File(d, SET)
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) throw IOException("could not save the settings") }
    }

    // ------------------------------------------------------------------ helpers

    private fun size(f: File): Long = if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { x -> size(x) }

    /** Ids of every picture the plan uses, in order of first use (grid slots "f" / "b" hold ids or null, free items "ff" / "fb" hold objects with "id"). */
    private fun planIds(plan: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        val sheets = plan.optJSONArray("sheets") ?: return emptyList()
        for (n in 0 until sheets.length()) {
            val s = sheets.optJSONObject(n) ?: continue
            for (k in arrayOf("f", "b")) {
                val a = s.optJSONArray(k) ?: continue
                for (i in 0 until a.length()) if (!a.isNull(i)) a.optString(i).let { v -> if (v.isNotEmpty()) out.add(v) }
            }
            for (k in arrayOf("ff", "fb")) {
                val a = s.optJSONArray(k) ?: continue
                for (i in 0 until a.length()) a.optJSONObject(i)?.optString("id")?.let { v -> if (v.isNotEmpty()) out.add(v) }
            }
        }
        return out.toList()
    }

    private fun readMeta(f: File): JSONObject? = try { JSONObject(File(f, META).readText()) } catch (_: Exception) { null }

    /** Finished sessions, newest first. */
    private fun finished(): List<Pair<File, JSONObject>> {
        val kids = dir().listFiles() ?: return emptyList()
        val out = ArrayList<Pair<File, JSONObject>>()
        for (k in kids) {
            if (!k.isDirectory || !SID.matches(k.name)) continue
            val m = readMeta(k) ?: continue
            out.add(Pair(k, m))
        }
        out.sortByDescending { p -> p.second.optLong("t") }
        return out
    }

    /** Deletes unfinished leftovers (older than a minute and not being written) and sessions beyond the count / size limits. */
    private fun enforce() {
        synchronized(lock) {
            val kids = dir().listFiles() ?: return
            val now = System.currentTimeMillis()
            for (k in kids) {
                if (!k.isDirectory) continue
                val unfinished = k.name.endsWith(".tmp") || (SID.matches(k.name) && !File(k, META).isFile)
                if (unfinished && now - k.lastModified() > 60_000L && k.name.removeSuffix(".tmp") !in working) try { k.deleteRecursively() } catch (_: Exception) {}
            }
            val all = finished().toMutableList()
            while (all.size > keep) { try { all.removeAt(all.size - 1).first.deleteRecursively() } catch (_: Exception) { break } }
            if (mb > 0) {
                var total = all.sumOf { p -> size(p.first) }
                while (all.size > 1 && total > mb * 1024L * 1024L) {
                    val last = all.removeAt(all.size - 1).first
                    total -= size(last)
                    try { last.deleteRecursively() } catch (_: Exception) { break }
                }
            }
        }
    }

    // ------------------------------------------------------------------ save (background)

    /**
     * {op:"save", spec, plan, mode?, only?, rev?, to?, toName?, opts?, names?, pdf? (virtual path of the rendered PDF - must be passed right after the render,
     * the renderer purges its PDFs), pdfName?, hid? (the page's history id), ok?}. Answers {sid, pending:true} at once; the snapshot is built in a thread.
     */
    fun save(b: JSONObject): JSONObject {
        load()
        val spec = b.optJSONObject("spec") ?: throw BadReq("no layout settings to keep")
        val plan = b.optJSONObject("plan") ?: throw BadReq("no layout to keep")
        if (planIds(plan).isEmpty()) throw BadReq("the layout has no pictures")
        val sid = System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().replace("-", "").take(4)
        synchronized(lock) { working.add(sid) }
        val t = Thread {
            try { work(sid, b, spec, plan) } catch (_: Exception) { try { File(dir(), "$sid.tmp").deleteRecursively() } catch (_: Exception) {} }
            finally { synchronized(lock) { working.remove(sid) } }
            try { enforce() } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.name = "print-session"
        t.start()
        return JSONObject().put("sid", sid).put("pending", true)
    }

    private fun work(sid: String, b: JSONObject, spec: JSONObject, plan: JSONObject) {
        val tmp = File(dir(), "$sid.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("could not create the folder")
        val held = PrintSpace.items().associateBy { x -> x.id }
        val pics = JSONArray()
        var bytes = 0L
        for (id in planIds(plan)) {
            val x = held[id] ?: continue   // a picture that is already gone cannot be kept (its slot comes back empty, like everywhere else)
            val src = File(File(File(Core.local.root, PrintSpace.NAME), x.id), x.name)
            if (!src.isFile) continue
            val dstDir = File(File(tmp, PICS), x.id)
            dstDir.mkdirs()
            val dst = File(dstDir, x.name)
            src.copyTo(dst, overwrite = true)
            bytes += dst.length()
            pics.put(JSONObject().put("id", x.id).put("name", x.name).put("copies", x.copies).put("rot", x.rot).put("from", x.from))
        }
        if (pics.length() == 0) throw IOException("none of the pictures is held any more")
        var pages = 0
        val plist = JSONArray()
        try {   // previews are a bonus: any failure just leaves the entry without thumbnails
            val prep = PrintSpaceRender.prepare(JSONObject().put("spec", spec).put("plan", plan))
            pages = prep.optInt("pages")
            val lst = prep.optJSONArray("list")
            if (lst != null) for (i in 0 until lst.length()) plist.put(lst.get(i))
            val pv = File(tmp, PREV)
            pv.mkdirs()
            for (n in 0 until minOf(pages, PREV_PAGES)) {
                val jpg = PrintSpaceRender.page(prep.getString("id"), n, PREV_W)
                File(pv, "$n.jpg").writeBytes(jpg)
                bytes += jpg.size
            }
        } catch (_: Exception) { /* no previews */ }
        var hasPdf = false
        val pv = b.optString("pdf")
        if (pv.isNotEmpty()) try {
            val f = Core.local.real(vnorm(pv))
            if (f.isFile) { f.copyTo(File(tmp, PDF), overwrite = true); bytes += f.length(); hasPdf = true }
        } catch (_: Exception) { /* print again will draw the sheets from the pictures instead */ }
        val names = b.optJSONArray("names") ?: JSONArray()
        val meta = JSONObject().put("v", 1).put("sid", sid).put("t", System.currentTimeMillis())
            .put("spec", spec).put("plan", plan).put("mode", b.optString("mode", "front"))
            .put("only", b.optString("only")).put("rev", b.optBoolean("rev", false))
            .put("to", b.opt("to")).put("toName", b.optString("toName")).put("opts", b.opt("opts")).put("names", names)
            .put("pics", pics).put("pages", pages).put("plist", plist).put("hasPdf", hasPdf)
            .put("pdfName", b.optString("pdfName")).put("bytes", bytes).put("hid", b.optString("hid"))
        if (b.has("ok") && !b.isNull("ok")) meta.put("ok", b.optBoolean("ok"))
        File(tmp, META).writeText(meta.toString())
        val fin = File(dir(), sid)
        if (!tmp.renameTo(fin)) throw IOException("could not finish the snapshot")
    }

    // ------------------------------------------------------------------ read

    /** Light list for the Recent prints screen (no plan / spec): newest first. */
    fun list(): JSONObject {
        load()
        enforce()
        val a = JSONArray()
        var total = 0L
        for ((f, m) in finished()) {
            val pv = File(f, PREV).listFiles()?.count { x -> x.name.endsWith(".jpg") } ?: 0
            total += m.optLong("bytes")
            val o = JSONObject().put("sid", m.optString("sid")).put("t", m.optLong("t")).put("names", m.optJSONArray("names") ?: JSONArray())
                .put("toName", m.optString("toName")).put("opts", m.opt("opts")).put("pics", (m.optJSONArray("pics") ?: JSONArray()).length())
                .put("pages", m.optInt("pages")).put("prev", pv).put("hasPdf", m.optBoolean("hasPdf")).put("bytes", m.optLong("bytes"))
                .put("hid", m.optString("hid"))
            if (m.has("ok")) o.put("ok", m.optBoolean("ok"))
            a.put(o)
        }
        val busy = synchronized(lock) { working.size }
        return JSONObject().put("sessions", a).put("count", a.length()).put("bytes", total).put("pending", busy)
            .put("cfg", JSONObject().put("keep", keep).put("mb", mb))
    }

    fun get(sid: String): JSONObject {
        load()
        val m = readMeta(box(sid)) ?: throw NotFound("this print is no longer kept")
        val held = PrintSpace.items().map { x -> x.id }.toHashSet()
        val pics = m.optJSONArray("pics") ?: JSONArray()
        var inSpace = 0
        for (i in 0 until pics.length()) if (pics.getJSONObject(i).optString("id") in held) inSpace++
        return JSONObject().put("session", m).put("inSpace", inSpace).put("stored", pics.length())
    }

    /** One stored preview page (JPEG file) or NotFound. */
    fun previewFile(sid: String, n: Int): File {
        val f = File(File(box(sid), PREV), "${n.coerceIn(0, 999)}.jpg")
        if (!f.isFile) throw NotFound("no preview")
        return f
    }

    /** Virtual path of the stored PDF (an ordinary local path for doPrint / Jobs.startPrint) + its name. */
    fun pdfInfo(sid: String): JSONObject {
        val m = readMeta(box(sid)) ?: throw NotFound("this print is no longer kept")
        if (!File(box(sid), PDF).isFile) throw NotFound("the sheets of this print were not kept - open it in the editor")
        val nm = m.optString("pdfName").ifEmpty { "Print space " + sid + ".pdf" }
        return JSONObject().put("path", "/${PrintSpace.NAME}/$DIR/$sid/$PDF").put("name", nm)
    }

    // ------------------------------------------------------------------ restore

    /**
     * Puts the stored pictures back into the Print space under their old ids (missing ones are copied in, held ones only get the stored copies / turn) and
     * answers the stored layout so the page can open the editor with it. Honours the 500 picture cap, NOT the storage limit (the user asked for this print).
     */
    fun restore(sid: String): JSONObject {
        load()
        val bx = box(sid)
        val m = readMeta(bx) ?: throw NotFound("this print is no longer kept")
        val pics = m.optJSONArray("pics") ?: JSONArray()
        var added = 0
        var have = 0
        var failed = 0
        for (i in 0 until pics.length()) {
            val p = pics.getJSONObject(i)
            val id = p.optString("id")
            val name = p.optString("name")
            val src = File(File(File(bx, PICS), id), name)
            val r = if (src.isFile) PrintSpace.adopt(id, name, src, p.optString("from"), p.optInt("copies", 1), p.optInt("rot", 0)) else "missing"
            when (r) { "added" -> added++; "have" -> have++; else -> failed++ }
        }
        return JSONObject().put("restored", added).put("held", have).put("failed", failed).put("session", m).put("ps", PrintSpace.json())
    }

    // ------------------------------------------------------------------ change

    fun mark(sid: String, ok: Boolean) {
        synchronized(lock) {
            val f = File(box(sid), META)
            val m = readMeta(box(sid)) ?: return
            m.put("ok", ok)
            val tmp = File(box(sid), "$META.tmp")
            tmp.writeText(m.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun remove(sids: List<String>): Int {
        var n = 0
        for (s in sids) { val f = box(s); if (f.exists() && f.deleteRecursively()) n++ }
        return n
    }

    fun clear(): Int {
        var n = 0
        for ((f, _) in finished()) if (f.deleteRecursively()) n++
        return n
    }

    fun setSettings(newKeep: Int?, newMb: Int?) {
        load()
        synchronized(lock) {
            if (newKeep != null) keep = newKeep.coerceIn(1, MAX_KEEP)
            if (newMb != null) mb = newMb.coerceIn(0, MAX_MB)
            saveSettings()
        }
        enforce()
    }

    /** POST /api/pssess body: {op:"save", ...} | {op:"get", sid} | {op:"restore", sid} | {op:"pdf", sid} | {op:"mark", sid, ok} | {op:"rm", sids:[...]} | {op:"clear"} | {op:"cfg", keep?, mb?}. */
    fun post(b: JSONObject): JSONObject {
        val op = b.optString("op")
        val res = when (op) {
            "save" -> save(b)
            "get" -> get(b.getString("sid"))
            "restore" -> restore(b.getString("sid"))
            "pdf" -> pdfInfo(b.getString("sid"))
            "mark" -> { mark(b.getString("sid"), b.optBoolean("ok")); JSONObject() }
            "rm" -> JSONObject().put("removed", remove(b.getJSONArray("sids").strings()))
            "clear" -> JSONObject().put("removed", clear())
            "cfg" -> { setSettings(if (b.has("keep")) b.getInt("keep") else null, if (b.has("mb")) b.getInt("mb") else null); JSONObject() }
            else -> throw BadReq("unknown print history operation: $op")
        }
        if (op != "get" && op != "restore") { val l = list(); for (k in l.keys()) res.put(k, l.get(k)) }
        return res
    }
}
