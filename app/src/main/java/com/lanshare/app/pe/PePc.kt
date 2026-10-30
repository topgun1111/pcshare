package com.lanshare.app.pe

import com.lanshare.app.core.Core
import com.lanshare.app.core.Http
import com.lanshare.app.core.errText
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Phone -> PC for "Phone Explorer, one tap": finds PCs running phone_explorer.py (UDP 41235) and asks one of them (HTTP 8798) to open Explorer on this phone's FTP share. */
object PePc {
    const val UDP_PORT = 41235
    class Pc(val ip: String, val name: String, val port: Int)

    /** PCs that answer a broadcast within [timeoutMs]. */
    fun find(timeoutMs: Int = 1800): List<Pc> {
        val found = LinkedHashMap<String, Pc>()
        val s = DatagramSocket()
        try {
            s.broadcast = true
            s.soTimeout = 300
            val msg = "PHONEEXPLORER_PC_DISCOVER".toByteArray()
            val targets = linkedSetOf("255.255.255.255")   // plus the /24 broadcast of every own address (some Wi-Fi setups drop the limited broadcast)
            try {
                Core.disc.ownIps.filter { it.count { c -> c == '.' } == 3 && !it.startsWith("127.") && !it.startsWith("100.") }
                    .forEach { targets.add(it.substringBeforeLast('.') + ".255") }
            } catch (_: Throwable) {}
            val t0 = System.currentTimeMillis()
            var last = 0L
            while (System.currentTimeMillis() - t0 < timeoutMs) {
                if (System.currentTimeMillis() - last > 700) {
                    for (t in targets) try { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(t), UDP_PORT)) } catch (_: Exception) {}
                    last = System.currentTimeMillis()
                }
                try {
                    val p = DatagramPacket(ByteArray(512), 512)
                    s.receive(p)
                    val o = JSONObject(String(p.data, 0, p.length, Charsets.UTF_8))
                    if (o.optString("app") == "PhoneExplorerPC") {
                        val ip = p.address.hostAddress ?: continue
                        found[ip] = Pc(ip, o.optString("name", "PC"), o.optInt("port", 8798))
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {}
            }
        } finally { try { s.close() } catch (_: Exception) {} }
        return found.values.toList()
    }

    /** The PC itself answered with an error (wrong password, remote commands off, job lost ...): shown as is, never retried. */
    class PcError(msg: String) : IOException(msg)

    private val vers = ConcurrentHashMap<String, Pair<Int, Long>>()   // "ip:port" -> (PC program version, when asked)

    private fun e(v: String) = URLEncoder.encode(v, "UTF-8")

    /** The address as typed ("  http://100.64.1.2:8798/ ", "pc.tail1234.ts.net.", "pc:9000") -> host and port. */
    fun target(addr: String, port: Int): Pair<String, Int> {
        var h = addr.trim().replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "")
        h = h.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').trim().trimEnd('.')
        var p = port
        val i = h.lastIndexOf(':')
        if (i > 0 && h.indexOf(':') == i) {   // exactly one ':' = host:port (an IPv6 literal has several)
            h.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 }?.let { p = it }
            h = h.substring(0, i)
        }
        if (h.isEmpty()) throw IOException("No PC address")
        return Pair(h, p)
    }

    /** True when the failure happened before any byte of the request could reach the PC, so sending it again cannot run it twice. */
    private fun connectFailure(x: Exception) = x is java.net.ConnectException || x is java.net.NoRouteToHostException ||
        x is UnknownHostException || (x is SocketTimeoutException && (x.message ?: "").contains("connect", true))

    /** "PC not reachable (...)": the text the screen retries on (ui.html), with the likely reason in plain words. */
    private fun unreachable(x: Exception): IOException {
        val m = errText(x); val l = m.lowercase()
        val why = when {
            x is UnknownHostException || "unable to resolve" in l -> "cannot find that PC by name - type its Tailscale address 100.x.x.x"
            "refused" in l -> "connection refused - Phone Explorer is not running on the PC, or its port is blocked"
            "abort" in l || "reset" in l || "broken pipe" in l || "closed" in l -> "the connection was cut ($m) - Wi-Fi or Tailscale switched networks"
            x is SocketTimeoutException || "timed out" in l || "unreachable" in l -> "no answer ($m) - is the PC on, and Tailscale connected on both?"
            else -> m
        }
        return IOException("PC not reachable ($why)")
    }

    /**
     * One POST to the PC -> (HTTP status, JSON body or null). Tries up to [tries] times with a short pause. A failed try is
     * repeated when [always] is set (the request is safe to repeat) or when it failed while connecting; otherwise it throws at once.
     * Each try opens a new socket, so a socket that Android destroyed (VPN / Wi-Fi change = "Software caused connection abort") is replaced.
     */
    private fun call(ip: String, port: Int, path: String, form: String?, soMs: Int, connectMs: Int, tries: Int, always: Boolean): Pair<Int, JSONObject?> {
        var last: Exception? = null
        for (k in 0 until tries) {
            if (k > 0) try { Thread.sleep(if (k == 1) 700L else 1800L) } catch (_: InterruptedException) { break }
            try {
                val bytes = form?.toByteArray(Charsets.UTF_8)
                val hdr = if (bytes == null) mapOf("Content-Length" to "0") else mapOf("Content-Type" to "application/x-www-form-urlencoded")
                val r = Http.request(ip, port, "POST", path, hdr, soMs, bytes?.let { ByteArrayInputStream(it) }, (bytes?.size ?: 0).toLong(), connectMs)
                try {
                    val txt = String(r.readUpTo(4 shl 20), Charsets.UTF_8)   // 64 KB of text can grow 6x as JSON escapes
                    return Pair(r.status, try { JSONObject(txt) } catch (_: Exception) { null })
                } finally { r.close() }
            } catch (x: Exception) {
                last = x
                if (!(always || connectFailure(x))) break
            }
        }
        throw last ?: IOException("no response")
    }

    /** Tells the PC to open Explorer on this phone (the PC takes the phone's address from the connection). Throws when it fails. */
    fun open(ip0: String, hport0: Int, port: Int, user: String, pin: String, name: String) {
        val (ip, hport) = target(ip0, hport0)
        val path = "/open?port=$port&user=${e(user)}&pin=${e(pin)}&name=${e(name)}"
        val (st, o) = try { call(ip, hport, path, null, 8000, 8000, 3, false) } catch (x: Exception) { throw unreachable(x) }
        if (st != 200) throw IOException(o?.optString("error", "")?.ifEmpty { null } ?: ("PC answered " + st))
    }

    /**
     * Result of a remote command run on the PC through /exec. [done] is false while the command still runs on the PC
     * (the screen then asks again with the same id and poll = true).
     */
    class ExecResult(val out: String, val err: String, val code: Int, val truncated: Boolean, val done: Boolean = true)

    private fun result(st: Int, o: JSONObject?): ExecResult {
        if (o == null) throw IOException("PC answered something unexpected (HTTP $st) - is another program using that port?")
        if (st != 200) throw PcError(o.optString("error", "PC answered $st"))
        return ExecResult(o.optString("out", ""), o.optString("err", ""), o.optInt("code", -1), o.optBoolean("truncated", false), o.optBoolean("done", true))
    }

    /** 2 when the PC program runs commands as jobs (this app polls them), 1 for the old one (one request that waits for the end). */
    private fun version(ip: String, port: Int): Int {
        val key = "$ip:$port"; val now = System.currentTimeMillis()
        vers[key]?.let { (v, t) -> if (now - t < (if (v >= 2) 600_000L else 60_000L)) return v }
        val (_, o) = try { call(ip, port, "/exec?probe=1", null, 10_000, 8_000, 3, true) } catch (x: Exception) { throw unreachable(x) }
        val v = o?.optInt("v", 1) ?: 1
        vers[key] = Pair(v, now)
        return v
    }

    /**
     * One step of a remote command. The screen calls it again until [ExecResult.done]: the first call (poll = false) starts
     * the command under [id], every later one (poll = true) only asks how it is going, so each call is a short request
     * (the PC answers within ~20 s) instead of one connection that sits idle for minutes and gets cut by Tailscale / Wi-Fi.
     * The same id never runs the command twice. Throws "PC not reachable (...)" when no answer came (safe to call again),
     * and the PC's own message when it refused (wrong password, remote commands off ...).
     */
    fun exec(ip0: String, hport0: Int, pass: String, cmd: String, id: String = "", poll: Boolean = false): ExecResult {
        val (ip, hport) = target(ip0, hport0)
        val v = if (id.isEmpty()) 1 else version(ip, hport)
        if (v >= 2) {
            val form = "id=${e(id)}&wait=20&pass=${e(pass)}" + if (poll) "&poll=1" else "&cmd=${e(cmd)}"   // in the body: no URL length limit
            val (st, o) = try { call(ip, hport, "/exec", form, 45_000, 8_000, 3, true) } catch (x: Exception) { throw unreachable(x) }
            return result(st, o)
        }
        if (poll) throw PcError("job not found - the Phone Explorer program on the PC was restarted")
        // old PC program: one request that waits for the end. It cannot be repeated after it was sent (it would run twice).
        val path = "/exec?pass=${e(pass)}&cmd=${e(cmd)}"
        if (path.length > 15000) throw PcError("command too long for this PC program - update Phone Explorer on the PC")
        val (st, o) = try { call(ip, hport, path, null, 325_000, 8_000, 3, false) } catch (x: Exception) {
            throw if (connectFailure(x)) unreachable(x) else IOException("the connection to the PC broke while the command was running (${errText(x)}) - it may have run there")
        }
        return result(st, o)
    }
}
