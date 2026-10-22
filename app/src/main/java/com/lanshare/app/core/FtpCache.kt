package com.lanshare.app.core

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory folder-listing cache for FTP / FTPS servers (FTP speed-up, part 1).
 * LRU (300 folders / 60 000 entries in total); a listing is served without any network call while it is younger than [freshMs].
 * Every write on the server drops the folder, its parents (and the subtree for remove / rename / move) - see FtpFs.dirty.
 * [lock] serialises identical listings, so thumbnails / prefetch / the UI asking for the same folder cost one request.
 */
object FtpCache {
    @Volatile var freshMs = 30_000L
    private const val MAX_DIRS = 300
    private const val MAX_ITEMS = 60_000
    private class E(val items: List<Item>, val at: Long)
    private val map = LinkedHashMap<String, E>(64, 0.75f, true)   // access order = LRU
    private var total = 0
    private val locks = ConcurrentHashMap<String, Any>()

    private fun key(id: String, abs: String) = id + "\u0000" + abs

    fun get(id: String, abs: String): List<Item>? = synchronized(map) {
        val e = map[key(id, abs)]
        if (e == null || System.currentTimeMillis() - e.at > freshMs) null else e.items
    }

    fun put(id: String, abs: String, items: List<Item>) = synchronized(map) {
        val k = key(id, abs)
        map.remove(k)?.let { total -= it.items.size }
        if (items.size > MAX_ITEMS / 2) return@synchronized   // one huge folder must not flush everything else
        map[k] = E(items, System.currentTimeMillis()); total += items.size
        val it = map.entries.iterator()
        while ((map.size > MAX_DIRS || total > MAX_ITEMS) && it.hasNext()) { val e = it.next(); total -= e.value.items.size; it.remove() }
    }

    /** Forgets [abs] (and everything below it when [tree]). */
    fun drop(id: String, abs: String, tree: Boolean) = synchronized(map) {
        val k = key(id, abs); val kp = k.trimEnd('/') + "/"
        val it = map.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key == k || (tree && e.key.startsWith(kp))) { total -= e.value.items.size; it.remove() } }
    }

    fun clear(id: String) = synchronized(map) {
        val p = id + "\u0000"
        val it = map.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key.startsWith(p)) { total -= e.value.items.size; it.remove() } }
    }

    fun lock(id: String, abs: String): Any { if (locks.size > 500) locks.clear(); return locks.getOrPut(key(id, abs)) { Any() } }
}
