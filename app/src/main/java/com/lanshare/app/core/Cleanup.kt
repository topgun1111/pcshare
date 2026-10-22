package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * "Free up space" scan (HANDOVER 12.10): one background walk of the storage root that lists what is usually safe to remove.
 * Nothing is deleted here: the page deletes the chosen paths through the normal `POST /api/rm` (-> Recycle bin).
 *  - big: files >= 100 MB (top 30)   - old: files in /Download not changed for 90 days (top 100)   - apk: installer files (top 50)
 *  - junk: temp / log / backup / unfinished-download files older than 3 days, thumbnail caches (.thumbnails ...), `.trashed-*`, Thumbs.db ... (top 100)
 *  - empty: folders without any file (topmost only, standard folders kept) and 0-byte files (top 200)
 *  - shots: screenshots + screen recordings older than 30 days (top 100)
 *  - chat: WhatsApp / Telegram media older than 90 days and >= 256 KB, also under Android/media (top 100)
 *  - reclaim: bytes of junk + empty + old + apk + shots + chat, every file counted once (big files are NOT in it)
 *  - bin: Recycle bin items + bytes   - free / total of the storage volume
 * `GET /api/cleanup` -> {running, scanned, error, result}; `POST /api/cleanup {op: start | cancel}`.
 */
object Cleanup {
    private const val BIG = 100L * 1024 * 1024
    private const val OLD_MS = 90L * 24 * 3600 * 1000
    private const val OLD_MIN = 1L * 1024 * 1024
    private const val DAY = 24L * 3600 * 1000
    private const val CHAT_MIN = 256L * 1024
    private val APK = setOf("apk", "xapk", "apks", "apkm")
    private val JUNK_EXT = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "crdownload", "download", "opdownload")
    private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store", ".nomedia.bak")
    private val JUNK_DIRS = setOf(".thumbnails", ".thumbs", ".thumbcache", ".cache", ".tmp", ".temp")
    private val SHOT_DIRS = setOf("screenshots", "screenshot", "ekran görüntüleri", "ekran goruntuleri", "screen recordings", "screenrecorder", "screen_recorder", "ekran kayıtları")
    private val KEEP_DIRS = setOf("android", "dcim", "download", "downloads", "pictures", "movies", "music", "documents", "alarms", "notifications", "ringtones", "podcasts", "audiobooks", "recordings")
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
        val big = Cat(30); val old = Cat(100); val apk = Cat(50); val junk = Cat(100); val empty = Cat(200); val shots = Cat(100); val chat = Cat(100)
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
            if (sz == 0L && !hit) { empty.add(fl); hit = true }
            if (inDl && sz >= OLD_MIN && now - m > OLD_MS) { old.add(fl); hit = true }
            if (inShot && now - m > 30 * DAY && (ext in CHAT_EXT || ext == "mov")) { shots.add(fl); hit = true }
            if (inChat && sz >= CHAT_MIN && now - m > OLD_MS && ext in CHAT_EXT && !hit) { chat.add(fl); hit = true }
            if (hit) { reclaimN++; reclaimB += sz }
        }

        /** returns true when the folder holds no file at all (counts as "empty tree" for its parent) */
        fun walk(d: File, rel: String, inShotP: Boolean, inChatP: Boolean, inJunkP: Boolean): Boolean {
            if (budget[0]-- <= 0 || cancel) return false
            val fs = d.listFiles() ?: return false
            val inDl = rel.lowercase(Locale.ROOT).let { it == "/download" || it.startsWith("/download/") || it == "/downloads" || it.startsWith("/downloads/") }
            var nonEmpty = false
            val emptyKids = ArrayList<F>()
            for (f in fs) {
                if (cancel) return false
                val hidden = f.name.startsWith(".")
                val r = "$rel/${f.name}"
                val low = f.name.lowercase(Locale.ROOT)
                if (f.isDirectory) {
                    if (hidden && !inJunkP && low !in JUNK_DIRS) { nonEmpty = true; continue }
                    if (rel.isEmpty() && f.name == "Android") {
                        // only the chat apps' media folders; the rest of Android/ is app-private
                        nonEmpty = true
                        File(f, "media").listFiles()?.filter { it.isDirectory && CHAT_APPS.any { a -> it.name.startsWith(a) } }
                            ?.forEach { walk(it, "/Android/media/${it.name}", false, true, false) }
                        continue
                    }
                    val sh = inShotP || low in SHOT_DIRS
                    val ch = inChatP || low.contains("whatsapp") || low.contains("telegram")
                    val jk = inJunkP || low in JUNK_DIRS
                    if (walk(f, r, sh, ch, jk)) emptyKids.add(F(r, 0, f.lastModified(), true)) else nonEmpty = true
                } else {
                    nonEmpty = true
                    scanned++
                    file(r, f, f.length(), f.lastModified(), inDl, inShotP, inChatP, inJunkP, hidden)
                }
            }
            if (cancel) return false
            if (!nonEmpty) return true   // the parent reports the topmost empty folder
            // folder has content: its empty children are reported (standard top-level folders are kept)
            for (k in emptyKids) if (!(rel.isEmpty() && k.path.trimStart('/').lowercase(Locale.ROOT) in KEEP_DIRS)) empty.add(k)
            return false
        }
        walk(File(rp), "", false, false, false)
        if (cancel) throw IllegalStateException("cancelled")
        var binN = 0; var binB = 0L
        try { for (e in Bin.list()) { binN++; binB += e.size } } catch (_: Exception) {}
        return JSONObject()
            .put("free", root.freeSpace).put("total", root.totalSpace)
            .put("bin", JSONObject().put("items", binN).put("bytes", binB))
            .put("reclaim", JSONObject().put("count", reclaimN).put("bytes", reclaimB))
            .put("big", big.json()).put("old", old.json()).put("apk", apk.json())
            .put("junk", junk.json()).put("empty", empty.json()).put("shots", shots.json()).put("chat", chat.json())
    }
}
