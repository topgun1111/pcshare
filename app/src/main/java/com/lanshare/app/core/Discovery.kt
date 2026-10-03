package com.lanshare.app.core

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Peer(val id: String, var ip: String, var port: Int, var name: String, var seen: Long, var ips: List<String>, var ok: Boolean) {
    fun snapshot() = Peer(id, ip, port, name, seen, ips, ok)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("ip", ip).put("port", port).put("name", name)
        .put("seen", seen / 1000.0).put("ips", JSONArray(ips)).put("ok", ok)
}

/** UDP beacons + unicast sweep + TCP scan + live checks. Same protocol as the Python version (devices interoperate). */
class Discovery(val port: Int) {
    private val tag = "LANShare/disc"
    private val lock = Any()
    private val peers = HashMap<String, Peer>()
    @Volatile var ifaces: List<Iface> = Net.ifaces()
    @Volatile var ownIps: Set<String> = ifaces.map { it.ip }.toSet()
    private val out = DatagramSocket().also { it.broadcast = true }
    private val wake = Semaphore(0)
    private val sweepBusy = AtomicBoolean(false)
    private val replied = ConcurrentHashMap<String, Long>()
    private val pool = daemonPool(48)
    private val probePool = daemonPool(8)   // own pool: live checks must never queue behind a subnet scan
    @Volatile private var gen = 0           // bump -> listen() re-creates its UDP socket
    @Volatile private var tick = System.currentTimeMillis()   // last beacon-loop heartbeat (detects phone sleep / unlock)

    private fun daemonPool(n: Int): ExecutorService =
        Executors.newFixedThreadPool(n) { r -> Thread(r).also { it.isDaemon = true } }

    fun hello(): JSONObject = JSONObject().put("app", "lanshare").put("id", Cfg.id).put("name", Cfg.name)
        .put("port", port).put("ips", JSONArray(ownIps.sorted()))

    private fun msg() = hello().toString().toByteArray(Charsets.UTF_8)

    fun start() {
        for ((n, fn) in listOf<Pair<String, () -> Unit>>("listen" to ::listen, "beacon" to ::beacon, "tcp" to ::tcpLoop, "live" to ::liveLoop))
            Thread({ fn() }, "disc-$n").also { it.isDaemon = true }.start()
    }

    private fun refreshIfaces() { ifaces = Net.ifaces(); ownIps = ifaces.map { it.ip }.toSet() }

    // ---- peer table
    fun add(pid: String, ip: String, port: Int, name: String, ips: List<String> = emptyList()): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            var p = peers[pid]
            val isNew = p == null
            if (p == null) { p = Peer(pid, ip, port, name.take(40), now, emptyList(), true); peers[pid] = p }
            else if (!p.ok) p.ip = ip   // last address failed - try the newest one
            p.port = port; p.name = name.take(40); p.seen = now
            val all = HashSet<String>(p.ips)
            all.add(ip)
            for (i in ips) if (Net.ipToInt(i) != null) all.add(i)
            all.removeAll(ownIps)
            p.ips = all.sorted().take(8)
            return isNew
        }
    }

    fun get(pid: String): Peer? = synchronized(lock) { peers[pid]?.snapshot() }

    fun setPeerIp(pid: String, ip: String) { synchronized(lock) { peers[pid]?.let { it.ip = ip } } }

    fun list(): List<Peer> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            peers.entries.removeAll { now - it.value.seen > PEER_TTL }
            return peers.values.map { it.snapshot() }.sortedBy { it.name.lowercase() }
        }
    }

    fun unicast(ip: String) {
        try {
            val d = msg()
            out.send(DatagramPacket(d, d.size, InetAddress.getByName(ip), BEACON_PORT))
        } catch (_: Exception) {}
    }

    // ---- UDP
    private fun openBeaconSocket(): DatagramSocket? {
        val s = try { DatagramSocket(null) } catch (_: Exception) { return null }
        return try {
            s.reuseAddress = true
            s.soTimeout = 3000
            s.bind(InetSocketAddress(BEACON_PORT))
            s
        } catch (e: Exception) { s.close(); null }
    }

    private fun listen() {
        while (true) {
            val g = gen
            val s = openBeaconSocket()
            if (s == null) {
                Log.w(tag, "cannot listen for beacons (TCP scan still works)")
                Thread.sleep(10_000); continue
            }
            val buf = ByteArray(4096)
            try {
                while (g == gen) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { s.receive(pk) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
                    try {
                        val ip = pk.address.hostAddress ?: continue
                        val m = JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8))
                        if (m.optString("app") != "lanshare" || m.getString("id") == Cfg.id || ip in ownIps) continue
                        val isNew = add(m.getString("id"), ip, m.getInt("port"), m.getString("name"), m.optJSONArray("ips").strings())
                        val now = System.currentTimeMillis()
                        if (isNew || now - (replied[ip] ?: 0L) > 6000) { replied[ip] = now; unicast(ip) }   // instant two-way discovery
                    } catch (_: Exception) {}
                }
            } finally { s.close() }
            Thread.sleep(1000)
        }
    }

    private fun beacon() {
        var n = 0
        while (true) {
            try {
                val now = System.currentTimeMillis()
                if (now - tick > 8000) resumed()   // we were frozen (screen lock / doze) -> network state is stale
                tick = now
                if (n % 5 == 0) refreshIfaces()
                announce()
                if (n < 4 || n % 5 == 0) sweep()
            } catch (_: Exception) {}
            n++
            Thread.sleep(2000)
        }
    }

    /** After the process was suspended: rebuild sockets/addresses and rescan a few times (Wi-Fi needs seconds to come back). */
    private fun resumed() {
        gen++
        refreshIfaces()
        val now = System.currentTimeMillis()
        synchronized(lock) { peers.values.forEach { it.seen = now; it.ok = false } }   // let liveLoop re-verify instead of expiring them
        Thread {
            repeat(6) {
                try { refreshIfaces(); scanNow() } catch (_: Exception) {}
                Thread.sleep(3000)
            }
        }.also { it.isDaemon = true }.start()
    }

    fun announce() {
        val data = msg()
        var sent = false
        for (f in ifaces) {   // one socket per interface so the packet leaves on that interface
            for (t in listOf(Net.intToIp(f.net.broadcast), "255.255.255.255")) {
                try {
                    DatagramSocket(null).use { s ->
                        s.broadcast = true
                        try { s.bind(InetSocketAddress(InetAddress.getByName(f.ip), 0)) } catch (_: Exception) {}
                        s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), BEACON_PORT))
                        sent = true
                    }
                } catch (_: Exception) {}
            }
        }
        if (!sent) {
            for (t in setOf("255.255.255.255") + ifaces.map { Net.intToIp(it.net.broadcast) }) {
                try { out.send(DatagramPacket(data, data.size, InetAddress.getByName(t), BEACON_PORT)) } catch (_: Exception) {}
            }
        }
    }

    private fun candidates(): List<String> {
        val nets = ifaces.map { it.net }.toMutableList()
        val hosts = ArrayList<String>()
        val seen = HashSet<String>(ownIps)
        fun put(h: String) { if (seen.add(h)) hosts.add(h) }
        for (ip in Net.arpNeighbors()) {
            put(ip)
            Net.ipToInt(ip)?.let { val c = Cidr.of(it, 24); if (c !in nets) nets.add(c) }
        }
        for (net in nets) for (h in net.hosts()) put(h)
        return hosts.take(1100)
    }

    /** UDP unicast to every host - works even when the router/hotspot drops broadcasts. */
    private fun sweep() { for (h in candidates()) unicast(h) }

    // ---- TCP scan (most reliable path: plain HTTP hello on each host)
    private fun tcpProbe(ip: String): Boolean {
        for (p in BASE_PORT until BASE_PORT + 5) {
            val s = Net.newSocket(ip)
            var connected = false
            try {
                s.connect(InetSocketAddress(ip, p), 700)
                connected = true
            } catch (e: ConnectException) {
                val m = e.message ?: ""
                if (!(m.contains("refused", true) || m.contains("ECONNREFUSED"))) return false   // unreachable: nobody home
            } catch (_: Exception) {
                return false                                                                     // timeout etc.
            } finally { try { s.close() } catch (_: Exception) {} }
            if (connected) {
                try {
                    val m = Http.hello(ip, p, 2500)
                    if (m.optString("app") == "lanshare" && m.getString("id") != Cfg.id) {
                        add(m.getString("id"), ip, m.getInt("port"), m.getString("name"), m.optJSONArray("ips").strings())
                        return true
                    }
                } catch (_: Exception) {}
            }
        }
        return false
    }

    private fun tcpSweep() {
        if (!sweepBusy.compareAndSet(false, true)) return
        try {
            val known = list().map { it.ip }.toSet()
            pool.invokeAll(candidates().filter { it !in known }.map { h -> Callable { tcpProbe(h) } })
        } finally { sweepBusy.set(false) }
    }

    private fun tcpLoop() {
        var k = 0
        while (true) {
            try { tcpSweep() } catch (_: Exception) {}
            k++
            val empty = synchronized(lock) { peers.isEmpty() }
            val wait = if (k < 6) 3L else if (empty) 8L else 30L
            wake.tryAcquire(wait, TimeUnit.SECONDS)
            wake.drainPermits()
        }
    }

    fun scanNow() {
        announce()
        Thread { try { sweep() } catch (_: Exception) {} }.also { it.isDaemon = true }.start()
        wake.release()
    }

    // ---- keep known peers alive / fix their address
    private fun probe(peer: Peer) {
        val cands = listOf(peer.ip) + peer.ips.filter { it != peer.ip }
        for (ip in cands.take(5)) {
            try {
                val m = Http.hello(ip, peer.port, 2500)
                if (m.optString("id") == peer.id) {
                    add(peer.id, ip, m.getInt("port"), m.getString("name"), m.optJSONArray("ips").strings())
                    synchronized(lock) { peers[peer.id]?.let { it.ip = ip; it.ok = true } }
                    return
                }
            } catch (_: Exception) { continue }
        }
        synchronized(lock) { peers[peer.id]?.let { it.ok = false } }
    }

    private fun liveLoop() {
        while (true) {
            Thread.sleep(3000)
            try { probePool.invokeAll(list().map { p -> Callable { probe(p) } }) } catch (_: Exception) {}
        }
    }

    /** "+ IP" in the UI: ip or ip:port. Returns false when nothing answers. */
    fun addIp(input: String): Boolean {
        var ip = input.trim()
        var port0: Int? = null
        if (ip.count { it == ':' } == 1) {
            val i = ip.indexOf(':')
            port0 = ip.substring(i + 1).toIntOrNull() ?: throw BadReq("invalid port")
            ip = ip.substring(0, i)
        }
        if (Net.ipToInt(ip) == null) throw BadReq("invalid IP address")
        for (p in (if (port0 != null) listOf(port0) else (BASE_PORT until BASE_PORT + 20).toList())) {
            try {
                val m = Http.hello(ip, p, 2000)
                if (m.optString("app") == "lanshare" && m.getString("id") != Cfg.id) {
                    add(m.getString("id"), ip, m.getInt("port"), m.getString("name"), m.optJSONArray("ips").strings())
                    unicast(ip)
                    return true
                }
            } catch (_: Exception) { continue }
        }
        return false
    }

    companion object { const val PEER_TTL = 60_000L }
}
