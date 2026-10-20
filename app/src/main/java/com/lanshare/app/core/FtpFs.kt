package com.lanshare.app.core

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** FTP / FTPS server config helpers (saved in lanshare.json under "ftp"), same role as [Smb]. */
object Ftp {
    val state = ConcurrentHashMap<String, Boolean>()   // server id -> did the last operation work?
    /** server id -> does the server's FTP port answer right now? (background probe, so the Devices list shows the real state) */
    val reach = ConcurrentHashMap<String, Boolean>()
    @Volatile private var probing = false

    private fun startProbe() {
        if (probing) return
        probing = true
        Thread {
            while (true) {
                try { probeAll() } catch (_: Throwable) {}
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }.also { it.isDaemon = true; it.name = "ftp-probe" }.start()
    }

    private fun probeAll() {
        val a = Cfg.ftp()
        val ids = HashSet<String>()
        val ts = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { c ->
            val id = c.optString("id"); ids.add(id)
            Thread {
                val (h, p) = split(c.optString("host"))
                val s = Net.newSocket(h)
                try { s.connect(InetSocketAddress(h, p), 2500); reach[id] = true }
                catch (_: Exception) { reach[id] = false }
                finally { try { s.close() } catch (_: Exception) {} }
            }.also { t -> t.isDaemon = true; t.start() }
        }
        ts.forEach { try { it.join(3500) } catch (_: InterruptedException) {} }
        reach.keys.removeAll { it !in ids }
    }

    fun cfg(id: String): JSONObject? {
        val a = Cfg.ftp()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    /** '192.168.1.5', '192.168.1.5:2121', 'ftp://host/dir' or 'ftps://host:990' -> (host, port). The scheme / path part is handled by [parse]. */
    fun split(host0: String): Pair<String, Int> {
        var host = host0.trim().substringAfter("://").substringBefore('/').trim()
        if (host.contains('@')) host = host.substringAfterLast('@')
        if (host.count { it == ':' } == 1) {
            val i = host.indexOf(':')
            val p = host.substring(i + 1).toIntOrNull()
            if (p != null && p in 1..65535) return host.substring(0, i) to p
        }
        return host to 21
    }

    /** What the address field may carry besides the host: "ftps://" switches TLS on, "/pub/x" after the host becomes the start folder. */
    private class Parsed(val host: String, val ftps: Boolean?, val dir: String, val sftp: Boolean)
    private fun parse(h0: String): Parsed {
        val t = h0.trim()
        val scheme = if (t.contains("://")) t.substringBefore("://").lowercase() else ""
        val rest = t.substringAfter("://")
        val dir = if (rest.contains('/')) "/" + rest.substringAfter('/').trim('/') else ""
        return Parsed(rest.substringBefore('/').trim(), if (scheme == "ftps") true else null, if (dir == "/") "" else dir, scheme == "sftp")
    }

    private fun build(id: String, host0: String, user: String, password: String, name: String, ftps: Boolean, dir0: String, mode: String, natfix: Boolean, verify: Boolean, timeout: Int, encoding: String, hidden: Boolean, sftpFlag: Boolean): JSONObject {
        val p = parse(host0)
        val sftp = p.sftp || sftpFlag
        val hostS = if (sftp && p.host.count { it == ':' } != 1) p.host + ":22" else p.host   // SFTP: port 22 unless the address names one
        if (p.host.isEmpty()) throw BadReq("enter the server's address")
        val dir = dir0.trim().ifEmpty { p.dir }.let { if (it.isEmpty()) "" else vnorm(it) }
        return JSONObject().put("id", id).put("host", hostS).put("user", user.trim()).put("password", password)
            .put("name", name.trim().take(40).ifEmpty { split(p.host).first }).put("ftps", (p.ftps ?: ftps) && !sftp).put("proto", if (sftp) "sftp" else "ftp").put("dir", dir)
            .put("mode", if (mode == "active") "active" else "passive").put("natfix", natfix).put("verify", verify)
            .put("timeout", timeout.coerceIn(5, 300)).put("hidden", hidden)
            .put("encoding", encoding.trim().let { if (it.isNotEmpty() && try { java.nio.charset.Charset.isSupported(it) } catch (_: Exception) { false }) it else "" })
    }

    /** Tests the login (lists the start folder: throws a readable error if address or login is wrong), then saves the server. Returns its id. */
    fun add(host0: String, user: String, password: String, name: String, ftps: Boolean, dir: String, mode: String = "passive", natfix: Boolean = true, verify: Boolean = true, timeout: Int = 60, encoding: String = "", hidden: Boolean = false, sftp: Boolean = false): String {
        val c = build("ftp:" + java.util.UUID.randomUUID().toString().replace("-", "").take(6), host0, user, password, name, ftps, dir, mode, natfix, verify, timeout, encoding, hidden, sftp)
        FtpFs.create(c).ls("/")
        val list = ArrayList<JSONObject>()
        val cur = Cfg.ftp()
        for (i in 0 until cur.length()) list.add(cur.getJSONObject(i))
        list.add(c)
        Cfg.setFtp(JSONArray(list.take(10)))
        split(c.getString("host")).first.let { if (viaOf(it) == "Tailscale") Cfg.addPin(it) }
        Cfg.save()
        return c.getString("id")
    }

    /** Edits a saved server in place (same id and position). An empty [password] keeps the stored one. Tested first; nothing changes when it fails. */
    fun update(id: String, host0: String, user: String, password: String, name: String, ftps: Boolean, dir: String, mode: String = "passive", natfix: Boolean = true, verify: Boolean = true, timeout: Int = 60, encoding: String = "", hidden: Boolean = false, sftp: Boolean = false): String {
        val old = cfg(id) ?: throw BadReq("this server is no longer in the list")
        val c = build(id, host0, user, if (password.isEmpty()) old.optString("password", "") else password, name, ftps, dir, mode, natfix, verify, timeout, encoding, hidden, sftp)
        if (old.optString("hostkey").isNotEmpty() && old.optString("host") == c.optString("host") && old.optString("proto") == c.optString("proto")) c.put("hostkey", old.optString("hostkey")).put("hostkeytype", old.optString("hostkeytype"))   // the pinned SSH host key stays
        FtpFs.create(c).ls("/")
        val cur = Cfg.ftp()
        val list = ArrayList<JSONObject>()
        for (i in 0 until cur.length()) cur.getJSONObject(i).let { list.add(if (it.optString("id") == id) c else it) }
        state.remove(id); reach.remove(id)
        FtpFs.forget(id)
        Cfg.setFtp(JSONArray(list))
        split(c.getString("host")).first.let { if (viaOf(it) == "Tailscale") Cfg.addPin(it) }
        Cfg.save()
        return id
    }

    fun remove(id: String) {
        val cur = Cfg.ftp()
        val list = ArrayList<JSONObject>()
        for (i in 0 until cur.length()) list.add(cur.getJSONObject(i))
        list.removeAll { it.optString("id") == id }
        state.remove(id)
        FtpFs.forget(id)
        Cfg.setFtp(JSONArray(list.take(10)))
        Cfg.save()
    }

    fun peers(): List<JSONObject> {
        val a = Cfg.ftp()
        if (a.length() > 0) startProbe()
        return (0 until a.length()).map {
            val c = a.getJSONObject(it)
            // "smb": true = a saved entry without "last seen" (the page treats it like a saved PC); "ftp" tells the page it can neither print nor run LANShare
            JSONObject().put("id", c.getString("id")).put("name", c.getString("name")).put("ip", c.getString("host"))
                .put("ok", reach[c.getString("id")] ?: false).put("smb", true).put("ftp", true)
        }
    }

    fun status(): JSONArray = JSONArray(Cfg.ftp().let { a ->
        (0 until a.length()).map {
            val c = a.getJSONObject(it)
            JSONObject().put("id", c.getString("id")).put("name", c.getString("name")).put("host", c.getString("host"))
                .put("user", c.optString("user", "")).put("ftps", c.optBoolean("ftps", false)).put("dir", c.optString("dir", ""))
                .put("mode", c.optString("mode", "passive")).put("natfix", c.optBoolean("natfix", true)).put("verify", c.optBoolean("verify", true))
                .put("proto", c.optString("proto", "ftp")).put("timeout", c.optInt("timeout", 60)).put("encoding", c.optString("encoding", "")).put("hidden", c.optBoolean("hidden", false))
                .put("ok", state[c.getString("id")] ?: true)
        }
    })
}

internal class FtpPool { val sem = Semaphore(4); val idle = ArrayDeque<Pair<FTPClient, Long>>(); @Volatile var feat: Set<String>? = null; @Volatile var noCopy = false }   // most servers allow only a few logins per client

/** One borrowed control connection. [release] puts it back (keep = true, only when no transfer is pending) or closes it. */
internal class Lease(val c: FTPClient, private val pool: FtpPool) {
    private var done = false
    fun release(keep: Boolean) {
        if (done) return
        done = true
        if (keep && c.isConnected) synchronized(pool.idle) { pool.idle.addLast(c to System.currentTimeMillis()) }
        else try { c.disconnect() } catch (_: Exception) {}
        pool.sem.release()
    }
}

/** Read-only, seekable view of a remote FTP file: REST + RETR on seek, and again (at the current position) after a dropped connection. */
private class FtpSource(private val fs: FtpFs, private val path: String, override val size: Long) : Source() {
    private var pos = 0L
    private var lease: Lease? = null
    private var ins: InputStream? = null
    override val seekable: Boolean get() = true

    private fun drop() {   // an unfinished transfer cannot be reused safely: the control connection is closed
        try { ins?.close() } catch (_: Exception) {}
        ins = null
        lease?.release(false); lease = null
    }

    override fun seek(pos: Long) { drop(); this.pos = pos }

    /** The whole file was read: close the data connection, finish the command, and give the connection back for the next request. */
    private fun finish() {
        val l = lease
        try { ins?.close() } catch (_: Exception) {}
        ins = null; lease = null
        val ok = try { l?.c?.completePendingCommand() == true } catch (_: Exception) { false }
        l?.release(ok)
    }

    private fun begin() {
        val l = fs.lease()
        lease = l
        try {
            l.c.setRestartOffset(pos)
            ins = l.c.retrieveFileStream(path) ?: throw if (pos > 0 && l.c.replyCode in 500..504) IOException("this server cannot seek in files") else fs.replyErr(l.c)
        } catch (e: Throwable) { drop(); throw e }
    }

    override fun read(): Int {
        val b = ByteArray(1)
        while (true) { val n = read(b, 0, 1); if (n < 0) return -1; if (n == 1) return b[0].toInt() and 255 }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= size) return -1
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                if (ins == null) begin()
                val n = ins!!.read(b, off, len)
                if (n < 0) { finish(); return -1 }   // data connection ended
                pos += n
                if (pos >= size) finish()
                return n
            } catch (e: NotFound) { throw e
            } catch (e: Denied) { throw e
            } catch (e: Exception) {
                last = e
                drop()
                try { Thread.sleep(500L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw IOException("connection lost (${last?.message})")
    }

    override fun close() { drop() }
}

/**
 * An FTP / FTPS (explicit TLS) server, same interface as LocalFs / SmbFs. Paths are virtual ('/' = the configured start folder).
 * Uses a small pool of logged-in connections per server (max 4). Directory listings use MLSD when the server has it, LIST otherwise.
 */
class FtpFs private constructor(c: JSONObject) : Endpoint {
    override val id: String = c.getString("id")
    override val name: String = c.getString("name")
    private val host: String
    private val port: Int
    private val user: String = c.optString("user", "")
    private val pw: String = c.optString("password", "")
    private val ftps: Boolean = c.optBoolean("ftps", false)
    private val active: Boolean = c.optString("mode", "passive") == "active"   // active mode: the server connects back to this phone
    private val natfix: Boolean = c.optBoolean("natfix", true)                 // replace a private address in the PASV reply by the server address
    private val timeoutMs: Int = c.optInt("timeout", 60).coerceIn(5, 300) * 1000
    private val encoding: String = c.optString("encoding", "")                  // empty = UTF-8 with auto-detect
    private val hidden: Boolean = c.optBoolean("hidden", false)                // LIST -a for servers that hide dot files
    private val verify: Boolean = c.optBoolean("verify", true)                 // check size + checksum after an upload
    private val base: String = c.optString("dir", "").let { if (it.isEmpty()) "" else vnorm(it) }.let { if (it == "/") "" else it }

    init { val (h, p) = Ftp.split(c.getString("host")); host = h; port = p }

    companion object {
        private val pools = ConcurrentHashMap<String, FtpPool>()
        fun create(cfg: JSONObject): Endpoint = if (cfg.optString("proto") == "sftp") SftpFs(cfg) else FtpFs(cfg)
        /** A server was removed / edited: close its idle connections now. */
        fun forget(id: String) { SftpFs.forget(id); pools.keys.filter { it.startsWith("$id|") }.forEach { k -> pools.remove(k)?.let { p -> synchronized(p.idle) { p.idle.forEach { try { it.first.disconnect() } catch (_: Exception) {} }; p.idle.clear() } } } }
        private val IDLE_MAX_MS = 25_000L
        private const val HASH_MAX = 512L shl 20   // larger files: size check only (the server would need minutes to hash them)
    }

    private val poolKey get() = "$id|$host|$port|$user|${pw.hashCode()}|$ftps|$active|$natfix|$timeoutMs|$encoding|$hidden"
    private val pool: FtpPool get() = pools.getOrPut(poolKey) { FtpPool() }

    // ------------------------------------------------------------------ connections
    private fun newClient(): FTPClient {
        val c = if (ftps) FTPSClient("TLS", false).also { it.trustManager = TrustManagerUtils.getAcceptAllTrustManager() } else FTPClient()   // LAN servers mostly use self-signed certificates
        val ip = try { InetAddress.getByName(host).hostAddress ?: host } catch (_: UnknownHostException) { throw IOException("Unknown server address: $host") }
        c.setSocketFactory(object : SocketFactory() {   // every socket (control + data) goes out through the right network (Wi-Fi / Tailscale), like the other clients
            override fun createSocket(): Socket = Net.newSocket(ip)
            override fun createSocket(h: String, p: Int): Socket = Net.newSocket(ip).also { it.connect(InetSocketAddress(h, p)) }
            override fun createSocket(h: String, p: Int, la: InetAddress, lp: Int): Socket = Net.newSocket(ip).also { it.connect(InetSocketAddress(h, p)) }
            override fun createSocket(a: InetAddress, p: Int): Socket = Net.newSocket(ip).also { it.connect(InetSocketAddress(a, p)) }
            override fun createSocket(a: InetAddress, p: Int, la: InetAddress, lp: Int): Socket = Net.newSocket(ip).also { it.connect(InetSocketAddress(a, p)) }
        })
        if (encoding.isEmpty()) { c.controlEncoding = "UTF-8"; c.setAutodetectUTF8(true) } else c.controlEncoding = encoding
        c.setListHiddenFiles(hidden)
        c.connectTimeout = 8_000
        c.defaultTimeout = minOf(timeoutMs, 30_000)
        c.setBufferSize(256 * 1024)
        try {
            c.connect(ip, port)
            c.soTimeout = timeoutMs
            c.setDataTimeout(timeoutMs)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("The server refused the connection: " + c.replyString.trim())
            val u = user.ifEmpty { "anonymous" }
            if (!c.login(u, if (user.isEmpty() && pw.isEmpty()) "anonymous@" else pw)) throw Denied("Access denied - check the username and password")
            if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }   // data connections encrypted too
            if (active) c.enterLocalActiveMode() else c.enterLocalPassiveMode()
            if (!natfix) c.setPassiveNatWorkaroundStrategy(null)   // the server's PASV address is trusted as it is
            c.setFileType(FTP.BINARY_FILE_TYPE)
            c.setControlKeepAliveTimeout(20)   // NOOP on the control line while a long transfer runs (idle timeouts of servers)
            c.setControlKeepAliveReplyTimeout(5_000)
            return c
        } catch (e: Throwable) { try { c.disconnect() } catch (_: Exception) {}; throw e }
    }

    internal fun lease(): Lease {
        val p = pool
        if (!p.sem.tryAcquire(30, TimeUnit.SECONDS)) throw IOException("The server has too many open transfers - try again")
        try {
            while (true) {
                val e = synchronized(p.idle) { if (p.idle.isEmpty()) null else p.idle.removeLast() } ?: break
                val (c, t) = e
                val ok = try { c.isConnected && (System.currentTimeMillis() - t < IDLE_MAX_MS || c.sendNoOp()) } catch (_: Exception) { false }
                if (ok) return Lease(c, p)
                try { c.disconnect() } catch (_: Exception) {}
            }
            return Lease(newClient(), p)
        } catch (e: Throwable) { p.sem.release(); throw e }
    }

    private fun mapErr(e: Throwable): Throwable {
        if (e is NotFound || e is Denied || e is Exists || e is BadReq || e is Cancelled || e is Full) return e
        return when (e) {
            is FTPConnectionClosedException -> IOException("Lost the connection to the server - try again")
            is ConnectException -> IOException("Cannot connect to $host:$port (${e.message})")
            is SocketTimeoutException -> IOException("The server is not answering")
            is UnknownHostException -> IOException("Unknown server address: $host")
            is javax.net.ssl.SSLException -> IOException("TLS problem: ${errText(e)} (is this a plain FTP server? untick FTPS)")
            is IOException -> e
            else -> IOException(errText(e), e)
        }
    }

    /** Runs [fn] on a pooled connection. The connection goes back to the pool only when the call ended cleanly. */
    /** A dropped / refused connection (not a "no", a bad password or a certificate problem): worth another try. */
    private fun transient(e: Throwable) = e is FTPConnectionClosedException || e is java.net.SocketException || e is SocketTimeoutException ||
        e is java.io.EOFException || (e is IOException && e.message?.startsWith("The server closed") == true)

    /**
     * Runs [fn] on a pooled connection. The connection goes back to the pool only when the call ended cleanly.
     * A connection that cannot be opened is retried twice (0.7 s, 1.4 s); a call that fails in the middle is retried too when it is [idem]potent.
     */
    private fun <T> op(idem: Boolean = true, fn: (FTPClient) -> T): T {
        var attempt = 0
        while (true) {
            val l = try { lease() } catch (e: Throwable) {
                if (transient(e) && attempt < 2) { attempt++; Thread.sleep(700L * attempt); continue }
                val m = mapErr(e); Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true); throw m
            }
            var keep = false
            try {
                val r = fn(l.c)
                keep = true
                Ftp.state[id] = true
                return r
            } catch (e: Throwable) {
                val m = mapErr(e)
                keep = m is NotFound || m is Denied || m is Exists || m is BadReq || m is Full
                if (!(idem && transient(e) && attempt < 2)) {
                    if (m !is NotFound && m !is Exists && m !is BadReq && m !is Cancelled) Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true)
                    throw m
                }
            } finally { l.release(keep) }
            attempt++
            Thread.sleep(700L * attempt)
        }
    }

    /** The reply the server just sent, as the matching error. */
    internal fun replyErr(c: FTPClient): Exception {
        val code = c.replyCode
        val txt = c.replyString.trim().replace(Regex("^\\d{3}[ -]"), "")
        val low = txt.lowercase()
        return when {
            code == 530 || code == 332 || code == 532 -> Denied("Access denied - check the username and password")
            code == 550 && ("permission" in low || "denied" in low || "not allowed" in low || "access" in low || "read-only" in low) -> Denied("Access denied by the server ($txt)")
            code == 550 -> NotFound("Not found on the server (or not allowed): $txt")
            code == 552 || code == 452 || "disk full" in low || "quota" in low || "no space" in low -> Full("The server is full ($txt)")
            code == 553 -> BadReq("The server does not accept that name ($txt)")
            code == 421 -> IOException("The server closed the connection ($txt)")
            else -> IOException(txt.ifEmpty { "FTP error $code" })
        }
    }

    // ------------------------------------------------------------------ paths and entries
    private fun fp(v: String): String {
        val n = vnorm(v)
        if (n.indexOf('\r') >= 0 || n.indexOf('\n') >= 0) throw BadReq("invalid name")
        return if (base.isEmpty()) n else if (n == "/") base else base + n
    }

    private fun toItem(f: FTPFile, dir: Boolean): Item =
        Item(f.name, dir, if (dir) 0L else maxOf(f.size, 0L), try { (f.timestamp?.timeInMillis ?: 0L) / 1000 } catch (_: Exception) { 0L }, null, null, f.name.startsWith("."))

    private fun listRaw(c: FTPClient, path: String): List<Item> {
        var arr: Array<FTPFile>? = try { c.mlistDir(path) } catch (_: IOException) { null }
        var mlsd = arr != null && FTPReply.isPositiveCompletion(c.replyCode)
        if (!mlsd) {
            if (c.replyCode == 550 || c.replyCode == 553) throw replyErr(c)   // folder missing / not allowed: LIST would only say the same
            arr = c.listFiles(path)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw replyErr(c)
        }
        val out = ArrayList<Item>()
        var linkChecks = 0
        for (f in arr ?: return out) {
            if (f == null) continue
            val n = f.name ?: continue
            if (n == "." || n == ".." || n.isEmpty()) continue
            val raw = f.rawListing.orEmpty().lowercase()
            if (mlsd && ("type=cdir" in raw || "type=pdir" in raw)) continue
            var dir = f.isDirectory
            if (f.isSymbolicLink && linkChecks < 40) {   // a link: folder or file? try to enter it (cap, one round trip each)
                linkChecks++
                dir = try { c.changeWorkingDirectory(vjoin(path, n)) } catch (_: IOException) { false }
            }
            if (!f.isDirectory && !f.isFile && !f.isSymbolicLink) continue   // devices, pipes, ...
            out.add(toItem(f, dir))
        }
        return out
    }

    /** The entry [v] itself (null = missing). */
    private fun entry(c: FTPClient, v: String): Item? {
        val n = vnorm(v)
        if (n == "/") return Item("/", true, 0L, 0L)
        val parent = fp(vdir(n))
        val want = vbase(n)
        return listRaw(c, parent).firstOrNull { it.name == want }
    }

    override fun ls(v: String): List<Item> = op { c -> listRaw(c, fp(v)) }

    override fun names(v: String): MutableSet<String> =
        try { ls(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }

    private fun searchIn(c: FTPClient, v: String, ql: String, out: MutableList<Item>, end: Long): Boolean {
        val stack = ArrayDeque<String>()
        stack.addLast(vnorm(v))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val entries = try { listRaw(c, fp(d)) } catch (e: IOException) { if (e is FTPConnectionClosedException) throw e; continue }
            for (i in entries) {
                if (i.dir) stack.addLast(vjoin(d, i.name))
                if (i.name.lowercase().contains(ql)) {
                    out.add(i.copy(path = vjoin(d, i.name)))
                    if (out.size >= 300) return true
                }
            }
            if (System.currentTimeMillis() > end) return true
        }
        return false
    }

    override fun search(v: String, q: String): SearchResult {
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + 15_000L
        val partial = op(false) { c -> searchIn(c, v, q.lowercase(), out, end) }
        if (!partial) out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    override fun walk(v: String): List<WalkItem> { val res = ArrayList<WalkItem>(); walkEach(v) { res.add(it) }; return res }

    override fun walkEach(v: String, visit: (WalkItem) -> Unit) {
        val sent = HashSet<String>()   // after a transparent retry the walk restarts: what was already handed over is not handed over twice
        val out: (WalkItem) -> Unit = { w -> if (sent.add((if (w.skip) "!" else if (w.dir) "d" else "f") + w.rel)) visit(w) }
        op { c ->
            val me = entry(c, v) ?: throw NotFound("No such file or directory")
            if (!me.dir) { out(WalkItem("", false, me.size)); return@op }
            out(WalkItem("", true, 0))
            val stack = ArrayDeque<String>()
            stack.addLast("")
            while (stack.isNotEmpty()) {   // a folder's entry is handed over before its children: mkdir always precedes its files
                val r0 = stack.removeLast()
                val kids = try { listRaw(c, fp(if (r0.isEmpty()) v else vjoin(v, r0))) }
                           catch (e: IOException) { if (e is FTPConnectionClosedException) throw e; out(WalkItem(if (r0.isEmpty()) "(folder)" else r0, false, 0, true)); continue }
                for (k in kids) {
                    val r = if (r0.isEmpty()) k.name else r0 + "/" + k.name
                    if (k.dir) { out(WalkItem(r, true, 0)); stack.addLast(r) } else out(WalkItem(r, false, k.size))
                }
            }
        }
    }

    override fun stat(v: String): JSONObject {
        val n = vnorm(v)
        if (n == "/") return JSONObject().put("name", name).put("path", "/").put("dir", true).put("size", 0L).put("mtime", 0L)
        return op { c ->
            val me = entry(c, n) ?: throw NotFound("No such file or directory")
            val o = JSONObject().put("name", me.name).put("path", n).put("dir", me.dir).put("size", me.size).put("mtime", me.mtime)
            if (me.dir) {   // totals, bounded like the SMB ones (20 s / 300 000 entries)
                val tot = LongArray(3); var partial = false; var seen = 0
                val end = System.currentTimeMillis() + 20_000L
                val stack = ArrayDeque<String>(); stack.addLast(n)
                loop@ while (stack.isNotEmpty()) {
                    val d = stack.removeLast()
                    val kids = try { listRaw(c, fp(d)) } catch (e: IOException) { if (e is FTPConnectionClosedException) throw e; continue }
                    for (k in kids) {
                        if (k.dir) { tot[1]++; stack.addLast(vjoin(d, k.name)) } else { tot[0]++; tot[2] += k.size }
                        if (++seen >= 300_000) { partial = true; break@loop }
                    }
                    if (System.currentTimeMillis() > end) { partial = true; break }
                }
                o.put("files", tot[0]).put("folders", tot[1]).put("total", tot[2]).put("partial", partial)
            }
            o
        }
    }

    override fun space(v: String): Pair<Long, Long>? = null   // FTP has no standard free-space command

    override fun open(v: String): Source {
        val p = fp(v)
        val me = op { c -> entry(c, v) } ?: throw NotFound("No such file or directory")
        if (me.dir) throw IOException("Is a directory")
        val sz = if (me.size > 0) me.size else (op { c -> c.getSize(p)?.trim()?.toLongOrNull() } ?: 0L)   // some listings carry no size: ask with SIZE
        return FtpSource(this, p, sz)
    }

    // ------------------------------------------------------------------ writing
    private fun mkdirs(c: FTPClient, abs: String) {
        if (abs.isEmpty() || abs == "/") return
        var cur = ""
        for (part in abs.split('/')) {
            if (part.isEmpty()) continue
            cur += "/$part"
            if (c.changeWorkingDirectory(cur)) continue
            if (!c.makeDirectory(cur) && !c.changeWorkingDirectory(cur)) throw replyErr(c)
        }
    }

    override fun mkdir(v: String) { op { c -> mkdirs(c, fp(v)) } }

    // ------------------------------------------------------------------ checksums
    private class HashPlan(val cmd: String, val jdk: String?, val len: Int, val opt: String?)

    private class Sum(p: HashPlan) {
        private val md: MessageDigest? = p.jdk?.let { MessageDigest.getInstance(it) }
        private val crc: CRC32? = if (md == null) CRC32() else null
        fun update(b: ByteArray, o: Int, n: Int) { if (md != null) md.update(b, o, n) else crc!!.update(b, o, n) }
        fun hex(): String = if (md != null) md.digest().joinToString("") { "%02x".format(it) } else "%08x".format(crc!!.value)
    }

    /** What the server says it can do (FEAT), asked once per server. */
    private fun feats(c: FTPClient): Set<String> {
        pool.feat?.let { return it }
        val s = HashSet<String>()
        try {
            if (c.sendCommand("FEAT") == 211) {
                for (ln in c.replyStrings) {
                    val t = ln.trim()
                    if (t.isEmpty() || t.startsWith("211")) continue
                    val key = t.substringBefore(' ').uppercase(Locale.ROOT)
                    s.add(key)
                    if (key == "HASH") t.substringAfter(' ', "").split(';').forEach { a ->
                        val n = a.trim().trimEnd('*').uppercase(Locale.ROOT)
                        if (n.isNotEmpty()) s.add("HASH:$n")
                    }
                }
            }
        } catch (e: IOException) { return s }
        pool.feat = s
        return s
    }

    private fun hashPlan(f: Set<String>): HashPlan? = when {
        "XSHA256" in f -> HashPlan("XSHA256", "SHA-256", 64, null)
        "XSHA1" in f -> HashPlan("XSHA1", "SHA-1", 40, null)
        "XMD5" in f -> HashPlan("XMD5", "MD5", 32, null)
        "XCRC" in f -> HashPlan("XCRC", null, 8, null)
        "HASH:SHA-256" in f -> HashPlan("HASH", "SHA-256", 64, "SHA-256")
        "HASH:SHA-1" in f -> HashPlan("HASH", "SHA-1", 40, "SHA-1")
        "HASH:MD5" in f -> HashPlan("HASH", "MD5", 32, "MD5")
        else -> null
    }

    /** The server's checksum of [path] (lower-case hex), null when it refuses. An IOException (timeout) leaves the connection unusable. */
    private fun remoteHash(c: FTPClient, p: HashPlan, path: String): String? {
        val old = c.soTimeout
        try {
            c.soTimeout = 300_000   // the server reads the whole file
            if (p.opt != null && c.sendCommand("OPTS", "HASH " + p.opt) !in 200..299) return null
            if (c.sendCommand(p.cmd, path) !in 200..299) return null
            val line = c.replyString.trim().substringAfter(' ')
            return Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{${p.len}}(?![0-9a-fA-F])").find(line)?.value?.lowercase(Locale.ROOT)
        } finally { try { c.soTimeout = old } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------------ writing
    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) = upload(v, input, size, 0L, null, cb)

    /** Server-side copy (ProFTPD mod_copy: SITE CPFR / CPTO). False when the server has no such command or refuses; the size of the copy is checked. */
    override fun copyWithin(v: String, toV: String): Boolean {
        if (pool.noCopy || vnorm(v) == "/" || vnorm(toV) == "/") return false
        val a = fp(v); val b = fp(toV)
        return op(false) { c ->
            mkdirs(c, b.substringBeforeLast('/', ""))
            if (c.sendCommand("SITE", "CPFR $a") !in 300..399) { if (c.replyCode in 500..504) pool.noCopy = true; return@op false }
            if (c.sendCommand("SITE", "CPTO $b") !in 200..299) return@op false
            val sa = c.getSize(a)?.trim()?.toLongOrNull(); val sb = c.getSize(b)?.trim()?.toLongOrNull()
            if (sa != null && sb != sa) { try { c.deleteFile(b) } catch (_: Exception) {}; return@op false }   // incomplete copy: copy the normal way
            true
        }
    }

    override val setsMtime: Boolean get() = true

    /** Gives the stored file its original modification time (MFMT, UTC) when the server has it. */
    override fun setMtime(v: String, sec: Long) {
        if (sec <= 0 || vnorm(v) == "/") return
        val path = fp(v)
        op { c ->
            if ("MFMT" in feats(c)) {
                val f = java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                c.sendCommand("MFMT", f.format(java.util.Date(sec * 1000)) + " " + path)
            }
            Unit
        }
    }

    override val resumable: Boolean get() = true

    private fun pkey(tmp: String) = "ftp|$id|$tmp"

    override fun partial(v: String, tag: String): Long = try {
        val tmp = fp(v) + ".lspart"
        if (vnorm(v) == "/" || !Partials.matches(pkey(tmp), tag)) 0L
        else op { c -> c.getSize(tmp)?.trim()?.toLongOrNull() ?: 0L }
    } catch (e: Cancelled) { throw e } catch (_: Exception) { 0L }

    override fun writeResume(v: String, input: InputStream, size: Long, offset: Long, tag: String, cb: ((Int) -> Unit)?) {
        if (size < 0 || offset < 0 || offset > size) throw BadReq("bad resume offset")
        upload(v, input, size, offset, tag, cb)
    }

    private fun commit(c: FTPClient, p: String, tmp: String) {
        try { c.deleteFile(p) } catch (_: Exception) {}   // replace an existing file of that name
        if (!c.rename(tmp, p)) throw replyErr(c)
    }

    /**
     * Stores [input] (which starts AT [offset]) as "name.lspart", checks it (size, and checksum when the server has XSHA256 / XSHA1 / XMD5 / XCRC / HASH
     * and the whole file went through this call), then renames it. With a [tag] (resumable copy) the .lspart stays after a failure and the next try
     * continues with APPE; a cancel, a mismatch or a failed check removes it.
     */
    private fun upload(v: String, input: InputStream, size: Long, offset: Long, tag: String?, cb: ((Int) -> Unit)?) {
        val p = fp(v)
        if (vnorm(v) == "/") throw BadReq("invalid path")
        val tmp = "$p.lspart"
        val pk = pkey(tmp)
        var ok = false
        var keep = tag != null
        try {
            val l = lease()
            var clean = false
            try {
                val c = l.c
                mkdirs(c, p.substringBeforeLast('/', ""))
                if (offset > 0) {
                    val have = c.getSize(tmp)?.trim()?.toLongOrNull() ?: -1L
                    if (tag == null || !Partials.matches(pk, tag) || have != offset) throw BadReq("resume offset mismatch (have ${maxOf(have, 0L)})")
                }
                if (tag != null) Partials.put(pk, tag)
                val plan = if (verify && offset == 0L && size in 1..HASH_MAX) hashPlan(feats(c)) else null
                val sum = plan?.let { Sum(it) }
                val os = (if (offset > 0) c.appendFileStream(tmp) else c.storeFileStream(tmp))
                    ?: throw (if (offset > 0 && c.replyCode in 500..504) BadReq("resume offset mismatch (this server cannot append)") else replyErr(c))
                try {
                    val buf = ByteArray(CHUNK)
                    var left = size - offset
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("connection lost")
                        os.write(buf, 0, n)
                        sum?.update(buf, 0, n)
                        left -= n
                        cb?.invoke(n)
                    }
                } finally { try { os.close() } catch (_: Exception) {} }
                if (!c.completePendingCommand()) throw replyErr(c)
                if (verify && size > 0) {   // size check: cheap, works on nearly every server
                    val got = c.getSize(tmp)?.trim()?.toLongOrNull()
                    if (got != null && got != size) {
                        if (got > size) keep = false
                        throw IOException("Upload incomplete: the server has $got of $size bytes")
                    }
                }
                var reconnect = false
                if (sum != null && plan != null) {
                    var remote: String? = null
                    try { remote = remoteHash(c, plan, tmp) } catch (_: IOException) { reconnect = true }   // slow server: skip the check, the size matched
                    if (remote != null && !remote.equals(sum.hex(), ignoreCase = true)) {
                        keep = false
                        throw IOException("Upload check failed: the file on the server differs from the original (checksum) - try again")
                    }
                    if (reconnect) l.release(false)
                }
                if (reconnect) op(false) { c2 -> commit(c2, p, tmp) } else commit(c, p, tmp)
                clean = !reconnect; ok = true
                Ftp.state[id] = true
            } catch (e: Throwable) {
                val m = mapErr(e)
                if (m is Cancelled || m is BadReq) keep = false
                if (m !is NotFound && m !is Exists && m !is BadReq && m !is Cancelled) Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true)
                throw m
            } finally { l.release(clean) }
        } finally {
            if (ok || !keep) Partials.forget(pk)
            if (!ok && !keep) try { op { c -> c.deleteFile(tmp) } } catch (_: Exception) {}   // never leave a half file behind
        }
    }

    private fun why(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(80)

    private fun delTree(c: FTPClient, v: String, fails: MutableList<String>, progress: ((String) -> Unit)?) {
        val kids = try { listRaw(c, fp(v)) } catch (e: Exception) { fails.add("${vbase(v)}: ${why(e)}"); return }
        for (k in kids) {
            val cv = vjoin(v, k.name)
            try {
                if (k.dir) {
                    val before = fails.size
                    delTree(c, cv, fails, progress)
                    if (fails.size > before) continue   // something inside stayed, so the folder cannot go either
                    if (!c.removeDirectory(fp(cv))) throw replyErr(c)
                } else if (!c.deleteFile(fp(cv))) throw replyErr(c)
                progress?.invoke(k.name)
            } catch (e: Cancelled) { throw e
            } catch (e: Exception) { fails.add("${k.name}: ${why(e)}") }
        }
    }

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val n = vnorm(v)
        if (n == "/") throw Denied("cannot delete the server root")
        op(false) { c ->
            val me = entry(c, n) ?: return@op
            val fails = ArrayList<String>()
            if (me.dir) {
                delTree(c, n, fails, progress)
                if (fails.isEmpty()) { try { if (!c.removeDirectory(fp(n))) throw replyErr(c); progress?.invoke(me.name) } catch (e: Cancelled) { throw e } catch (e: Exception) { fails.add("${me.name}: ${why(e)}") } }
            } else {
                try { if (!c.deleteFile(fp(n))) throw replyErr(c); progress?.invoke(me.name) } catch (e: Cancelled) { throw e } catch (e: Exception) { fails.add("${me.name}: ${why(e)}") }
            }
            if (fails.isNotEmpty())
                throw IOException("Could not delete " + (if (fails.size == 1) fails[0] else fails.size.toString() + " items (" + fails.take(3).joinToString("; ") + (if (fails.size > 3) "; ..." else "") + ")"))
            Unit
        }
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        if (vnorm(v) == "/") throw BadReq("cannot rename the root")
        val dst = vjoin(vdir(v), newName)
        op(false) { c ->
            val clash = entry(c, dst)
            if (clash != null && !vbase(v).equals(newName, ignoreCase = true)) throw Exists("name already exists")
            if (!c.rename(fp(v), fp(dst))) throw replyErr(c)
            Unit
        }
    }

    override fun move(v: String, toV: String) {
        op(false) { c ->
            if (entry(c, toV) != null) throw Exists("name already exists")
            mkdirs(c, fp(vdir(toV)))
            if (!c.rename(fp(v), fp(toV))) throw replyErr(c)
            Unit
        }
    }
}
