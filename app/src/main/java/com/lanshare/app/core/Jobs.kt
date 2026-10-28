package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Shared copy/cut clipboard (one per device, same as the Python CLIP dict). */
object Clip {
    @Volatile var op: String? = null
    @Volatile var dev: String? = null
    @Volatile var paths: List<String> = emptyList()

    @Synchronized fun clear() { op = null; dev = null; paths = emptyList() }
    @Synchronized fun set(op: String, dev: String, paths: List<String>) { this.op = op; this.dev = dev; this.paths = paths }
    fun isEmpty() = op == null
    fun toJson(): Any = if (op == null) JSONObject.NULL
        else JSONObject().put("op", op).put("dev", dev).put("paths", org.json.JSONArray(paths))
}

class Job(@Volatile var label: String) {
    @Volatile var id = ""            // set for jobs that are registered in [Jobs.all] by someone else than [Jobs.start]
    @Volatile var state = "run"      // run | done | cancel | error
    @Volatile var done = 0L
    @Volatile var total = 1L
    @Volatile var bytes = true
    @Volatile var error: String? = null
    @Volatile private var cancelled = false
    /** Cancel: besides the flag (polled between steps), every registered abort action runs at once - sockets that wait for a slow printer / PC are closed, so the job stops now instead of after the network timeout. */
    var cancel: Boolean
        get() = cancelled
        set(v) { cancelled = v; if (v) runAborts() }
    private val aborts = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onAbort(f: () -> Unit) { aborts.add(f); if (cancelled) try { f() } catch (_: Throwable) {} }
    private fun runAborts() { for (f in aborts) try { f() } catch (_: Throwable) {} }
    @Volatile var end = 0L
    @Volatile var note: String? = null   // final success text shown in the UI (print jobs)
    @Volatile var result: JSONObject? = null   // structured result (duplicate finder)

    // details for the progress card: the file being handled now, file counts, problems so far, where it is going
    @Volatile var cur = ""
    @Volatile var curDone = 0L
    @Volatile var curTotal = 0L
    @Volatile var files = 0
    @Volatile var filesTotal = 0
    @Volatile var failed = 0
    @Volatile var dest: String? = null
    @Volatile var rid: String? = null          // resume id this job continues (set by [Jobs.resume]); dropped from [Partials] when it ends fine
    @Volatile var resumeId: String? = null     // set when the job ended with an error and can be continued: the UI shows a Resume button
    private val problems = ArrayList<String>()
    @Synchronized fun addProblem(s: String) { if (problems.size < 30) problems.add(s) }
    @Synchronized private fun problemsJson() = org.json.JSONArray(problems.toList())
    /** A new file starts: the card shows its name and its own progress bar. */
    fun begin(name: String, size: Long) { cur = name; curDone = 0L; curTotal = size }

    // live view of a copy that merges into existing folders: what was replaced / added / skipped, and the open question ("replace this file?")
    @Volatile var live = false
    @Volatile var cR = 0
    @Volatile var cN = 0
    @Volatile var cS = 0
    @Volatile var ask: JSONObject? = null
    @Volatile var answer: String? = null
    private var askSeq = 0
    private val logBuf = ArrayList<String>()
    private var logBase = 0

    /** kind: R = replaced, N = new, S = skipped (kept what was there). */
    @Synchronized fun addLog(kind: String, name: String) {
        when (kind) { "R" -> cR++; "N" -> cN++; else -> cS++ }
        logBuf.add("$kind|$name")
        if (logBuf.size > 400) { logBuf.subList(0, 100).clear(); logBase += 100 }
    }

    @Synchronized private fun logSince(i: Int): Pair<Int, List<String>> {
        val from = maxOf(i, logBase)
        val end = logBase + logBuf.size
        return end to (if (from >= end) emptyList() else logBuf.subList(from - logBase, end - logBase).toList())
    }

    /** Pauses the job until the user answers in the UI: replace | skip | replace_all | skip_all. Cancelling the job ends the wait. */
    fun waitAnswer(q: JSONObject): String {
        answer = null
        synchronized(this) { askSeq++; q.put("q", askSeq) }
        ask = q
        cur = "Waiting for your answer\u2026"
        try {
            while (true) {
                if (cancel) throw Cancelled()
                val a = answer
                if (a != null) return a
                Thread.sleep(120)
            }
        } finally { ask = null; answer = null; cur = "" }
    }

    /** The answer only counts for the question that is open right now (a late double tap must not answer the next one). */
    fun give(q: Int, a: String) { if (ask?.optInt("q", -1) == q) answer = a }

    private val startedAt = System.currentTimeMillis()
    private var sampleT = startedAt
    private var sampleDone = 0L
    private var rate = 0.0   // smoothed units (bytes or items) per second

    /** Smoothed progress rate, sampled at most every 0.7 s however often the UI polls. Retries can move [done] backwards: never negative. */
    @Synchronized private fun rateNow(): Double {
        val now = System.currentTimeMillis()
        val dt = now - sampleT
        val d = done
        if (dt >= 700) {
            val inst = maxOf(d - sampleDone, 0L) * 1000.0 / dt
            rate = if (rate == 0.0) inst else rate * 0.7 + inst * 0.3
            sampleT = now; sampleDone = d
        }
        return rate
    }

    fun toJson(since: Int = -1): JSONObject = JSONObject().put("state", state).put("done", done).put("total", total).put("bytes", bytes)
        .put("error", error ?: JSONObject.NULL).put("label", label).also {
            if (cancel) it.put("cancel", true)
            ask?.let { a -> it.put("ask", a) }   // the open "replace this file?" question must always reach the UI
            if (end > 0) it.put("end", end / 1000.0)
            it.put("elapsed", ((if (end > 0) end else System.currentTimeMillis()) - startedAt) / 1000.0)
            if (cur.isNotEmpty()) it.put("cur", cur).put("curDone", curDone).put("curTotal", curTotal)
            if (files > 0 || filesTotal > 0) it.put("files", files).put("filesTotal", filesTotal)
            if (failed > 0) it.put("failed", failed).put("problems", problemsJson())
            dest?.let { d -> it.put("dest", d) }
            if (state == "error") resumeId?.let { r -> it.put("resume", r) }
            note?.let { n -> it.put("note", n) }
            result?.let { r -> it.put("result", r) }
            if (live) {
                it.put("live", true).put("cR", cR).put("cN", cN).put("cS", cS)
                if (since >= 0) { val (n, l) = logSince(since); it.put("logn", n).put("log", org.json.JSONArray(l)) }
            }
            if (state == "run") {
                val r = rateNow()
                val warm = System.currentTimeMillis() - startedAt >= 2000   // a few seconds of data before promising anything
                if (r > 0 && warm) {
                    if (bytes) it.put("speed", Math.round(r))
                    val left = maxOf(total - done, 0L)
                    if (left > 0 && left / r < 172800) it.put("eta", Math.ceil(left / r).toLong())   // no promise when the speed is almost 0
                }
            }
        }
}

object Jobs {
    val all = ConcurrentHashMap<String, Job>()

    fun prune() {
        val now = System.currentTimeMillis()
        all.entries.removeAll { it.value.state != "run" && it.value.end > 0 && now - it.value.end > 600_000 }
    }


    const val PRINT_PORT = 8799   // pcprint.py on the PC listens here

    @Volatile private var convIp: String? = null

    /** IP of a PC whose pcprint.py can turn Office files into PDF (v11 /convert + Word/LibreOffice). Throws with the real reason when none can. */
    fun converterIp(): String {
        val ips = LinkedHashSet<String>()
        convIp?.let { ips.add(it) }
        for (p in Core.disc.list()) { ips.add(p.ip); ips.addAll(p.ips) }
        try { val a = Cfg.smb(); for (k in 0 until a.length()) a.optJSONObject(k)?.optString("host")?.takeIf { it.isNotEmpty() }?.let { ips.add(Smb.split(it).first) } } catch (_: Exception) {}
        val res = java.util.concurrent.ConcurrentHashMap<String, String>()   // ip -> "ok" | "old" | "noengine"
        val ts = ips.map { ip -> Thread {
            try {
                val r = Http.request(ip, PRINT_PORT, "GET", "/ping", emptyMap(), 2500)
                try {
                    if (r.status == 200) JSONObject(String(r.readUpTo(32768), Charsets.UTF_8)).let {
                        res[ip] = if (!it.optBoolean("convert", false)) "old" else if (it.isNull("office")) "noengine" else "ok"
                    }
                } finally { r.close() }
            } catch (_: Exception) {}
        }.also { t -> t.isDaemon = true; t.start() } }
        ts.forEach { try { it.join(3500) } catch (_: InterruptedException) {} }
        res.entries.firstOrNull { it.value == "ok" }?.let { convIp = it.key; return it.key }
        throw IOException(when {
            res.containsValue("noengine") -> "pcprint.py is running on a PC, but it found no Word / Excel / PowerPoint or LibreOffice to convert with (Office installed for another Windows user, Microsoft Store/Click-to-Run edition, or not activated?) - install LibreOffice there"
            res.containsValue("old") -> "pcprint.py on the PC is an old version - replace it with v11 and restart it"
            else -> "no PC with pcprint.py answered on port $PRINT_PORT (${ips.size} checked) - start pcprint.py on the PC, same Wi-Fi, allow it through the Windows firewall"
        })
    }

    /** Office file -> PDF through pcprint.py /convert on [ip]. */
    fun officeToPdf(ip: String, f: File, name: String, out: File) {
        f.inputStream().use { ins ->
            val r = Http.request(ip, PRINT_PORT, "POST", "/convert?name=" + URLEncoder.encode(name, "UTF-8"), emptyMap(), 180_000, ins, f.length())
            try {
                if (r.status == 404) throw IOException("update pcprint.py on the PC (v11) to convert office files")
                if (r.status != 200) {
                    val t = String(r.readUpTo(2000), Charsets.UTF_8)
                    throw IOException(try { JSONObject(t).optString("error", t) } catch (_: Exception) { t })
                }
                out.outputStream().use { o -> val b = ByteArray(64 * 1024); while (true) { val n = r.body.read(b); if (n < 0) break; o.write(b, 0, n) } }
                if (out.length() == 0L) throw IOException("empty answer from the PC")
            } finally { r.close() }
        }
    }

    private class PrintFail(msg: String) : IOException(msg)   // the PC answered and refused / failed to print this file

    /** (address, display name) of the PC to print on. */
    internal fun printTarget(dev: String): Pair<String, String> = when {
        dev == "local" -> throw BadReq("choose the PC to print on")
        dev.startsWith("smb:") -> {
            val c = Smb.cfg(dev) ?: throw IOException("that SMB share was removed")
            Smb.split(c.getString("host")).first to c.optString("name").ifEmpty { c.getString("host") }
        }
        dev.startsWith("ftp:") -> throw BadReq("an FTP server cannot print")
        dev.startsWith("gd:") -> throw BadReq("Google Drive cannot print")
        else -> (Core.disc.get(dev) ?: throw IOException("that device is offline")).let { it.ip to it.name }
    }

    /** What the print service on that PC reports right now: {ok, printer?, problem?}. ok=false = nothing answered on the print port. */
    fun printerStatus(dev: String, printer: String? = null): JSONObject {
        if (WifiPrinters.isWifi(dev)) return WifiPrinters.status(dev)
        val (ip, _) = try { printTarget(dev) } catch (e: BadReq) { throw e } catch (e: IOException) { return JSONObject().put("ok", false).put("why", errText(e)) }
        return try {
            val pq = if (printer.isNullOrEmpty()) "" else "?printer=" + URLEncoder.encode(printer, "UTF-8")
            val r = Http.request(ip, PRINT_PORT, "GET", "/ping$pq", emptyMap(), if (pq.isEmpty()) 3000 else 9000)
            try {
                if (r.status != 200) return JSONObject().put("ok", false)
                val o = JSONObject(String(r.readUpTo(32768), Charsets.UTF_8))
                JSONObject().put("ok", true)
                    .put("printer", if (o.isNull("printer")) JSONObject.NULL else o.optString("printer"))
                    .put("problem", if (o.isNull("problem")) JSONObject.NULL else o.optString("problem"))
                    .put("default", if (o.isNull("default")) JSONObject.NULL else o.optString("default"))
                    .put("printers", o.optJSONArray("printers") ?: org.json.JSONArray())
                    .put("quiet", o.optBoolean("quiet", false))   // pcprint.py 27+: this printer's quiet bytes are known, so Quiet / Normal can be offered
                    .put("trays", o.optJSONArray("trays") ?: org.json.JSONArray())   // paper trays of the chosen printer (pcprint.py 21+): [{id, name}]
                    .put("pypdf", o.optBoolean("pypdf", false))
                    .put("engine", if (o.isNull("engine")) JSONObject.NULL else o.optString("engine"))
                    .put("office", if (o.isNull("office")) JSONObject.NULL else o.optString("office"))   // what turns Word/Excel/PowerPoint files into PDF on the PC (null = nothing, or an older pcprint.py)
            } finally { r.close() }
        } catch (_: Exception) { JSONObject().put("ok", false) }
    }

    /** pcprint.py 22+: what prints right now, what still waits (with ids) and the last finished jobs. old=true: pcprint.py is older than 22 (no waiting list). */
    fun pcQueue(dev: String): JSONObject {
        val (ip, _) = try { printTarget(dev) } catch (e: BadReq) { throw e } catch (e: IOException) { return JSONObject().put("ok", false).put("why", errText(e)) }
        return try {
            val r = Http.request(ip, PRINT_PORT, "GET", "/jobs", emptyMap(), 4000)
            try {
                if (r.status == 404) return JSONObject().put("ok", true).put("old", true)
                if (r.status != 200) return JSONObject().put("ok", false)
                val o = JSONObject(String(r.readUpTo(262144), Charsets.UTF_8))
                o.put("ok", true).put("old", !o.has("waiting"))
            } finally { r.close() }
        } catch (_: Exception) { JSONObject().put("ok", false) }
    }

    /** pcprint.py 28+: what the PC window shows. {ok, old?, status:{dot,text}, lines:[{t,k}], pos, reset}. pos < 0 = from the start. */
    fun pcScreen(dev: String, pos: Long): JSONObject {
        val (ip, _) = try { printTarget(dev) } catch (e: BadReq) { throw e } catch (e: IOException) { return JSONObject().put("ok", false).put("why", errText(e)) }
        return try {
            val r = Http.request(ip, PRINT_PORT, "GET", "/screen?pos=$pos", emptyMap(), 4000)
            try {
                if (r.status == 404) return JSONObject().put("ok", true).put("old", true)
                if (r.status != 200) return JSONObject().put("ok", false)
                JSONObject(String(r.readUpTo(1 shl 20), Charsets.UTF_8)).put("ok", true)
            } finally { r.close() }
        } catch (_: Exception) { JSONObject().put("ok", false) }
    }

    /** pcprint.py 31+: the PC's diag text (GET /api/pcdiag?pos=N). {ok, old?, text, why?}. An older PC answers 404 = old. */
    fun pcDiag(dev: String, pos: Long): JSONObject {
        val (ip, _) = try { printTarget(dev) } catch (e: BadReq) { throw e } catch (e: IOException) { return JSONObject().put("ok", false).put("why", errText(e)) }
        return try {
            val r = Http.request(ip, PRINT_PORT, "GET", "/api/pcdiag?pos=$pos", emptyMap(), 6000)
            try {
                if (r.status == 404) return JSONObject().put("ok", true).put("old", true)
                if (r.status != 200) return JSONObject().put("ok", false).put("why", "HTTP ${r.status}")
                JSONObject(String(r.readUpTo(4 shl 20), Charsets.UTF_8)).put("ok", true)
            } finally { r.close() }
        } catch (e: Exception) { JSONObject().put("ok", false).put("why", errText(e)) }
    }

    /** Phone Explorer, one tap: asks the PC (pcprint.py 29+) to open Windows Explorer on this phone's FTP share. {ok, result: "ok" | "old" (pcprint.py < 29)}. The PC takes the phone's address from the connection itself. */
    fun pcOpenPhone(dev: String, port: Int, user: String, pin: String): JSONObject {
        val (ip, _) = printTarget(dev)
        return try {
            val path = "/phoneshare?port=$port&user=" + URLEncoder.encode(user, "UTF-8") + "&pin=" + URLEncoder.encode(pin, "UTF-8")
            val r = Http.request(ip, PRINT_PORT, "POST", path, mapOf("Content-Length" to "0"), 8000)
            try {
                if (r.status == 404) {   // the running service has no /phoneshare: report which version it is
                    var v = "?"
                    try { Http.request(ip, PRINT_PORT, "GET", "/ping", emptyMap(), 3000).let { pr -> try { v = JSONObject(String(pr.readUpTo(32768), Charsets.UTF_8)).optString("version", "?") } finally { pr.close() } } } catch (_: Exception) {}
                    return JSONObject().put("ok", false).put("result", "old").put("version", v)
                }
                val o = try { JSONObject(String(r.readUpTo(4096), Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                if (r.status != 200) throw IOException(o.optString("error", "PC answered " + r.status))
                JSONObject().put("ok", true).put("result", "ok")
            } finally { r.close() }
        } catch (e: IOException) { throw e } catch (e: Exception) { throw IOException("PC not reachable (${errText(e)})") }
    }

    /** Cancels one WAITING job on the PC: {ok, result: "ok" | "printing" (already on its way to the printer) | "gone" (printed already) | "old" (pcprint.py < 22)}. */
    fun pcCancel(dev: String, id: Int): JSONObject {
        val (ip, _) = printTarget(dev)
        return try {
            val r = Http.request(ip, PRINT_PORT, "POST", "/cancel?id=$id", mapOf("Content-Length" to "0"), 5000)
            try {
                if (r.status == 404) return JSONObject().put("ok", false).put("result", "old")
                val o = try { JSONObject(String(r.readUpTo(4096), Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                JSONObject().put("ok", o.optBoolean("ok", false)).put("result", o.optString("result", "gone"))
            } finally { r.close() }
        } catch (e: Exception) { throw IOException("PC not reachable (${errText(e)})") }
    }

    /** Streams the files to the print service (pcprint.py) on the PC; it prints them on the PC's default printer. */
    private val PRINT_KEYS = listOf("printer", "copies", "duplex", "color", "fit", "paper", "nup", "booklet", "border", "pages",
        "reverse", "range", "wm", "wm_under", "hdr", "ftr", "noauto", "margin", "scale", "align", "autorot", "tray", "quiet")

    /** "/print?name=..&nup=4&duplex=long..." - the FinePrint-style options chosen in the app, passed on to pcprint.py. */
    // pcprint.py only knows pdf, jpg/png/bmp/gif/tif, txt/log/md and office files: everything else is converted on the phone first (see PrintPrep)

    private fun printQuery(name: String, opts: JSONObject?, extra: String = ""): String {
        val sb = StringBuilder("/print?name=").append(URLEncoder.encode(name, "UTF-8")).append(extra)
        if (opts != null) for (k in PRINT_KEYS) {
            if (!opts.has(k) || opts.isNull(k)) continue
            val v = opts.get(k).toString().trim()
            if (v.isNotEmpty() && v != "false") sb.append('&').append(k).append('=').append(URLEncoder.encode(if (v == "true") "1" else v, "UTF-8"))
        }
        return sb.toString()
    }

    /** Joined job: this file could not be sent, so tell the PC to leave its place empty (otherwise it waits for it and never prints the rest). */
    private fun skipBatchSlot(ip: String, bid: String, idx: Int, n: Int) {
        try {
            Http.request(ip, PRINT_PORT, "POST", "/print?name=skip.txt&batch=$bid&idx=$idx&n=$n&join=1&skip=1", emptyMap(), 120_000,
                java.io.ByteArrayInputStream(ByteArray(0)), 0L).close()
        } catch (_: Exception) {}
    }

    fun startPrint(srcId: String, paths: List<String>, dstId: String, opts: JSONObject? = null): String {
        val src = ep(srcId)
        if (PdfPrint.isPdf(dstId)) {   // "Save as PDF": same layout options, the result is a PDF file on this phone (PdfPrint)
            val pid = UUID.randomUUID().toString().replace("-", "").take(8)
            prune()
            val pjob = Job("Saving PDF")
            all[pid] = pjob
            Thread({ PdfPrint.work(pjob, src, paths, opts) }, "pdfprint-$pid").also { it.isDaemon = true }.start()
            return pid
        }
        if (WifiPrinters.isWifi(dstId)) {   // a printer on the same Wi-Fi: straight over IPP, no PC (WifiPrint)
            val wp = WifiPrinters.get(dstId) ?: throw IOException("that printer is no longer on the network")
            val wid = UUID.randomUUID().toString().replace("-", "").take(8)
            prune()
            val wjob = Job("Printing on ${wp.name}")
            all[wid] = wjob
            Thread({ Http.abortable(wjob) { WifiPrint.work(wjob, src, wp, paths, opts) } }, "wprint-$wid").also { it.isDaemon = true }.start()
            return wid
        }
        val (ip, pcName) = printTarget(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job("Printing on $pcName")
        all[jid] = job
        Thread({ Http.abortable(job) { printWork(job, src, ip, pcName, paths, opts) } }, "print-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    /** Deletes the paths in the background with a live label ("Deleting x - 128 items removed") and a Cancel button. */
    fun startDelete(devId: String, paths: List<String>): String {
        val e = ep(devId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job("Deleting")
        job.bytes = false
        job.total = maxOf(paths.size, 1).toLong()
        job.filesTotal = 0
        all[jid] = job
        Thread({ deleteWork(job, e, paths) }, "del-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    /** A delete the user asked for: this phone's own files go to the recycle bin, everything else is deleted for good. */
    fun removeUser(e: Endpoint, p: String, progress: ((String) -> Unit)? = null) {
        if (((e as? ArcEp)?.base ?: e) is LocalFs && Bin.canTrash(p)) Bin.trash(p, progress) else e.remove(p, progress)
    }

    private fun deleteWork(job: Job, e: Endpoint, paths: List<String>) {
        val fails = ArrayList<String>()
        var failCount = 0
        var removed = 0
        val handled = HashSet<String>()   // several entries of one ZIP: removed in a single rewrite of the archive
        try {
            if (e is ArcEp) {
                val groups = LinkedHashMap<String, MutableList<String>>()
                for (p0 in paths) { val p = vnorm(p0); val a = arcSplit(p) ?: continue; if (a.second != "/") groups.getOrPut(a.first) { ArrayList() }.add(p) }
                for ((arc, ps) in groups) {
                    if (ps.size < 2) continue
                    if (job.cancel) throw Cancelled()
                    job.label = "Deleting ${ps.size} items from ${vbase(arc)}"
                    try { removed += e.removeMany(ps); handled.addAll(ps); job.files = removed }
                    catch (x: Cancelled) { throw x
                    } catch (x: Exception) { handled.addAll(ps); failCount++; job.failed = failCount; job.addProblem("${vbase(arc)}: ${errText(x)}"); if (fails.size < 50) fails.add("${vbase(arc)}: ${errText(x)}") }
                }
            }
            for ((i, p0) in paths.withIndex()) {
                if (job.cancel) throw Cancelled()
                val p = vnorm(p0)
                if (p in handled) { job.done = (i + 1).toLong(); continue }
                val top = vbase(p)
                job.label = "Deleting ${i + 1}/${paths.size}: $top" + (if (removed > 0) " ($removed items removed)" else "")
                try {
                    removeUser(e, p) { n ->
                        if (job.cancel) throw Cancelled()
                        removed++
                        job.files = removed; job.cur = top
                        job.label = "Deleting ${i + 1}/${paths.size}: $top - $removed items removed" + (if (n != top) " (now: $n)" else "")
                    }
                } catch (x: Cancelled) { throw x
                } catch (x: Exception) { failCount++; job.failed = failCount; job.addProblem("$top: ${errText(x)}"); if (fails.size < 50) fails.add("$top: ${errText(x)}") }
                job.done = (i + 1).toLong()
            }
            job.done = job.total
            if (failCount == 0) {
                job.label = "Deleted $removed item${if (removed == 1) "" else "s"}"
                job.note = job.label
                job.state = "done"
            } else {
                job.error = "Deleted $removed item${if (removed == 1) "" else "s"}, but " +
                    (if (failCount == 1) fails[0] else "$failCount things stayed - " + fails.take(3).joinToString("; ") + (if (failCount > 3) "; ..." else ""))
                job.state = "error"
            }
        } catch (x: Cancelled) {
            job.label = "Stopped after $removed items"
            job.state = "cancel"
        } catch (x: Throwable) {
            job.error = errText(x)
            job.state = "error"
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    private fun printWork(job: Job, src: Endpoint, ip: String, pcName: String, paths: List<String>, opts: JSONObject?) {
        try {
            val chosen = opts?.optString("printer").orEmpty()
            val notes = LinkedHashSet<String>()   // things the PC could not honour (e.g. layout options on a .docx)
            var printer = ""
            var problem = ""   // what the printer already reports before we send (paper jam, out of paper, ...)
            try {
                val r = Http.request(ip, PRINT_PORT, "GET", if (chosen.isEmpty()) "/ping" else "/ping?printer=" + URLEncoder.encode(chosen, "UTF-8"), emptyMap(), if (chosen.isEmpty()) 4000 else 9000)
                try {
                    if (r.status != 200) throw IOException("HTTP ${r.status}")
                    try {
                        val o = JSONObject(String(r.readUpTo(4096), Charsets.UTF_8))
                        if (!o.isNull("printer")) printer = o.optString("printer")
                        if (!o.isNull("problem")) problem = o.optString("problem")
                    } catch (_: Exception) {}
                } finally { r.close() }
            } catch (e: IOException) {
                throw IOException("$pcName ($ip) is not reachable on port $PRINT_PORT - is pcprint.py running there? (${errText(e)})")
            }
            val dest = if (printer.isNotEmpty()) "$pcName ($printer)" else pcName
            val files = ArrayList<Pair<String, Long>>()
            for (p0 in paths) {
                val p = vnorm(p0)
                for (w in src.walk(p)) if (!w.dir) files.add((if (w.rel.isEmpty()) p else p + "/" + w.rel) to w.size)
            }
            if (files.isEmpty()) throw BadReq("nothing to print")
            job.total = maxOf(files.sumOf { it.second }, 1L)
            job.filesTotal = files.size
            job.dest = dest
            val failed = ArrayList<String>()
            // pictures laid out together (opts.sheet): every picture is sent with the batch id, pcprint.py prints one set of sheets after the last one
            val sheet = opts?.optString("sheet") == "1" && files.all { it.first.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff") + PrintPrep.PICS }
            // "Print all files as one job": every file goes to the PC with the same batch id + join=1, pcprint.py joins them after the last one
            val join = !sheet && files.size > 1 && opts?.opt("join").let { it == true || it == "1" || it == "true" || (it is Number && it.toInt() != 0) }
            val bid = java.lang.Long.toString(System.nanoTime(), 36)
            val rots = opts?.optJSONArray("rots")
            for ((i, f0) in files.withIndex()) {
                val (sp, size) = f0
                if (job.cancel) throw Cancelled()
                job.label = "Printing ${i + 1}/${files.size} on $dest" + (if (problem.isNotEmpty()) " (printer reports: $problem)" else "")
                val name = vbase(sp)
                job.files = i; job.begin(name, size)
                var sent = 0L
                val f = src.open(sp)
                var tmp: java.io.File? = null
                var opened: java.io.InputStream? = null
                var tmp2: java.io.File? = null   // converted copy (pdf / jpg / txt) when it is a different file than the download
                var registered = false   // the PC took this file into its batch (answered 200 / 500), so it must not be skipped
                try {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    var body: java.io.InputStream = f
                    var bodyLen = f.size
                    var sendName = name
                    val kind = PrintPrep.kind(ext)
                    if (kind == PrintPrep.Kind.NO || (kind == PrintPrep.Kind.SNIFF && size > PrintPrep.SNIFF_MAX)) throw PrintFail(if (ext.isEmpty()) "this file type cannot be printed" else "$ext files cannot be printed")
                    val cropBox = if (ext in PrintPrep.IMG_EXT) PrintPrep.cropBox(opts, i) else null   // "Trim blank edges"
                    if (cropBox != null || kind == PrintPrep.Kind.PIC || kind == PrintPrep.Kind.WEB || kind == PrintPrep.Kind.SNIFF) {
                        try {
                            Core.cacheDir.mkdirs()
                            val t = java.io.File(Core.cacheDir, "print-" + System.nanoTime().toString(36)).also { tmp = it }
                            t.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    if (job.cancel) throw Cancelled()
                                    val n = f.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n); sent += n; job.done += n
                                }
                            }
                            val out = (if (cropBox != null) PrintPrep.trim(t, name, cropBox) else PrintPrep.convert(kind, t, name)).also { if (it.file != t) tmp2 = it.file }
                            val ins = out.file.inputStream(); opened = ins; body = ins; bodyLen = out.file.length()
                            sendName = out.name
                        } catch (x: Cancelled) { throw x
                        } catch (x: Throwable) { throw PrintFail("this file could not be converted for printing (" + errText(x) + ")") }
                    } else if (kind == PrintPrep.Kind.TEXT) sendName = name.substringBeforeLast('.') + ".txt"
                    val counted = body === f   // converted pictures were already counted while they were read
                    // key: a repeated request (retry, reconnect) must not print twice - pcprint.py ignores a key it has already seen
                    val extra = (if (sheet || join) "&batch=$bid&idx=$i&n=${files.size}&rot=${rots?.optInt(i, 0) ?: 0}" + (if (join) "&join=1" else "") else "") + "&key=k$bid-$i"
                    val r = Http.request(ip, PRINT_PORT, "POST", printQuery(sendName, opts, extra), emptyMap(),
                        120_000, body, bodyLen) { n -> if (job.cancel) throw Cancelled(); if (counted) { sent += n; job.done += n } }
                    try {
                        registered = r.status == 200 || r.status == 500
                        val t = String(r.readUpTo(2000), Charsets.UTF_8)
                        if (r.status != 200) throw PrintFail(try { JSONObject(t).optString("error", t) } catch (_: Exception) { t })
                        // printed, but the printer reported trouble right after (jam, out of paper, offline, ...)
                        val w = try { JSONObject(t).let { o -> if (o.isNull("warning")) "" else o.optString("warning") } } catch (_: Exception) { "" }
                        val nt = try { JSONObject(t).let { o -> if (o.isNull("note")) "" else o.optString("note") } } catch (_: Exception) { "" }
                        if (nt.isNotEmpty()) notes.add("$name: $nt")
                        if (w.isNotEmpty()) { failed.add("$name: sent, but the printer reports $w"); job.done += maxOf(size - sent, 0L) }
                    } finally { r.close() }
                } catch (e: PrintFail) {
                    failed.add("$name: ${errText(e)}")
                    job.done += maxOf(size - sent, 0L)   // keep the progress bar moving
                    if (sheet) break   // an incomplete set of sheets must not be printed
                    if (join && !registered) skipBatchSlot(ip, bid, i, files.size)   // the PC counts the files: tell it this one will not come
                } finally { f.close(); try { opened?.close() } catch (_: Exception) {}; try { tmp?.delete() } catch (_: Exception) {}; try { tmp2?.delete() } catch (_: Exception) {} }
            }
            job.done = job.total
            val ok = files.size - failed.size
            if (failed.isEmpty()) {
                job.label = "Printed ${files.size} file${if (files.size > 1) "s" else ""} on $dest"
                job.note = job.label + (if (notes.isNotEmpty()) " (" + notes.joinToString("; ") + ")" else "")
                job.state = "done"
            } else {
                job.label = "Print problem on $dest"
                job.error = "Problem on $dest ($ok of ${files.size} OK) - " + failed.joinToString("; ")
                job.state = "error"
            }
        } catch (e: Cancelled) {
            job.state = "cancel"
        } catch (e: Throwable) {   // incl. OutOfMemoryError / LinkageError: the job must end, not stay "running" forever
            if (job.cancel) job.state = "cancel"   // the socket was closed by Cancel: that is the cancel, not an error
            else { job.error = errText(e); job.state = "error" }
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    /**
     * Many files out of a ZIP that lives on another device: one sequential download of the archive (shown on the job's bar) is much faster
     * than one request per file. Best effort - if it fails, the files are simply read one by one.
     */
    private fun pinArchives(job: Job, src: Endpoint, plan: List<Pair<String, List<WalkItem>?>>) {
        val e = src as? ArcEp ?: return
        val arcs = LinkedHashSet<String>()
        var files = 0
        for ((p, w) in plan) {
            val a = arcSplit(p) ?: continue
            arcs.add(a.first)
            files += w?.count { !it.dir } ?: 0
        }
        if (files < 8) return
        val keepLabel = job.label
        val keepTotal = job.total
        for (a in arcs) {
            val size = e.archiveSize(a)
            if (size <= 0L) continue
            job.label = "Downloading " + vbase(a)
            job.total = size
            job.done = 0
            try { e.pin(a, job) } catch (x: Cancelled) { throw x } catch (_: Exception) {}
        }
        job.label = keepLabel
        job.total = keepTotal
        job.done = 0
    }

    // ---------------------------------------------------------------- zip
    private class ZItem(val src: String, val entry: String, val dir: Boolean, val size: Long)

    /** Already compressed: stored instead of deflated, which makes zipping photos / videos several times faster. */
    private val STORE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4", "m4v", "mkv", "mov", "avi", "webm", "3gp",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "rar", "7z", "gz", "bz2", "xz", "cbz", "cbr", "apk", "jar",
        "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "pdf")

    /** Packs files and folders of one device into a new .zip in a folder of the same device. */
    fun startZip(devId: String, paths: List<String>, dir: String, name: String, replace: Boolean = false, exclude: List<String> = emptyList()): String {   // replace: reuse the exact name when it exists (the old file goes only after the new ZIP is complete)
        val e = ep(devId)
        val ps = paths.map { vnorm(it) }
        if (ps.isEmpty()) throw BadReq("nothing selected")
        if (ps.any { it == "/" }) throw Denied("cannot zip the root")
        val d = vnorm(dir)
        if (arcSplit(d) != null) throw Denied("Archives are read-only - choose a normal folder for the ZIP")
        var n = name.trim().replace('\\', '/').substringAfterLast('/').trim()   // a file name, never a path
        if (n.isEmpty() || n == "." || n == ".." || n.lowercase() == ".zip") throw BadReq("enter a name for the ZIP")
        if (!n.lowercase().endsWith(".zip")) n += ".zip"
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job("Zipping")
        all[jid] = job
        Thread({ zipWork(job, e, ps, d, n, replace, exclude.map { vnorm(it) }) }, "zip-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    /** Folders (and file kinds) that are build output / dependencies: left out when "skip build folders" is on. */
    private val BUILD_DIRS = setOf("build", "dist", "node_modules", "__pycache__", "venv", ".venv", "target", ".gradle", ".idea", ".git")
    private fun isBuildPart(seg: String) = seg in BUILD_DIRS || seg.endsWith(".pyc") || seg.endsWith(".class")

    /**
     * What the ZIP dialog offers to leave out: inside the selected items, the topmost hidden entry (name starts with ".") or build / dependency folder of every branch
     * with its size and file count, e.g. {path:"/Proj/app/.gradle", rel:"app/.gradle", dir:true, kind:"hidden", size, files}. The selected items themselves are never listed.
     */
    fun zipScan(devId: String, paths: List<String>): JSONArray {
        val e = ep(devId)
        val res = LinkedHashMap<String, JSONObject>()
        for (p0 in paths) {
            val p = vnorm(p0)
            if (p == "/") continue
            for (w in e.walk(p)) {
                if (w.rel.isEmpty() || w.skip) continue
                val segs = w.rel.split('/')
                val at = segs.indexOfFirst { it.startsWith(".") || isBuildPart(it) }
                if (at < 0) continue
                val relTop = segs.subList(0, at + 1).joinToString("/")
                val o = res.getOrPut(p + "/" + relTop) {
                    JSONObject().put("path", p + "/" + relTop).put("rel", vbase(p) + "/" + relTop).put("dir", at < segs.size - 1 || w.dir)
                        .put("kind", if (segs[at].startsWith(".")) "hidden" else "build").put("size", 0L).put("files", 0L)
                }
                if (!w.dir) { o.put("size", o.getLong("size") + w.size); o.put("files", o.getLong("files") + 1) }
                if (res.size >= 400) break
            }
        }
        return JSONArray(res.values.toList())
    }

    private fun zipWork(job: Job, e: Endpoint, paths: List<String>, dir: String, name: String, replace: Boolean = false, exclude: List<String> = emptyList()) {
        val dst: Endpoint = (e as? ArcEp)?.base ?: e
        var tmp: File? = null
        var uploading = false
        var target = ""
        try {
            // 1. what goes in (folders are walked; links and unreadable folders are left out)
            val items = ArrayList<ZItem>()
            val tops = HashSet<String>()
            var skipped = 0
            var filtered = 0
            for (p in paths) {
                if (job.cancel) throw Cancelled()
                val walked = e.walk(p)
                val top = uniqueName(destName(p), tops, walked.firstOrNull()?.dir ?: false)
                tops.add(top)
                for (w in walked) {
                    if (exclude.isNotEmpty() && w.rel.isNotEmpty()) { val ap = p + "/" + w.rel; if (exclude.any { ap == it || ap.startsWith("$it/") }) { if (!w.dir) filtered++; continue } }
                    if (w.skip) { skipped++; continue }
                    items.add(ZItem(if (w.rel.isEmpty()) p else p + "/" + w.rel, if (w.rel.isEmpty()) top else top + "/" + w.rel, w.dir, w.size))
                }
            }
            if (items.isEmpty()) throw BadReq("nothing to zip")
            val total = items.sumOf { if (it.dir) 0L else it.size }

            // 2. where it goes: next to the originals, under a name that is free
            val taken = try { dst.names(dir) } catch (x: Cancelled) { throw x } catch (x: Exception) { throw IOException("cannot read the destination folder: ${errText(x)}") }
            val finalName = if (replace && name in taken) name else uniqueName(name, taken, false)
            target = vjoin(dir, finalName)
            val local = dst as? LocalFs
            val out: File
            if (local != null) {   // written straight into the destination folder (as .lspart, renamed when complete)
                val real = local.real(target)
                real.parentFile?.mkdirs()
                out = File(real.path + ".lspart")
                val free = try { local.root.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
                if (total + (5L shl 20) > free) throw Full("Not enough free space on this device (needs up to ${total / 1048576} MB)")
            } else {   // another device / SMB share: built here first, then uploaded
                Core.cacheDir.mkdirs()
                out = File(Core.cacheDir, "z_" + UUID.randomUUID().toString().take(8) + ".part")
                val free = try { Core.cacheDir.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
                if (total + (50L shl 20) > free) throw Full("Not enough free space on this phone to build the ZIP (needs up to ${total / 1048576} MB)")
            }
            tmp = out

            // 3. pack
            job.bytes = true
            job.total = maxOf(total, 1L)
            job.done = 0
            job.label = "Zipping $finalName"
            job.filesTotal = items.count { !it.dir }
            val buf = ByteArray(1 shl 16)
            Zip64Writer(out.outputStream().buffered(1 shl 16)).use { zw ->   // no 4 GB / 65,535-entry limit (ZIP64 where needed)
                for (item in items) {
                    if (job.cancel) throw Cancelled()
                    if (item.dir) { zw.putDir(item.entry); continue }
                    try {
                        job.begin(item.entry, item.size)
                        zw.beginFile(item.entry, item.size, item.entry.substringAfterLast('.', "").lowercase() in STORE_EXT)
                        e.open(item.src).use { s ->
                            while (true) {
                                if (job.cancel) throw Cancelled()
                                val n = s.read(buf)
                                if (n < 0) break
                                zw.write(buf, 0, n)
                                job.done += n; job.curDone += n
                            }
                        }
                        zw.endFile(); job.files++
                    } catch (x: Cancelled) { throw x
                    } catch (x: Exception) { throw IOException(item.entry + ": " + errText(x)) }
                }
                zw.finish()
            }

            // 4. put it in place
            if (local != null) {
                val real = local.real(target)
                if (!out.renameTo(real)) { real.delete(); if (!out.renameTo(real)) throw IOException("could not move the ZIP into place") }
                IndexManager.changed(real)   // name index: the new ZIP
            } else {
                val sz = out.length()
                job.label = "Saving $finalName to ${dst.name}"
                job.total = maxOf(sz, 1L)
                job.done = 0
                uploading = true
                out.inputStream().buffered(1 shl 16).use { ins -> dst.write(target, ins, sz) { n -> if (job.cancel) throw Cancelled(); job.done += n } }
            }
            job.done = job.total
            job.label = "Created $finalName"
            job.note = "Created $finalName" + (if (skipped > 0) " - $skipped item(s) (links or unreadable folders) were left out" else "") + (if (filtered > 0) " - $filtered file(s) left out by your choice" else "")
            job.state = "done"
        } catch (x: Cancelled) {
            job.state = "cancel"
            if (uploading) try { dst.remove(target) } catch (_: Exception) {}   // drop the half-uploaded file
        } catch (x: Throwable) {   // incl. OutOfMemoryError: the job must end, not stay "running" forever
            job.error = errText(x)
            job.state = "error"
        } finally {
            tmp?.let { if (it.exists()) it.delete() }
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    fun ep(dev: String): Endpoint = ArcEp(when {   // ArcEp: paths inside .zip/.rar files ("a.zip!/dir") are served from the archive
        dev == "local" -> Core.local
        dev == "root" -> RootShell.fs()   // su file access: throws unless Settings > Root access is on, granted, and the request comes from the app's own screen
        dev.startsWith("smb:") -> SmbFs.create(Smb.cfg(dev) ?: throw IOException("that SMB share was removed"))
        dev.startsWith("ftp:") -> FtpFs.create(Ftp.cfg(dev) ?: throw IOException("that FTP server was removed"))
        dev.startsWith("gd:") -> GdriveFs(Gdrive.cfg(dev) ?: throw IOException("that Google account was removed"))
        else -> RemoteFs(Core.disc.get(dev) ?: throw IOException("that device is offline"))
    })

    fun start(srcId: String, paths: List<String>, dstId: String, ddir: String, cut: Boolean, label: String, conflict: String = "rename"): String {   // conflict: what to do when the name exists at the destination - rename ("name (1)") | replace | skip
        val src = ep(srcId)
        val dst = ep(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job(label)
        all[jid] = job
        val cutOk = cut && paths.none { arcSplit(it) != null } && arcSplit(vnorm(ddir)) == null   // nothing can be moved out of / into an archive: it is copied
        Thread({ work(job, src, dst, srcId, paths, ddir, cutOk, conflict) }, "job-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    /**
     * Continues a copy / move that ended with an error ([Partials] keeps what is left): finished files are skipped (same size), a file with a
     * kept ".lspart" continues where it stopped, the rest is copied. Items go back to the destination names the first run chose.
     */
    fun resume(rid: String): String {
        val sp = Partials.job(rid) ?: throw NotFound("Nothing to resume (the record expired or was already finished)")
        val src = ep(sp.getString("src"))
        val dst = ep(sp.getString("dst"))
        val arr = sp.getJSONArray("paths")
        val paths = (0 until arr.length()).map { arr.getString(it) }
        val nm = sp.optJSONObject("names") ?: JSONObject()
        val forced = HashMap<String, String>().also { m -> for (k in nm.keys()) m[k] = nm.getString(k) }
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job(sp.optString("label", "Copying"))
        job.rid = rid
        all[jid] = job
        val ddir = sp.optString("ddir", "/")
        val cut = sp.optBoolean("cut")
        Thread({ work(job, src, dst, src.id, paths, ddir, cut, "resume", forced) }, "job-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private val autoTried = HashMap<String, Long>()

    /** The network came back: continues interrupted copies that involve an FTP server (younger than 30 min, each at most once per 2 min, never while running). */
    @Synchronized fun autoResume() {
        val now = System.currentTimeMillis()
        for ((rid, sp) in Partials.jobs()) {
            if (!(sp.optString("src").startsWith("ftp:") || sp.optString("dst").startsWith("ftp:"))) continue
            if (now - sp.optLong("t") > 30 * 60_000L || (autoTried[rid] ?: 0L) > now - 120_000L) continue
            if (all.values.any { it.rid == rid && it.state == "run" }) continue
            autoTried[rid] = now
            try { resume(rid) } catch (_: Exception) {}
        }
    }

    /** What the interrupted-transfer list shows: id, label, how many items are left, where to. */
    fun resumable(): JSONArray {
        val a = JSONArray()
        for ((id, sp) in Partials.jobs()) a.put(JSONObject().put("id", id).put("label", sp.optString("label")).put("left", sp.optJSONArray("paths")?.length() ?: 0)
            .put("dst", sp.optString("ddir")).put("t", sp.optLong("t") / 1000.0))
        return a
    }

    private fun work(job: Job, src: Endpoint, dst: Endpoint, srcId: String, paths: List<String>, ddir: String, cut: Boolean, conflict: String = "rename", forced: Map<String, String>? = null) {
        var dest: String? = null
        val unsim = conflict == "unsim"                      // gallery "copy / move if not similar": skip files whose picture is already at the destination
        val lite = src.id == dst.id && cut && !unsim          // same-device move without a file walk (plain renames)
        val simIdx = if (unsim) DupFinder.SimIndex(dst) else null
        val pending = LinkedHashMap<String, String>()   // top-level items started but not finished cleanly: path -> destination name used
        var planned: List<String> = emptyList()
        var visited = 0                                  // how many planned items were started
        val same = src.id == dst.id
        val fails = ArrayList<String>()   // what went wrong, one line each (first 50)
        var failCount = 0
        var skippedTotal = 0
        fun fail(what: String, e: Throwable) { failCount++; job.failed = failCount; job.addProblem("$what: ${errText(e)}"); if (fails.size < 50) fails.add("$what: ${errText(e)}") }
        /** The run ended with an error: remember what is left so "Resume" can continue it (also after the app was closed). */
        fun saveResume() {
            if (same && cut) return   // same-device moves are renames: nothing partial
            val left = JSONArray(); val names = JSONObject()
            for ((i, p) in planned.withIndex()) {
                val nn = pending[p]
                if (nn != null) { left.put(p); names.put(p, nn) } else if (i >= visited) left.put(p)
            }
            if (left.length() == 0) { job.rid?.let { Partials.dropJob(it) }; return }
            val id = job.rid ?: UUID.randomUUID().toString().replace("-", "").take(8)
            Partials.putJob(id, JSONObject().put("src", src.id).put("dst", dst.id).put("ddir", vnorm(ddir)).put("cut", cut).put("label", job.label)
                .put("paths", left).put("names", names))
            job.resumeId = id
        }
        try {
            val ddirN = vnorm(ddir)
            job.dest = dst.name + " \u203a " + ddirN
            job.cur = "Reading the file list\u2026"   // shown on the card, so a slow / stuck first step is visible instead of a silent 0%
            val plan = ArrayList<Pair<String, List<WalkItem>?>>()
            for (p0 in paths) {
                if (job.cancel) throw Cancelled()
                val p = vnorm(p0)
                if (p == "/") throw Denied("cannot copy the root")
                if (same && (ddirN == p || ddirN.startsWith("$p/"))) throw BadReq("cannot put a folder inside itself")
                if (same && cut && vdir(p) == ddirN) continue   // moving into the same folder: nothing to do
                try { plan.add(p to (if (lite) null else src.walk(p))) }
                catch (e: Cancelled) { throw e } catch (e: Exception) { fail(vbase(p), e) }   // one unreadable item must not stop the others
            }
            planned = plan.map { it.first }
            if (lite) { job.bytes = false; job.total = maxOf(plan.size, 1).toLong() }
            else job.total = maxOf(plan.sumOf { (_, w) -> w!!.sumOf { it.size } }, 1L)
            job.filesTotal = if (lite) plan.size else plan.sumOf { (_, w) -> w!!.count { !it.dir && !it.skip } }
            if (!lite) pinArchives(job, src, plan)
            if (job.cancel) throw Cancelled()
            job.cur = "Checking the destination folder\u2026"
            val taken = try { dst.names(ddirN) } catch (e: Cancelled) { throw e } catch (e: Exception) { throw IOException("cannot read the destination folder: ${errText(e)}") }
            // SAFETY: nothing that already exists at the destination is ever deleted to make room. Folders are merged; only files with the
            // same name are replaced - one by one as the user decides (conflict "ask"), or all / none ("replace" / "skip").
            job.cur = ""
            val dstDirs = HashMap<String, Map<String, Item>>()
            fun listing(dir: String): Map<String, Item> = dstDirs.getOrPut(dir) {
                try { dst.lsFast(dir).associateBy { it.name } } catch (e: Cancelled) { throw e } catch (_: Exception) { emptyMap() }
            }
            var sticky: String? = null   // "replace" / "skip" once the user answered "all"
            var kept = 0                 // files the user chose to skip while moving: their originals stay
            for ((p, walked) in plan) {
                if (job.cancel) throw Cancelled()
                visited++
                val isDir = walked?.firstOrNull()?.dir ?: false
                val dn = destName(p)
                val clash = dn in taken
                val selfHit = same && vjoin(ddirN, dn) == p                      // never merge an item into itself: keep both instead
                val fname = forced?.get(p)                                       // resume: the name the interrupted run used - merge into it
                val merge = if (fname != null) true else clash && !selfHit && conflict != "rename"
                val nn = if (fname != null) fname else if (merge) dn else uniqueName(dn, taken, isDir)
                taken.add(nn)
                val d = vjoin(ddirN, nn)
                val fastMove = same && cut && !merge && (!unsim || isDir)   // unsim: only whole new folders are renamed, files are checked one by one
                if (merge || unsim) job.live = true
                dest = if (merge) null else d   // a cancel removes only what this job created itself
                if (fastMove) {
                    try { src.move(p, d) } catch (e: Cancelled) { throw e } catch (e: Exception) { fail(vbase(p), e) }
                    job.done += 1; job.files++
                    continue
                }
                pending[p] = nn
                val wl: List<WalkItem> = walked ?: src.walk(p)   // a move onto an existing name is done as copy + delete of the original
                val counting = !lite
                var bad = 0
                var skipped = 0
                var keptHere = 0
                for (w in wl) {
                    if (job.cancel) throw Cancelled()
                    if (w.skip) { skipped++; continue }   // link / unreadable folder: not copied
                    var target = if (w.rel.isEmpty()) d else d + "/" + w.rel
                    var shown = if (w.rel.isEmpty()) nn else nn + "/" + w.rel
                    try {
                        if (w.dir) {
                            if (merge) {
                                val ex = listing(vdir(target))[vbase(target)]
                                if (ex != null) { if (!ex.dir) throw IOException("a file with this name is in the way"); continue }
                            }
                            dst.mkdir(target); continue
                        }
                        val sp = if (w.rel.isEmpty()) p else p + "/" + w.rel
                        var existed = false
                        if (simIdx != null) {   // already there (same picture, another name / size / quality)? then neither copy nor move it: the original stays
                            job.cur = shown
                            if (simIdx.match(src, sp, w.size, vdir(target), job)) {
                                job.files++
                                if (counting) job.done += w.size
                                keptHere++; job.addLog("S", shown)
                                continue
                            }
                        }
                        if (merge) {
                            val ex = listing(vdir(target))[vbase(target)]
                            if (ex != null) {
                                if (ex.dir) throw IOException("a folder with this name is in the way")
                                existed = true
                                var act: String? = sticky ?: when (conflict) { "replace" -> "replace"; "skip" -> "skip"; "newer" -> "newer"; "resume" -> "resume"; "unsim" -> "both"; else -> null }
                                if (act == null) {
                                    val q = JSONObject().put("name", shown).put("srcSize", w.size).put("dstSize", ex.size).put("dstMtime", ex.mtime)
                                        .put("sDev", src.id).put("sPath", sp).put("dDev", dst.id).put("dPath", target)   // lets the UI show both previews
                                    try { q.put("srcMtime", src.stat(sp).optLong("mtime")) } catch (_: Exception) {}
                                    if (src.id == dst.id) DupFinder.look(src, sp, w.size, target, ex.size)?.let { q.put("look", it) }   // "same" / "similar": shown on the replace card
                                    act = job.waitAnswer(q)
                                    if (act == "replace_all") { sticky = "replace"; act = "replace" }
                                    else if (act == "skip_all") { sticky = "skip"; act = "skip" }
                                    else if (act == "newer_all") { sticky = "newer"; act = "newer" }
                                    else if (act == "both_all") { sticky = "both"; act = "both" }
                                    else if (act == "unsim_all") { sticky = "unsim"; act = "unsim" }
                                }
                                if (act == "unsim") act = if (src.id == dst.id && DupFinder.look(src, sp, w.size, target, ex.size) != null) "skip" else "both"   // "only if not similar": look-alike stays where it is, a different picture with the same name is kept next to it
                                if (act == "resume") act = if (ex.size == w.size) "done" else "replace"   // continuing: a complete file stays, anything else is copied again (from its .lspart when there is one)
                                if (act == "done") { job.files++; if (counting) job.done += w.size; continue }   // verified complete: neither "kept" nor "skipped"
                                if (act == "newer") {   // replace only when the new file is newer (2 s tolerance: FAT / SMB clocks)
                                    val sm = try { src.stat(sp).optLong("mtime") } catch (e: Cancelled) { throw e } catch (_: Exception) { 0L }
                                    act = if (sm > ex.mtime + 2) "replace" else "skip"
                                }
                                if (act == "both") {    // keep both: the new file gets "name (1).ext" next to the existing one
                                    val nm = uniqueName(vbase(target), listing(vdir(target)).keys, false)
                                    target = vjoin(vdir(target), nm)
                                    shown = shown.substringBeforeLast('/', "").let { if (it.isEmpty()) nm else "$it/$nm" }
                                    existed = false
                                }
                                if (act == "skip") {
                                    job.files++
                                    if (counting) job.done += w.size
                                    keptHere++; job.addLog("S", shown)
                                    continue
                                }
                            }
                        }
                        copyFile(job, src, dst, sp, target, counting)   // an existing file is overwritten through a temp file: never half-written
                        simIdx?.added(vdir(target), target, w.size)
                        if (unsim && cut && w.rel.isNotEmpty()) try { src.remove(sp) } catch (e: Cancelled) { throw e } catch (_: Exception) {}   // move inside a folder: each copied file leaves the source now, so skipped look-alikes are the only ones left behind
                        if (merge) dstDirs.remove(vdir(target))
                        job.addLog(if (existed) "R" else "N", shown)
                    } catch (e: Cancelled) { throw e
                    } catch (e: Exception) {
                        bad++
                        fail(if (w.rel.isEmpty()) vbase(p) else vbase(p) + "/" + w.rel, e)
                        job.files++
                        if (counting) job.done += w.size   // keep the progress bar moving
                        if (w.rel.isEmpty() && w.dir) break   // the top folder itself could not be used: its content has no place to go
                    }
                }
                if (bad == 0) pending.remove(p)
                skippedTotal += skipped
                kept += keptHere
                if (!counting) { job.done += 1; job.files++ }
                if (cut) {
                    // SAFETY: the original is removed only when every single thing in it was copied
                    if (bad == 0 && skipped == 0 && keptHere == 0) {
                        try { src.remove(p) } catch (e: Cancelled) { throw e }
                        catch (e: Exception) { fail(vbase(p), IOException("copied, but the original could not be removed: ${errText(e)}")) }
                    } else if (bad == 0 && skipped == 0) {
                        // the user kept some files: the original stays so nothing is lost
                    } else if (bad == 0) {
                        failCount++; if (fails.size < 50) fails.add("${vbase(p)}: copied, but $skipped item(s) (links or unreadable folders) could not be copied, so the original was kept")
                    } else if (fails.size < 50) fails.add("${vbase(p)}: not moved - the original was kept")
                }
            }
            if (unsim) job.note = "Done - ${job.cN} ${if (cut) "moved" else "copied"}, ${job.cS} skipped (already there / look the same)" + (if (kept > 0 && cut) " - their originals were kept" else "")
            else if (job.live) job.note = "Done - ${job.cR} replaced, ${job.cN} new, ${job.cS} skipped" + (if (kept > 0) " (originals of skipped files were kept)" else "")
            job.done = job.total
            if (failCount == 0) {
                job.state = "done"
                job.rid?.let { Partials.dropJob(it) }
                if (skippedTotal > 0 && !job.live) job.note = "Done - $skippedTotal item(s) (links or unreadable folders) were skipped"
                if (cut && Clip.paths.isNotEmpty() && Clip.dev == srcId) Clip.clear()
            } else {
                job.error = (if (failCount == 1) fails[0] else "$failCount problems - " + fails.take(3).joinToString("; ") + (if (failCount > 3) "; ..." else ""))
                job.state = "error"
                saveResume()
            }
        } catch (e: Cancelled) {
            job.state = "cancel"
            val d = dest
            if (d != null && !lite) try { dst.remove(d) } catch (_: Exception) {}   // drop the half-copied item; finished ones stay
            job.rid?.let { Partials.dropJob(it) }   // cancelled on purpose: no Resume offer any more
        } catch (e: Throwable) {   // incl. OutOfMemoryError / LinkageError: the job must end, not stay "running" forever
            job.error = errText(e)
            job.state = "error"
            try { saveResume() } catch (_: Throwable) {}
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    private const val RESUME_MIN = 4L shl 20   // smaller files are simply copied again

    /**
     * Copies one file. When both ends allow it (file >= 4 MB, readable from any offset, destination can resume) an interrupted copy continues
     * where it stopped - in this loop after a dropped connection, and in a later run (Resume / copy again) because the destination keeps
     * "name.lspart" and [Partials] remembers which version of the source it belongs to. Attempts that make progress do not count as failures.
     */
    private fun copyFile(job: Job, src: Endpoint, dst: Endpoint, sp: String, target: String, count: Boolean = true) {
        var stalls = 0          // failed attempts in a row that got (almost) nowhere
        var tries = 0           // all attempts: a hard stop so a link that always breaks after a little progress cannot loop for ever
        var best = 0L           // furthest point of this file that was ever in the destination
        var resumeTried = false
        while (true) {
            var sent = 0L
            var reached = 0L
            try {
                if (src.id == dst.id && (src.id.startsWith("ftp:") || src.id.startsWith("gd:")) && !sp.contains("!/") && !target.contains("!/")) {   // same FTP server: it copies by itself, nothing travels through the phone
                    val sz = try { src.stat(sp).optLong("size") } catch (e: Cancelled) { throw e } catch (_: Exception) { -1L }
                    if (sz >= 0 && (try { dst.copyWithin(sp, target) } catch (e: Cancelled) { throw e } catch (_: Exception) { false })) {
                        job.begin(vbase(sp), sz); job.curDone = sz
                        if (count) { job.done += sz; job.files++ }
                        return
                    }
                }
                val f = src.open(sp)
                try {
                    val keepMt = (src.id.startsWith("ftp:") || dst.id.startsWith("ftp:") || src.id.startsWith("gd:") || dst.id.startsWith("gd:")) && dst.setsMtime   // copies to / from an FTP server keep the original date
                    val statMt = { try { src.stat(sp).optLong("mtime") } catch (e: Cancelled) { throw e } catch (_: Exception) { 0L } }
                    val smt = if (keepMt) statMt() else -1L
                    val can = dst.resumable && f.seekable && f.size >= RESUME_MIN
                    resumeTried = resumeTried || can
                    var off = 0L
                    var tag = ""
                    if (can) {
                        val mt = if (smt >= 0) smt else statMt()
                        tag = "${f.size}:$mt"
                        off = dst.partial(target, tag)
                        if (off !in 1 until f.size) off = 0L
                    }
                    job.begin(vbase(sp), f.size)
                    if (off > 0) { f.seek(off); job.curDone = off; sent = off; reached = off; if (count) job.done += off }
                    val cb: (Int) -> Unit = { n ->
                        if (job.cancel) throw Cancelled()
                        sent += n; reached += n
                        job.curDone += n
                        if (count) job.done += n
                    }
                    if (can) dst.writeResume(target, f, f.size, off, tag, cb) else dst.write(target, f, f.size, cb)
                    if (smt > 0) try { dst.setMtime(target, smt) } catch (e: Cancelled) { throw e } catch (_: Exception) {}
                } finally { f.close() }
                if (count) job.files++
                return
            } catch (e: Cancelled) { throw e
            } catch (e: Exception) {
                if (count) job.done -= sent
                val fatal = e is NotFound || e is Denied || e is Exists || e is Full || (e is BadReq && !(e.message ?: "").startsWith("resume offset"))
                if (fatal) throw e   // retrying cannot help (Full: the part stays, free some space and resume)
                tries++
                if (reached >= best + (1L shl 20)) stalls = 0 else stalls++
                best = maxOf(best, reached)
                val maxStalls = if (resumeTried) 6 else 3
                if (stalls > maxStalls || tries > 40) {
                    throw IOException(errText(e) + if (resumeTried && best > 0) " - the copied part is kept: Resume (or copy again) continues from ${best / 1048576} MB" else "")
                }
                if (job.cancel) throw Cancelled()
                val wait = if (resumeTried) minOf(2000L shl minOf(stalls, 4), 30_000L) else 1500L * (stalls + 1)
                val until = System.currentTimeMillis() + wait
                while (System.currentTimeMillis() < until) {   // the card shows why it stands still; Cancel works while waiting
                    job.cur = "Connection lost - retrying (${minOf(stalls + 1, maxStalls)}/$maxStalls): ${vbase(sp)}"
                    if (job.cancel) throw Cancelled()
                    Thread.sleep(250)
                }
            }
        }
    }
}
