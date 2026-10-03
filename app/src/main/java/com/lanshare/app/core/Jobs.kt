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

    fun toJson(): JSONObject = JSONObject().put("state", state).put("done", done).put("total", total).put("bytes", bytes)
        .put("error", error ?: JSONObject.NULL).put("label", label).also {
            if (cancel) it.put("cancel", true)
            if (end > 0) it.put("end", end / 1000.0)
            note?.let { n -> it.put("note", n) }
        }
}

object Jobs {
    val all = ConcurrentHashMap<String, Job>()


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

    /** Streams the files to the print service (pcprint.py) on the PC; it prints them on the PC's default printer. */
    fun startPrint(srcId: String, paths: List<String>, dstId: String): String {
        val src = ep(srcId)
        val (ip, pcName) = printTarget(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        val job = Job("Printing on $pcName")
        all[jid] = job
        Thread({ printWork(job, src, ip, pcName, paths) }, "print-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun printWork(job: Job, src: Endpoint, ip: String, pcName: String, paths: List<String>) {
        try {
            var printer = ""
            try {
                val r = Http.request(ip, PRINT_PORT, "GET", "/ping", emptyMap(), 4000)
                try {
                    if (r.status != 200) throw IOException("HTTP ${r.status}")
                    printer = try { JSONObject(String(r.readUpTo(4096), Charsets.UTF_8)).optString("printer") } catch (_: Exception) { "" }
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
                job.label = "Printing ${i + 1}/${files.size} on $dest"
                val name = vbase(sp)
                var sent = 0L
                val f = src.open(sp)
                try {
                    val r = Http.request(ip, PRINT_PORT, "POST", "/print?name=" + URLEncoder.encode(name, "UTF-8"), emptyMap(),
                        120_000, f, f.size) { n -> if (job.cancel) throw Cancelled(); sent += n; job.done += n }
                    try {
                        if (r.status != 200) {
                            val t = String(r.readUpTo(500), Charsets.UTF_8)
                            throw PrintFail(try { JSONObject(t).optString("error", t) } catch (_: Exception) { t })
                        }
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
                job.error = "Printed $ok of ${files.size} on $dest. Failed - " + failed.joinToString("; ")
                job.state = "error"
            }
        } catch (e: Cancelled) {
            job.state = "cancel"
        } catch (e: Exception) {
            job.error = errText(e)
            job.state = "error"
        } finally {
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
        val now = System.currentTimeMillis()
        all.entries.removeAll { it.value.state != "run" && it.value.end > 0 && now - it.value.end > 600_000 }
        val job = Job(label)
        all[jid] = job
        Thread({ work(job, src, dst, srcId, paths, ddir, cut) }, "job-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun work(job: Job, src: Endpoint, dst: Endpoint, srcId: String, paths: List<String>, ddir: String, cut: Boolean) {
        var dest: String? = null
        val same = src.id == dst.id
        try {
            val ddirN = vnorm(ddir)
            val plan = ArrayList<Pair<String, List<WalkItem>?>>()
            for (p0 in paths) {
                val p = vnorm(p0)
                if (p == "/") throw Denied("cannot copy the root")
                if (same && (ddirN == p || ddirN.startsWith("$p/"))) throw BadReq("cannot put a folder inside itself")
                if (same && cut && vdir(p) == ddirN) continue   // moving into the same folder: nothing to do
                plan.add(p to (if (same && cut) null else src.walk(p)))
            }
            if (same && cut) { job.bytes = false; job.total = maxOf(plan.size, 1).toLong() }
            else job.total = maxOf(plan.sumOf { (_, w) -> w!!.sumOf { it.size } }, 1L)
            val taken = dst.names(ddirN)
            for ((p, walked) in plan) {
                val isDir = walked?.firstOrNull()?.dir ?: false
                val nn = uniqueName(vbase(p), taken, isDir)
                taken.add(nn)
                val d = vjoin(ddirN, nn)
                dest = d
                if (same && cut) { src.move(p, d); job.done += 1; continue }
                for (w in walked!!) {
                    if (job.cancel) throw Cancelled()
                    val target = if (w.rel.isEmpty()) d else d + "/" + w.rel
                    if (w.dir) { dst.mkdir(target); continue }
                    val sp = if (w.rel.isEmpty()) p else p + "/" + w.rel
                    copyFile(job, src, dst, sp, target)
                }
                if (cut) src.remove(p)
            }
            job.done = job.total
            job.state = "done"
            if (cut && Clip.paths.isNotEmpty() && Clip.dev == srcId) Clip.clear()
        } catch (e: Cancelled) {
            job.state = "cancel"
            val d = dest
            if (d != null && !(same && cut)) try { dst.remove(d) } catch (_: Exception) {}   // drop the half-copied item; finished ones stay
        } catch (e: Exception) {
            job.error = errText(e)
            job.state = "error"
        } finally {
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
            } catch (e: NotFound) { throw e
            } catch (e: Denied) { throw e
            } catch (e: Exists) { throw e
            } catch (e: BadReq) { throw e
            } catch (e: IOException) {
                job.done -= sent
                if (attempt == 3) throw IOException(errText(e))
                Thread.sleep(1500L * (attempt + 1))
            }
        }
    }
}
