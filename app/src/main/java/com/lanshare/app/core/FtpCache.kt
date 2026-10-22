package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory folder-listing cache for FTP / FTPS servers (FTP speed-up, part 1).
 * LRU (300 folders / 60 000 entries in total); a listing is served without any network call while it is younger than [freshMs].
 * Every write on the server drops the folder, its parents (and the subtree for remove / rename / move) - see FtpFs.dirty.
 * [lock] serialises identical listings, so thumbnails / prefetch / the UI asking for the same folder cost one request.
 * Part 2: every listing is also saved to disk (`filesDir/ftpcache/<server>.json`, debounced); [getStale] returns the newest known
 * listing of any age (memory first, then disk) so a folder can be painted at once and refreshed in the background (FtpFs.ls).
 * The disk copy follows every [drop] / [clear], so a write on the server never leaves a stale listing behind.
 */
object FtpCache {
    @Volatile var freshMs = 30_000L
    private const val MAX_DIRS = 300
    private const val MAX_ITEMS = 60_000
    private class E(val items: List<Item>, val at: Long)
    private val map = LinkedHashMap<String, E>(64, 0.75f, true)   // access order = LRU
    private var total = 0
    private val locks = ConcurrentHashMap<String, Any>()

    // ---- disk copy (part 2) ----
    private const val DISK_DIRS = 200          // per server
    private const val DISK_ITEMS = 40_000      // per server
    private const val MAX_AGE = 30L * 24 * 3600 * 1000
    @Volatile private var dir: File? = null
    private val disk = ConcurrentHashMap<String, LinkedHashMap<String, E>>()   // server id -> abs -> listing (loaded lazily)
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var saver = false

    fun init(filesDir: File) {
        dir = File(filesDir, "ftpcache").also { it.mkdirs() }
        cfg = File(filesDir, "ftpcache.cfg")
        try { cfg?.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { freshMs = it.coerceIn(0L, 3_600_000L) } } catch (_: Throwable) {}
    }

    // ---- part 5: TTL setting, counters, timing log (GET /api/ftpcache) ----
    private var cfg: File? = null
    val hits = java.util.concurrent.atomic.AtomicInteger()    // fresh cache hits
    val stale = java.util.concurrent.atomic.AtomicInteger()   // answered from an old listing (refreshed in the background)
    val miss = java.util.concurrent.atomic.AtomicInteger()    // real server listings
    private val tlog = ArrayDeque<String>()
    fun logTime(kind: String, path: String, ms: Long) = synchronized(tlog) {
        tlog.addLast(kind + " " + ms + " ms " + path); while (tlog.size > 40) tlog.removeFirst()
    }
    fun setTtlSec(sec: Int) { freshMs = (sec.coerceIn(0, 3600) * 1000L); try { cfg?.writeText(freshMs.toString()) } catch (_: Throwable) {} }
    fun statsJson(): JSONObject {
        val o = JSONObject()
        o.put("ttl", freshMs / 1000).put("hits", hits.get()).put("stale", stale.get()).put("miss", miss.get())
        synchronized(map) { o.put("folders", map.size).put("items", total) }
        var bytes = 0L; try { dir?.listFiles()?.forEach { bytes += it.length() } } catch (_: Throwable) {}
        o.put("disk", bytes)
        val a = JSONArray(); synchronized(tlog) { tlog.reversed().forEach { a.put(it) } }
        return o.put("log", a)
    }

    private fun fileOf(id: String): File? = dir?.let { File(it, java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { b -> "%02x".format(b) }.take(16) + ".json") }

    private fun diskOf(id: String): LinkedHashMap<String, E> = disk.getOrPut(id) {
        val m = LinkedHashMap<String, E>()
        try {
            val f = fileOf(id)
            if (f != null && f.isFile) {
                val o = JSONObject(f.readText()); val d = o.optJSONObject("d")
                val now = System.currentTimeMillis()
                if (o.optInt("v") == 1 && d != null) for (k in d.keys()) {
                    val e = d.getJSONObject(k); val at = e.optLong("at")
                    if (now - at > MAX_AGE) continue
                    val a = e.getJSONArray("i")
                    m[k] = E((0 until a.length()).map { Item.fromJson(a.getJSONObject(it)) }, at)
                }
            }
        } catch (_: Throwable) { m.clear() }   // unreadable copy = start empty, never a failed listing
        m
    }

    private fun touch(id: String) { dirty.add(id); scheduleSave() }

    private fun scheduleSave() {
        if (dir == null || saver) return
        saver = true
        Thread({
            try { Thread.sleep(4000) } catch (_: InterruptedException) {}
            saver = false
            val ids = dirty.toList(); dirty.removeAll(ids.toSet())
            for (id in ids) save(id)
        }, "ftpcache-save").also { it.isDaemon = true }.start()
    }

    private fun save(id: String) {
        try {
            val f = fileOf(id) ?: return
            val m = diskOf(id)
            val d = JSONObject()
            synchronized(m) { for ((k, e) in m) d.put(k, JSONObject().put("at", e.at).put("i", JSONArray(e.items.map { it.toJson() }))) }
            val t = File(f.path + ".tmp")
            t.writeText(JSONObject().put("v", 1).put("d", d).toString())
            if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
        } catch (_: Throwable) {}
    }

    private fun diskPut(id: String, abs: String, items: List<Item>) {
        if (dir == null) return
        val m = diskOf(id)
        synchronized(m) {
            m.remove(abs)
            if (items.size > DISK_ITEMS / 2) return
            m[abs] = E(items, System.currentTimeMillis())
            var n = m.values.sumOf { it.items.size }
            val it = m.entries.iterator()   // insertion order = oldest first
            while ((m.size > DISK_DIRS || n > DISK_ITEMS) && it.hasNext()) { val e = it.next(); n -= e.value.items.size; it.remove() }
        }
        touch(id)
    }

    private fun diskDrop(id: String, abs: String, tree: Boolean) {
        if (dir == null) return
        val m = diskOf(id); val kp = abs.trimEnd('/') + "/"
        var changed = false
        synchronized(m) {
            val it = m.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key == abs || (tree && e.key.startsWith(kp))) { it.remove(); changed = true } }
        }
        if (changed) touch(id)
    }

    /** Newest known listing of [abs] whatever its age (memory, then disk); null = never seen. NOT for name-clash checks. */
    fun getStale(id: String, abs: String): List<Item>? {
        synchronized(map) { map[key(id, abs)]?.let { return it.items } }
        if (dir == null) return null
        val m = diskOf(id)
        return synchronized(m) { m[abs]?.items }
    }

    /** Forgets everything saved (memory + disk) - for a "Clear FTP cache" button (part 5). */
    fun clearAll() {
        synchronized(map) { map.clear(); total = 0 }
        disk.clear(); dirty.clear(); hits.set(0); stale.set(0); miss.set(0); synchronized(tlog) { tlog.clear() }
        try { dir?.listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    private fun key(id: String, abs: String) = id + "\u0000" + abs

    fun get(id: String, abs: String): List<Item>? = synchronized(map) {
        val e = map[key(id, abs)]
        if (e == null || System.currentTimeMillis() - e.at > freshMs) null else e.items
    }

    /** The cached listing of [abs] only when it was stored at or after [since] (ms): lets a forced refresh reuse one that finished while it waited. */
    fun getSince(id: String, abs: String, since: Long): List<Item>? = synchronized(map) {
        val e = map[key(id, abs)]
        if (e == null || e.at < since) null else e.items
    }

    fun put(id: String, abs: String, items: List<Item>) = synchronized(map) {
        val k = key(id, abs)
        map.remove(k)?.let { total -= it.items.size }
        if (items.size > MAX_ITEMS / 2) return@synchronized   // one huge folder must not flush everything else
        map[k] = E(items, System.currentTimeMillis()); total += items.size
        val it = map.entries.iterator()
        while ((map.size > MAX_DIRS || total > MAX_ITEMS) && it.hasNext()) { val e = it.next(); total -= e.value.items.size; it.remove() }
        diskPut(id, abs, items)
    }

    /** Forgets [abs] (and everything below it when [tree]). */
    fun drop(id: String, abs: String, tree: Boolean) = synchronized(map) {
        val k = key(id, abs); val kp = k.trimEnd('/') + "/"
        val it = map.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key == k || (tree && e.key.startsWith(kp))) { total -= e.value.items.size; it.remove() } }
        diskDrop(id, abs, tree)
    }

    fun clear(id: String) = synchronized(map) {
        val p = id + "\u0000"
        val it = map.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key.startsWith(p)) { total -= e.value.items.size; it.remove() } }
        disk.remove(id); try { fileOf(id)?.delete() } catch (_: Throwable) {}; dirty.remove(id)
    }

    /** Part 4: folders one search / walk / folder-total may store in the cache (so a big tree cannot flush everything else). */
    class Fill(var left: Int = 60)

    /** Part 4: stores a listing read by search / walk / totals - unless a write on the server finished after [startedAt] (the answer may predate it) or [f] is used up. */
    fun fill(id: String, abs: String, items: List<Item>, startedAt: Long, dirtyAt: Long, f: Fill) {
        if (f.left <= 0 || dirtyAt >= startedAt) return
        f.left--
        put(id, abs, items)
    }

    fun lock(id: String, abs: String): Any { if (locks.size > 500) locks.clear(); return locks.getOrPut(key(id, abs)) { Any() } }
}
