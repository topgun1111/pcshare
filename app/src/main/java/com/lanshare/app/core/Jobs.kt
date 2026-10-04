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

    fun toJson(): JSONObject = JSONObject().put("state", state).put("done", done).put("total", total).put("bytes", bytes)
        .put("error", error ?: JSONObject.NULL).put("label", label).also {
            if (cancel) it.put("cancel", true)
            if (end > 0) it.put("end", end / 1000.0)
            note?.let { n -> it.put("note", n) }
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

    private class PrintFail(msg: String) : IOException(msg)   // the PC answered and refused / failed to print this file

    /** (address, display name) of the PC to print on. */
    private fun printTarget(dev: String): Pair<String, String> = when {
        dev == "local" -> throw BadReq("choose the PC to print on")
        dev.startsWith("smb:") -> {
            val c = Smb.cfg(dev) ?: throw IOException("that SMB share was removed")
            Smb.split(c.getString("host")).first to c.optString("name").ifEmpty { c.getString("host") }
        }
        else -> (Core.disc.get(dev) ?: throw IOException("that device is offline")).let { it.ip to it.name }
    }

    /** What the print service on that PC reports right now: {ok, printer?, problem?}. ok=false = nothing answered on the print port. */
    fun printerStatus(dev: String, printer: String? = null): JSONObject {
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
    // pcprint.py only knows jpg/png/bmp/gif/tif and txt/log/md for layout options: other pictures are converted to JPEG here
    // (the phone decodes them natively), code/config text files are sent as .txt. The PC service needs no update for this.
    private val PRINT_PICS = setOf("webp", "heic", "heif")
    private val PRINT_TEXT = setOf("php", "phtml", "js", "mjs", "ts", "tsx", "jsx", "css", "scss", "json", "xml", "yml", "yaml", "toml",
        "ini", "cfg", "conf", "env", "sql", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb",
        "sh", "bat", "ps1", "gradle", "properties", "tsv", "srt", "vtt")

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
        all[jid] = job
        Thread({ deleteWork(job, e, paths) }, "del-$jid").also { it.isDaemon = true }.start()
        return jid
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
                    e.remove(p) { n ->
                        if (job.cancel) throw Cancelled()
                        removed++
                        job.label = "Deleting ${i + 1}/${paths.size}: $top - $removed items removed" + (if (n != top) " (now: $n)" else "")
                    }
                } catch (x: Cancelled) { throw x
                } catch (x: Exception) { failCount++; if (fails.size < 50) fails.add("$top: ${errText(x)}") }
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
            val failed = ArrayList<String>()
            // pictures laid out together (opts.sheet): every picture is sent with the batch id, pcprint.py prints one set of sheets after the last one
            val sheet = opts?.optString("sheet") == "1" && files.all { it.first.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "bmp", "gif", "tif", "tiff") + PRINT_PICS }
            val bid = java.lang.Long.toString(System.nanoTime(), 36)
            val rots = opts?.optJSONArray("rots")
            for ((i, f0) in files.withIndex()) {
                val (sp, size) = f0
                if (job.cancel) throw Cancelled()
                job.label = "Printing ${i + 1}/${files.size} on $dest" + (if (problem.isNotEmpty()) " (printer reports: $problem)" else "")
                val name = vbase(sp)
                var sent = 0L
                val f = src.open(sp)
                var tmp: java.io.File? = null
                try {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    var body: java.io.InputStream = f
                    var bodyLen = f.size
                    var sendName = name
                    if (ext in PRINT_PICS) {
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
                            val jpg = Thumbs.forPrint(t)
                            body = java.io.ByteArrayInputStream(jpg); bodyLen = jpg.size.toLong()
                            sendName = name.substringBeforeLast('.') + ".jpg"
                        } catch (x: Cancelled) { throw x
                        } catch (x: Throwable) { throw PrintFail("this picture could not be converted for printing (" + errText(x) + ")") }
                    } else if (ext in PRINT_TEXT) sendName = name.substringBeforeLast('.') + ".txt"
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
                } finally { f.close(); try { tmp?.delete() } catch (_: Exception) {} }
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
            val buf = ByteArray(1 shl 16)
            Zip64Writer(out.outputStream().buffered(1 shl 16)).use { zw ->   // no 4 GB / 65,535-entry limit (ZIP64 where needed)
                for (item in items) {
                    if (job.cancel) throw Cancelled()
                    if (item.dir) { zw.putDir(item.entry); continue }
                    try {
                        zw.beginFile(item.entry, item.size, item.entry.substringAfterLast('.', "").lowercase() in STORE_EXT)
                        e.open(item.src).use { s ->
                            while (true) {
                                if (job.cancel) throw Cancelled()
                                val n = s.read(buf)
                                if (n < 0) break
                                zw.write(buf, 0, n)
                                job.done += n
                            }
                        }
                        zw.endFile()
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

    fun start(srcId: String, paths: List<String>, dstId: String, ddir: String, cut: Boolean, label: String): String {
        val src = ep(srcId)
        val dst = ep(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job(label)
        all[jid] = job
        val cutOk = cut && paths.none { arcSplit(it) != null }   // nothing can be moved out of an archive: it is copied
        Thread({ work(job, src, dst, srcId, paths, ddir, cutOk) }, "job-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun work(job: Job, src: Endpoint, dst: Endpoint, srcId: String, paths: List<String>, ddir: String, cut: Boolean) {
        var dest: String? = null
        val same = src.id == dst.id
        val fails = ArrayList<String>()   // what went wrong, one line each (first 50)
        var failCount = 0
        var skippedTotal = 0
        fun fail(what: String, e: Throwable) { failCount++; if (fails.size < 50) fails.add("$what: ${errText(e)}") }
        try {
            val ddirN = vnorm(ddir)
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
            if (!(same && cut)) pinArchives(job, src, plan)
            val taken = try { dst.names(ddirN) } catch (e: Cancelled) { throw e } catch (e: Exception) { throw IOException("cannot read the destination folder: ${errText(e)}") }
            for ((p, walked) in plan) {
                if (job.cancel) throw Cancelled()
                val isDir = walked?.firstOrNull()?.dir ?: false
                val nn = uniqueName(destName(p), taken, isDir)
                taken.add(nn)
                val d = vjoin(ddirN, nn)
                dest = d
                if (same && cut) {
                    try { src.move(p, d) } catch (e: Cancelled) { throw e } catch (e: Exception) { fail(vbase(p), e) }
                    job.done += 1
                    continue
                }
                var bad = 0
                var skipped = 0
                for (w in walked!!) {
                    if (job.cancel) throw Cancelled()
                    if (w.skip) { skipped++; continue }   // link / unreadable folder: not copied
                    val target = if (w.rel.isEmpty()) d else d + "/" + w.rel
                    try {
                        if (w.dir) { dst.mkdir(target); continue }
                        val sp = if (w.rel.isEmpty()) p else p + "/" + w.rel
                        copyFile(job, src, dst, sp, target)
                    } catch (e: Cancelled) { throw e
                    } catch (e: Exception) {
                        bad++
                        fail(if (w.rel.isEmpty()) vbase(p) else vbase(p) + "/" + w.rel, e)
                        job.done += w.size   // keep the progress bar moving
                    }
                }
                skippedTotal += skipped
                if (cut) {
                    // SAFETY: the original is removed only when every single thing in it was copied
                    if (bad == 0 && skipped == 0) {
                        try { src.remove(p) } catch (e: Cancelled) { throw e }
                        catch (e: Exception) { fail(vbase(p), IOException("copied, but the original could not be removed: ${errText(e)}")) }
                    } else if (bad == 0) {
                        failCount++; if (fails.size < 50) fails.add("${vbase(p)}: copied, but $skipped item(s) (links or unreadable folders) could not be copied, so the original was kept")
                    } else if (fails.size < 50) fails.add("${vbase(p)}: not moved - the original was kept")
                }
            }
            job.done = job.total
            if (failCount == 0) {
                job.state = "done"
                if (skippedTotal > 0) job.note = "Done - $skippedTotal item(s) (links or unreadable folders) were skipped"
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

    private fun copyFile(job: Job, src: Endpoint, dst: Endpoint, sp: String, target: String) {
        for (attempt in 0..3) {
            var sent = 0L
            try {
                val f = src.open(sp)
                try {
                    dst.write(target, f, f.size) { n ->
                        if (job.cancel) throw Cancelled()
                        sent += n
                        job.done += n
                    }
                } finally { f.close() }
                return
            } catch (e: Cancelled) { throw e
            } catch (e: NotFound) { job.done -= sent; throw e
            } catch (e: Denied) { job.done -= sent; throw e
            } catch (e: Exists) { job.done -= sent; throw e
            } catch (e: BadReq) { job.done -= sent; throw e
            } catch (e: Full) { job.done -= sent; throw e   // retrying cannot create space
            } catch (e: Exception) {   // IOException or a raw library error: retry a few times
                job.done -= sent
                if (attempt == 3) throw IOException(errText(e))
                if (job.cancel) throw Cancelled()
                Thread.sleep(1500L * (attempt + 1))
            }
        }
    }
}
