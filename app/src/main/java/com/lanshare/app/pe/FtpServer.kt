package com.lanshare.app.pe

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal FTP server (RFC 959 subset) made for Windows Explorer's built-in FTP client.
 * Pure JDK, no Android classes, so it can be unit-tested on a PC.
 *
 * Supported: USER PASS QUIT SYST FEAT OPTS NOOP PWD CWD CDUP TYPE MODE STRU PASV EPSV PORT EPRT
 *            LIST NLST MLSD MLST RETR STOR APPE DELE MKD RMD RNFR RNTO SIZE MDTM REST ABOR
 * Optional read-only mode and folder whitelist (virtual paths such as "/DCIM").
 * Only LAN-type client addresses are accepted (private, link-local, loopback, CGNAT/Tailscale).
 *
 * FTPS (explicit TLS, RFC 4217): when [tls] returns a context the server offers AUTH TLS / PBSZ / PROT (FileZilla, Android clients ...);
 * plain FTP keeps working for Windows Explorer unless [tlsRequired] is true (then login needs AUTH TLS first and data must be PROT P).
 */
class FtpServer(
    private val root: File,
    private val port: Int,
    private val user: String,
    private val passwordProvider: () -> String,
    private val readOnly: () -> Boolean = { false },
    private val allowedDirs: () -> List<String> = { emptyList() },
    private val log: (String) -> Unit = {},
    private val tls: () -> SSLContext? = { null },
    private val tlsRequired: () -> Boolean = { false }
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var pool: ExecutorService = Executors.newCachedThreadPool()
    private val clientCount = AtomicInteger(0)

    private val bytes = AtomicLong(0)
    private val activeCount = AtomicInteger(0)
    @Volatile private var lastName = ""

    val clients: Int get() = clientCount.get()
    /** Total bytes moved since start (both directions); the service samples it to show speed. */
    val bytesTotal: Long get() = bytes.get()
    val activeTransfers: Int get() = activeCount.get()
    /** Label of the most recent transfer, e.g. "To PC: video.mp4". */
    val lastTransfer: String get() = lastName
    val isRunning: Boolean get() = running

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        pool = Executors.newCachedThreadPool()
        running = true
        val p = pool
        p.execute {
            while (running) {
                try {
                    val c = s.accept()
                    if (!isLan(c.inetAddress)) {
                        log("Rejected non-LAN client ${c.inetAddress}")
                        c.close()
                        continue
                    }
                    p.execute { Session(c).run() }
                } catch (e: IOException) {
                    if (running) log("accept error: ${e.message}")
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun isLan(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        val b = a.address
        if (a is Inet4Address && b.size == 4) {
            // 100.64.0.0/10 (CGNAT, used by Tailscale)
            if ((b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40) return true
        }
        // fc00::/7 unique local IPv6
        if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }

    private inner class Session(private val ctrl: Socket) {
        private var input = BufferedReader(InputStreamReader(ctrl.getInputStream(), Charsets.UTF_8))
        private var out: OutputStream = ctrl.getOutputStream()
        private var tlsOn = false     // control connection is encrypted (after AUTH TLS)
        private var pbsz = false
        private var protP = false     // data connections are encrypted (PROT P)
        private var userOk = false
        private var loggedIn = false
        private var cwd = "/"
        private var rnfr: File? = null
        private var restart = 0L
        private var pasv: ServerSocket? = null
        private var activeAddr: InetSocketAddress? = null
        private var failedLogins = 0

        fun run() {
            clientCount.incrementAndGet()
            try {
                ctrl.soTimeout = 15 * 60 * 1000
                reply("220 PhoneExplorer ready")
                while (running) {
                    val line = input.readLine() ?: break
                    val sp = line.indexOf(' ')
                    val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.ROOT)
                    val arg = if (sp < 0) "" else line.substring(sp + 1)
                    if (!handle(cmd, arg)) break
                }
            } catch (_: Exception) {
            } finally {
                closePasv()
                try { ctrl.close() } catch (_: Exception) {}
                clientCount.decrementAndGet()
            }
        }

        private fun reply(s: String) {
            out.write((s + "\r\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun closePasv() {
            try { pasv?.close() } catch (_: Exception) {}
            pasv = null
        }

        // ---- paths -------------------------------------------------------------------------

        private fun resolve(arg: String): String {
            val base = if (arg.startsWith("/")) arg else "$cwd/$arg"
            val parts = mutableListOf<String>()
            for (p in base.split("/")) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(p)
                }
            }
            return "/" + parts.joinToString("/")
        }

        private fun toFile(virtual: String): File = File(root, virtual.trimStart('/'))

        // ---- access policy (whitelist + read-only) -------------------------------------------

        private fun allowed(): List<String> =
            allowedDirs().map { it.trim().trim('/') }.filter { it.isNotEmpty() }.map { resolve("/$it") }.filter { it != "/" }

        /** Browsing: the path is inside an allowed folder, or is a parent leading to one. */
        private fun canRead(v: String): Boolean {
            val al = allowed()
            if (al.isEmpty()) return true
            val prefix = if (v == "/") "/" else "$v/"
            return al.any { v == it || v.startsWith("$it/") || it.startsWith(prefix) }
        }

        /** Writing: only inside an allowed folder (parents are browse-only), never in read-only mode. */
        private fun canWrite(v: String): Boolean {
            if (readOnly()) return false
            val al = allowed()
            return al.isEmpty() || al.any { v == it || v.startsWith("$it/") }
        }

        /** Delete/rename: like canWrite, but the whitelisted folder itself is protected. */
        private fun canModify(v: String): Boolean = canWrite(v) && allowed().none { it == v }

        private fun childPath(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

        // ---- data connection ---------------------------------------------------------------

        private fun configure(s: Socket) {
            s.tcpNoDelay = true
            s.sendBufferSize = 256 * 1024
            s.receiveBufferSize = 256 * 1024
            s.soTimeout = 60000
        }

        /** PROT P: the data connection is wrapped in TLS (the server is the TLS server on both passive and active connections). */
        private fun wrapData(s: Socket): Socket {
            if (!protP) return s
            val ctx = tls() ?: return s
            try {
                val ss = ctx.socketFactory.createSocket(s, s.inetAddress.hostAddress, s.port, true) as SSLSocket
                ss.useClientMode = false
                ss.soTimeout = 60000
                ss.startHandshake()
                return ss
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw e
            }
        }

        private fun openData(): Socket? {
            val p = pasv
            val a = activeAddr
            if (p == null && a == null) {
                reply("425 Use PASV or PORT first")
                return null
            }
            if (tlsRequired() && !protP) {
                reply("522 Data connection must be encrypted (PROT P)")
                return null
            }
            reply("150 Opening data connection")
            return try {
                if (p != null) {
                    p.soTimeout = 20000
                    val s = p.accept()
                    closePasv()
                    if (s.inetAddress != ctrl.inetAddress) {
                        s.close()
                        reply("425 Data connection from wrong host")
                        null
                    } else {
                        configure(s)
                        wrapData(s)
                    }
                } else {
                    activeAddr = null
                    val s = Socket()
                    s.connect(a, 20000)
                    configure(s)
                    wrapData(s)
                }
            } catch (e: Exception) {
                closePasv()
                activeAddr = null
                reply("425 Can't open data connection")
                null
            }
        }

        private fun copy(src: InputStream, dst: OutputStream) {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                bytes.addAndGet(n.toLong())
            }
            dst.flush()
        }

        private fun beginTransfer(label: String) {
            activeCount.incrementAndGet()
            lastName = label
        }

        private fun endTransfer() {
            activeCount.decrementAndGet()
        }

        // ---- listing format ----------------------------------------------------------------

        private val sixMonths = 183L * 24 * 3600 * 1000

        private fun listLine(f: File): String {
            val d = Date(f.lastModified())
            val recent = System.currentTimeMillis() - f.lastModified() < sixMonths
            val fmt = SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US)
            val perm = (if (f.isDirectory) "d" else "-") + (if (f.canWrite()) "rwxrwxrwx" else "r-xr-xr-x")
            val size: Long = if (f.isDirectory) 4096L else f.length()
            return String.format(Locale.US, "%s 1 phone phone %12d %s %s", perm, size, fmt.format(d), f.name)
        }

        private fun mlFacts(f: File): String {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val w = f.canWrite() && !readOnly()
            return if (f.isDirectory) {
                "type=dir;modify=${fmt.format(Date(f.lastModified()))};perm=${if (w) "cdeflmp" else "elp"};"
            } else {
                "type=file;size=${f.length()};modify=${fmt.format(Date(f.lastModified()))};perm=${if (w) "adfrw" else "r"};"
            }
        }

        private fun doList(arg: String, mode: Int) { // mode: 0 = LIST, 1 = NLST, 2 = MLSD
            var target = arg.trim()
            while (target.startsWith("-")) {                  // ignore options such as -l -a
                val i = target.indexOf(' ')
                target = if (i < 0) "" else target.substring(i + 1).trim()
            }
            val v = if (target.isEmpty()) cwd else resolve(target)
            val f = toFile(v)
            if (!f.exists() || !canRead(v)) {
                reply("550 No such file or directory")
                return
            }
            if (mode == 2 && !f.isDirectory) {
                reply("501 Not a directory")
                return
            }
            val data = openData() ?: return
            try {
                val w = data.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val items = if (f.isDirectory) {
                    (f.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) } ?: emptyList())
                        .filter { canRead(childPath(v, it.name)) }
                } else listOf(f)
                for (item in items) {
                    when (mode) {
                        1 -> w.write(item.name)
                        2 -> w.write(mlFacts(item) + " " + item.name)
                        else -> w.write(listLine(item))
                    }
                    w.write("\r\n")
                }
                w.flush()
                data.close()
                reply("226 Transfer complete")
            } catch (e: Exception) {
                try { data.close() } catch (_: Exception) {}
                reply("426 Transfer aborted")
            }
        }

        // ---- command handler ---------------------------------------------------------------

        private fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "AUTH" -> {
                    val ctx = tls()
                    val m = arg.trim().uppercase(Locale.ROOT)
                    if (ctx == null) { reply("502 TLS is not enabled"); return true }
                    if (m != "TLS" && m != "TLS-C" && m != "SSL" && m != "TLS-P") { reply("504 Unknown AUTH type"); return true }
                    if (tlsOn) { reply("503 Already using TLS"); return true }
                    reply("234 Proceed with negotiation")
                    try {
                        val ss = ctx.socketFactory.createSocket(ctrl, ctrl.inetAddress.hostAddress, ctrl.port, false) as SSLSocket
                        ss.useClientMode = false
                        ss.soTimeout = 15 * 60 * 1000
                        ss.startHandshake()
                        input = BufferedReader(InputStreamReader(ss.inputStream, Charsets.UTF_8))
                        out = ss.outputStream
                        tlsOn = true
                        userOk = false; loggedIn = false; pbsz = false; protP = false   // RFC 4217: start over after the handshake
                    } catch (e: Exception) { return false }
                    return true
                }
                "PBSZ" -> {
                    if (!tlsOn) reply("503 AUTH TLS first") else { pbsz = true; reply("200 PBSZ=0") }
                    return true
                }
                "PROT" -> {
                    val l = arg.trim().uppercase(Locale.ROOT)
                    when {
                        !tlsOn || !pbsz -> reply("503 AUTH TLS and PBSZ first")
                        l == "P" -> { protP = true; reply("200 Data connections are now encrypted") }
                        l == "C" -> if (tlsRequired()) reply("534 Policy requires encrypted data connections") else { protP = false; reply("200 Data connections are now plain") }
                        else -> reply("504 Only PROT P and PROT C are supported")
                    }
                    return true
                }
                "USER" -> {
                    if (tlsRequired() && !tlsOn) { reply("534 Policy requires TLS (send AUTH TLS first)"); return true }
                    userOk = arg == user
                    reply("331 Password required")
                    return true
                }
                "PASS" -> {
                    if (userOk && arg == passwordProvider()) {
                        loggedIn = true
                        reply("230 Logged in")
                    } else {
                        failedLogins++
                        Thread.sleep(700)
                        reply("530 Login incorrect")
                        if (failedLogins >= 5) return false
                    }
                    return true
                }
                "QUIT" -> { reply("221 Bye"); return false }
                "NOOP" -> { reply("200 OK"); return true }
                "OPTS" -> { reply("200 OK"); return true }
                "SYST" -> { reply("215 UNIX Type: L8"); return true }
                "FEAT" -> {
                    reply("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n MLST type*;size*;modify*;perm*;" + (if (tls() != null) "\r\n AUTH TLS\r\n PBSZ\r\n PROT" else "") + "\r\n211 End")
                    return true
                }
            }
            if (!loggedIn) {
                reply("530 Please log in")
                return true
            }
            when (cmd) {
                "PWD", "XPWD" -> reply("257 \"${cwd.replace("\"", "\"\"")}\" is current directory")
                "CWD", "XCWD" -> {
                    val v = resolve(arg)
                    if (toFile(v).isDirectory && canRead(v)) { cwd = v; reply("250 Directory changed") }
                    else reply("550 No such directory")
                }
                "CDUP", "XCUP" -> { cwd = resolve(".."); reply("250 Directory changed") }
                "TYPE", "MODE", "STRU" -> reply("200 OK")
                "PASV" -> {
                    closePasv(); activeAddr = null
                    try {
                        val s = ServerSocket(0)
                        pasv = s
                        val ip = ctrl.localAddress.address
                        val a = if (ip.size == 4) ip else byteArrayOf(127, 0, 0, 1)
                        val p = s.localPort
                        reply("227 Entering Passive Mode (${a.joinToString(",") { (it.toInt() and 0xFF).toString() }},${p shr 8},${p and 0xFF})")
                    } catch (e: IOException) {
                        reply("425 Can't open passive port")
                    }
                }
                "EPSV" -> {
                    closePasv(); activeAddr = null
                    try {
                        val s = ServerSocket(0)
                        pasv = s
                        reply("229 Entering Extended Passive Mode (|||${s.localPort}|)")
                    } catch (e: IOException) {
                        reply("425 Can't open passive port")
                    }
                }
                "PORT" -> {
                    try {
                        val n = arg.trim().split(",").map { it.trim().toInt() }
                        require(n.size == 6 && n.all { it in 0..255 })
                        val addr = InetAddress.getByAddress(byteArrayOf(n[0].toByte(), n[1].toByte(), n[2].toByte(), n[3].toByte()))
                        if (addr != ctrl.inetAddress) { reply("504 Only your own address is allowed"); return true }
                        closePasv()
                        activeAddr = InetSocketAddress(addr, (n[4] shl 8) or n[5])
                        reply("200 PORT ok")
                    } catch (e: Exception) {
                        reply("501 Bad PORT argument")
                    }
                }
                "EPRT" -> {
                    try {
                        val d = arg.trim()[0]
                        val f = d.toString().let { d2 -> arg.trim().split(d2) }  // "", proto, host, port, ""
                        val addr = InetAddress.getByName(f[2])
                        val prt = f[3].toInt()
                        require(prt in 1..65535)
                        if (addr != ctrl.inetAddress) { reply("504 Only your own address is allowed"); return true }
                        closePasv()
                        activeAddr = InetSocketAddress(addr, prt)
                        reply("200 EPRT ok")
                    } catch (e: Exception) {
                        reply("501 Bad EPRT argument")
                    }
                }
                "LIST" -> doList(arg, 0)
                "NLST" -> doList(arg, 1)
                "MLSD" -> doList(arg, 2)
                "MLST" -> {
                    val v = if (arg.isBlank()) cwd else resolve(arg)
                    val f = toFile(v)
                    if (f.exists() && canRead(v)) reply("250-Listing $v\r\n ${mlFacts(f)} $v\r\n250 End") else reply("550 Not found")
                }
                "RETR" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    if (!f.isFile || !canRead(v)) { reply("550 File not found"); return true }
                    val data = openData() ?: return true
                    beginTransfer("To PC: ${f.name}")
                    try {
                        RandomAccessFile(f, "r").use { raf ->
                            raf.seek(restart)
                            copy(java.nio.channels.Channels.newInputStream(raf.channel), data.getOutputStream())
                        }
                        data.close()
                        reply("226 Transfer complete")
                    } catch (e: Exception) {
                        try { data.close() } catch (_: Exception) {}
                        reply("426 Transfer aborted")
                    } finally { endTransfer() }
                    restart = 0
                }
                "STOR", "APPE" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    val parent = f.parentFile
                    if (!canWrite(v)) { reply("550 Permission denied (read-only or folder not allowed)"); return true }
                    if (parent == null || !parent.isDirectory || f.isDirectory) { reply("550 Cannot write there"); return true }
                    val data = openData() ?: return true
                    beginTransfer("From PC: ${f.name}")
                    try {
                        RandomAccessFile(f, "rw").use { raf ->
                            when {
                                cmd == "APPE" -> raf.seek(raf.length())
                                restart > 0 -> raf.seek(restart)
                                else -> raf.setLength(0)
                            }
                            val os = java.nio.channels.Channels.newOutputStream(raf.channel)
                            copy(data.getInputStream(), os)
                        }
                        data.close()
                        reply("226 Transfer complete")
                    } catch (e: Exception) {
                        try { data.close() } catch (_: Exception) {}
                        reply("426 Transfer aborted")
                    } finally { endTransfer() }
                    restart = 0
                }
                "DELE" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    if (!canModify(v)) reply("550 Permission denied")
                    else if (f.isFile && f.delete()) reply("250 Deleted") else reply("550 Delete failed")
                }
                "RMD", "XRMD" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    if (!canModify(v)) reply("550 Permission denied")
                    else if (f.isDirectory && f.delete()) reply("250 Removed") else reply("550 Remove failed (folder not empty?)")
                }
                "MKD", "XMKD" -> {
                    val v = resolve(arg)
                    if (!canWrite(v)) reply("550 Permission denied")
                    else if (toFile(v).mkdir()) reply("257 \"${v.replace("\"", "\"\"")}\" created") else reply("550 Create failed")
                }
                "RNFR" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    if (!canModify(v)) reply("550 Permission denied")
                    else if (f.exists()) { rnfr = f; reply("350 Ready for RNTO") } else reply("550 Not found")
                }
                "RNTO" -> {
                    val from = rnfr
                    rnfr = null
                    val tv = resolve(arg)
                    val to = toFile(tv)
                    if (from == null) reply("503 RNFR first")
                    else if (!canWrite(tv)) reply("550 Permission denied")
                    else if (to.exists()) reply("550 Target exists")
                    else if (from.renameTo(to)) reply("250 Renamed")
                    else reply("550 Rename failed")
                }
                "SIZE" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    if (f.isFile && canRead(v)) reply("213 ${f.length()}") else reply("550 Not a file")
                }
                "MDTM" -> {
                    val v = resolve(arg)
                    val f = toFile(v)
                    if (f.exists() && canRead(v)) {
                        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        fmt.timeZone = TimeZone.getTimeZone("UTC")
                        reply("213 ${fmt.format(Date(f.lastModified()))}")
                    } else reply("550 Not found")
                }
                "REST" -> {
                    restart = arg.trim().toLongOrNull() ?: 0L
                    reply("350 Restarting at $restart")
                }
                "ABOR" -> reply("226 OK")
                else -> reply("502 Command not implemented")
            }
            return true
        }
    }
}
