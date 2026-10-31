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

    /** The Remote shell that is open right now (the screen reports it through the bridge: MainActivity.Bridge.rshLive). Kept in memory only, password included. */
    private class LiveShell(val ip: String, val port: Int, val pass: String, val sid: String)
    @Volatile private var live: LiveShell? = null

    /** The screen opened / ended a shell. An empty [sid] = none is open. */
    fun setLiveShell(ip: String, port: Int, pass: String, sid: String) {
        live = if (sid.isEmpty() || ip.isEmpty()) null else LiveShell(ip, port, pass, sid)
    }

    /**
     * The app is being closed: ends the live shell on the PC (act=close; the PC kills the shell and everything it started).
     * Fire and forget on a short-lived thread with a short timeout. If the process dies before it is sent, the PC closes the shell
     * itself after 5 minutes without contact (phone_explorer.py TERM_IDLE).
     */
    fun endLiveShell() {
        val l = live ?: return
        live = null
        Thread({
            try {
                val (ip, hport) = target(l.ip, l.port)
                call(ip, hport, "/term", "act=close&sid=${e(l.sid)}&pass=${e(l.pass)}", 6_000, 3_000, 2, true)
            } catch (_: Throwable) { }
        }, "pe-term-close").start()
    }

    /**
     * One step of the real terminal on the PC (phone_explorer.py /term, Remote shell screen): [act] = open | read | in | resize | close,
     * [extra] = the other form fields (shell, cols, rows, o, wait, seq, data, rs = resize counter). Every act is safe to repeat (open = same sid -> same shell,
     * read = same offset -> same bytes, in = same seq -> typed once), so a cut connection is simply tried again with a new socket.
     */
    fun term(ip0: String, hport0: Int, pass: String, act: String, sid: String, extra: Map<String, String>): JSONObject {
        val (ip, hport) = target(ip0, hport0)
        val form = StringBuilder("act=${e(act)}&sid=${e(sid)}&pass=${e(pass)}")
        for ((k, v) in extra) form.append('&').append(k).append('=').append(e(v))
        val (st, o) = try { call(ip, hport, "/term", form.toString(), 45_000, 8_000, 3, true) } catch (x: Exception) { throw unreachable(x) }
        if (st == 404 && o?.optString("error") == "not found") throw PcError("update Phone Explorer on the PC (the real terminal needs the new program)")
        if (o == null) throw IOException("PC answered something unexpected (HTTP $st) - is another program using that port?")
        if (st != 200) throw PcError(o.optString("error", "PC answered $st"))
        return o
    }
}
