package com.lanshare.app.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipeShare
import com.hierynomus.protocol.transport.TransportException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
                .put("share", c.optString("share", "")).put("user", c.optString("user", "")).put("ok", state[c.getString("id")] ?: true)
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

/** Minimal DCE/RPC client for the SRVSVC pipe: NetrShareEnum (level 1) = the list of shares a PC offers (C$, D$, Users, ...). */
private object Srvsvc {
    private val SRV = byteArrayOf(0xc8.toByte(), 0x4f, 0x32, 0x4b, 0x70, 0x16, 0xd3.toByte(), 0x01,
        0x12, 0x78, 0x5a, 0x47, 0xbf.toByte(), 0x6e, 0xe1.toByte(), 0x88.toByte())
    private val NDR = byteArrayOf(0x04, 0x5d, 0x88.toByte(), 0x8a.toByte(), 0xeb.toByte(), 0x1c, 0xc9.toByte(), 0x11,
        0x9f.toByte(), 0xe8.toByte(), 0x08, 0x00, 0x2b, 0x10, 0x48, 0x60)
    private val HIDDEN = setOf("admin$", "ipc$", "print$")

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun header(b: ByteBuffer, type: Int, len: Int, callId: Int) {
        b.put(5.toByte()).put(0.toByte()).put(type.toByte()).put(3.toByte())   // v5.0, packet type, first+last fragment
        b.putInt(0x10)                                                         // little-endian, ASCII, IEEE floats
        b.putShort(len.toShort()).putShort(0.toShort()).putInt(callId)
    }

    private fun bind(): ByteArray {
        val b = le(72)
        header(b, 11, 72, 1)
        b.putShort(4280.toShort()).putShort(4280.toShort()).putInt(0)          // max frag sizes, assoc group
        b.put(1.toByte()).put(0.toByte()).put(0.toByte()).put(0.toByte())      // one context item
        b.putShort(0.toShort()).put(1.toByte()).put(0.toByte())                // context id 0, one transfer syntax
        b.put(SRV).putShort(3.toShort()).putShort(0.toShort())                 // srvsvc v3.0
        b.put(NDR).putInt(2)                                                   // NDR v2
        return b.array()
    }

    private fun enumRequest(server: String): ByteArray {
        val name = (server + "\u0000").toByteArray(Charsets.UTF_16LE)
        val cnt = server.length + 1
        val pad = (4 - name.size % 4) % 4
        val stub = le(4 + 12 + name.size + pad + 12 + 8 + 4 + 8)
        stub.putInt(0x00020000).putInt(cnt).putInt(0).putInt(cnt).put(name)    // ServerName (unique ptr + string)
        repeat(pad) { stub.put(0.toByte()) }
        stub.putInt(1).putInt(1).putInt(0x00020004)                            // SHARE_ENUM_STRUCT: level 1, container ptr
        stub.putInt(0).putInt(0)                                               // container: 0 entries, null buffer
        stub.putInt(-1)                                                        // PreferedMaximumLength = unlimited
        stub.putInt(0x00020008).putInt(0)                                      // ResumeHandle
        val b = le(24 + stub.capacity())
        header(b, 0, b.capacity(), 2)
        b.putInt(stub.capacity()).putShort(0.toShort()).putShort(15.toShort()) // alloc hint, context 0, opnum 15 = NetrShareEnum
        b.put(stub.array())
        return b.array()
    }

    private fun parse(r: ByteArray): List<Pair<String, Int>> {
        if (r.size < 24 || r[2].toInt() != 2) throw IOException("The PC refused to list its shares")
        val b = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
        b.position(24)                                                         // 16 header + 8 response header
        b.getInt(); b.getInt()                                                 // level, union switch
        if (b.getInt() == 0) return emptyList()
        val n = b.getInt()
        if (b.getInt() == 0 || n <= 0) return emptyList()
        b.getInt()                                                             // array max count
        val nm = IntArray(n); val ty = IntArray(n); val rm = IntArray(n)
        for (i in 0 until n) { nm[i] = b.getInt(); ty[i] = b.getInt(); rm[i] = b.getInt() }
        fun str(): String {
            b.getInt(); b.getInt()                                             // max count, offset
            val a = b.getInt()
            val raw = ByteArray(a * 2)
            b.get(raw)
            while (b.position() % 4 != 0) b.get()
            return String(raw, Charsets.UTF_16LE).trimEnd('\u0000')
        }
        val out = ArrayList<Pair<String, Int>>()
        for (i in 0 until n) {
            val name = if (nm[i] != 0) str() else ""
            if (rm[i] != 0) str()
            out.add(name to ty[i])
        }
        return out
    }

    /** Disk shares of the server (normal shared folders only): every hidden share ending in $ (C$, D$, ADMIN$, IPC$, ...) is left out. */
    fun list(session: Session, host: String): List<String> {
        val ps = session.connectShare("IPC$") as? PipeShare ?: throw IOException("The PC does not allow listing its shares")
        val pipe = ps.open("srvsvc", SMB2ImpersonationLevel.Impersonation, EnumSet.of(AccessMask.MAXIMUM_ALLOWED), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        try {
            val ack = pipe.transact(bind())
            if (ack.size < 3 || ack[2].toInt() != 12) throw IOException("The PC refused the share list request")
            return parse(pipe.transact(enumRequest("\\\\" + host)))
                .filter { (it.second and 0x0f) == 0 && it.first.isNotEmpty() && !it.first.endsWith("$") && it.first.lowercase() !in HIDDEN }
                .map { it.first }
        } finally {
            try { pipe.close() } catch (_: Exception) {}
            try { ps.close() } catch (_: Exception) {}
        }
    }
}

/**
 * A PC / NAS over SMB2/3 (smbj), same interface as LocalFs.
 * No share name configured (normal case): virtual '/' lists every drive/share of the PC (C$, D$, Users, ...) and
 * '/C$/Users/x' lives inside the share named by the first path segment. A legacy config with a share name
 * still opens just that share, with '/' = share root.
 */
class SmbFs private constructor(c: JSONObject) : Endpoint {
    override val id: String = c.getString("id")
    override val name: String = c.getString("name")
    private val host: String
    private val port: Int
    private val shareName: String = c.optString("share", "").trim().trim('\\', '/')
    private val multi: Boolean = shareName.isEmpty()
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

    private fun auth(): AuthenticationContext =
        if (user.isEmpty()) AuthenticationContext.guest() else AuthenticationContext(user, pw.toCharArray(), null)

    private fun connect(sh: String): SmbConn {
        val key = "$id|$host|$port|$sh|$user|${pw.hashCode()}"
        pool[key]?.let { if (it.alive) return it else { pool.remove(key); it.close() } }
        val client = SMBClient(cfg)
        try {
            val conn = client.connect(host, port)
            val session = conn.authenticate(auth())
            val share = session.connectShare(sh) as? DiskShare
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
                "STATUS_BAD_NETWORK_NAME" -> NotFound("Drive/share not found on that PC")
                "STATUS_ACCESS_DENIED" -> Denied("Access denied by the PC (protected, read-only or not allowed for this user)")
                "STATUS_SHARING_VIOLATION" -> IOException("In use by another program on the PC")
                "STATUS_LOGON_FAILURE", "STATUS_ACCOUNT_DISABLED", "STATUS_ACCOUNT_LOCKED_OUT",
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

    /** Virtual path -> (share, path inside the share, '/'-rooted). */
    private fun split(v: String): Pair<String, String> {
        if (!multi) return shareName to vnorm(v)
        val segs = vnorm(v).split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) throw BadReq("open one of the PC's drives first")
        return segs[0] to ("/" + segs.drop(1).joinToString("/"))
    }

    private fun isRoot(v: String) = multi && vnorm(v) == "/"

    /** Runs [fn] on the cached share that [v] lives in (one transparent reconnect if the link went stale) and keeps the status dot up to date. */
    private fun <T> op(v: String, retry: Boolean = true, fn: (DiskShare, String) -> T): T {
        val (sh, rv) = split(v)
        try {
            val r = try { fn(connect(sh).share, rv) }
                    catch (e: TransportException) { dropConn(); if (!retry) throw e; fn(connect(sh).share, rv) }
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

    /** Shared folders the PC offers (RPC share list); hidden $ shares such as C$ are not shown. */
    private fun shareNames(): List<String> {
        val client = SMBClient(cfg)
        try {
            val session = client.connect(host, port).authenticate(auth())
            val names = try { Srvsvc.list(session, host) } catch (_: Exception) { emptyList() }
            return names
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun listShares(): List<Item> {
        try {
            val names = shareNames()
            Smb.state[id] = true
            if (names.isEmpty()) throw NotFound("No shared folders found on that PC (hidden $ shares are not shown - share a folder in Windows first)")
            return names.sortedWith(compareBy({ !(it.length == 2 && it[1] == '$') }, { it.lowercase() }))
                .map { Item(it, true, 0L, 0L) }
        } catch (e: Throwable) {
            val m = mapErr(e)
            if (m !is NotFound && m !is BadReq) Smb.state[id] = m is Denied && (Smb.state[id] ?: true)
            throw m
        }
    }

    private fun item(name: String, attrs: Long, size: Long, mtime: Long, path: String? = null): Item {
        val d = isDir(attrs)
        return Item(name, d, if (d) 0L else size, mtime, null, path)
    }

    private fun listRaw(s: DiskShare, v: String): List<Item> =
        s.list(rel(v)).filter { it.fileName != "." && it.fileName != ".." }.map {
            item(it.fileName, it.fileAttributes, it.endOfFile, try { it.lastWriteTime.toEpochMillis() / 1000 } catch (_: Exception) { 0L })
        }

    override fun ls(v: String): List<Item> = if (isRoot(v)) listShares() else op(v) { s, rv -> listRaw(s, rv) }

    /** Breadth-first-ish search below [v]; returns true when cut short (300 hits / time budget). */
    private fun searchIn(s: DiskShare, v: String, ql: String, out: MutableList<Item>, end: Long, prefix: String): Boolean {
        val stack = ArrayDeque<String>()
        stack.addLast(vnorm(v))
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val entries = try { listRaw(s, d) } catch (_: Exception) { continue }
            for (i in entries) {
                if (i.dir) stack.addLast(vjoin(d, i.name))
                if (i.name.lowercase().contains(ql)) {
                    out.add(i.copy(path = prefix + vjoin(d, i.name)))
                    if (out.size >= 300) return true
                }
            }
            if (System.currentTimeMillis() > end) return true
        }
        return false
    }

    override fun search(v: String, q: String): SearchResult {
        val ql = q.lowercase()
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + 15_000L
        var partial = false
        if (isRoot(v)) {
            for (sh in listShares().map { it.name }) {
                if (partial) break
                try { partial = op("/$sh") { s, rv -> searchIn(s, rv, ql, out, end, "/$sh") } } catch (_: Exception) {}
            }
        } else {
            val pre = if (multi) "/" + split(v).first else ""
            partial = op(v) { s, rv -> searchIn(s, rv, ql, out, end, pre) }
        }
        if (!partial) out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    override fun names(v: String): MutableSet<String> =
        try { ls(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }

    override fun walk(v: String): List<WalkItem> = op(v) { s, rv ->
        val st = s.getFileInformation(rel(rv)).standardInformation
        if (!st.isDirectory) return@op listOf(WalkItem("", false, st.endOfFile))
        val res = arrayListOf(WalkItem("", true, 0))
        val stack = ArrayDeque<String>()
        stack.addLast("")
        while (stack.isNotEmpty()) {   // a folder's entries are added before its children: mkdir always precedes its files
            val r0 = stack.removeLast()
            for (i in listRaw(s, if (r0.isEmpty()) rv else vjoin(rv, r0))) {
                val r = if (r0.isEmpty()) i.name else r0 + "/" + i.name
                res.add(WalkItem(r, i.dir, i.size))
                if (i.dir) stack.addLast(r)
            }
        }
        res
    }

    override fun open(v: String): Source = op(v) { s, rv ->
        val p = rel(rv)
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
        op(v, retry = false) { s, rv ->
            val p = rel(rv)
            if (p.isEmpty()) throw BadReq("invalid path")
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

    override fun mkdir(v: String) { op(v) { s, rv -> mkdirs(s, rel(rv)) } }

    private fun isReparse(a: Long) = EnumWithValue.EnumUtils.isSet(a, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT)

    private fun why(e: Throwable): String = if (e is SMBApiException) when (e.status.name) {
        "STATUS_ACCESS_DENIED" -> "access denied"
        "STATUS_SHARING_VIOLATION" -> "in use by another program"
        "STATUS_CANNOT_DELETE" -> "protected or read-only"
        "STATUS_DIRECTORY_NOT_EMPTY" -> "folder not empty"
        else -> e.status.name.removePrefix("STATUS_").lowercase().replace('_', ' ')
    } else (e.message ?: e.javaClass.simpleName)

    /** Deletes this one entry (file, empty folder or link) without following links; clears read-only once if needed. Already gone = fine. */
    private fun delEntry(s: DiskShare, p: String, link: Boolean) {
        fun once() {
            val opts = EnumSet.of(SMB2CreateOptions.FILE_DELETE_ON_CLOSE)
            if (link) opts.add(SMB2CreateOptions.FILE_OPEN_REPARSE_POINT)   // a junction/symlink is removed itself, its target is never touched
            s.open(p, EnumSet.of(AccessMask.DELETE), EnumSet.noneOf(FileAttributes::class.java),
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, opts).close()
        }
        try { once() } catch (e: SMBApiException) {
            when (e.status.name) {
                "STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_NO_SUCH_FILE" -> return
                "STATUS_CANNOT_DELETE", "STATUS_ACCESS_DENIED" -> {
                    try { s.setFileInformation(p, FileBasicInformation(FileTime(0L), FileTime(0L), FileTime(0L), FileTime(0L),
                        FileAttributes.FILE_ATTRIBUTE_NORMAL.value)) } catch (_: Exception) {}   // drop read-only/hidden/system, then retry once
                    try { once() } catch (e2: SMBApiException) {
                        if (e2.status.name in setOf("STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND", "STATUS_NO_SUCH_FILE")) return
                        throw e2
                    }
                }
                else -> throw e
            }
        }
    }

    /** Empties folder [p] item by item; whatever cannot be deleted is collected in [fails] and the rest carries on. */
    private fun delTree(s: DiskShare, p: String, fails: MutableList<String>) {
        val kids = try { s.list(p) } catch (e: Exception) { fails.add("${p.substringAfterLast('\\')}: ${why(e)}"); return }
        for (c in kids) {
            val n = c.fileName
            if (n == "." || n == "..") continue
            val cp = "$p\\$n"
            val link = isReparse(c.fileAttributes)
            try {
                if (isDir(c.fileAttributes) && !link) {
                    val before = fails.size
                    delTree(s, cp, fails)
                    if (fails.size > before) continue   // something inside stayed, so the folder cannot go either
                }
                delEntry(s, cp, link)
            } catch (e: Exception) { fails.add("$n: ${why(e)}") }
        }
    }

    override fun remove(v: String) {
        op(v) { s, rv ->
            if (rv == "/") throw Denied("cannot delete the " + (if (multi) "drive" else "share") + " root")
            val p = rel(rv)
            val name = p.substringAfterLast('\\')
            val parent = if ('\\' in p) p.substringBeforeLast('\\') else ""
            // attributes come from the parent's listing: asking for the entry itself would follow a link into its target
            val me = s.list(parent).firstOrNull { it.fileName.equals(name, ignoreCase = true) } ?: return@op
            val link = isReparse(me.fileAttributes)
            val fails = ArrayList<String>()
            if (isDir(me.fileAttributes) && !link) delTree(s, p, fails)
            if (fails.isEmpty()) {
                try { delEntry(s, p, link) } catch (e: Exception) { fails.add("$name: ${why(e)}") }
            }
            if (fails.isNotEmpty())
                throw IOException("Could not delete " + (if (fails.size == 1) fails[0] else fails.size.toString() + " items (" + fails.take(3).joinToString("; ") + (if (fails.size > 3) "; ..." else "") + ")"))
        }
    }

    private fun renameTo(s: DiskShare, from: String, to: String) {
        val e = s.open(from, EnumSet.of(AccessMask.DELETE), EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.noneOf(SMB2CreateOptions::class.java))
        e.use { it.rename(to, false) }
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        op(v) { s, rv ->
            if (rv == "/") throw BadReq("cannot rename a drive")
            val dst = rel(vjoin(vdir(rv), newName))
            if (s.fileExists(dst) || s.folderExists(dst)) throw Exists("name already exists")
            renameTo(s, rel(rv), dst)
        }
    }

    override fun move(v: String, toV: String) {
        val (a, ra) = split(v)
        val (b, rb) = split(toV)
        if (a != b) throw BadReq("cannot move between different drives - copy instead")
        op(v) { s, _ -> renameTo(s, rel(ra), rel(rb)) }
    }
}
