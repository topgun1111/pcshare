package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * "Free up space" scan (HANDOVER 12.10): one background walk of the storage root that lists what is usually safe to remove.
 * Nothing is deleted here: the page deletes the chosen paths through the normal `POST /api/rm` (-> Recycle bin).
 *  - big: files >= 100 MB (top 30)   - old: files in /Download not changed for 90 days (top 100)   - apk: installer files (top 50)
 *  - junk: temp / log / backup / unfinished-download files older than 3 days, `.trashed-*`, Thumbs.db ... (top 100)
 *  - shots: screenshots + screen recordings older than 30 days (top 100)
 *  - chat: WhatsApp / Telegram media older than 90 days and >= 256 KB, also under Android/media (top 100)
 *  - reclaim: bytes of junk + old + apk + shots + chat, every file counted once (big files are NOT in it)
 *  - appcache: LANShare's own regenerable caches (thumbnails, FTP listings, temp copies) as {count, bytes, groups[]}; cleared by `POST /api/cleanup {op: clearcache}`
 *  - bin: Recycle bin items + bytes   - free / total of the storage volume
 * `GET /api/cleanup` -> {running, scanned, error, result}; `POST /api/cleanup {op: start | cancel}`.
 */
object Cleanup {
    private const val BIG = 100L * 1024 * 1024
    private const val OLD_MS = 90L * 24 * 3600 * 1000
    private const val OLD_MIN = 1L * 1024 * 1024
    private const val DAY = 24L * 3600 * 1000
    private const val CHAT_MIN = 256L * 1024
    private const val HOUR = 3600L * 1000
    private val APK = setOf("apk", "xapk", "apks", "apkm")
    private val JUNK_EXT = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "crdownload", "download", "opdownload")
    private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store", ".nomedia.bak")
    private val JUNK_DIRS = setOf(".cache", ".tmp", ".temp")   // gallery thumbnail folders (.thumbnails ...) are deliberately NOT here: they may hold the only copy of a photo
    private val SHOT_DIRS = setOf("screenshots", "screenshot", "ekran görüntüleri", "ekran goruntuleri", "screen recordings", "screenrecorder", "screen_recorder", "ekran kayıtları")
    private val CHAT_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "mp4", "3gp", "mkv", "opus", "m4a", "mp3", "aac", "ogg", "amr", "pdf", "docx", "xlsx", "pptx", "zip")
    private val CHAT_APPS = listOf("com.whatsapp", "org.telegram", "org.thunderdog")

    @Volatile private var running = false
    @Volatile private var cancel = false
    @Volatile private var scanned = 0
    @Volatile private var err: String? = null
    @Volatile private var result: JSONObject? = null

    private class F(val path: String, val size: Long, val m: Long, val dir: Boolean = false)

    /** one result list: exact count / bytes, only the biggest [cap] kept in memory */
    private class Cat(val cap: Int) {
        var n = 0; var b = 0L; var l = ArrayList<F>()
        fun add(f: F) {
            n++; b += f.size; l.add(f)
            if (l.size > cap * 5) l = ArrayList(l.sortedByDescending { it.size }.take(cap))   // keep memory small
        }
        fun json(): JSONObject = JSONObject().put("count", n).put("bytes", b).put("items", JSONArray().also { a ->
            l.sortedByDescending { it.size }.take(cap).forEach { a.put(JSONObject().put("path", it.path).put("size", it.size).put("m", it.m).put("d", it.dir)) } })
    }

    fun status(): JSONObject = JSONObject().put("running", running).put("scanned", scanned)
        .put("error", err ?: JSONObject.NULL).put("result", result ?: JSONObject.NULL)

    fun cancel() { cancel = true }

    // ---- LANShare's own caches: everything here is rebuilt on demand, nothing is user data ----
    private class CGroup(val key: String, val label: String, val roots: List<File>, val minAgeMs: Long)

    private fun cacheGroups(): List<CGroup> {
        val g = ArrayList<CGroup>()
        val fd = Cfg.dir
        if (fd != null) {
            g.add(CGroup("thumbs", "Picture and video thumbnails", listOf("thumbcache", "vthumbcache", "rthumbcache").map { File(fd, it) }, 0L))
            g.add(CGroup("ftp", "Remote folder listings (FTP / SFTP)", listOf(File(fd, "ftpcache")), 0L))
        }
        val cd = try { Core.appCtx?.cacheDir } catch (_: Throwable) { null }
        if (cd != null) {
            // only our own temp folders (never the WebView cache); files younger than an hour may still be in use
            val names = setOf("office", "office-undo", "open", "subs", "print", "pdf", "pdfc", "img", "ocr", "sscan", "arc")
            val roots = (cd.listFiles() ?: emptyArray()).filter { it.name in names || it.name.startsWith("new-") }
            g.add(CGroup("temp", "Temporary copies and scan caches", roots, HOUR))
        }
        return g
    }

    private fun appCacheJson(): JSONObject {
        val now = System.currentTimeMillis()
        var n = 0; var b = 0L
        val arr = JSONArray()
        for (c in cacheGroups()) {
            var gn = 0; var gb = 0L
            for (r in c.roots) try { r.walkTopDown().forEach { f -> if (f.isFile && now - f.lastModified() >= c.minAgeMs) { gn++; gb += f.length() } } } catch (_: Exception) {}
            n += gn; b += gb
            arr.put(JSONObject().put("key", c.key).put("label", c.label).put("count", gn).put("bytes", gb))
        }
        return JSONObject().put("count", n).put("bytes", b).put("groups", arr)
    }

    /** Deletes the caches above; returns {freed, appcache} (appcache = the sizes that are left). */
    fun clearAppCache(): JSONObject {
        val before = appCacheJson().optLong("bytes")
        val now = System.currentTimeMillis()
        for (c in cacheGroups()) {
            if (c.key == "ftp") try { FtpCache.clearAll() } catch (_: Throwable) {}   // memory + disk
            for (r in c.roots) try {
                r.walkBottomUp().forEach { f ->
                    if (f.isFile) { if (now - f.lastModified() >= c.minAgeMs) f.delete() }
                    else if (f != r) f.delete()   // a folder only goes when it is empty
                }
            } catch (_: Exception) {}
        }
        val after = appCacheJson()
        return JSONObject().put("freed", maxOf(0L, before - after.optLong("bytes"))).put("appcache", after)
    }

    fun start() {
        synchronized(this) {
            if (running) return
            running = true; cancel = false; err = null; scanned = 0
        }
        Thread({
            try { result = scan() } catch (e: Throwable) { err = errText(e) } finally { running = false }
        }, "cleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }

    private fun scan(): JSONObject {
        val root = Core.local.root
        val rp = root.path.trimEnd('/')
        val now = System.currentTimeMillis()
        val big = Cat(30); val old = Cat(100); val apk = Cat(50); val junk = Cat(100); val shots = Cat(100); val chat = Cat(100)
        var reclaimN = 0; var reclaimB = 0L
        val budget = intArrayOf(300000)   // directories

        fun file(r: String, f: File, sz: Long, m: Long, inDl: Boolean, inShot: Boolean, inChat: Boolean, inJunk: Boolean, hidden: Boolean) {
            val ext = f.extension.lowercase(Locale.ROOT); val low = f.name.lowercase(Locale.ROOT)
            val fl = F(r, sz, m)
            var hit = false
            if (inJunk || low in JUNK_NAMES || low.startsWith(".trashed-") || low.startsWith("~$") || low.endsWith("~") || low.startsWith("._") ||
                (ext in JUNK_EXT && now - m > 3 * DAY)) { junk.add(fl); hit = true }
            if (hidden) { if (hit) { reclaimN++; reclaimB += sz }; return }     // hidden files only count as junk
            if (sz >= BIG) big.add(fl)
            if (ext in APK) { apk.add(fl); hit = true }
            if (inDl && sz >= OLD_MIN && now - m > OLD_MS) { old.add(fl); hit = true }
            if (inShot && now - m > 30 * DAY && (ext in CHAT_EXT || ext == "mov")) { shots.add(fl); hit = true }
            if (inChat && sz >= CHAT_MIN && now - m > OLD_MS && ext in CHAT_EXT && !hit) { chat.add(fl); hit = true }
            if (hit) { reclaimN++; reclaimB += sz }
        }

        /** walks one folder; the result is unused now that empty folders are no longer reported */
        fun walk(d: File, rel: String, inShotP: Boolean, inChatP: Boolean, inJunkP: Boolean): Boolean {
            if (budget[0]-- <= 0 || cancel) return false
            val fs = d.listFiles() ?: return false
            val inDl = rel.lowercase(Locale.ROOT).let { it == "/download" || it.startsWith("/download/") || it == "/downloads" || it.startsWith("/downloads/") }
            for (f in fs) {
                if (cancel) return false
                val hidden = f.name.startsWith(".")
                val r = "$rel/${f.name}"
                val low = f.name.lowercase(Locale.ROOT)
                if (f.isDirectory) {
                    if (hidden && !inJunkP && low !in JUNK_DIRS) { continue }
                    if (rel.isEmpty() && f.name == "Android") {
                        // only the chat apps' media folders; the rest of Android/ is app-private
                        File(f, "media").listFiles()?.filter { it.isDirectory && CHAT_APPS.any { a -> it.name.startsWith(a) } }
                            ?.forEach { walk(it, "/Android/media/${it.name}", false, true, false) }
                        continue
                    }
                    val sh = inShotP || low in SHOT_DIRS
                    val ch = inChatP || low.contains("whatsapp") || low.contains("telegram")
                    val jk = inJunkP || low in JUNK_DIRS
                    walk(f, r, sh, ch, jk)
                } else {
                    scanned++
                    file(r, f, f.length(), f.lastModified(), inDl, inShotP, inChatP, inJunkP, hidden)
                }
            }
            return false
        }
        walk(File(rp), "", false, false, false)
        if (cancel) throw IllegalStateException("cancelled")
        var binN = 0; var binB = 0L
        try { for (e in Bin.list()) { binN++; binB += e.size } } catch (_: Exception) {}
        return JSONObject()
            .put("free", root.freeSpace).put("total", root.totalSpace)
            .put("bin", JSONObject().put("items", binN).put("bytes", binB))
            .put("appcache", appCacheJson())
            .put("reclaim", JSONObject().put("count", reclaimN).put("bytes", reclaimB))
            .put("big", big.json()).put("old", old.json()).put("apk", apk.json())
            .put("junk", junk.json()).put("shots", shots.json()).put("chat", chat.json())
    }
}
