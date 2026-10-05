package com.lanshare.app.core

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import org.json.JSONObject

/** IPv4 network, normalised (host bits cleared), so equal networks compare equal. */
data class Cidr(val network: Int, val prefix: Int) {
    val mask: Int get() = if (prefix == 0) 0 else -1 shl (32 - prefix)
    val broadcast: Int get() = network or mask.inv()
    fun contains(ip: Int) = (ip and mask) == network
    fun hosts(): Sequence<String> = sequence {
        var a = network + 1
        while (Integer.compareUnsigned(a, broadcast) < 0) { yield(Net.intToIp(a)); a++ }
    }
    override fun toString() = Net.intToIp(network) + "/" + prefix

    companion object {
        fun of(ip: Int, prefix: Int) = Cidr(ip and (if (prefix == 0) 0 else -1 shl (32 - prefix)), prefix)
    }
}

data class Iface(val ip: String, val net: Cidr)

object Net {
    private val SKIP_IF = listOf("lo", "rmnet", "ccmni", "tun", "ppp", "dummy", "v4-", "clat", "docker", "veth")

    fun ipToInt(s: String): Int? {
        val p = s.trim().split(".")
        if (p.size != 4) return null
        var r = 0
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            r = (r shl 8) or n
        }
        return r
    }

    fun intToIp(i: Int) = "${(i ushr 24) and 255}.${(i ushr 16) and 255}.${(i ushr 8) and 255}.${i and 255}"

    fun ifaces(): List<Iface> {
        val found = LinkedHashMap<String, Int>()   // ip -> prefix length
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            if (en != null) for (ni in java.util.Collections.list(en)) {
                if (SKIP_IF.any { ni.name.startsWith(it) }) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address) found[a.hostAddress ?: continue] = ia.networkPrefixLength.toInt()
                }
            }
        } catch (_: Exception) {}
        if (found.isEmpty()) {   // last resort: ask the OS which local address it would use to reach typical gateways
            for (probe in listOf("192.168.43.1", "192.168.1.1", "10.0.0.1", "8.8.8.8")) {
                try {
                    DatagramSocket().use { s ->
                        s.connect(InetAddress.getByName(probe), 9)
                        s.localAddress.hostAddress?.let { found.putIfAbsent(it, 24) }
                    }
                } catch (_: Exception) {}
            }
        }
        val res = ArrayList<Iface>()
        for ((ip, pl) in found) {
            if (ip.startsWith("127.") || ip.startsWith("169.254.") || ip.startsWith("0.")) continue
            val n = ipToInt(ip) ?: continue
            res.add(Iface(ip, Cidr.of(n, if (pl < 22 || pl > 30) 24 else pl)))
        }
        return res
    }

    /** This phone's own Tailscale (100.64.0.0/10) addresses - the tun interface is skipped by ifaces(), but peers need to learn these. */
    fun tailscaleIps(): List<String> {
        return try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            java.util.Collections.list(en).flatMap { ni ->
                ni.interfaceAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress?.takeIf { ip -> viaOf(ip) == "Tailscale" } }
            }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    fun arpNeighbors(): List<String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] else null
        }
    } catch (_: Exception) { emptyList() }   // blocked on Android 10+: the sweep still works without it

    /** Our own address on the same subnet as [ip]. Binding outgoing sockets to it makes Android use Wi-Fi/hotspot, not a VPN. */
    fun srcFor(ip: String): String? {
        val a = ipToInt(ip) ?: return null
        return Core.discOrNull()?.ifaces?.firstOrNull { it.net.contains(a) }?.ip
    }

    fun newSocket(ip: String): Socket {
        val s = Socket()
        try { srcFor(ip)?.let { s.bind(InetSocketAddress(InetAddress.getByName(it), 0)) } } catch (_: Exception) {}
        return s
    }
}

// ------------------------------------------------------------------ tiny HTTP/1.1 client (peers only, Connection: close)
class HttpResp(val status: Int, val headers: Map<String, String>, private val sock: Socket, raw: InputStream) : Closeable {
    val contentLength: Long = headers["content-length"]?.trim()?.toLongOrNull() ?: -1L
    val body: InputStream = if (contentLength >= 0) LimitedIn(raw, contentLength) else raw

    fun readUpTo(max: Int): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (bo.size() < max) {
            val n = body.read(buf, 0, minOf(buf.size, max - bo.size()))
            if (n < 0) break
            bo.write(buf, 0, n)
        }
        return bo.toByteArray()
    }

    override fun close() { try { sock.close() } catch (_: Exception) {} }
}

object Http {
    fun request(ip: String, port: Int, method: String, path: String, headers: Map<String, String>, timeoutMs: Int,
                body: InputStream? = null, bodyLen: Long = 0, onSent: ((Int) -> Unit)? = null): HttpResp {
        val s = Net.newSocket(ip)
        try {
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val o = s.getOutputStream()
            val sb = StringBuilder("$method $path HTTP/1.1\r\nHost: $ip:$port\r\nConnection: close\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            if (body != null) sb.append("Content-Length: ").append(bodyLen).append("\r\n")
            sb.append("\r\n")
            o.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) {
                val buf = ByteArray(65536)
                var left = bodyLen
                while (left > 0) {
                    val n = body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("connection lost")
                    onSent?.invoke(n)
                    o.write(buf, 0, n)
                    left -= n
                }
            }
            o.flush()
            val ins = BufferedInputStream(s.getInputStream(), 65536)
            val status = (readLine(ins) ?: throw IOException("no response")).split(" ", limit = 3).getOrNull(1)?.toIntOrNull()
                ?: throw IOException("bad response")
            val hdrs = HashMap<String, String>()
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) hdrs[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            return HttpResp(status, hdrs, s, ins)
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    fun getJson(ip: String, port: Int, path: String, timeoutMs: Int): JSONObject {
        val r = request(ip, port, "GET", path, emptyMap(), timeoutMs)
        try {
            if (r.status != 200) throw IOException("HTTP ${r.status}")
            return JSONObject(String(r.readUpTo(1 shl 20), Charsets.UTF_8))
        } finally { r.close() }
    }

    fun hello(ip: String, port: Int, timeoutMs: Int = 2500) = getJson(ip, port, "/p/hello", timeoutMs)
}
