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
    private class Parsed(val host: String, val ftps: Boolean?, val dir: String)
    private fun parse(h0: String): Parsed {
        val t = h0.trim()
        val scheme = if (t.contains("://")) t.substringBefore("://").lowercase() else ""
        val rest = t.substringAfter("://")
        val dir = if (rest.contains('/')) "/" + rest.substringAfter('/').trim('/') else ""
        return Parsed(rest.substringBefore('/').trim(), if (scheme == "ftps") true else null, if (dir == "/") "" else dir)
    }

    private fun build(id: String, host0: String, user: String, password: String, name: String, ftps: Boolean, dir0: String): JSONObject {
        val p = parse(host0)
        if (p.host.isEmpty()) throw BadReq("enter the server's address")
        val dir = dir0.trim().ifEmpty { p.dir }.let { if (it.isEmpty()) "" else vnorm(it) }
        return JSONObject().put("id", id).put("host", p.host).put("user", user.trim()).put("password", password)
            .put("name", name.trim().take(40).ifEmpty { split(p.host).first }).put("ftps", p.ftps ?: ftps).put("dir", dir)
    }

    /** Tests the login (lists the start folder: throws a readable error if address or login is wrong), then saves the server. Returns its id. */
    fun add(host0: String, user: String, password: String, name: String, ftps: Boolean, dir: String): String {
        val c = build("ftp:" + java.util.UUID.randomUUID().toString().replace("-", "").take(6), host0, user, password, name, ftps, dir)
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
    fun update(id: String, host0: String, user: String, password: String, name: String, ftps: Boolean, dir: String): String {
        val old = cfg(id) ?: throw BadReq("this server is no longer in the list")
        val c = build(id, host0, user, if (password.isEmpty()) old.optString("password", "") else password, name, ftps, dir)
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
                .put("ok", state[c.getString("id")] ?: true)
        }
    })
}

internal class FtpPool { val sem = Semaphore(4); val idle = ArrayDeque<Pair<FTPClient, Long>>() }   // most servers allow only a few logins per client

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
    private val base: String = c.optString("dir", "").let { if (it.isEmpty()) "" else vnorm(it) }.let { if (it == "/") "" else it }

    init { val (h, p) = Ftp.split(c.getString("host")); host = h; port = p }

    companion object {
        private val pools = ConcurrentHashMap<String, FtpPool>()
        fun create(cfg: JSONObject): Endpoint = FtpFs(cfg)
        /** A server was removed / edited: close its idle connections now. */
        fun forget(id: String) { pools.keys.filter { it.startsWith("$id|") }.forEach { k -> pools.remove(k)?.let { p -> synchronized(p.idle) { p.idle.forEach { try { it.first.disconnect() } catch (_: Exception) {} }; p.idle.clear() } } } }
        private val IDLE_MAX_MS = 25_000L
    }

    private val poolKey get() = "$id|$host|$port|$user|${pw.hashCode()}|$ftps"
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
        c.controlEncoding = "UTF-8"
        c.setAutodetectUTF8(true)
        c.connectTimeout = 8_000
        c.defaultTimeout = 30_000
        c.setBufferSize(256 * 1024)
        try {
            c.connect(ip, port)
            c.soTimeout = 60_000
            c.setDataTimeout(60_000)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("The server refused the connection: " + c.replyString.trim())
            val u = user.ifEmpty { "anonymous" }
            if (!c.login(u, if (user.isEmpty() && pw.isEmpty()) "anonymous@" else pw)) throw Denied("Access denied - check the username and password")
            if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }   // data connections encrypted too
            c.enterLocalPassiveMode()
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
    private fun <T> op(fn: (FTPClient) -> T): T {
        val l = try { lease() } catch (e: Throwable) { val m = mapErr(e); Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true); throw m }
        var keep = false
        try {
            val r = fn(l.c)
            keep = true
            Ftp.state[id] = true
            return r
        } catch (e: Throwable) {
            val m = mapErr(e)
            keep = m is NotFound || m is Denied || m is Exists || m is BadReq || m is Full
            if (m !is NotFound && m !is Exists && m !is BadReq && m !is Cancelled) Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true)
            throw m
        } finally { l.release(keep) }
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
        val partial = op { c -> searchIn(c, v, q.lowercase(), out, end) }
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

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        val p = fp(v)
        if (vnorm(v) == "/") throw BadReq("invalid path")
        val tmp = "$p.lspart"
        var ok = false
        try {
            val l = lease()
            var clean = false
            try {
                val c = l.c
                mkdirs(c, p.substringBeforeLast('/', ""))
                val os = c.storeFileStream(tmp) ?: throw replyErr(c)
                try {
                    val buf = ByteArray(CHUNK)
                    var left = size
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("connection lost")
                        os.write(buf, 0, n)
                        left -= n
                        cb?.invoke(n)
                    }
                } finally { try { os.close() } catch (_: Exception) {} }
                if (!c.completePendingCommand()) throw replyErr(c)
                try { c.deleteFile(p) } catch (_: Exception) {}   // replace an existing file of that name
                if (!c.rename(tmp, p)) throw replyErr(c)
                clean = true; ok = true
                Ftp.state[id] = true
            } catch (e: Throwable) {
                val m = mapErr(e)
                if (m !is NotFound && m !is Exists && m !is BadReq && m !is Cancelled) Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true)
                throw m
            } finally { l.release(clean) }
        } finally {
            if (!ok) try { op { c -> c.deleteFile(tmp) } } catch (_: Exception) {}   // never leave a half file behind
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
        op { c ->
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
        op { c ->
            val clash = entry(c, dst)
            if (clash != null && !vbase(v).equals(newName, ignoreCase = true)) throw Exists("name already exists")
            if (!c.rename(fp(v), fp(dst))) throw replyErr(c)
            Unit
        }
    }

    override fun move(v: String, toV: String) {
        op { c ->
            if (entry(c, toV) != null) throw Exists("name already exists")
            mkdirs(c, fp(vdir(toV)))
            if (!c.rename(fp(v), fp(toV))) throw replyErr(c)
            Unit
        }
    }
}
