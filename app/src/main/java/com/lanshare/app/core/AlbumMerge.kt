package com.lanshare.app.core

import android.media.MediaScannerConnection
import java.util.UUID

/**
 * Albenleri birleştirme (Gallery > albümleri basılı tut > Merge): seçilen albümlerin dosyaları hedef albüme TAŞINIR.
 * Aynı isim hedefte varsa dosyalara bakılır ([DupFinder.look]):
 *  - görsel olarak farklı (aynı isim, başka resim)  -> sormadan yeniden adlandırılır ("IMG_1 (1).jpg")
 *  - skipSimilar: hedefte (herhangi bir isimle) görsel olarak aynı / benzer resim zaten varsa dosya taşınmaz, kendi albümünde kalır ([DupFinder.SimIndex])
 *  - aynı / benzer resim                             -> kullanıcıya sorulur (Replace / Skip / Keep both / Replace if newer, "hepsi için" seçeneğiyle)
 * Alt klasörler (kendi albümleri) dokunulmaz. Kaynak albüm ancak tamamen boşaldıysa silinir; atlanan / hatalı dosya varsa kalır.
 * Değiştirilen (replace) dosya geri dönüşüm kutusuna gider. Sonunda MediaStore taranır, albüm listesi güncel kalır.
 */
object AlbumMerge {
    fun start(target: String, sources: List<String>, skipSimilar: Boolean = false): String {
        val jid = UUID.randomUUID().toString().replace("-", "").take(8)
        Jobs.prune()
        val job = Job("Merging albums")
        job.bytes = false
        job.total = 1
        job.live = true
        Jobs.all[jid] = job
        Thread({ work(job, vnorm(target), sources.map { vnorm(it) }.distinct(), skipSimilar) }, "merge-$jid").also { it.isDaemon = true }.start()
        return jid
    }

    private fun work(job: Job, target: String, srcs0: List<String>, skipSimilar: Boolean) {
        val e = Core.local
        val touched = ArrayList<String>()
        var failCount = 0
        val fails = ArrayList<String>()
        fun fail(what: String, x: Throwable) { failCount++; job.failed = failCount; job.addProblem("$what: ${errText(x)}"); if (fails.size < 20) fails.add("$what: ${errText(x)}") }
        try {
            if (target == "/") throw BadReq("no target album")
            val srcs = srcs0.filter { it != target && it != "/" }
            if (srcs.isEmpty()) throw BadReq("select at least two albums")
            job.dest = e.name + " › " + target
            job.cur = "Reading the albums…"
            // hedefteki mevcut dosyalar (küçük / büyük harf fark etmez: telefon dosya sistemi büyük-küçük harfe duyarsız)
            val have = HashMap<String, Item>()
            for (it in e.lsFast(target)) have[it.name.lowercase()] = it
            val plan = ArrayList<Pair<String, List<Item>>>()
            for (s in srcs) {
                if (job.cancel) throw Cancelled()
                try { plan.add(s to e.lsFast(s).filter { !it.dir && !it.name.startsWith(".") }) } catch (x: Cancelled) { throw x } catch (x: Exception) { fail(vbase(s), x) }
            }
            job.total = maxOf(plan.sumOf { it.second.size }, 1).toLong()
            job.filesTotal = plan.sumOf { it.second.size }
            job.cur = ""
            var sticky: String? = null
            val sim = if (skipSimilar) DupFinder.SimIndex(e) else null   // "skip look-alikes": files whose picture is already in the target (any name) stay in their album
            var renamed = 0; var moved = 0; var removedDirs = 0
            for ((s, files) in plan) {
                var left = 0   // bu albümde yerinde kalan dosya sayısı (atlanan / hatalı)
                for (f in files) {
                    if (job.cancel) throw Cancelled()
                    job.begin(f.name, f.size)
                    val sp = vjoin(s, f.name)
                    try {
                        if (sim != null) {
                            job.cur = f.name
                            if (sim.match(e, sp, f.size, target, job)) { left++; job.files++; job.done++; job.addLog("S", f.name); continue }
                        }
                        val lc = f.name.lowercase()
                        val ex = have[lc]
                        var name = f.name
                        var replace = false
                        if (ex != null) {
                            val tp = vjoin(target, ex.name)
                            val look = DupFinder.look(e, sp, f.size, tp, ex.size)
                            if (job.cancel) throw Cancelled()
                            if (look == null) {
                                name = uniq(f.name, have)   // aynı isim ama farklı resim: yeniden adlandır
                                renamed++
                            } else {
                                var act: String? = sticky
                                if (act == null) {
                                    val q = org.json.JSONObject().put("name", vbase(s) + "/" + f.name).put("srcSize", f.size).put("dstSize", ex.size).put("dstMtime", ex.mtime)
                                        .put("srcMtime", f.mtime).put("sDev", "local").put("sPath", sp).put("dDev", "local").put("dPath", tp).put("look", look)
                                    act = job.waitAnswer(q)
                                    when (act) {
                                        "replace_all" -> { sticky = "replace"; act = "replace" }
                                        "skip_all" -> { sticky = "skip"; act = "skip" }
                                        "newer_all" -> { sticky = "newer"; act = "newer" }
                                        "both_all" -> { sticky = "both"; act = "both" }
                                    }
                                }
                                if (act == "newer") act = if (f.mtime > ex.mtime + 2) "replace" else "skip"
                                when (act) {
                                    "skip" -> { left++; job.files++; job.done++; job.addLog("S", f.name); continue }
                                    "both" -> { name = uniq(f.name, have); renamed++ }
                                    else -> replace = true
                                }
                            }
                        }
                        val dp = vjoin(target, name)
                        if (replace) Jobs.removeUser(e, vjoin(target, ex!!.name))   // eski dosya geri dönüşüm kutusuna
                        e.move(sp, dp)
                        have[name.lowercase()] = Item(name, false, f.size, f.mtime)
                        sim?.added(target, dp, f.size)
                        touched.add(dp); touched.add(sp); moved++
                        job.addLog(if (replace) "R" else "N", name)
                    } catch (x: Cancelled) { throw x
                    } catch (x: Exception) { left++; fail(f.name, x) }
                    job.files++; job.done++
                }
                // kaynak albüm tamamen boşaldıysa kaldır (içinde başka bir şey varsa dokunma)
                if (left == 0) try {
                    if (e.names(s).isEmpty()) { e.remove(s); removedDirs++ }
                } catch (x: Cancelled) { throw x } catch (_: Exception) {}
            }
            job.cur = ""
            try {   // MediaStore: taşınan / silinen yollar güncellensin
                val ctx = Core.appCtx
                val paths = touched.map { e.real(it).path }.distinct()
                if (ctx != null && paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null, null)
            } catch (_: Throwable) {}
            job.note = "Done - $moved moved" + (if (renamed > 0) ", $renamed renamed" else "") + (if (job.cR > 0) ", ${job.cR} replaced" else "") +
                (if (job.cS > 0) ", ${job.cS} skipped (kept in their album)" else "") + (if (removedDirs > 0) ", $removedDirs empty album${if (removedDirs > 1) "s" else ""} removed" else "")
            job.done = job.total
            if (failCount == 0) job.state = "done"
            else { job.error = if (failCount == 1) fails[0] else "$failCount problems - " + fails.take(3).joinToString("; ") + (if (failCount > 3) "; ..." else ""); job.state = "error" }
        } catch (x: Cancelled) { job.state = "cancel"
        } catch (x: Throwable) { job.error = errText(x); job.state = "error"
        } finally {
            if (job.state == "run") { job.state = "error"; if (job.error == null) job.error = "stopped unexpectedly" }
            job.end = System.currentTimeMillis()
        }
    }

    /** "name (1).ext", büyük-küçük harfe duyarsız; seçilen isim hemen "dolu" sayılır. */
    private fun uniq(name: String, have: Map<String, Item>): String {
        val (base, ext) = splitExt(name)
        var i = 1
        while ("$base ($i)$ext".lowercase() in have) i++
        return "$base ($i)$ext"
    }
}
