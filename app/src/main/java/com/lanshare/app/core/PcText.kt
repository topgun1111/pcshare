package com.lanshare.app.core

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PCs that run lanshare_text.py (completely separate from pcprint.py: own ports, nothing shared). They are not LANShare peers (no file access), so they are found here:
 *  1. the PC's own UDP beacon (port [TEXT_BEACON_PORT]; broadcast on Wi-Fi / LAN, unicast over Tailscale) -> listener thread, started by [start];
 *  2. every ~30 s (on demand, see [kick]) the addresses we already know - other devices, saved SMB hosts, pinned hosts, ARP neighbours - are asked
 *     `GET :[TEXT_PORT]/ping`, and a reply with `"clip": true` + `"app": "lanshare-text"` makes that host a text target.
 * Both ways end in a handshake: our `GET /ping?id=&name=&port=` tells the PC who this phone is, so the PC can send text back (also over Tailscale, where broadcasts do not travel).
 * A PC seen on a Tailscale address (100.64.0.0/10) is also pinned, so it is probed again later from any network.
 * They appear next to the phones as "<PC name> (PC)" with the id "pc:<PC id>" (usable as the default device, in Direct Share, the tile ...).
 */
object PcText {
    const val PREFIX = "pc:"
    const val TEXT_PORT = 48558          // lanshare_text.py receiver (TCP): GET /ping, POST /p/clip
    const val TEXT_BEACON_PORT = 48559   // lanshare_text.py beacon (UDP)
    private const val TTL = 100_000L
    private class Pc(val id: String, val ip: String, val port: Int, val name: String, val seen: Long)
    private val pcs = ConcurrentHashMap<String, Pc>()
    private val helloAt = ConcurrentHashMap<String, Long>()
    private val busy = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    @Volatile private var lastScan = 0L

    private fun peerOf(p: Pc) = Peer(PREFIX + p.id, p.ip, p.port, p.name + " (PC)", p.seen, listOf(p.ip), true)

    /** PCs that answered / beaconed within the last 100 s. */
    fun peers(): List<Peer> {
        val now = System.currentTimeMillis()
        return pcs.values.filter { now - it.seen < TTL }.sortedBy { it.name.lowercase() }.map { peerOf(it) }
    }

    /** A PC by its "pc:<id>" id (also when it was last seen a while ago: the send itself tells whether it is still there). An old "pc:<ip>" id still works. */
    fun get(id: String): Peer? {
        if (!id.startsWith(PREFIX)) return null
        val k = id.removePrefix(PREFIX)
        return (pcs[k] ?: pcs.values.firstOrNull { it.ip == k })?.let { peerOf(it) }
    }

    private fun register(ip: String, id: String, name: String, port: Int) {
        if (id.isBlank() || ip.isBlank()) return
        pcs[id] = Pc(id, ip, if (port in 1..65535) port else TEXT_PORT, name.ifBlank { ip }, System.currentTimeMillis())
        if (viaOf(ip) == "Tailscale") try { Cfg.addPin(ip) } catch (_: Exception) {}
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** `GET /ping` with this phone's id / name / port (the handshake). Registers the PC when it answers like lanshare_text.py. Throws when nothing answers. */
    private fun probe(ip: String, port: Int = TEXT_PORT, timeoutMs: Int = 1500) {
        val q = "id=" + enc(Cfg.id) + "&name=" + enc(Cfg.name) + "&port=" + (Core.discOrNull()?.port ?: 0)
        val r = Http.request(ip, port, "GET", "/ping?$q", emptyMap(), timeoutMs)
        try {
            if (r.status == 200) {
                val o = JSONObject(String(r.readUpTo(32768), Charsets.UTF_8))
                if (o.optBoolean("clip", false) && o.optString("app") == "lanshare-text")
                    register(ip, o.optString("id").ifBlank { ip }, o.optString("name"), port)
            }
        } finally { r.close() }
    }

    /** A beacon from a PC: it is a target at once; the handshake follows at most every 20 s per PC (in the background). */
    private fun onBeacon(ip: String, o: JSONObject) {
        val id = o.optString("id")
        if (id.isBlank()) return
        val port = o.optInt("port", TEXT_PORT)
        register(ip, id, o.optString("name"), port)
        val now = System.currentTimeMillis()
        if (now - (helloAt[id] ?: 0L) < 20_000) return
        helloAt[id] = now
        Thread { try { probe(ip, if (port in 1..65535) port else TEXT_PORT, 3000) } catch (_: Throwable) {} }.also { it.isDaemon = true }.start()
    }

    /** Listens for the PCs' beacons (idempotent; called when the core starts and on every [kick]). */
    fun start() {
        if (!listening.compareAndSet(false, true)) return
        Thread {
            while (true) {
                var s: DatagramSocket? = null
                try {
                    s = DatagramSocket(null)
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(TEXT_BEACON_PORT))
                    val buf = ByteArray(4096)
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        try {
                            val ip = p.address?.hostAddress
                            val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                            if (ip != null && o.optString("app") == "lanshare-text") onBeacon(ip, o)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                } finally { try { s?.close() } catch (_: Exception) {} }
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }   // port busy / network change: try again
            }
        }.also { it.isDaemon = true; it.name = "pc-text-beacons"; it.start() }
    }

    /** Starts a background look-around unless one ran in the last 30 s. Cheap to call often. */
    fun kick() {
        start()
        val now = System.currentTimeMillis()
        if (now - lastScan < 30_000 || !busy.compareAndSet(false, true)) return
        lastScan = now
        Thread { try { scan() } catch (_: Throwable) {} finally { busy.set(false) } }.also { it.isDaemon = true }.start()
    }

    private fun scan() {
        val ips = LinkedHashSet<String>()
        pcs.values.forEach { ips.add(it.ip) }
        val d = Core.discOrNull()
        if (d != null) for (p in d.list()) { ips.add(p.ip); ips.addAll(p.ips) }
        try { val a = Cfg.smb(); for (k in 0 until a.length()) a.optJSONObject(k)?.optString("host")?.takeIf { it.isNotEmpty() }?.let { ips.add(Smb.split(it).first) } } catch (_: Exception) {}
        try { ips.addAll(Cfg.pins()) } catch (_: Exception) {}
        try { ips.addAll(Net.arpNeighbors()) } catch (_: Exception) {}
        d?.ownIps?.let { ips.removeAll(it) }
        ips.removeAll { it.isBlank() }
        val pool = Executors.newFixedThreadPool(16) { r -> Thread(r).also { it.isDaemon = true } }
        try {
            for (ip in ips) pool.execute { try { probe(ip) } catch (_: Exception) {} }
        } finally {
            pool.shutdown()
            try { pool.awaitTermination(15, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
    }
}
