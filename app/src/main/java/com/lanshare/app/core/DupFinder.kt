package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Exact duplicate finder (only inside the selected folders and their subfolders, never the rest of the disk): files of equal size, equal hash of the first 64 KB, then equal SHA-256 of the whole content (byte-identical).
 * Runs as a [Job] ("Finding duplicates"); the groups come back in the job's `result`.
 */
object DupFinder {
    private const val HEAD = 64 * 1024
    private const val MAX_GROUPS = 500

    private class F(val path: String, val size: Long)

    fun start(devId: String, paths: List<String>): String {
        val e = Jobs.ep(devId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        val job = Job("Finding duplicates")
        job.bytes = true
        job.total = 1
        Jobs.all[jid] = job
        Thread({ work(job, e, paths) }, "dup-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun hash(e: Endpoint, p: String, limit: Long, job: Job): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        e.open(p).use { s ->
            var left = limit
            while (left > 0) {
                if (job.cancel) throw Cancelled()
                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                md.update(buf, 0, n); left -= n; job.done += n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun work(job: Job, e: Endpoint, paths: List<String>) {
        try {
            val files = ArrayList<F>()
            // only the selected folders: root (= whole disk) refused, duplicates and folders nested in another selected folder dropped (else files would match themselves)
            val dirs = paths.map { vnorm(it) }.filter { it != "/" }.distinct().let { l -> l.filter { p -> l.none { it != p && p.startsWith(it + "/") } } }
            for ((i, p) in dirs.withIndex()) {
                if (job.cancel) throw Cancelled()
                job.label = "Scanning folders ${i + 1}/${dirs.size}: ${vbase(p)} (${files.size} files)"
                for (w in e.walk(p)) {
                    if (w.dir || w.skip || w.size <= 0) continue
                    if (w.rel.isEmpty()) continue   // a selected file itself: only the contents of the selected folders are compared
                    files.add(F(if (w.rel.isEmpty()) p else vnorm(p + "/" + w.rel), w.size))
                }
            }
            val bySize = files.groupBy { it.size }.filter { it.value.size > 1 }
            val cand = bySize.values.sumOf { g -> g.sumOf { it.size } }
            job.total = maxOf(cand, 1L); job.done = 0
            var seen = 0; val nCand = bySize.values.sumOf { it.size }
            val groups = ArrayList<Pair<Long, List<String>>>()
            for ((size, g) in bySize) {
                val byHead = HashMap<String, MutableList<F>>()
                for (f in g) {
                    job.label = "Comparing ${++seen}/$nCand: ${vbase(f.path)}"
                    try { byHead.getOrPut(hash(e, f.path, minOf(size, HEAD.toLong()), job)) { ArrayList() }.add(f) } catch (x: Cancelled) { throw x } catch (_: Exception) {}
                }
                for (h in byHead.values) {
                    if (h.size < 2) continue
                    if (size <= HEAD) { groups.add(size to h.map { it.path }); continue }   // the head hash already covered the whole file
                    val full = HashMap<String, MutableList<String>>()
                    for (f in h) {
                        if (job.cancel) throw Cancelled()
                        job.label = "Verifying: ${vbase(f.path)}"
                        try { full.getOrPut(hash(e, f.path, size, job)) { ArrayList() }.add(f.path) } catch (x: Cancelled) { throw x } catch (_: Exception) {}
                    }
                    for (l in full.values) if (l.size > 1) groups.add(size to l)
                }
            }
            groups.sortByDescending { it.first * (it.second.size - 1) }
            val arr = JSONArray()
            var wasted = 0L; var extra = 0
            for ((size, l) in groups) {
                wasted += size * (l.size - 1); extra += l.size - 1
                if (arr.length() < MAX_GROUPS) arr.put(JSONObject().put("size", size).put("files", JSONArray(l.sorted())))
            }
            job.result = JSONObject().put("groups", arr).put("total", groups.size).put("extra", extra).put("wasted", wasted).put("scanned", files.size)
            job.done = job.total
            job.note = if (groups.isEmpty()) "No exact duplicates found (${files.size} files checked)" else "$extra duplicate files, ${fmtBytes(wasted)} can be freed"
            job.state = "done"
        } catch (x: Cancelled) { job.state = "cancel"
        } catch (x: Exception) { job.error = errText(x); job.state = "error" }
        job.end = System.currentTimeMillis()
    }

    private fun fmtBytes(n: Long): String {
        var v = n.toDouble(); var i = 0; val u = arrayOf("B", "KB", "MB", "GB", "TB")
        while (v >= 1024 && i < 4) { v /= 1024; i++ }
        return (if (i > 0) String.format(java.util.Locale.US, "%.1f", v) else n.toString()) + " " + u[i]
    }
}
