package com.lanshare.app.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.transport.TransportException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/** SMB share config helpers + the smbj-backed endpoint below (SmbFs). */
object Smb {
    val state = java.util.concurrent.ConcurrentHashMap<String, Boolean>()   // share id -> did the last operation work?

    fun cfg(sid: String): JSONObject? {
        val a = Cfg.smb()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == sid) return a.getJSONObject(i)
        return null
    }

    /** '192.168.1.5' or '192.168.1.5:4455' or 'PC' -> (host, port). */
    fun split(host0: String): Pair<String, Int> {
        val host = host0.trim().trim('\\', '/')
        if (host.count { it == ':' } == 1) {
            val i = host.indexOf(':')
            val p = host.substring(i + 1).toIntOrNull()
            if (p != null && p in 1..65535) return host.substring(0, i) to p
        }
        return host to 445
    }

    fun peers(): List<JSONObject> = Cfg.smb().let { a ->
        (0 until a.length()).map {
            val c = a.getJSONObject(it)
            JSONObject().put("id", c.getString("id")).put("name", c.getString("name")).put("ip", c.getString("host"))
                .put("ok", state[c.getString("id")] ?: true).put("smb", true)
        }
    }

    fun status(): JSONArray = JSONArray(Cfg.smb().let { a ->
        (0 until a.length()).map {
            val c = a.getJSONObject(it)
            JSONObject().put("id", c.getString("id")).put("name", c.getString("name")).put("host", c.getString("host"))
                .put("share", c.getString("share")).put("user", c.optString("user", "")).put("ok", state[c.getString("id")] ?: true)
        }
    })
}

/** One cached SMB connection (client + session + share) per configured share. */
private class SmbConn(val key: String, val client: SMBClient, val conn: Connection, val session: Session, val share: DiskShare) {
    val alive: Boolean get() = try { share.isConnected && conn.isConnected } catch (_: Exception) { false }
    fun close() {
        try { share.close() } catch (_: Exception) {}
        try { session.close() } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }
}

private class SmbSource(private val f: SmbFile, override val size: Long) : Source() {
    private var pos = 0L
    override val seekable: Boolean get() = true
    override fun seek(pos: Long) { this.pos = pos }
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= size) return -1
        val n = f.read(b, pos, off, minOf(len.toLong(), size - pos).toInt())
        if (n <= 0) return -1
        pos += n
        return n
    }
    override fun close() { try { f.close() } catch (_: Exception) {} }
}

/** A shared folder on a PC / NAS (SMB2/3 via smbj), same interface as LocalFs. Virtual '/' is the share root. */
class SmbFs private constructor(c: JSONObject) : Endpoint {
    override val id: String = c.getString("id")
    override val name: String = c.getString("name")
    private val host: String
    private val port: Int
    private val shareName: String = c.getString("share").trim().trim('\\', '/')
    private val user: String = c.optString("user", "")
    private val pw: String = c.optString("password", "")

    init {
        val (h, p) = Smb.split(c.getString("host"))
        host = h; port = p
    }

    companion object {
        private val pool = ConcurrentHashMap<String, SmbConn>()
        private val cfg: SmbConfig by lazy {
            // Android's built-in BouncyCastle lacks MD4/RC4 (NTLM): replace it with the full bcprov once
            try { Security.removeProvider("BC"); Security.insertProviderAt(BouncyCastleProvider(), 1) } catch (_: Exception) {}
            SmbConfig.builder().withTimeout(15, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build()
        }

        fun create(cfg: JSONObject): Endpoint = SmbFs(cfg)

        private fun isDir(a: Long) = EnumWithValue.EnumUtils.isSet(a, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
    }

    private fun connect(): SmbConn {
        val key = "$id|$host|$port|$shareName|$user|${pw.hashCode()}"
        pool[key]?.let { if (it.alive) return it else { pool.remove(key); it.close() } }
        val client = SMBClient(cfg)
        try {
            val conn = client.connect(host, port)
            val auth = if (user.isEmpty()) AuthenticationContext.guest()
                       else AuthenticationContext(user, pw.toCharArray(), null)
            val session = conn.authenticate(auth)
            val share = session.connectShare(shareName) as? DiskShare
                ?: throw IOException("That share is not a file share")
            return SmbConn(key, client, conn, session, share).also { pool[key] = it }
        } catch (e: Throwable) {
            try { client.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun dropConn() {
        pool.keys.filter { it.startsWith("$id|") }.forEach { k -> pool.remove(k)?.close() }
    }

    private fun mapErr(e: Throwable): Throwable {
        if (e is NotFound || e is Denied || e is Exists || e is BadReq || e is Cancelled) return e
        if (e is SMBApiException) {
            val msg = (e.message ?: "").ifEmpty { e.status.name }
            return when (e.status.name) {   // matched by name: stays compatible across smbj versions
                "STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_NO_SUCH_FILE" -> NotFound(msg)
                "STATUS_BAD_NETWORK_NAME" -> NotFound("Share not found on that server - check the share name")
                "STATUS_ACCESS_DENIED", "STATUS_LOGON_FAILURE", "STATUS_ACCOUNT_DISABLED", "STATUS_ACCOUNT_LOCKED_OUT",
                "STATUS_WRONG_PASSWORD", "STATUS_PASSWORD_EXPIRED" -> Denied("Access denied - check the username and password")
                "STATUS_OBJECT_NAME_COLLISION" -> Exists(msg)
                else -> IOException(msg)
            }
        }
        val low = (e.message ?: "").lowercase()
        if ("logon" in low || "authenticat" in low) return Denied("Access denied - check the username and password")
        if (e is IOException) return e
        return IOException(errText(e), e)
    }

    /** Runs [fn] on the cached share (one transparent reconnect if the link went stale) and keeps the status dot up to date. */
    private fun <T> op(retry: Boolean = true, fn: (DiskShare) -> T): T {
        try {
            val r = try { fn(connect().share) }
                    catch (e: TransportException) { dropConn(); if (!retry) throw e; fn(connect().share) }
            Smb.state[id] = true
            return r
        } catch (e: Throwable) {
            if (e is TransportException || (e is SMBApiException && e.status.name == "STATUS_NETWORK_NAME_DELETED")) dropConn()
            val m = mapErr(e)
            if (m !is NotFound && m !is Exists && m !is BadReq && m !is Cancelled)
                Smb.state[id] = m is Denied && (Smb.state[id] ?: true)
            throw m
        }
    }

    private fun rel(v: String) = vnorm(v).trim('/').replace('/', '\\')

    private fun item(name: String, attrs: Long, size: Long, mtime: Long, path: String? = null): Item {
        val d = isDir(attrs)
        return Item(name, d, if (d) 0L else size, mtime, null, path)
    }

    private fun listRaw(s: DiskShare, v: String): List<Item> =
        s.list(rel(v)).filter { it.fileName != "." && it.fileName != ".." }.map {
            item(it.fileName, it.fileAttributes, it.endOfFile, try { it.lastWriteTime.toEpochMillis() / 1000 } catch (_: Exception) { 0L })
        }

    override fun ls(v: String): List<Item> = op { listRaw(it, v) }

    override fun search(v: String, q: String): SearchResult = op { s ->
        val ql = q.lowercase()
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + 15_000L
        val stack = ArrayDeque<String>()
        stack.addLast(vnorm(v))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val entries = try { listRaw(s, d) } catch (_: Exception) { continue }
            for (i in entries) {
                if (i.dir) stack.addLast(vjoin(d, i.name))
                if (i.name.lowercase().contains(ql)) {
                    out.add(i.copy(path = vjoin(d, i.name)))
                    if (out.size >= 300) return@op SearchResult(out, true)
                }
            }
            if (System.currentTimeMillis() > end) return@op SearchResult(out, true)
        }
        out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        SearchResult(out, false)
    }

    override fun names(v: String): MutableSet<String> =
        try { ls(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }

    override fun walk(v: String): List<WalkItem> = op { s ->
        val st = s.getFileInformation(rel(v)).standardInformation
        if (!st.isDirectory) return@op listOf(WalkItem("", false, st.endOfFile))
        val res = arrayListOf(WalkItem("", true, 0))
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {   // a folder's entries are added before its children: mkdir always precedes its files
            val r0 = stack.removeLast()
            for (i in listRaw(s, if (r0.isEmpty()) v else vjoin(v, r0))) {
                val r = if (r0.isEmpty()) i.name else r0 + "/" + i.name
                res.add(WalkItem(r, i.dir, i.size))
                if (i.dir) stack.addLast(r)
            }
        }
        res
    }

    override fun open(v: String): Source = op { s ->
        val p = rel(v)
        val st = s.getFileInformation(p).standardInformation
        if (st.isDirectory) throw IOException("Is a directory")
        val f = s.openFile(p, EnumSet.of(AccessMask.GENERIC_READ), EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions::class.java))
        SmbSource(f, st.endOfFile)
    }

    private fun mkdirs(s: DiskShare, p: String) {
        if (p.isEmpty()) return
        var cur = ""
        for (part in p.split('\\')) {
            if (part.isEmpty()) continue
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            if (!s.folderExists(cur)) s.mkdir(cur)
        }
    }

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        op(retry = false) { s ->
            val p = rel(v)
            mkdirs(s, p.substringBeforeLast('\\', ""))
            val tmp = "$p.lspart"
            var ok = false
            try {
                s.openFile(tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), EnumSet.noneOf(SMB2ShareAccess::class.java),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF, EnumSet.noneOf(SMB2CreateOptions::class.java)).use { f ->
                    val buf = ByteArray(CHUNK)
                    var left = size
                    var off = 0L
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("connection lost")
                        f.write(buf, off, 0, n)
                        off += n
                        left -= n
                        cb?.invoke(n)
                    }
                    f.flush()
                    f.rename(p, true)   // replace an existing file of that name
                }
                ok = true
            } finally {
                if (!ok) try { s.rm(tmp) } catch (_: Exception) {}
            }
        }
    }

    override fun mkdir(v: String) { op { mkdirs(it, rel(v)) } }

    override fun remove(v: String) {
        if (vnorm(v) == "/") throw Denied("cannot delete the share root")
        op { s ->
            val p = rel(v)
            if (s.getFileInformation(p).standardInformation.isDirectory) s.rmdir(p, true) else s.rm(p)
        }
    }

    private fun renameTo(s: DiskShare, from: String, to: String) {
        val e = s.open(from, EnumSet.of(AccessMask.DELETE), EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions::class.java))
        e.use { it.rename(to, false) }
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        val dst = rel(vjoin(vdir(v), newName))
        op { s ->
            if (s.fileExists(dst) || s.folderExists(dst)) throw Exists("name already exists")
            renameTo(s, rel(v), dst)
        }
    }

    override fun move(v: String, toV: String) { op { s -> renameTo(s, rel(v), rel(toV)) } }
}
