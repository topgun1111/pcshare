package com.lanshare.app.core

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
    @Volatile var cancel = false
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
        try {
            while (true) {
                if (cancel) throw Cancelled()
                val a = answer
                if (a != null) return a
                Thread.sleep(120)
            }
        } finally { ask = null; answer = null }
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
            if (end > 0) it.put("end", end / 1000.0)
            it.put("elapsed", ((if (end > 0) end else System.currentTimeMillis()) - startedAt) / 1000.0)
            if (cur.isNotEmpty()) it.put("cur", cur).put("curDone", curDone).put("curTotal", curTotal)
            if (files > 0 || filesTotal > 0) it.put("files", files).put("filesTotal", filesTotal)
            if (failed > 0) it.put("failed", failed).put("problems", problemsJson())
            dest?.let { d -> it.put("dest", d) }
            note?.let { n -> it.put("note", n) }
            result?.let { r -> it.put("result", r) }
            if (live) {
                it.put("live", true).put("cR", cR).put("cN", cN).put("cS", cS)
                ask?.let { a -> it.put("ask", a) }
                if (since >= 0) { val (n, l) = logSince(since); it.put("logn", n).put("log", org.json.JSONArray(l)) }
            }
            if (state == "run") {
                val r = rateNow()
                val warm = System.currentTimeMillis() - startedAt >= 2000   // a few seconds of data before promising anything
                if (r > 0 && warm) {
                    if (bytes) it.put("speed", Math.round(r))
                    val left = maxOf(total - done, 0L)
                    if (left > 0) it.put("eta", Math.ceil(left / r).toLong())
                }
            }
        }
}

object Jobs {
    val all = ConcurrentHashMap<String, Job>()

    private fun prune() {
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
                    .put("pypdf", o.optBoolean("pypdf", false))
                    .put("engine", if (o.isNull("engine")) JSONObject.NULL else o.optString("engine"))
                    .put("office", if (o.isNull("office")) JSONObject.NULL else o.optString("office"))   // what turns Word/Excel/PowerPoint files into PDF on the PC (null = nothing, or an older pcprint.py)
            } finally { r.close() }
        } catch (_: Exception) { JSONObject().put("ok", false) }
    }

    /** Streams the files to the print service (pcprint.py) on the PC; it prints them on the PC's default printer. */
    private val PRINT_KEYS = listOf("printer", "copies", "duplex", "color", "fit", "paper", "nup", "booklet", "border", "pages",
        "reverse", "range", "wm", "wm_under", "hdr", "ftr", "noauto", "margin", "scale", "align", "autorot")

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
            Thread({ WifiPrint.work(wjob, src, wp, paths, opts) }, "wprint-$wid").also { it.isDaemon = true }.start()
            return wid
        }
        val (ip, pcName) = printTarget(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job("Printing on $pcName")
        all[jid] = job
        Thread({ printWork(job, src, ip, pcName, paths, opts) }, "print-$jid").also { it.isDaemon = true }.start()
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
        try {
            for ((i, p0) in paths.withIndex()) {
                if (job.cancel) throw Cancelled()
                val p = vnorm(p0)
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
                try {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    var body: java.io.InputStream = f
                    var bodyLen = f.size
                    var sendName = name
                    val kind = PrintPrep.kind(ext)
                    if (kind == PrintPrep.Kind.NO || (kind == PrintPrep.Kind.SNIFF && size > PrintPrep.SNIFF_MAX)) throw PrintFail(if (ext.isEmpty()) "this file type cannot be printed" else "$ext files cannot be printed")
                    if (kind == PrintPrep.Kind.PIC || kind == PrintPrep.Kind.WEB || kind == PrintPrep.Kind.SNIFF) {
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
                            val out = PrintPrep.convert(kind, t, name).also { if (it.file != t) tmp2 = it.file }
                            val ins = out.file.inputStream(); opened = ins; body = ins; bodyLen = out.file.length()
                            sendName = out.name
                        } catch (x: Cancelled) { throw x
                        } catch (x: Throwable) { throw PrintFail("this file could not be converted for printing (" + errText(x) + ")") }
                    } else if (kind == PrintPrep.Kind.TEXT) sendName = name.substringBeforeLast('.') + ".txt"
                    val counted = body === f   // converted pictures were already counted while they were read
                    val extra = if (sheet) "&batch=$bid&idx=$i&n=${files.size}&rot=${rots?.optInt(i, 0) ?: 0}" else ""
                    val r = Http.request(ip, PRINT_PORT, "POST", printQuery(sendName, opts, extra), emptyMap(),
                        120_000, body, bodyLen) { n -> if (job.cancel) throw Cancelled(); if (counted) { sent += n; job.done += n } }
                    try {
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
            job.error = errText(e)
            job.state = "error"
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
    fun startZip(devId: String, paths: List<String>, dir: String, name: String): String {
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
        Thread({ zipWork(job, e, ps, d, n) }, "zip-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun zipWork(job: Job, e: Endpoint, paths: List<String>, dir: String, name: String) {
        val dst: Endpoint = (e as? ArcEp)?.base ?: e
        var tmp: File? = null
        var uploading = false
        var target = ""
        try {
            // 1. what goes in (folders are walked; links and unreadable folders are left out)
            val items = ArrayList<ZItem>()
            val tops = HashSet<String>()
            var skipped = 0
            for (p in paths) {
                if (job.cancel) throw Cancelled()
                val walked = e.walk(p)
                val top = uniqueName(destName(p), tops, walked.firstOrNull()?.dir ?: false)
                tops.add(top)
                for (w in walked) {
                    if (w.skip) { skipped++; continue }
                    items.add(ZItem(if (w.rel.isEmpty()) p else p + "/" + w.rel, if (w.rel.isEmpty()) top else top + "/" + w.rel, w.dir, w.size))
                }
            }
            if (items.isEmpty()) throw BadReq("nothing to zip")
            val total = items.sumOf { if (it.dir) 0L else it.size }

            // 2. where it goes: next to the originals, under a name that is free
            val taken = try { dst.names(dir) } catch (x: Cancelled) { throw x } catch (x: Exception) { throw IOException("cannot read the destination folder: ${errText(x)}") }
            val finalName = uniqueName(name, taken, false)
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
            job.note = "Created $finalName" + (if (skipped > 0) " - $skipped item(s) (links or unreadable folders) were left out" else "")
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
        dev.startsWith("smb:") -> SmbFs.create(Smb.cfg(dev) ?: throw IOException("that SMB share was removed"))
        else -> RemoteFs(Core.disc.get(dev) ?: throw IOException("that device is offline"))
    })

    fun start(srcId: String, paths: List<String>, dstId: String, ddir: String, cut: Boolean, label: String, conflict: String = "rename"): String {   // conflict: what to do when the name exists at the destination - rename ("name (1)") | replace | skip
        val src = ep(srcId)
        val dst = ep(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job(label)
        all[jid] = job
        val cutOk = cut && paths.none { arcSplit(it) != null }   // nothing can be moved out of an archive: it is copied
        Thread({ work(job, src, dst, srcId, paths, ddir, cutOk, conflict) }, "job-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun work(job: Job, src: Endpoint, dst: Endpoint, srcId: String, paths: List<String>, ddir: String, cut: Boolean, conflict: String = "rename") {
        var dest: String? = null
        val same = src.id == dst.id
        val fails = ArrayList<String>()   // what went wrong, one line each (first 50)
        var failCount = 0
        var skippedTotal = 0
        fun fail(what: String, e: Throwable) { failCount++; job.failed = failCount; job.addProblem("$what: ${errText(e)}"); if (fails.size < 50) fails.add("$what: ${errText(e)}") }
        try {
            val ddirN = vnorm(ddir)
            job.dest = dst.name + " \u203a " + ddirN
            val plan = ArrayList<Pair<String, List<WalkItem>?>>()
            for (p0 in paths) {
                val p = vnorm(p0)
                if (p == "/") throw Denied("cannot copy the root")
                if (same && (ddirN == p || ddirN.startsWith("$p/"))) throw BadReq("cannot put a folder inside itself")
                if (same && cut && vdir(p) == ddirN) continue   // moving into the same folder: nothing to do
                try { plan.add(p to (if (same && cut) null else src.walk(p))) }
                catch (e: Cancelled) { throw e } catch (e: Exception) { fail(vbase(p), e) }   // one unreadable item must not stop the others
            }
            if (same && cut) { job.bytes = false; job.total = maxOf(plan.size, 1).toLong() }
            else job.total = maxOf(plan.sumOf { (_, w) -> w!!.sumOf { it.size } }, 1L)
            job.filesTotal = if (same && cut) plan.size else plan.sumOf { (_, w) -> w!!.count { !it.dir && !it.skip } }
            if (!(same && cut)) pinArchives(job, src, plan)
            val taken = try { dst.names(ddirN) } catch (e: Cancelled) { throw e } catch (e: Exception) { throw IOException("cannot read the destination folder: ${errText(e)}") }
            // SAFETY: nothing that already exists at the destination is ever deleted to make room. Folders are merged; only files with the
            // same name are replaced - one by one as the user decides (conflict "ask"), or all / none ("replace" / "skip").
            val dstDirs = HashMap<String, Map<String, Item>>()
            fun listing(dir: String): Map<String, Item> = dstDirs.getOrPut(dir) {
                try { dst.ls(dir).associateBy { it.name } } catch (e: Cancelled) { throw e } catch (_: Exception) { emptyMap() }
            }
            var sticky: String? = null   // "replace" / "skip" once the user answered "all"
            var kept = 0                 // files the user chose to skip while moving: their originals stay
            for ((p, walked) in plan) {
                if (job.cancel) throw Cancelled()
                val isDir = walked?.firstOrNull()?.dir ?: false
                val dn = destName(p)
                val clash = dn in taken
                val selfHit = same && vjoin(ddirN, dn) == p                      // never merge an item into itself: keep both instead
                val merge = clash && !selfHit && conflict != "rename"
                val nn = if (merge) dn else uniqueName(dn, taken, isDir)
                taken.add(nn)
                val d = vjoin(ddirN, nn)
                val fastMove = same && cut && !merge
                if (merge) job.live = true
                dest = if (merge) null else d   // a cancel removes only what this job created itself
                if (fastMove) {
                    try { src.move(p, d) } catch (e: Cancelled) { throw e } catch (e: Exception) { fail(vbase(p), e) }
                    job.done += 1; job.files++
                    continue
                }
                val wl: List<WalkItem> = walked ?: src.walk(p)   // a move onto an existing name is done as copy + delete of the original
                val counting = !(same && cut)
                var bad = 0
                var skipped = 0
                var keptHere = 0
                for (w in wl) {
                    if (job.cancel) throw Cancelled()
                    if (w.skip) { skipped++; continue }   // link / unreadable folder: not copied
                    val target = if (w.rel.isEmpty()) d else d + "/" + w.rel
                    val shown = if (w.rel.isEmpty()) nn else nn + "/" + w.rel
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
                        if (merge) {
                            val ex = listing(vdir(target))[vbase(target)]
                            if (ex != null) {
                                if (ex.dir) throw IOException("a folder with this name is in the way")
                                existed = true
                                var act: String? = sticky ?: when (conflict) { "replace" -> "replace"; "skip" -> "skip"; else -> null }
                                if (act == null) {
                                    val q = JSONObject().put("name", shown).put("srcSize", w.size).put("dstSize", ex.size).put("dstMtime", ex.mtime)
                                    try { q.put("srcMtime", src.stat(sp).optLong("mtime")) } catch (_: Exception) {}
                                    act = job.waitAnswer(q)
                                    if (act == "replace_all") { sticky = "replace"; act = "replace" }
                                    else if (act == "skip_all") { sticky = "skip"; act = "skip" }
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
            if (job.live) job.note = "Done - ${job.cR} replaced, ${job.cN} new, ${job.cS} skipped" + (if (kept > 0) " (originals of skipped files were kept)" else "")
            job.done = job.total
            if (failCount == 0) {
                job.state = "done"
                if (skippedTotal > 0 && !job.live) job.note = "Done - $skippedTotal item(s) (links or unreadable folders) were skipped"
                if (cut && Clip.paths.isNotEmpty() && Clip.dev == srcId) Clip.clear()
            } else {
                job.error = (if (failCount == 1) fails[0] else "$failCount problems - " + fails.take(3).joinToString("; ") + (if (failCount > 3) "; ..." else ""))
                job.state = "error"
            }
        } catch (e: Cancelled) {
            job.state = "cancel"
            val d = dest
            if (d != null && !(same && cut)) try { dst.remove(d) } catch (_: Exception) {}   // drop the half-copied item; finished ones stay
        } catch (e: Throwable) {   // incl. OutOfMemoryError / LinkageError: the job must end, not stay "running" forever
            job.error = errText(e)
            job.state = "error"
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    private fun copyFile(job: Job, src: Endpoint, dst: Endpoint, sp: String, target: String, count: Boolean = true) {
        for (attempt in 0..3) {
            var sent = 0L
            try {
                val f = src.open(sp)
                try {
                    job.begin(vbase(sp), f.size)
                    dst.write(target, f, f.size) { n ->
                        if (job.cancel) throw Cancelled()
                        sent += n
                        job.curDone += n
                        if (count) job.done += n
                    }
                } finally { f.close() }
                if (count) job.files++
                return
            } catch (e: Cancelled) { throw e
            } catch (e: NotFound) { if (count) job.done -= sent; throw e
            } catch (e: Denied) { if (count) job.done -= sent; throw e
            } catch (e: Exists) { if (count) job.done -= sent; throw e
            } catch (e: BadReq) { if (count) job.done -= sent; throw e
            } catch (e: Full) { if (count) job.done -= sent; throw e   // retrying cannot create space
            } catch (e: Exception) {   // IOException or a raw library error: retry a few times
                if (count) job.done -= sent
                if (attempt == 3) throw IOException(errText(e))
                if (job.cancel) throw Cancelled()
                Thread.sleep(1500L * (attempt + 1))
            }
        }
    }
}
