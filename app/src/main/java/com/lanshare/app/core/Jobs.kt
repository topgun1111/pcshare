package com.lanshare.app.core

import org.json.JSONObject
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
    fun printerStatus(dev: String): JSONObject {
        val (ip, _) = try { printTarget(dev) } catch (e: BadReq) { throw e } catch (e: IOException) { return JSONObject().put("ok", false).put("why", errText(e)) }
        return try {
            val r = Http.request(ip, PRINT_PORT, "GET", "/ping", emptyMap(), 3000)
            try {
                if (r.status != 200) return JSONObject().put("ok", false)
                val o = JSONObject(String(r.readUpTo(4096), Charsets.UTF_8))
                JSONObject().put("ok", true)
                    .put("printer", if (o.isNull("printer")) JSONObject.NULL else o.optString("printer"))
                    .put("problem", if (o.isNull("problem")) JSONObject.NULL else o.optString("problem"))
            } finally { r.close() }
        } catch (_: Exception) { JSONObject().put("ok", false) }
    }

    /** Streams the files to the print service (pcprint.py) on the PC; it prints them on the PC's default printer. */
    fun startPrint(srcId: String, paths: List<String>, dstId: String): String {
        val src = ep(srcId)
        val (ip, pcName) = printTarget(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job("Printing on $pcName")
        all[jid] = job
        Thread({ printWork(job, src, ip, pcName, paths) }, "print-$jid").also { it.isDaemon = true }.start()
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

    private fun printWork(job: Job, src: Endpoint, ip: String, pcName: String, paths: List<String>) {
        try {
            var printer = ""
            var problem = ""   // what the printer already reports before we send (paper jam, out of paper, ...)
            try {
                val r = Http.request(ip, PRINT_PORT, "GET", "/ping", emptyMap(), 4000)
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
            for ((i, f0) in files.withIndex()) {
                val (sp, size) = f0
                if (job.cancel) throw Cancelled()
                job.label = "Printing ${i + 1}/${files.size} on $dest" + (if (problem.isNotEmpty()) " (printer reports: $problem)" else "")
                val name = vbase(sp)
                var sent = 0L
                val f = src.open(sp)
                try {
                    val r = Http.request(ip, PRINT_PORT, "POST", "/print?name=" + URLEncoder.encode(name, "UTF-8"), emptyMap(),
                        120_000, f, f.size) { n -> if (job.cancel) throw Cancelled(); sent += n; job.done += n }
                    try {
                        val t = String(r.readUpTo(500), Charsets.UTF_8)
                        if (r.status != 200) throw PrintFail(try { JSONObject(t).optString("error", t) } catch (_: Exception) { t })
                        // printed, but the printer reported trouble right after (jam, out of paper, offline, ...)
                        val w = try { JSONObject(t).let { o -> if (o.isNull("warning")) "" else o.optString("warning") } } catch (_: Exception) { "" }
                        if (w.isNotEmpty()) { failed.add("$name: sent, but the printer reports $w"); job.done += maxOf(size - sent, 0L) }
                    } finally { r.close() }
                } catch (e: PrintFail) {
                    failed.add("$name: ${errText(e)}")
                    job.done += maxOf(size - sent, 0L)   // keep the progress bar moving
                } finally { f.close() }
            }
            job.done = job.total
            val ok = files.size - failed.size
            if (failed.isEmpty()) {
                job.label = "Printed ${files.size} file${if (files.size > 1) "s" else ""} on $dest"
                job.note = job.label
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

    fun ep(dev: String): Endpoint = when {
        dev == "local" -> Core.local
        dev.startsWith("smb:") -> SmbFs.create(Smb.cfg(dev) ?: throw IOException("that SMB share was removed"))
        else -> RemoteFs(Core.disc.get(dev) ?: throw IOException("that device is offline"))
    }

    fun start(srcId: String, paths: List<String>, dstId: String, ddir: String, cut: Boolean, label: String): String {
        val src = ep(srcId)
        val dst = ep(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        prune()
        val job = Job(label)
        all[jid] = job
        Thread({ work(job, src, dst, srcId, paths, ddir, cut) }, "job-$jid").also { it.isDaemon = true }.start()
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
            val taken = try { dst.names(ddirN) } catch (e: Cancelled) { throw e } catch (e: Exception) { throw IOException("cannot read the destination folder: ${errText(e)}") }
            for ((p, walked) in plan) {
                if (job.cancel) throw Cancelled()
                val isDir = walked?.firstOrNull()?.dir ?: false
                val nn = uniqueName(vbase(p), taken, isDir)
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
