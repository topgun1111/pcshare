package com.lanshare.app.pe

import com.lanshare.app.core.Core
import com.lanshare.app.core.Http
import com.lanshare.app.core.errText
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
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

    /** Tells the PC to open Explorer on this phone (the PC takes the phone's address from the connection). Throws when it fails. */
    fun open(ip: String, hport: Int, port: Int, user: String, pin: String, name: String) {
        val e = { v: String -> URLEncoder.encode(v, "UTF-8") }
        val path = "/open?port=$port&user=${e(user)}&pin=${e(pin)}&name=${e(name)}"
        try {
            val r = Http.request(ip, hport, "POST", path, mapOf("Content-Length" to "0"), 8000)
            try { if (r.status != 200) throw IOException("PC answered " + r.status) } finally { r.close() }
        } catch (ex: IOException) { throw ex } catch (ex: Exception) { throw IOException("PC not reachable (${errText(ex)})") }
    }

    /** Result of a remote command run on the PC through /exec. */
    class ExecResult(val out: String, val err: String, val code: Int, val truncated: Boolean)

    /**
     * Runs [cmd] on the PC at [ip]:[hport] through phone_explorer.py's /exec endpoint, guarded by the password set in
     * phone_explorer.py (the PC must also have the "Remote commands" button ON). Throws when it fails, including
     * when the PC has that feature turned off or the password is wrong (the PC's error message is the exception text).
     */
    fun exec(ip: String, hport: Int, pass: String, cmd: String, timeoutMs: Int = 65000): ExecResult {
        val e = { v: String -> URLEncoder.encode(v, "UTF-8") }
        val path = "/exec?pass=${e(pass)}&cmd=${e(cmd)}"
        try {
            val r = Http.request(ip, hport, "POST", path, mapOf("Content-Length" to "0"), timeoutMs)
            try {
                val body = String(r.readUpTo(262144), Charsets.UTF_8)
                val o = JSONObject(body)
                if (r.status != 200) throw IOException(o.optString("error", "PC answered " + r.status))
                return ExecResult(o.optString("out", ""), o.optString("err", ""), o.optInt("code", -1), o.optBoolean("truncated", false))
            } finally { r.close() }
        } catch (ex: IOException) { throw ex } catch (ex: Exception) { throw IOException("PC not reachable (${errText(ex)})") }
    }
}
