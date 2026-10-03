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

class Job(val label: String) {
    @Volatile var state = "run"      // run | done | cancel | error
    @Volatile var done = 0L
    @Volatile var total = 1L
    @Volatile var bytes = true
    @Volatile var error: String? = null
    @Volatile var cancel = false
    @Volatile var end = 0L

    fun toJson(): JSONObject = JSONObject().put("state", state).put("done", done).put("total", total).put("bytes", bytes)
        .put("error", error ?: JSONObject.NULL).put("label", label).also {
            if (cancel) it.put("cancel", true)
            if (end > 0) it.put("end", end / 1000.0)
        }
}

object Jobs {
    val all = ConcurrentHashMap<String, Job>()


    const val PRINT_PORT = 8799   // pcprint.py on the PC listens here

    private fun printHost(dev: String): String = when {
        dev == "local" -> throw BadReq("choose the PC to print on")
        dev.startsWith("smb:") -> Smb.split((Smb.cfg(dev) ?: throw IOException("that SMB share was removed")).getString("host")).first
        else -> (Core.disc.get(dev) ?: throw IOException("that device is offline")).ip
    }

    /** Streams the files to the print service (pcprint.py) on the PC; it prints them on the PC's default printer. */
    fun startPrint(srcId: String, paths: List<String>, dstId: String): String {
        val src = ep(srcId)
        val ip = printHost(dstId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        val job = Job("Printing")
        all[jid] = job
        Thread({ printWork(job, src, ip, paths) }, "print-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun printWork(job: Job, src: Endpoint, ip: String, paths: List<String>) {
        try {
            try {
                Http.request(ip, PRINT_PORT, "GET", "/ping", emptyMap(), 4000).close()
            } catch (e: IOException) {
                throw IOException("print service not reachable on the PC - run pcprint.py there once (${errText(e)})")
            }
            val files = ArrayList<Pair<String, Long>>()
            for (p0 in paths) {
                val p = vnorm(p0)
                for (w in src.walk(p)) if (!w.dir) files.add((if (w.rel.isEmpty()) p else p + "/" + w.rel) to w.size)
            }
            if (files.isEmpty()) throw BadReq("nothing to print")
            job.total = maxOf(files.sumOf { it.second }, 1L)
            for ((sp, _) in files) {
                if (job.cancel) throw Cancelled()
                val f = src.open(sp)
                try {
                    val r = Http.request(ip, PRINT_PORT, "POST", "/print?name=" + URLEncoder.encode(vbase(sp), "UTF-8"), emptyMap(),
                        120_000, f, f.size) { n -> if (job.cancel) throw Cancelled(); job.done += n }
                    try {
                        if (r.status != 200) {
                            val t = String(r.readUpTo(500), Charsets.UTF_8)
                            throw IOException(try { JSONObject(t).optString("error", t) } catch (_: Exception) { t })
                        }
                    } finally { r.close() }
                } finally { f.close() }
            }
            job.done = job.total
            job.state = "done"
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
