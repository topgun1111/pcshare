package com.lanshare.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Exact duplicate finder (only inside the selected folders and their subfolders, never the rest of the disk): files of equal size, equal hash of the first 64 KB, then equal SHA-256 of the whole content (byte-identical).
 * "Similar images" mode (similar = true): the same picture saved with another size / quality / format. Every image gets a 256-bit difference hash (17 x 16 grey grid,
 * bit = left pixel brighter than right one) and a width / height; pictures with about the same shape whose hashes differ in <= [SIM_BITS] bits are grouped, the biggest
 * (most pixels, then bytes) comes first and is the one that stays ticked. Only images up to 40 MB; others are skipped.
 * Runs as a [Job] ("Finding duplicates"); the groups come back in the job's `result`.
 */
object DupFinder {
    private const val HEAD = 64 * 1024
    private const val MAX_GROUPS = 500
    private const val SIM_BITS = 20                    // of 256: about 8 % different
    private const val MAX_IMG = 40L * 1024 * 1024
    private const val MAX_BUCKET = 3000                // a hash byte shared by more pictures than this (e.g. all-white pages) is not compared pairwise
    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp")

    private class F(val path: String, val size: Long)

    fun start(devId: String, paths: List<String>, similar: Boolean = false): String {
        val e = Jobs.ep(devId)
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        val job = Job(if (similar) "Finding similar images" else "Finding duplicates")
        job.bytes = true
        job.total = 1
        Jobs.all[jid] = job
        Thread({ work(job, e, paths, similar) }, "dup-$jid").also { it.isDaemon = true }.start()
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

    private fun work(job: Job, e: Endpoint, paths: List<String>, similar: Boolean = false) {
        try {
            val files = ArrayList<F>()
            // only the selected folders: root (= whole disk) refused, duplicates and folders nested in another selected folder dropped (else files would match themselves)
            val dirs = paths.map { vnorm(it) }.filter { it != "/" }.distinct().let { l -> l.filter { p -> l.none { it != p && p.startsWith(it + "/") } } }
            for ((i, p) in dirs.withIndex()) {
                if (job.cancel) throw Cancelled()
                job.label = "Scanning folders ${i + 1}/${dirs.size}: ${vbase(p)} (${files.size} files)"
                val label = "Scanning folders ${i + 1}/${dirs.size}: ${vbase(p)}"
                var tick = 0L
                e.walkEach(p) { w ->
                    if (job.cancel) throw Cancelled()
                    if (w.dir || w.skip || w.size <= 0) return@walkEach
                    if (w.rel.isEmpty()) return@walkEach   // a selected file itself: only the contents of the selected folders are compared
                    files.add(F(vnorm(p + "/" + w.rel), w.size))
                    val now = System.currentTimeMillis()
                    if (now - tick > 300) { tick = now; job.label = "$label (${files.size} files)" }
                }
                job.label = "$label (${files.size} files)"
            }
            if (similar) { similarPass(job, e, files); job.end = System.currentTimeMillis(); return }
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

    private class Sig(val path: String, val size: Long, val w: Int, val h: Int, val b: LongArray)

    private fun grey(c: Int): Int {   // transparent pixels count as white
        val a = Color.alpha(c)
        val g = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        return (g * a + 255 * (255 - a)) / 255
    }

    /** Difference hash of one image, null when it cannot be decoded. */
    private fun sig(e: Endpoint, f: F): Sig? {
        val bytes = e.open(f.path).use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= 68 && o.outHeight / (s * 2) >= 64) s *= 2
        val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val sm = Bitmap.createScaledBitmap(bm, 68, 64, true)
        if (sm !== bm) bm.recycle()
        val px = IntArray(68 * 64)
        sm.getPixels(px, 0, 68, 0, 0, 68, 64); sm.recycle()
        val g = Array(16) { y -> IntArray(17) { x ->
            var sum = 0
            for (dy in 0 until 4) for (dx in 0 until 4) sum += grey(px[(y * 4 + dy) * 68 + x * 4 + dx])
            sum / 16 } }
        val bits = LongArray(4)
        for (y in 0 until 16) for (x in 0 until 16) if (g[y][x] > g[y][x + 1]) { val i = y * 16 + x; bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63)) }
        return Sig(f.path, f.size, o.outWidth, o.outHeight, bits)
    }

    private fun near(a: Sig, b: Sig): Boolean {
        // about the same shape (resized copies keep it) ...
        if (Math.abs(a.w.toLong() * b.h - b.w.toLong() * a.h) > 0.04 * a.w.toDouble() * b.h) return false
        var d = 0
        for (i in 0 until 4) d += java.lang.Long.bitCount(a.b[i] xor b.b[i])
        return d <= SIM_BITS
    }

    /**
     * Album merge: do two files of this phone show the same thing? "same" = byte-identical, "similar" = the same picture in another size / quality
     * (difference hash, like "Similar images"), null = different pictures (or videos / files that only share a name). Never throws.
     */
    fun look(e: Endpoint, a: String, sa: Long, b: String, sb: Long): String? {
        try {
            if (sa == sb && sa > 0) {
                val dummy = Job("")
                if (hash(e, a, sa, dummy) == hash(e, b, sb, dummy)) return "same"
            }
            val ext = { p: String -> p.substringAfterLast('.', "").lowercase() }
            if (ext(a) in IMG_EXT && ext(b) in IMG_EXT && sa in 1..MAX_IMG && sb in 1..MAX_IMG) {
                val x = sig(e, F(a, sa)); val y = sig(e, F(b, sb))
                if (x != null && y != null && near(x, y)) return "similar"
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * "Copy / move only if not similar": a per-job index of the pictures already in the destination folder(s). [match] says whether a source file
     * is already there - a picture that looks the same (difference hash, other name / size / quality) or, for videos, other files and undecodable
     * pictures, a byte-identical file. Destination hashes are computed once and cached for the whole job; [added] registers files the job just wrote.
     */
    class SimIndex(private val dst: Endpoint) {
        private class Ent(val path: String, val size: Long) { var sig: Sig? = null; var bad = false }
        private val dirs = HashMap<String, ArrayList<Ent>>()
        private fun ext(p: String) = p.substringAfterLast('.', "").lowercase()
        private fun ents(dir: String): ArrayList<Ent> = dirs.getOrPut(dir) {
            val l = ArrayList<Ent>()
            try { for (it in dst.lsFast(dir)) if (!it.dir && it.size > 0 && !it.name.startsWith(".")) l.add(Ent(vjoin(dir, it.name), it.size)) }
            catch (x: Cancelled) { throw x } catch (_: Exception) {}
            l
        }
        fun added(dir: String, path: String, size: Long) { dirs[dir]?.add(Ent(path, size)) }
        fun match(src: Endpoint, sp: String, size: Long, dir: String, job: Job): Boolean {
            try {
                val list = ents(dir)
                if (list.isEmpty() || size <= 0) return false
                val img = ext(sp) in IMG_EXT && size <= MAX_IMG
                val ss = if (img) sig(src, F(sp, size)) else null
                if (ss != null) {
                    for (d in list) {
                        if (job.cancel) throw Cancelled()
                        if (d.bad || ext(d.path) !in IMG_EXT || d.size > MAX_IMG) continue
                        if (d.sig == null) { d.sig = try { sig(dst, F(d.path, d.size)) } catch (x: Cancelled) { throw x } catch (_: Throwable) { null }; if (d.sig == null) d.bad = true }
                        val ds = d.sig
                        if (ds != null && near(ss, ds)) return true
                    }
                } else {   // not a decodable picture: byte-identical file of the same size
                    var sh: String? = null
                    for (d in list) {
                        if (job.cancel) throw Cancelled()
                        if (d.size != size) continue
                        val dummy = Job("")
                        if (sh == null) sh = hash(src, sp, size, dummy)
                        if (hash(dst, d.path, d.size, dummy) == sh) return true
                    }
                }
            } catch (x: Cancelled) { throw x } catch (_: Throwable) {}
            return false
        }
    }

    private fun similarPass(job: Job, e: Endpoint, files: List<F>) {
        val imgs = files.filter { it.size in 1..MAX_IMG && it.path.substringAfterLast('.', "").lowercase() in IMG_EXT }
        job.bytes = false; job.total = maxOf(imgs.size, 1).toLong(); job.done = 0
        val sigs = arrayOfNulls<Sig>(imgs.size)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "sim").also { it.isDaemon = true } }
        val cnt = java.util.concurrent.atomic.AtomicInteger()
        try {
            val fut = imgs.mapIndexed { i, f ->
                pool.submit(Runnable {
                    if (!job.cancel) {
                        try { sigs[i] = sig(e, f) } catch (_: Throwable) {}
                        job.done = cnt.incrementAndGet().toLong()
                        job.label = "Reading pictures ${cnt.get()}/${imgs.size}: ${vbase(f.path)}"
                    }
                })
            }
            for (x in fut) { x.get(); if (job.cancel) throw Cancelled() }
        } finally { pool.shutdownNow() }
        if (job.cancel) throw Cancelled()
        val all = sigs.filterNotNull()
        job.label = "Comparing ${all.size} pictures"
        val parent = IntArray(all.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        // every pair within 20 of 256 bits shares at least one of the 32 hash bytes: only pictures sharing a byte are compared
        for (chunk in 0 until 32) {
            if (job.cancel) throw Cancelled()
            val buckets = HashMap<Int, MutableList<Int>>()
            val word = chunk ushr 3; val shift = (chunk and 7) * 8
            for (i in all.indices) buckets.getOrPut(((all[i].b[word] ushr shift) and 0xFF).toInt()) { ArrayList() }.add(i)
            for (l in buckets.values) {
                if (l.size < 2 || l.size > MAX_BUCKET) continue
                for (x in 0 until l.size) for (y in x + 1 until l.size) {
                    val a = l[x]; val b = l[y]
                    if (find(a) != find(b) && near(all[a], all[b])) parent[find(a)] = find(b)
                }
            }
        }
        val byRoot = HashMap<Int, MutableList<Sig>>()
        for (i in all.indices) byRoot.getOrPut(find(i)) { ArrayList() }.add(all[i])
        val groups = byRoot.values.filter { it.size > 1 }.map { g -> g.sortedWith(compareBy<Sig>({ -(it.w.toLong() * it.h) }, { -it.size }, { it.path })) }
            .sortedByDescending { g -> g.drop(1).sumOf { it.size } }
        val arr = JSONArray()
        var wasted = 0L; var extra = 0
        for (g in groups) {
            wasted += g.drop(1).sumOf { it.size }; extra += g.size - 1
            if (arr.length() < MAX_GROUPS) arr.put(JSONObject().put("size", g[0].size)
                .put("files", JSONArray(g.map { it.path })).put("sizes", JSONArray(g.map { it.size })).put("dims", JSONArray(g.map { "${it.w}x${it.h}" })))
        }
        job.result = JSONObject().put("groups", arr).put("total", groups.size).put("extra", extra).put("wasted", wasted).put("scanned", imgs.size).put("similar", true)
        job.done = job.total
        job.note = if (groups.isEmpty()) "No similar images found (${imgs.size} pictures checked)" else "$extra similar pictures in ${groups.size} groups, ${fmtBytes(wasted)} can be freed"
        job.state = "done"
    }

    private fun fmtBytes(n: Long): String {
        var v = n.toDouble(); var i = 0; val u = arrayOf("B", "KB", "MB", "GB", "TB")
        while (v >= 1024 && i < 4) { v /= 1024; i++ }
        return (if (i > 0) String.format(java.util.Locale.US, "%.1f", v) else n.toString()) + " " + u[i]
    }
}
