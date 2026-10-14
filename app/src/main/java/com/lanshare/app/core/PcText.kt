package com.lanshare.app.core

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PCs that run pcprint.py v26+ also receive text: its print port (8799) answers `/p/clip` like a phone does. They are not LANShare peers (no
 * beacons, no file access), so they are found here: every ~30 s (on demand, see [kick]) the addresses we already know - other devices, saved
 * SMB hosts, pinned hosts, ARP neighbours - are asked `GET /ping`, and a reply with `"clip": true` makes that host a text target.
 * They appear next to the phones as "<PC name> (PC)" with the id "pc:<ip>" (usable as the default device, in Direct Share, the tile ...).
 */
object PcText {
    const val PREFIX = "pc:"
    private const val TTL = 100_000L
    private class Pc(val ip: String, val name: String, val seen: Long)
    private val pcs = ConcurrentHashMap<String, Pc>()
    private val busy = AtomicBoolean(false)
    @Volatile private var lastScan = 0L

    private fun peerOf(p: Pc) = Peer(PREFIX + p.ip, p.ip, Jobs.PRINT_PORT, p.name + " (PC)", p.seen, listOf(p.ip), true)

    /** PCs that answered within the last 100 s. */
    fun peers(): List<Peer> {
        val now = System.currentTimeMillis()
        return pcs.values.filter { now - it.seen < TTL }.sortedBy { it.name.lowercase() }.map { peerOf(it) }
    }

    /** A PC by its "pc:<ip>" id (also when it was last seen a while ago: the send itself tells whether it is still there). */
    fun get(id: String): Peer? = if (id.startsWith(PREFIX)) pcs[id.removePrefix(PREFIX)]?.let { peerOf(it) } else null

    /** Starts a background look-around unless one ran in the last 30 s. Cheap to call often. */
    fun kick() {
        val now = System.currentTimeMillis()
        if (now - lastScan < 30_000 || !busy.compareAndSet(false, true)) return
        lastScan = now
        Thread { try { scan() } catch (_: Throwable) {} finally { busy.set(false) } }.also { it.isDaemon = true }.start()
    }

    private fun scan() {
        val ips = LinkedHashSet<String>()
        ips.addAll(pcs.keys)
        val d = Core.discOrNull()
        if (d != null) for (p in d.list()) { ips.add(p.ip); ips.addAll(p.ips) }
        try { val a = Cfg.smb(); for (k in 0 until a.length()) a.optJSONObject(k)?.optString("host")?.takeIf { it.isNotEmpty() }?.let { ips.add(Smb.split(it).first) } } catch (_: Exception) {}
        try { ips.addAll(Cfg.pins()) } catch (_: Exception) {}
        try { ips.addAll(Net.arpNeighbors()) } catch (_: Exception) {}
        d?.ownIps?.let { ips.removeAll(it) }
        ips.removeAll { it.isBlank() }
        val pool = Executors.newFixedThreadPool(16) { r -> Thread(r).also { it.isDaemon = true } }
        try {
            for (ip in ips.take(120)) pool.execute {
                try {
                    val r = Http.request(ip, Jobs.PRINT_PORT, "GET", "/ping", emptyMap(), 1500)
                    try {
                        if (r.status == 200) {
                            val o = JSONObject(String(r.readUpTo(32768), Charsets.UTF_8))
                            if (o.optBoolean("clip", false)) pcs[ip] = Pc(ip, o.optString("name").ifBlank { ip }, System.currentTimeMillis())
                        }
                    } finally { r.close() }
                } catch (_: Exception) {}
            }
        } finally {
            pool.shutdown()
            try { pool.awaitTermination(8, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
    }
}
