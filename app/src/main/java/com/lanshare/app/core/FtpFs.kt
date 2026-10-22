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
import java.security.SecureRandom
import javax.net.SocketFactory
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

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

internal class FtpPool { val sem = Semaphore(4); val idle = ArrayDeque<Pair<FTPClient, Long>>(); @Volatile var feat: Set<String>? = null; @Volatile var noCopy = false
    @Volatile var lastUse = 0L                 // part 3: last time anything used / warmed this server (the keeper drops idle connections 3 min after it)
    @Volatile var dirtyAt = 0L                 // part 4: last time a write on this server finished (listings that started before it are not stored by fill())
    @Volatile var noMlst = false               // part 4: the server listed MLST in FEAT but refused the command
    @Volatile var warming = false              // part 3: a warm-up login is running
    @Volatile var tls: SSLContext? = null      // part 3: ONE TLS context per server, so its session cache lives across connections (FTPS session reuse)
    val pfAt = ConcurrentHashMap<String, Long>()   // part 3: folder -> last prefetch try (no hammering)
    val pfBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var syst: String? = null   // SYST answer, asked once per server: later connections skip that round trip when listing
    val links = ConcurrentHashMap<String, Boolean>() }   // symlink path -> is it a folder (one CWD round trip each, remembered)   // most servers allow only a few logins per client

/** One borrowed control connection. [release] puts it back (keep = true, only when no transfer is pending) or closes it. */
internal class Lease(val c: FTPClient, private val pool: FtpPool) {
    private var done = false
    fun release(keep: Boolean) {
        if (done) return
        done = true
        pool.lastUse = System.currentTimeMillis()
        if (keep && c.isConnected) synchronized(pool.idle) { pool.idle.addLast(c to System.currentTimeMillis()) }
        else try { c.disconnect() } catch (_: Exception) {}
        pool.sem.release()
    }
}

/**
 * FTPS session reuse (FTP speed-up part 3). Many servers (vsftpd require_ssl_reuse, FileZilla Server, ProFTPD ...) only accept a data connection whose
 * TLS session is the control connection's, and a resumed session also saves a full handshake. JSSE / Conscrypt look a session up by the (host, port)
 * given to createSocket: the data socket gets a new port every time, so nothing was ever found. [ReuseFactory] hands every layered socket of one client
 * the CONTROL port (the first one it sees); together with the shared per-server context ([FtpPool.tls]) the control session is found again.
 * The port is only used as the cache key - the socket is already connected.
 */
private class ReuseFactory(private val d: SSLSocketFactory) : SSLSocketFactory() {
    @Volatile private var ctrl = -1
    private fun key(p: Int): Int { if (ctrl < 0) ctrl = p; return ctrl }
    override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
    override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket = d.createSocket(s, host, key(port), autoClose)
    override fun createSocket(host: String?, port: Int): Socket = d.createSocket(host, port)
    override fun createSocket(host: String?, port: Int, la: InetAddress?, lp: Int): Socket = d.createSocket(host, port, la, lp)
    override fun createSocket(a: InetAddress?, port: Int): Socket = d.createSocket(a, port)
    override fun createSocket(a: InetAddress?, port: Int, la: InetAddress?, lp: Int): Socket = d.createSocket(a, port, la, lp)
}

private class ReuseSpi(private val d: SSLContext) : SSLContextSpi() {
    private val f = ReuseFactory(d.socketFactory)
    override fun engineInit(km: Array<KeyManager>?, tm: Array<TrustManager>?, sr: SecureRandom?) {}   // [d] is initialised already
    override fun engineGetSocketFactory(): SSLSocketFactory = f
    override fun engineGetServerSocketFactory(): SSLServerSocketFactory = d.serverSocketFactory
    override fun engineCreateSSLEngine(): SSLEngine = d.createSSLEngine()
    override fun engineCreateSSLEngine(host: String?, port: Int): SSLEngine = d.createSSLEngine(host, port)
    override fun engineGetServerSessionContext(): SSLSessionContext? = d.serverSessionContext
    override fun engineGetClientSessionContext(): SSLSessionContext? = d.clientSessionContext
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
        fun forget(id: String) { SftpFs.forget(id); FtpCache.clear(id); pools.keys.filter { it.startsWith("$id|") }.forEach { k -> pools.remove(k)?.let { p -> synchronized(p.idle) { p.idle.forEach { try { it.first.disconnect() } catch (_: Exception) {} }; p.idle.clear() } } } }
        private val IDLE_MAX_MS = 25_000L
        private const val KEEP_EVERY_MS = 12_000L    // part 3: an idle connection older than this gets a NOOP from the keeper (servers drop idle logins after 30-60 s)
        private const val KEEP_MS = 180_000L         // part 3: idle connections are closed this long after the server was last used (no login held forever)
        private const val PF_KIDS = 6                // part 3: sub-folders prefetched after a listing
        private const val PF_GAP_MS = 20_000L        // part 3: the same folder is not prefetched twice within this time
        private const val LINK_MAX = 200             // part 4: symlinks resolved (CWD) per listing
        private const val LINK_BUDGET_MS = 4_000L    // part 4: time one listing may spend on symlink checks; the rest is resolved by the next listing
        private const val LINK_PAR_MIN = 8           // part 4: from this many unresolved links on, spare connections help
        private const val LINK_HELPERS = 2
        @Volatile private var keeperOn = false

        private fun startKeeper() {
            if (keeperOn) return
            keeperOn = true
            Thread({
                while (true) {
                    try { Thread.sleep(6_000) } catch (_: InterruptedException) {}
                    try { keep() } catch (_: Throwable) {}
                }
            }, "ftp-keeper").also { it.isDaemon = true }.start()
        }

        /** NOOP on every idle connection that is due, close dead ones and (3 min after the last use) all idle ones. */
        private fun keep() {
            val now = System.currentTimeMillis()
            for (p in pools.values) {
                val alive = now - p.lastUse < KEEP_MS
                val due = synchronized(p.idle) { p.idle.filter { !alive || now - it.second >= KEEP_EVERY_MS } }
                for (e in due) {
                    if (!synchronized(p.idle) { p.idle.remove(e) }) continue   // a lease took it meanwhile
                    val c = e.first
                    val ok = alive && try { c.isConnected && c.sendNoOp() } catch (_: Exception) { false }
                    if (ok) synchronized(p.idle) { p.idle.addLast(c to System.currentTimeMillis()) }
                    else try { c.disconnect() } catch (_: Exception) {}
                }
            }
        }
        private const val HASH_MAX = 512L shl 20   // larger files: size check only (the server would need minutes to hash them)
    }

    private val poolKey get() = "$id|$host|$port|$user|${pw.hashCode()}|$ftps|$active|$natfix|$timeoutMs|$encoding|$hidden"
    private val pool: FtpPool get() = pools.getOrPut(poolKey) { FtpPool() }.also { startKeeper() }

    // ------------------------------------------------------------------ connections
    /** FTPS client on the server's shared TLS context, with session reuse for data connections (see [ReuseFactory]). Self-signed certificates are accepted (LAN servers). */
    private fun newFtps(): FTPSClient {
        val p = pool
        val ctx = p.tls ?: synchronized(p) {
            p.tls ?: SSLContext.getInstance("TLS").also { it.init(null, arrayOf<TrustManager>(TrustManagerUtils.getAcceptAllTrustManager()), null); p.tls = it }
        }
        return FTPSClient(false, object : SSLContext(ReuseSpi(ctx), ctx.provider, ctx.protocol) {})
    }

    private fun newClient(): FTPClient {
        val c = if (ftps) newFtps() else FTPClient()
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
        c.setKeepAlive(true)   // part 3: SO_KEEPALIVE on the control socket (NAT / mobile networks forget silent connections)
        try {
            c.connect(ip, port)
            c.soTimeout = timeoutMs
            c.setTcpNoDelay(true)   // small command / reply lines must not wait for Nagle
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
            pool.syst?.let { try { c.configure(org.apache.commons.net.ftp.FTPClientConfig(it)) } catch (_: Throwable) {} }   // no SYST round trip before the first LIST
            val f = feats(c)   // asked once per server
            if ("MLST" in f) try { c.sendCommand("OPTS", "MLST type;size;modify;") } catch (_: IOException) {}   // shorter MLSD lines: only what the list shows
            return c
        } catch (e: Throwable) { try { c.disconnect() } catch (_: Exception) {}; throw e }
    }

    internal fun lease(): Lease {
        val p = pool
        if (!p.sem.tryAcquire(30, TimeUnit.SECONDS)) throw IOException("The server has too many open transfers - try again")
        return acquired(p)
    }

    /** Part 3: a connection for background work (prefetch) - only when at least 3 of the 4 slots are free, so it never makes a tap wait. null = busy. */
    private fun leaseSpare(): Lease? {
        val p = pool
        if (p.sem.availablePermits() < 3 || !p.sem.tryAcquire()) return null
        return acquired(p)
    }

    /** Part 4: a helper connection while a listing already holds one: only when at least 2 slots are free (one stays free for a tap). null = none. */
    private fun leaseHelper(): Lease? {
        val p = pool
        if (p.sem.availablePermits() < 2 || !p.sem.tryAcquire()) return null
        return try { acquired(p) } catch (_: Throwable) { null }   // acquired() gives the slot back when it fails
    }

    /** One slot of [FtpPool.sem] is already held: hand out an idle connection (checked) or open a new one; the slot is given back when that fails. */
    private fun acquired(p: FtpPool): Lease {
        p.lastUse = System.currentTimeMillis()
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
        val fe = pool.feat
        val tryMlsd = fe == null || "MLSD" in fe || "MLST" in fe   // FEAT says no MLSD: straight to LIST (saves a failed command on every folder)
        var arr: Array<FTPFile>? = if (tryMlsd) (try { c.mlistDir(path) } catch (_: IOException) { null }) else null
        var mlsd = arr != null && FTPReply.isPositiveCompletion(c.replyCode)
        if (!mlsd) {
            if (tryMlsd && (c.replyCode == 550 || c.replyCode == 553)) throw replyErr(c)   // folder missing / not allowed: LIST would only say the same
            arr = c.listFiles(path)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw replyErr(c)
            if (pool.syst == null) try { c.systemType?.let { st -> if ("UNIX" in st.uppercase() || "WINDOWS" in st.uppercase()) pool.syst = st } } catch (_: Exception) {}
        }
        val kept = ArrayList<FTPFile>()
        val links = ArrayList<String>()
        for (f in arr ?: return emptyList()) {
            if (f == null) continue
            val n = f.name ?: continue
            if (n == "." || n == ".." || n.isEmpty()) continue
            val raw = f.rawListing.orEmpty().lowercase()
            if (mlsd && ("type=cdir" in raw || "type=pdir" in raw)) continue
            if (!f.isDirectory && !f.isFile && !f.isSymbolicLink) continue   // devices, pipes, ...
            if (f.isSymbolicLink) links.add(vjoin(path, n))
            kept.add(f)
        }
        if (links.isNotEmpty()) resolveLinks(c, links)   // part 4: folder-or-file of every link, in parallel when there are many
        val out = ArrayList<Item>(kept.size)
        for (f in kept) {
            var dir = f.isDirectory
            if (f.isSymbolicLink) dir = pool.links[vjoin(path, f.name)] ?: (f.link?.endsWith("/") == true)   // not resolved (cap / time): guess from the target, the next listing tries again
            out.add(toItem(f, dir))
        }
        return out
    }

    /**
     * Part 4: folder or file for every symlink in [paths] (remembered in [FtpPool.links]). One CWD each, at most [LINK_MAX] per listing and [LINK_BUDGET_MS] in total;
     * with [LINK_PAR_MIN]+ links up to [LINK_HELPERS] spare connections work on the same queue (never the last free slot). A connection problem leaves the link unresolved
     * (nothing is remembered); what is left is resolved by the next listing.
     */
    private fun resolveLinks(c: FTPClient, paths: List<String>) {
        val p = pool
        val todo = ArrayList<String>()
        for (x in paths) { if (todo.size >= LINK_MAX) break; if (p.links[x] == null) todo.add(x) }
        if (todo.isEmpty()) return
        val q = java.util.concurrent.ConcurrentLinkedQueue(todo)
        val end = System.currentTimeMillis() + LINK_BUDGET_MS
        fun work(cl: FTPClient): Boolean {
            while (System.currentTimeMillis() < end) {
                val x = q.poll() ?: return true
                val r = try { cl.changeWorkingDirectory(x) } catch (_: IOException) { return false }
                if (p.links.size > 2000) p.links.clear()
                p.links[x] = r
            }
            return true
        }
        val threads = ArrayList<Thread>()
        if (todo.size >= LINK_PAR_MIN) repeat(LINK_HELPERS) {
            val l = leaseHelper() ?: return@repeat
            val t = Thread({ val ok = try { work(l.c) } catch (_: Throwable) { false }; l.release(ok) }, "ftp-links")
            t.isDaemon = true; t.start(); threads.add(t)
        }
        work(c)
        for (t in threads) try { t.join(LINK_BUDGET_MS + 2_000) } catch (_: InterruptedException) {}
    }

    private fun parentAbs(abs: String) = abs.substringBeforeLast('/', "").ifEmpty { "/" }

    /** A listing through the cache ([FtpCache]); [fresh] skips the cached copy. Identical concurrent listings share one request. */
    private fun listCached(c: FTPClient, abs: String, fresh: Boolean = false): List<Item> {
        if (!fresh) FtpCache.get(id, abs)?.let { return it }
        val t0 = System.currentTimeMillis()
        synchronized(FtpCache.lock(id, abs)) {
            if (!fresh) FtpCache.get(id, abs)?.let { return it }
            else FtpCache.getSince(id, abs, t0)?.let { return it }   // a listing that finished while this one waited for the lock (warm-up / prefetch / refresh) is as good as a new one
            val r = listRaw(c, abs)
            FtpCache.put(id, abs, r)
            return r
        }
    }

    /** After a change on the server: forget the listing of [v], of every parent up to the root, and of the subtree below it when [tree]. */
    private fun dirty(v: String, tree: Boolean = false) {
        pool.dirtyAt = System.currentTimeMillis()   // part 4: listings that started before this are not stored by fill()
        try {
            var a = fp(v)
            FtpCache.drop(id, a, tree)
            while (a != "/") { a = parentAbs(a); FtpCache.drop(id, a, false) }
        } catch (_: Exception) {}
    }

    override fun invalidate(v: String) { try { FtpCache.drop(id, fp(v), false) } catch (_: Exception) {} }

    /**
     * Part 4: a listing for search / walk / folder totals. Always from the server unless [useCache] and a fresh copy exists; what the server answers is stored
     * in the cache ([FtpCache.fill], capped per operation), so the folders a search / copy / "properties" just read open instantly afterwards.
     */
    private fun listFill(c: FTPClient, abs: String, f: FtpCache.Fill, useCache: Boolean): List<Item> {
        if (useCache) FtpCache.get(id, abs)?.let { return it }
        val t = System.currentTimeMillis()
        val r = listRaw(c, abs)
        FtpCache.fill(id, abs, r, t, pool.dirtyAt, f)
        return r
    }

    /** The entry [v] itself (null = missing). Reads the parent's cached listing unless [fresh] (name-clash checks); with MLST on the server a miss asks for just that entry. */
    private fun entry(c: FTPClient, v: String, fresh: Boolean = false): Item? {
        val n = vnorm(v)
        if (n == "/") return Item("/", true, 0L, 0L)
        val parent = fp(vdir(n))
        val want = vbase(n)
        if (!fresh) {
            FtpCache.get(id, parent)?.let { l -> return l.firstOrNull { it.name == want } }   // parent known: no round trip at all
            mlstEntry(c, fp(n), want)?.let { return it }
        }
        return listCached(c, parent, fresh).firstOrNull { it.name == want }
    }

    /** Part 4: MLST of one path (one control-channel round trip, no data connection, no parent listing). null = not possible / not understood: the caller lists the parent instead. */
    private fun mlstEntry(c: FTPClient, abs: String, want: String): Item? {
        val p = pool
        val fe = p.feat
        if (p.noMlst || fe == null || "MLST" !in fe) return null
        val f = try { c.mlistFile(abs) } catch (e: org.apache.commons.net.MalformedServerReplyException) { p.noMlst = true; return null }   // IOException (dropped connection) goes up
        if (f == null) {
            if (c.replyCode in 500..504) p.noMlst = true   // not implemented after all
            return null   // 550 (missing / not allowed) too: the parent listing gives the definite answer
        }
        val dir = when {
            f.isDirectory -> true
            f.isFile -> false
            f.isSymbolicLink -> { resolveLinks(c, listOf(abs)); p.links[abs] ?: return null }
            else -> return null
        }
        val mt = try { (f.timestamp?.timeInMillis ?: 0L) / 1000 } catch (_: Exception) { 0L }
        return Item(want, dir, if (dir) 0L else maxOf(f.size, 0L), mt, null, null, want.startsWith("."))
    }

    /** Same, only when the parent is already cached: no connection needed. */
    private fun cachedEntry(v: String): Item? {
        val n = vnorm(v)
        if (n == "/") return null
        val want = vbase(n)
        return FtpCache.get(id, fp(vdir(n)))?.firstOrNull { it.name == want }
    }

    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun ls(v: String): List<Item> {
        val r = lsPlain(v)
        prefetchAround(v, r)   // part 3: the parent and the first sub-folders are read in the background while the user looks at this list
        return r
    }

    private fun lsPlain(v: String): List<Item> {
        val a = fp(v)
        val t0 = System.currentTimeMillis()
        FtpCache.get(id, a)?.let { warmUp(); FtpCache.hits.incrementAndGet(); FtpCache.logTime("hit", a, System.currentTimeMillis() - t0); return it }   // fresh hit: no connection, no round trip (the connection is warmed up for the next tap)
        FtpCache.getStale(id, a)?.let { old -> FtpCache.stale.incrementAndGet(); FtpCache.logTime("stale", a, System.currentTimeMillis() - t0); refreshLater(v, a, old); return old }   // stale-while-revalidate: show the old list now, refresh in the background
        FtpCache.miss.incrementAndGet()
        return op { c -> listCached(c, a) }.also { FtpCache.logTime("miss", a, System.currentTimeMillis() - t0) }
    }

    /**
     * Part 3, warm-up: when a server is opened, log in (+ FEAT, OPTS, TLS) and read the start folder in the background, so the first tap finds a ready
     * connection and a fresh listing. No-op when a connection is idle already, or a warm-up runs, or the server's port does not answer.
     */
    override fun warmUp() {
        val p = pool
        p.lastUse = System.currentTimeMillis()
        if (p.warming || Ftp.reach[id] == false || synchronized(p.idle) { p.idle.isNotEmpty() }) return
        p.warming = true
        Thread({ try { warmRun() } finally { p.warming = false } }, "ftp-warm").also { it.isDaemon = true }.start()
    }

    private fun warmRun() {
        try {
            val l = leaseSpare() ?: return
            var keep = false
            try {
                val root = fp("/")
                if (FtpCache.get(id, root) == null) try { listCached(l.c, root) } catch (e: IOException) { if (transient(e)) throw e }   // start folder: the page asks for it next
                keep = true
            } finally { l.release(keep) }
        } catch (_: Throwable) {}
    }

    /**
     * Part 3, prefetch: after a listing of [v], list its parent and its first [PF_KIDS] sub-folders (UI order: A-Z) into the cache - one background worker per
     * server on a spare connection, depth 1, never twice within [PF_GAP_MS], stops as soon as a tap needs the connections. Errors are ignored.
     */
    private fun prefetchAround(v: String, items: List<Item>) {
        val p = pool
        if (Ftp.reach[id] == false) return
        val n = vnorm(v)
        val now = System.currentTimeMillis()
        val want = ArrayList<String>()
        if (n != "/") want.add(vdir(n))
        items.filter { it.dir }.sortedBy { it.name.lowercase() }.take(PF_KIDS).forEach { want.add(vjoin(n, it.name)) }
        val todo = want.filter { w -> val a = try { fp(w) } catch (_: Exception) { null }; a != null && FtpCache.get(id, a) == null && now - (p.pfAt[a] ?: 0L) > PF_GAP_MS }
        if (todo.isEmpty() || !p.pfBusy.compareAndSet(false, true)) return
        Thread({ try { prefetchRun(p, todo) } finally { p.pfBusy.set(false) } }, "ftp-prefetch").also { it.isDaemon = true }.start()
    }

    private fun prefetchRun(p: FtpPool, todo: List<String>) {
        try {
            val l = leaseSpare() ?: return
            var keep = true
            try {
                for (w in todo) {
                    if (p.sem.availablePermits() < 2 || p.sem.hasQueuedThreads()) break   // somebody needs the connections: stop
                    val a = fp(w)
                    if (FtpCache.get(id, a) != null) continue
                    if (p.pfAt.size > 500) p.pfAt.clear()
                    p.pfAt[a] = System.currentTimeMillis()
                    try { listCached(l.c, a) } catch (e: IOException) { if (transient(e)) { keep = false; break } }   // "no access" / missing: skip the folder
                }
            } catch (_: Throwable) { keep = false } finally { l.release(keep) }
        } catch (_: Throwable) {}
    }

    /** One background refresh per folder; when the server's answer differs the page is told to reload ([Core.onFtpChanged]). Offline = keep the old list. */
    private fun refreshLater(v: String, a: String, old: List<Item>) {
        if (!refreshing.add(a)) return
        Thread({
            try {
                val fresh = op { c -> listCached(c, a, true) }
                if (fresh != old) Core.onFtpChanged?.invoke(id, v)
            } catch (_: Throwable) {
            } finally { refreshing.remove(a) }
        }, "ftp-refresh").also { it.isDaemon = true }.start()
    }

    override fun names(v: String): MutableSet<String> {   // name-clash checks: never from the cache
        try { FtpCache.drop(id, fp(v), false) } catch (_: Exception) {}
        return try { lsPlain(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }
    }

    private fun searchIn(c: FTPClient, v: String, ql: String, out: MutableList<Item>, end: Long): Boolean {
        val fill = FtpCache.Fill()
        val stack = ArrayDeque<String>()
        stack.addLast(vnorm(v))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val entries = try { listFill(c, fp(d), fill, true) } catch (e: IOException) { if (e is FTPConnectionClosedException) throw e; continue }
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
            val fill = FtpCache.Fill()
            val stack = ArrayDeque<String>()
            stack.addLast("")
            while (stack.isNotEmpty()) {   // a folder's entry is handed over before its children: mkdir always precedes its files
                val r0 = stack.removeLast()
                val kids = try { listFill(c, fp(if (r0.isEmpty()) v else vjoin(v, r0)), fill, false) }   // a copy / move reads the server (never the cache), but leaves the listings behind
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
                val fill = FtpCache.Fill()
                val stack = ArrayDeque<String>(); stack.addLast(n)
                loop@ while (stack.isNotEmpty()) {
                    val d = stack.removeLast()
                    val kids = try { listFill(c, fp(d), fill, true) } catch (e: IOException) { if (e is FTPConnectionClosedException) throw e; continue }
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
        val me = cachedEntry(v) ?: op { c -> entry(c, v) } ?: throw NotFound("No such file or directory")
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

    override fun mkdir(v: String) { try { op { c -> mkdirs(c, fp(v)) } } finally { dirty(v) } }

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
    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) { try { upload(v, input, size, 0L, null, cb) } finally { dirty(v) } }

    /** Server-side copy (ProFTPD mod_copy: SITE CPFR / CPTO). False when the server has no such command or refuses; the size of the copy is checked. */
    override fun copyWithin(v: String, toV: String): Boolean {
        if (pool.noCopy || vnorm(v) == "/" || vnorm(toV) == "/") return false
        val a = fp(v); val b = fp(toV)
        dirty(toV)
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
        dirty(v)
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
        try { upload(v, input, size, offset, tag, cb) } finally { dirty(v) }
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
        try { op(false) { c ->
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
        } } finally { dirty(n, true) }
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        if (vnorm(v) == "/") throw BadReq("cannot rename the root")
        val dst = vjoin(vdir(v), newName)
        try { op(false) { c ->
            val clash = entry(c, dst, true)
            if (clash != null && !vbase(v).equals(newName, ignoreCase = true)) throw Exists("name already exists")
            if (!c.rename(fp(v), fp(dst))) throw replyErr(c)
            Unit
        } } finally { dirty(v, true); dirty(dst, true) }
    }

    override fun move(v: String, toV: String) {
        try { op(false) { c ->
            if (entry(c, toV, true) != null) throw Exists("name already exists")
            mkdirs(c, fp(vdir(toV)))
            if (!c.rename(fp(v), fp(toV))) throw replyErr(c)
            Unit
        } } finally { dirty(v, true); dirty(toV, true) }
    }
}
