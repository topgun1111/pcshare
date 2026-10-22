package com.lanshare.app.core

import android.util.Base64
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.jcraft.jsch.UserInfo
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class SConn(val s: Session, val ch: ChannelSftp)
internal class SPool { val sem = Semaphore(4); val idle = ArrayDeque<Pair<SConn, Long>>()
    @Volatile var dirtyAt = 0L                                    // part 4: last time a write on this server finished (see FtpCache.fill)
    val links = ConcurrentHashMap<String, Boolean>() }            // part 4: symlink path -> is it a folder (remembered, one stat each)

/** One borrowed SSH session + SFTP channel. [release] puts it back (keep = true) or closes it. */
internal class SLease(private val cn: SConn, private val pool: SPool) {
    val c: ChannelSftp get() = cn.ch
    private var done = false
    fun release(keep: Boolean) {
        if (done) return
        done = true
        if (keep && cn.s.isConnected && cn.ch.isConnected) synchronized(pool.idle) { pool.idle.addLast(cn to System.currentTimeMillis()) }
        else { try { cn.ch.disconnect() } catch (_: Exception) {}; try { cn.s.disconnect() } catch (_: Exception) {} }
        pool.sem.release()
    }
}

/** Trust on first use: the server's host key is pinned in the saved server ("hostkey"); a different key later is refused. */
private class Tofu(private val cfg: JSONObject) : HostKeyRepository {
    private fun enc(k: ByteArray) = Base64.encodeToString(k, Base64.NO_WRAP)
    val first: Boolean get() = cfg.optString("hostkey").isEmpty()
    override fun check(host: String?, key: ByteArray?): Int {
        val st = cfg.optString("hostkey")
        return when {
            st.isEmpty() -> HostKeyRepository.NOT_INCLUDED
            key != null && st == enc(key) -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }
    override fun add(hostkey: HostKey?, ui: UserInfo?) { if (hostkey != null) synchronized(cfg) { cfg.put("hostkey", hostkey.key).put("hostkeytype", hostkey.type) } }
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "lanshare"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** Read-only, seekable view of a remote SFTP file (re-opened at the current position after a dropped connection). */
internal class SftpSource(private val fs: SftpFs, private val path: String, override val size: Long) : Source() {
    private var pos = 0L
    private var lease: SLease? = null
    private var ins: InputStream? = null
    override val seekable: Boolean get() = true

    private fun drop() {
        val l = lease; val i = ins
        ins = null; lease = null
        var ok = true
        try { i?.close() } catch (_: Exception) { ok = false }
        l?.release(ok)
    }

    override fun seek(pos: Long) { drop(); this.pos = pos }

    private fun begin() {
        val l = fs.lease()
        lease = l
        try { ins = l.c.get(path, null as SftpProgressMonitor?, pos) }
        catch (e: Throwable) { lease = null; l.release(false); throw fs.mapErr(e) }
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
                if (n < 0) { drop(); return -1 }
                pos += n
                if (pos >= size) drop()
                return n
            } catch (e: NotFound) { throw e
            } catch (e: Denied) { throw e
            } catch (e: Exception) {
                last = e
                val l = lease; ins = null; lease = null; l?.release(false)
                try { Thread.sleep(500L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw IOException("connection lost (${last?.message})")
    }

    override fun close() { drop() }
}

/**
 * An SFTP (SSH) server, same interface as FtpFs / SmbFs. Saved in the same list as the FTP servers ("proto": "sftp"); password login only.
 * The host key is pinned on the first connection. Paths are virtual ('/' = the configured start folder).
 */
class SftpFs(private val cfg: JSONObject) : Endpoint {
    override val id: String = cfg.getString("id")
    override val name: String = cfg.getString("name")
    private val host: String
    private val port: Int
    private val user: String = cfg.optString("user", "")
    private val pw: String = cfg.optString("password", "")
    private val timeoutMs: Int = cfg.optInt("timeout", 60).coerceIn(5, 300) * 1000
    private val encoding: String = cfg.optString("encoding", "").ifEmpty { "UTF-8" }
    private val base: String = cfg.optString("dir", "").let { if (it.isEmpty()) "" else vnorm(it) }.let { if (it == "/") "" else it }

    init { val (h, p) = Ftp.split(cfg.getString("host")); host = h; port = if (cfg.getString("host").count { it == ':' } == 1) p else 22 }

    companion object {
        private val pools = ConcurrentHashMap<String, SPool>()
        fun forget(id: String) { FtpCache.clear(id); pools.keys.filter { it.startsWith("$id|") }.forEach { k -> pools.remove(k)?.let { p -> synchronized(p.idle) { p.idle.forEach { try { it.first.ch.disconnect(); it.first.s.disconnect() } catch (_: Exception) {} }; p.idle.clear() } } } }
        private const val IDLE_MAX_MS = 25_000L
        private const val LINK_MAX = 200             // part 4: symlinks resolved (stat) per listing
        private const val LINK_BUDGET_MS = 4_000L    // part 4: time one listing may spend on them; the rest is resolved by the next listing
    }

    private val poolKey get() = "$id|$host|$port|$user|${pw.hashCode()}|$timeoutMs|$encoding"
    private val pool: SPool get() = pools.getOrPut(poolKey) { SPool() }

    // ------------------------------------------------------------------ connections
    private fun newConn(): SConn {
        val ip = try { InetAddress.getByName(host).hostAddress ?: host } catch (_: UnknownHostException) { throw IOException("Unknown server address: $host") }
        val repo = Tofu(cfg)
        val jsch = JSch()
        jsch.hostKeyRepository = repo
        val s = jsch.getSession(user.ifEmpty { "anonymous" }, ip, port)
        try {
            s.setPassword(pw)
            s.userInfo = object : UserInfo {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String = pw
                override fun promptPassword(message: String?): Boolean = true
                override fun promptPassphrase(message: String?): Boolean = false
                override fun promptYesNo(message: String?): Boolean = repo.first   // first connection: trust the key and pin it
                override fun showMessage(message: String?) {}
            }
            s.setConfig("StrictHostKeyChecking", "ask")
            s.setConfig("PreferredAuthentications", "password")
            when (cfg.optString("hostkeytype")) {   // once pinned, only that kind of key is negotiated
                "" -> {}
                "ssh-rsa" -> s.setConfig("server_host_key", "rsa-sha2-512,rsa-sha2-256,ssh-rsa")
                else -> s.setConfig("server_host_key", cfg.optString("hostkeytype"))
            }
            s.setSocketFactory(object : com.jcraft.jsch.SocketFactory {   // every socket goes out through the right network (Wi-Fi / Tailscale)
                override fun createSocket(h: String, p: Int): Socket = Net.newSocket(ip).also { it.connect(InetSocketAddress(h, p), 8_000) }
                override fun getInputStream(so: Socket): InputStream = so.getInputStream()
                override fun getOutputStream(so: Socket): OutputStream = so.getOutputStream()
            })
            s.timeout = timeoutMs
            s.setServerAliveInterval(20_000)
            s.connect(8_000)
            val ch = s.openChannel("sftp") as ChannelSftp
            ch.connect(8_000)
            try { ch.setFilenameEncoding(encoding) } catch (_: Exception) {}
            return SConn(s, ch)
        } catch (e: Throwable) { try { s.disconnect() } catch (_: Exception) {}; throw e }
    }

    internal fun lease(): SLease {
        val p = pool
        if (!p.sem.tryAcquire(30, TimeUnit.SECONDS)) throw IOException("The server has too many open transfers - try again")
        try {
            while (true) {
                val e = synchronized(p.idle) { if (p.idle.isEmpty()) null else p.idle.removeLast() } ?: break
                val (cn, t) = e
                val ok = try { cn.s.isConnected && cn.ch.isConnected && (System.currentTimeMillis() - t < IDLE_MAX_MS || run { cn.s.sendKeepAliveMsg(); true }) } catch (_: Exception) { false }
                if (ok) return SLease(cn, p)
                try { cn.ch.disconnect(); cn.s.disconnect() } catch (_: Exception) {}
            }
            return SLease(newConn(), p)
        } catch (e: Throwable) { p.sem.release(); throw e }
    }

    internal fun mapErr(e: Throwable): Throwable {
        if (e is NotFound || e is Denied || e is Exists || e is BadReq || e is Cancelled || e is Full) return e
        return when (e) {
            is SftpException -> {
                val t = (e.message ?: "").ifEmpty { "SFTP error ${e.id}" }
                val low = t.lowercase()
                when {
                    e.id == 2 -> NotFound("Not found on the server: $t")
                    e.id == 3 -> Denied("Access denied by the server ($t)")
                    "no space" in low || "quota" in low || "disk full" in low -> Full("The server is full ($t)")
                    else -> IOException(t, e)
                }
            }
            is JSchException -> {
                val t = e.message ?: ""
                val cause = e.cause
                when {
                    "has been changed" in t -> IOException("The server's host key changed (possible attack). Remove the server and add it again to trust the new key.")
                    "UnknownHostKey" in t -> IOException("The server's host key was not accepted")
                    "Auth" in t -> Denied("Access denied - check the username and password")
                    cause is UnknownHostException -> IOException("Unknown server address: $host")
                    cause is ConnectException -> IOException("Cannot connect to $host:$port (${cause.message})")
                    cause is SocketTimeoutException || "timeout" in t.lowercase() -> IOException("The server is not answering")
                    else -> IOException("SSH: $t", e)
                }
            }
            is UnknownHostException -> IOException("Unknown server address: $host")
            is ConnectException -> IOException("Cannot connect to $host:$port (${e.message})")
            is SocketTimeoutException -> IOException("The server is not answering")
            is IOException -> e
            else -> IOException(errText(e), e)
        }
    }

    private fun transient(e: Throwable): Boolean = when (e) {
        is SftpException -> e.id == 6 || e.id == 7 || (e.message ?: "").contains("down", true)
        is JSchException -> { val t = e.message ?: ""; !("Auth" in t || "HostKey" in t || "host key" in t.lowercase()) && e.cause !is UnknownHostException }
        is java.net.SocketException, is SocketTimeoutException, is java.io.EOFException -> true
        else -> false
    }

    /** Runs [fn] on a pooled connection (a connection that cannot be opened is retried twice; a failure in the middle too when [idem]potent). */
    private fun <T> op(idem: Boolean = true, fn: (ChannelSftp) -> T): T {
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

    // ------------------------------------------------------------------ paths and entries
    private fun fp(v: String): String {
        val n = vnorm(v)
        if (n.indexOf('\r') >= 0 || n.indexOf('\n') >= 0) throw BadReq("invalid name")
        return if (base.isEmpty()) n else if (n == "/") base else base + n
    }

    /** JSch treats * ? [ in ls / get / rm paths as wildcards: escape them so a file name is never a pattern. */
    private fun q(p: String): String = buildString { for (ch in p) { if (ch == '\\' || ch == '*' || ch == '?' || ch == '[') append('\\'); append(ch) } }

    private fun listRaw(ch: ChannelSftp, path: String): List<Item> {
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + LINK_BUDGET_MS
        var links = 0
        for (o in ch.ls(q(path)) as Vector<*>) {
            val e = o as? ChannelSftp.LsEntry ?: continue
            val n = e.filename
            if (n == "." || n == ".." || n.isEmpty()) continue
            val a = e.attrs
            if (a.isChr || a.isBlk || a.isFifo || a.isSock) continue   // devices, pipes, ...
            var dir = a.isDir
            var size = a.size
            var mt = a.mTime.toLong()
            if (a.isLink) {   // part 4: folder or file? one stat each (remembered), at most LINK_MAX per listing and LINK_BUDGET_MS in total
                val lk = vjoin(path, n)
                val known = pool.links[lk]
                if (links < LINK_MAX && System.currentTimeMillis() < end) {
                    links++
                    val st = try { ch.stat(lk) } catch (ex: SftpException) { if (ex.id == 2) null else if (transient(ex)) throw ex else null }   // broken link / no access: stays a file
                    dir = st?.isDir ?: false
                    if (st != null && !dir) { size = st.size; mt = st.mTime.toLong() }   // a link to a file shows the file's size, not the link's
                    if (pool.links.size > 2000) pool.links.clear()
                    pool.links[lk] = dir
                } else dir = known ?: false
            }
            out.add(Item(n, dir, if (dir) 0L else maxOf(size, 0L), mt, null, null, n.startsWith(".")))
        }
        return out
    }

    private fun entry(ch: ChannelSftp, v: String): Item? {
        val n = vnorm(v)
        if (n == "/") return Item("/", true, 0L, 0L)
        val a = try { ch.stat(fp(n)) } catch (e: SftpException) { if (e.id == 2) return null else throw e }
        return Item(vbase(n), a.isDir, if (a.isDir) 0L else maxOf(a.size, 0L), a.mTime.toLong(), null, null, vbase(n).startsWith("."))
    }

    private fun sizeOf(ch: ChannelSftp, path: String): Long = try { ch.stat(path).size } catch (e: SftpException) { if (e.id == 2) -1L else throw e }

    // ------------------------------------------------------------------ listing cache (FTP speed-up part 4: the same FtpCache as FtpFs)
    private fun parentAbs(abs: String) = abs.substringBeforeLast('/', "").ifEmpty { "/" }

    /** A listing through [FtpCache]; [fresh] skips the cached copy. Identical concurrent listings share one request. */
    private fun listCached(ch: ChannelSftp, abs: String, fresh: Boolean = false): List<Item> {
        if (!fresh) FtpCache.get(id, abs)?.let { return it }
        val t0 = System.currentTimeMillis()
        synchronized(FtpCache.lock(id, abs)) {
            if (!fresh) FtpCache.get(id, abs)?.let { return it }
            else FtpCache.getSince(id, abs, t0)?.let { return it }
            val r = listRaw(ch, abs)
            FtpCache.put(id, abs, r)
            return r
        }
    }

    /** After a change on the server: forget the listing of [v], of every parent up to the root, and of the subtree below it when [tree]. */
    private fun dirty(v: String, tree: Boolean = false) {
        pool.dirtyAt = System.currentTimeMillis()
        try {
            var a = fp(v)
            FtpCache.drop(id, a, tree)
            while (a != "/") { a = parentAbs(a); FtpCache.drop(id, a, false) }
        } catch (_: Exception) {}
    }

    /** A listing for search / walk / folder totals: from the server (or a fresh cached copy when [useCache]); the answer is stored in the cache. */
    private fun listFill(ch: ChannelSftp, abs: String, f: FtpCache.Fill, useCache: Boolean): List<Item> {
        if (useCache) FtpCache.get(id, abs)?.let { return it }
        val t = System.currentTimeMillis()
        val r = listRaw(ch, abs)
        FtpCache.fill(id, abs, r, t, pool.dirtyAt, f)
        return r
    }

    override fun invalidate(v: String) { try { FtpCache.drop(id, fp(v), false) } catch (_: Exception) {} }

    private val refreshing = ConcurrentHashMap.newKeySet<String>()

    /** One background refresh per folder; when the server's answer differs the page is told to reload ([Core.onFtpChanged]). Offline = keep the old list. */
    private fun refreshLater(v: String, a: String, old: List<Item>) {
        if (!refreshing.add(a)) return
        Thread({
            try {
                val fresh = op { ch -> listCached(ch, a, true) }
                if (fresh != old) Core.onFtpChanged?.invoke(id, v)
            } catch (_: Throwable) {
            } finally { refreshing.remove(a) }
        }, "sftp-refresh").also { it.isDaemon = true }.start()
    }

    private fun lsPlain(v: String): List<Item> {
        val a = fp(v)
        FtpCache.get(id, a)?.let { return it }                                          // fresh hit: no connection
        FtpCache.getStale(id, a)?.let { old -> refreshLater(v, a, old); return old }    // stale-while-revalidate (memory, then disk)
        return op { ch -> listCached(ch, a) }
    }

    override fun ls(v: String): List<Item> = lsPlain(v)

    override fun names(v: String): MutableSet<String> {   // name-clash checks: never from the cache
        try { FtpCache.drop(id, fp(v), false) } catch (_: Exception) {}
        return try { lsPlain(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }
    }

    private fun searchIn(ch: ChannelSftp, v: String, ql: String, out: MutableList<Item>, end: Long): Boolean {
        val fill = FtpCache.Fill()
        val stack = ArrayDeque<String>()
        stack.addLast(vnorm(v))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val entries = try { listFill(ch, fp(d), fill, true) } catch (e: IOException) { continue } catch (e: SftpException) { if (transient(e)) throw e; continue }
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
        val partial = op(false) { ch -> searchIn(ch, v, q.lowercase(), out, end) }
        if (!partial) out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    override fun walk(v: String): List<WalkItem> { val res = ArrayList<WalkItem>(); walkEach(v) { res.add(it) }; return res }

    override fun walkEach(v: String, visit: (WalkItem) -> Unit) {
        val sent = HashSet<String>()   // after a transparent retry the walk restarts: what was already handed over is not handed over twice
        val out: (WalkItem) -> Unit = { w -> if (sent.add((if (w.skip) "!" else if (w.dir) "d" else "f") + w.rel)) visit(w) }
        op { ch ->
            val me = entry(ch, v) ?: throw NotFound("No such file or directory")
            if (!me.dir) { out(WalkItem("", false, me.size)); return@op }
            out(WalkItem("", true, 0))
            val fill = FtpCache.Fill()
            val stack = ArrayDeque<String>()
            stack.addLast("")
            while (stack.isNotEmpty()) {
                val r0 = stack.removeLast()
                val kids = try { listFill(ch, fp(if (r0.isEmpty()) v else vjoin(v, r0)), fill, false) }   // a copy / move reads the server, but leaves the listings behind
                           catch (e: Exception) { if (transient(e)) throw e; out(WalkItem(if (r0.isEmpty()) "(folder)" else r0, false, 0, true)); continue }
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
        return op { ch ->
            val me = entry(ch, n) ?: throw NotFound("No such file or directory")
            val o = JSONObject().put("name", me.name).put("path", n).put("dir", me.dir).put("size", me.size).put("mtime", me.mtime)
            if (me.dir) {   // totals, bounded like the SMB ones (20 s / 300 000 entries)
                val tot = LongArray(3); var partial = false; var seen = 0
                val end = System.currentTimeMillis() + 20_000L
                val fill = FtpCache.Fill()
                val stack = ArrayDeque<String>(); stack.addLast(n)
                loop@ while (stack.isNotEmpty()) {
                    val d = stack.removeLast()
                    val kids = try { listFill(ch, fp(d), fill, true) } catch (e: Exception) { if (transient(e)) throw e; continue }
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

    override fun space(v: String): Pair<Long, Long>? = null   // no portable free-space request in SFTP

    override fun open(v: String): Source {
        val p = fp(v)
        val me = op { ch -> entry(ch, v) } ?: throw NotFound("No such file or directory")
        if (me.dir) throw IOException("Is a directory")
        return SftpSource(this, q(p), me.size)
    }

    // ------------------------------------------------------------------ writing
    private fun mkdirs(ch: ChannelSftp, abs: String) {
        if (abs.isEmpty() || abs == "/") return
        var cur = ""
        for (part in abs.split('/')) {
            if (part.isEmpty()) continue
            cur += "/$part"
            try { if (ch.stat(cur).isDir) continue } catch (e: SftpException) { if (e.id != 2) throw e }
            ch.mkdir(cur)
        }
    }

    override fun mkdir(v: String) { try { op { ch -> mkdirs(ch, fp(v)) } } finally { dirty(v) } }

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) { try { upload(v, input, size, 0L, null, cb) } finally { dirty(v) } }

    override val setsMtime: Boolean get() = true

    override fun setMtime(v: String, sec: Long) {
        if (sec <= 0 || sec > Int.MAX_VALUE || vnorm(v) == "/") return
        val path = fp(v)
        try { op { ch -> ch.setMtime(path, sec.toInt()); Unit } } finally { dirty(v) }
    }

    override val resumable: Boolean get() = true

    private fun pkey(tmp: String) = "sftp|$id|$tmp"

    override fun partial(v: String, tag: String): Long = try {
        val tmp = fp(v) + ".lspart"
        if (vnorm(v) == "/" || !Partials.matches(pkey(tmp), tag)) 0L
        else op { ch -> maxOf(sizeOf(ch, tmp), 0L) }
    } catch (e: Cancelled) { throw e } catch (_: Exception) { 0L }

    override fun writeResume(v: String, input: InputStream, size: Long, offset: Long, tag: String, cb: ((Int) -> Unit)?) {
        if (size < 0 || offset < 0 || offset > size) throw BadReq("bad resume offset")
        try { upload(v, input, size, offset, tag, cb) } finally { dirty(v) }
    }

    /**
     * Stores [input] (which starts AT [offset]) as "name.lspart", checks the size, then renames it. With a [tag] (resumable copy) the .lspart stays
     * after a failure and the next try continues; a cancel, a mismatch or a wrong size removes it.
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
                val ch = l.c
                mkdirs(ch, p.substringBeforeLast('/', ""))
                if (offset > 0) {
                    val have = sizeOf(ch, tmp)
                    if (tag == null || !Partials.matches(pk, tag) || have != offset) throw BadReq("resume offset mismatch (have ${maxOf(have, 0L)})")
                }
                if (tag != null) Partials.put(pk, tag)
                val os = ch.put(tmp, null as SftpProgressMonitor?, if (offset > 0) ChannelSftp.RESUME else ChannelSftp.OVERWRITE)
                try {
                    val buf = ByteArray(CHUNK)
                    var left = size - offset
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("connection lost")
                        os.write(buf, 0, n)
                        left -= n
                        cb?.invoke(n)
                    }
                } finally { try { os.close() } catch (_: Exception) {} }
                val got = sizeOf(ch, tmp)
                if (got != size) {
                    if (got > size) keep = false
                    throw IOException("Upload incomplete: the server has ${maxOf(got, 0L)} of $size bytes")
                }
                try { ch.rm(q(p)) } catch (_: Exception) {}   // replace an existing file of that name
                ch.rename(tmp, p)
                clean = true; ok = true
                Ftp.state[id] = true
            } catch (e: Throwable) {
                val m = mapErr(e)
                if (m is Cancelled || m is BadReq) keep = false
                if (m !is NotFound && m !is Exists && m !is BadReq && m !is Cancelled) Ftp.state[id] = m is Denied && (Ftp.state[id] ?: true)
                throw m
            } finally { l.release(clean) }
        } finally {
            if (ok || !keep) Partials.forget(pk)
            if (!ok && !keep) try { op { ch -> ch.rm(q(tmp)) } } catch (_: Exception) {}   // never leave a half file behind
        }
    }

    // ------------------------------------------------------------------ delete / rename / move
    private fun why(e: Throwable): String = (e.message ?: e.javaClass.simpleName).take(80)

    private fun delTree(ch: ChannelSftp, v: String, fails: MutableList<String>, progress: ((String) -> Unit)?) {
        val kids = try { listRaw(ch, fp(v)) } catch (e: Exception) { fails.add("${vbase(v)}: ${why(e)}"); return }
        for (k in kids) {
            val cv = vjoin(v, k.name)
            try {
                if (k.dir) {
                    val before = fails.size
                    delTree(ch, cv, fails, progress)
                    if (fails.size > before) continue   // something inside stayed, so the folder cannot go either
                    ch.rmdir(fp(cv))
                } else ch.rm(q(fp(cv)))
                progress?.invoke(k.name)
            } catch (e: Cancelled) { throw e
            } catch (e: Exception) { fails.add("${k.name}: ${why(e)}") }
        }
    }

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val n = vnorm(v)
        if (n == "/") throw Denied("cannot delete the server root")
        try { op(false) { ch ->
            val me = entry(ch, n) ?: return@op
            val fails = ArrayList<String>()
            if (me.dir) {
                delTree(ch, n, fails, progress)
                if (fails.isEmpty()) { try { ch.rmdir(fp(n)); progress?.invoke(me.name) } catch (e: Cancelled) { throw e } catch (e: Exception) { fails.add("${me.name}: ${why(e)}") } }
            } else {
                try { ch.rm(q(fp(n))); progress?.invoke(me.name) } catch (e: Cancelled) { throw e } catch (e: Exception) { fails.add("${me.name}: ${why(e)}") }
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
        try { op(false) { ch ->
            val clash = entry(ch, dst)
            if (clash != null && !vbase(v).equals(newName, ignoreCase = true)) throw Exists("name already exists")
            ch.rename(fp(v), fp(dst))
            Unit
        } } finally { dirty(v, true); dirty(dst, true) }
    }

    override fun move(v: String, toV: String) {
        try { op(false) { ch ->
            if (entry(ch, toV) != null) throw Exists("name already exists")
            mkdirs(ch, fp(vdir(toV)))
            ch.rename(fp(v), fp(toV))
            Unit
        } } finally { dirty(v, true); dirty(toV, true) }
    }
}
