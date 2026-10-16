package com.lanshare.app.core

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/**
 * ROOT SUPPORT, part 2: the whole file system of the phone as one browsable place ("Root", dev id "root"), read and written through `su`
 * ([RootShell]). Virtual path "/" = the real "/". Same [Endpoint] contract as [LocalFs], so browsing, copy / move / delete jobs, rename, new folder,
 * search, details and the viewers work on it unchanged.
 *
 * Script notes: shell variables are written with the section sign (§) in the Kotlin source and turned into '$' by [sh] - that keeps the scripts
 * readable. Only toolbox commands every Android has are used (find, stat, cat, tail, mv, rm, mkdir, chown, chmod, df, ls); `stat -c`, `find -exec ... +`
 * and `tail -c +N` are the only "newer" features. listing has a plain shell-loop fallback when `find -exec +` is missing.
 * Files that are written keep the owner and mode of the file they replace (a new file gets the owner of its folder): an app's own data file stays
 * the app's. Deleting is permanent (no recycle bin) and refuses whole system folders.
 * NOT compiled / NOT device-tested.
 */
class RootFs : Endpoint {
    override val id = "root"
    override val name = "Root"

    private fun sh(s: String) = s.replace('§', '$')

    private fun run(script: String, secs: Int = 30): RootShell.Out {
        if (!RootShell.granted()) throw Denied("Root access is not granted")
        return RootShell.exec(sh(script), secs)
    }

    private fun real(v: String) = vnorm(v)
    private fun rq(v: String) = RootShell.q(real(v))

    private fun failOf(o: RootShell.Out): Nothing {
        val e = o.err.trim().lines().lastOrNull { it.isNotBlank() } ?: ""
        throw when {
            o.code == -1 -> IOException("the root command took too long and was stopped")
            e.contains("No such file", true) -> NotFound("No such file or directory")
            e.contains("Permission denied", true) || e.contains("Operation not permitted", true) || e.contains("Read-only", true) -> Denied(e.take(160))
            e.contains("No space", true) -> Full("No space left on the device")
            e.contains("exists", true) -> Exists("name already exists")
            else -> IOException(if (e.isNotEmpty()) e.take(200) else "root command failed (exit " + o.code + ")")
        }
    }

    private class St(val type: String, val size: Long, val mtime: Long, val path: String) {
        val dir get() = type.startsWith("directory")
        val link get() = type.startsWith("symbolic")
        val reg get() = type.startsWith("regular")
    }

    /** One line of `stat -c '%F|%s|%Y|%n'`. */
    private fun parseSt(l: String): St? {
        val a = l.split('|', limit = 4)
        return if (a.size < 4 || a[3].isEmpty()) null else St(a[0], a[1].toLongOrNull() ?: 0L, a[2].toLongOrNull() ?: 0L, a[3])
    }

    /** Skip the pseudo file systems when everything is walked from "/". */
    private fun prune(base: String) = if (base == "/") "\\( -path /proc -o -path /sys -o -path /dev \\) -prune -o " else ""

    /** `b=<base without trailing slash>`: "§b/" then names the folder itself (also when it is a link to a folder) and "/" for the root. */
    private fun bvar(base: String) = "b=${RootShell.q(base)}; b=§{b%/}; "

    /**
     * Streams the output of a script line by line. [onLine] returns false to stop. The process is killed after [secs] seconds or when it stops.
     * Returns true when it was cut short (limit / time) = the result is partial.
     */
    private fun stream(script: String, secs: Int, onLine: (String) -> Boolean): Boolean {
        if (!RootShell.granted()) throw Denied("Root access is not granted")
        val pr = RootShell.start(sh(script))
        try { pr.outputStream.close() } catch (_: IOException) {}
        val dr = Thread { try { pr.errorStream.readBytes() } catch (_: IOException) {} }
        dr.isDaemon = true; dr.start()
        var timedOut = false
        val killer = Thread { try { Thread.sleep(secs * 1000L); timedOut = true; pr.destroy() } catch (_: InterruptedException) {} }
        killer.isDaemon = true; killer.start()
        var stopped = false
        try {
            pr.inputStream.bufferedReader().use { br ->
                while (true) {
                    val l = br.readLine() ?: break
                    if (!onLine(l)) { stopped = true; break }
                }
            }
        } catch (_: IOException) {
        } finally {
            killer.interrupt()
            try { pr.destroy() } catch (_: Exception) {}
        }
        return stopped || timedOut
    }

    // ---------------------------------------------------------------- listing
    override fun ls(v: String): List<Item> {
        val p = real(v)
        val o = run(
            "d=${RootShell.q(p)}; d=§{d%/}; [ -z \"§d\" ] || [ -e \"§d\" ] || [ -L \"§d\" ] || exit 2; [ -d \"§d/\" ] || exit 4; " +
            "out=§(find \"§d/\" -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null); " +
            "if [ -z \"§out\" ]; then out=§(for f in \"§d\"/* \"§d\"/.[!.]* \"§d\"/..?*; do [ -e \"§f\" ] || [ -L \"§f\" ] || continue; stat -c '%F|%s|%Y|%n' \"§f\"; done 2>/dev/null); fi; " +
            "[ -n \"§out\" ] && printf '%s\\n' \"§out\"; echo '@@L'; " +
            "find \"§d/\" -mindepth 1 -maxdepth 1 -type l -exec sh -c 'for f; do [ -d \"§f\" ] && echo \"§f\"; done' sh {} + 2>/dev/null; exit 0"
        )
        when (o.code) {
            0 -> {}
            2 -> throw NotFound("No such file or directory")
            4 -> throw IOException("Not a directory")
            else -> failOf(o)
        }
        val lines = o.out.split('\n')
        val cut = lines.indexOf("@@L")
        val ents = lines.subList(0, if (cut < 0) lines.size else cut).mapNotNull { parseSt(it) }
        val linkDirs = if (cut < 0) emptySet() else lines.subList(cut + 1, lines.size).filter { it.isNotEmpty() }.map { it.substringAfterLast('/') }.toSet()
        val out = ArrayList<Item>(ents.size)
        for (e in ents) {
            val n = e.path.substringAfterLast('/')
            if (n.isEmpty()) continue
            val dir = e.dir || (e.link && n in linkDirs)   // a link to a folder opens like a folder (/sdcard -> /storage/emulated/0)
            out.add(Item(n, dir, if (dir) 0L else e.size, e.mtime))
        }
        return out
    }

    override fun names(v: String): MutableSet<String> = try {
        run("d=${rq(v)}; ls -1A \"§d/\" 2>/dev/null; exit 0").out.split('\n').filter { it.isNotEmpty() }.toMutableSet()
    } catch (_: IOException) { mutableSetOf() }

    /** Recursive name search below folder v (case-insensitive substring). Capped by count and time. */
    fun search(v: String, q: String, limit: Int, secs: Int): SearchResult {
        val base = real(v)
        val esc = q.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[")
        val out = ArrayList<Item>()
        val partial = stream(
            bvar(base) + "find \"§b/\" " + prune(base) + "-iname ${RootShell.q("*$esc*")} -exec stat -c '%F|%s|%Y|%n' {} \\; 2>/dev/null", secs
        ) { l ->
            val e = parseSt(l)
            if (e != null) {
                val vp = vnorm(e.path)
                if (vp != base) {
                    val d = e.dir
                    out.add(Item(vp.substringAfterLast('/'), d, if (d) 0L else e.size, e.mtime, null, vp))
                }
            }
            out.size < limit
        }
        if (!partial) out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    /** Started at "/" = "search everything": far higher result and time limits than a search inside one folder (same rule as [LocalFs]). */
    override fun search(v: String, q: String) = if (vnorm(v) == "/") search(v, q, 20_000, 80) else search(v, q, 300, 15)

    override fun walk(v: String): List<WalkItem> {
        val base = real(v)
        val o = run("p=${RootShell.q(base)}; [ -e \"§p\" ] || [ -L \"§p\" ] || exit 2; stat -c '%F|%s' \"§p\"; exit 0")
        if (o.code == 2) throw NotFound("No such file or directory")
        if (o.code != 0) failOf(o)
        val head = o.out.trim().split('|')
        if (!head[0].startsWith("directory")) return listOf(WalkItem("", false, head.getOrNull(1)?.toLongOrNull() ?: 0L))
        val res = arrayListOf(WalkItem("", true, 0))
        var first = true
        val partial = stream(bvar(base) + "find \"§b/\" " + prune(base) + "-exec stat -c '%F|%s|%n' {} + 2>/dev/null", 900) { l ->
            if (first) { first = false; return@stream true }   // the folder itself
            val a = l.split('|', limit = 3)
            if (a.size == 3 && a[2].isNotEmpty()) {
                val rel = vnorm(a[2]).removePrefix(base).trimStart('/')
                if (rel.isNotEmpty()) {
                    when {
                        a[0].startsWith("directory") -> res.add(WalkItem(rel, true, 0))
                        a[0].startsWith("regular") -> res.add(WalkItem(rel, false, a[1].toLongOrNull() ?: 0L))
                        else -> res.add(WalkItem(rel, false, 0, true))   // links, sockets, devices: never copied
                    }
                }
            }
            true
        }
        if (partial) throw IOException("the folder is too big or too slow to read completely")
        return res
    }

    /** Files / folders / bytes below [base] (links are not followed). Stops after [secs] seconds or [cap] entries; the flag says so. */
    private fun tree(base: String, secs: Int = 12, cap: Int = 400_000): Pair<LongArray, Boolean> {
        val tot = LongArray(3)   // files, folders, bytes
        var seen = 0
        var first = true
        val partial = stream(bvar(base) + "find \"§b/\" " + prune(base) + "-exec stat -c '%F|%s' {} + 2>/dev/null", secs) { l ->
            if (first) { first = false; return@stream true }
            val a = l.split('|', limit = 2)
            when {
                a[0].startsWith("directory") -> tot[1]++
                a[0].startsWith("regular") -> { tot[0]++; tot[2] += a.getOrNull(1)?.toLongOrNull() ?: 0L }
            }
            ++seen < cap
        }
        return tot to partial
    }

    override fun stat(v: String): JSONObject {
        val p = real(v)
        val o = run(
            "p=${RootShell.q(p)}; [ -e \"§p\" ] || [ -L \"§p\" ] || exit 2; stat -c '%F|%s|%Y|%X|%Z|%a|%U|%G' \"§p\"; " +
            "[ -L \"§p\" ] && echo '@@link'; [ -w \"§p\" ] || echo '@@ro'; exit 0"
        )
        if (o.code == 2) throw NotFound("No such file or directory")
        if (o.code != 0) failOf(o)
        val lines = o.out.split('\n')
        val a = lines[0].split('|')
        if (a.size < 8) throw IOException("could not read the details")
        val dir = a[0].startsWith("directory")
        val name = if (p == "/") "/" else p.substringAfterLast('/')
        val j = JSONObject().put("name", name).put("path", p).put("dir", dir)
            .put("size", if (dir) 0L else a[1].toLongOrNull() ?: 0L).put("mtime", a[2].toLongOrNull() ?: 0L)
            .put("atime", a[3].toLongOrNull() ?: 0L).put("ctime", a[4].toLongOrNull() ?: 0L)
            .put("readonly", "@@ro" in lines).put("hidden", name.startsWith(".")).put("link", "@@link" in lines)
            .put("mode", a[5]).put("owner", a[6]).put("group", a[7])
        if (dir) {
            val (t, partial) = tree(p)
            j.put("files", t[0]).put("folders", t[1]).put("total", t[2]).put("partial", partial)
        }
        return j
    }

    override fun space(v: String): Pair<Long, Long>? = try {
        val o = run("p=${rq(v)}; [ -e \"§p\" ] || p=/; df -k \"§p\" 2>/dev/null | tail -n 1; exit 0", 15)
        val t = o.out.trim().split(Regex("\\s+"))   // Filesystem 1K-blocks Used Available Use% Mounted-on: counted from the end
        val total = if (t.size >= 5) t[t.size - 5].toLongOrNull() else null
        val avail = if (t.size >= 5) t[t.size - 3].toLongOrNull() else null
        if (total != null && avail != null && total > 0) (avail * 1024) to (total * 1024) else null
    } catch (_: Exception) { null }

    // ---------------------------------------------------------------- reading
    /** A file read through `cat` (from the start) or `tail -c +N` (after a seek). Reading ahead a little is done by skipping, a real jump restarts the process. */
    private class RootSource(private val path: String, override val size: Long) : Source() {
        private var proc: Process? = null
        private var ins: InputStream? = null
        private var pos = 0L
        override val seekable: Boolean get() = true

        private fun ensure(): InputStream {
            ins?.let { return it }
            val q = RootShell.q(path)
            val pr = RootShell.start(if (pos == 0L) "cat $q" else "tail -c +${pos + 1} $q")
            try { pr.outputStream.close() } catch (_: IOException) {}
            Thread { try { pr.errorStream.readBytes() } catch (_: IOException) {} }.also { it.isDaemon = true }.start()
            proc = pr
            return pr.inputStream.buffered(64 * 1024).also { ins = it }
        }

        private fun stop() {
            try { ins?.close() } catch (_: IOException) {}
            try { proc?.destroy() } catch (_: Exception) {}
            ins = null; proc = null
        }

        override fun seek(pos: Long) {
            val d = pos - this.pos
            val cur = ins
            if (cur != null && d in 0..(256 * 1024)) {   // a short step forward: just read over it
                var left = d
                val skip = ByteArray(minOf(d, 65536L).toInt().coerceAtLeast(1))
                while (left > 0) { val n = cur.read(skip, 0, minOf(left, skip.size.toLong()).toInt()); if (n <= 0) break; left -= n }
                this.pos = pos - left
                if (left == 0L) return
            }
            stop()
            this.pos = pos
        }

        override fun read(): Int { val b = ensure().read(); if (b >= 0) pos++; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val n = ensure().read(b, off, len); if (n > 0) pos += n; return n }
        override fun close() = stop()
    }

    override fun open(v: String): Source {
        val p = real(v)
        val o = run("p=${RootShell.q(p)}; [ -e \"§p\" ] || exit 2; stat -L -c '%F|%s' \"§p\"; exit 0")
        if (o.code == 2) throw NotFound("No such file or directory")
        if (o.code != 0) failOf(o)
        val a = o.out.trim().split('|')
        if (a[0].startsWith("directory")) throw IOException("Is a directory")
        return RootSource(p, a.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    // ---------------------------------------------------------------- changing
    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        if (size < 0) throw BadReq("missing size")
        val p = real(v)
        if (p == "/") throw BadReq("not a file")
        space(vdir(p))?.let { (free, _) ->   // refuse up front instead of failing at 95 % (5 MB spare)
            if (size > 0 && free > 0 && size + (5L shl 20) > free) throw Full("Not enough free space on the root file system (needs ${size / 1048576} MB, ${free / 1048576} MB free)")
        }
        val tmp = "$p.lspart"
        // who owns the target now: an existing file keeps owner + mode, a new one gets the owner of its folder
        val i = run("p=${RootShell.q(p)}; d=§(dirname \"§p\"); mkdir -p \"§d\" || exit 1; [ -d \"§p\" ] && echo '@@DIR'; if [ -e \"§p\" ]; then stat -c '%u:%g %a' \"§p\"; else stat -c '%u:%g' \"§d\"; fi; exit 0")
        if (i.code != 0) failOf(i)
        if ("@@DIR" in i.out.split('\n')) throw IOException("Is a directory")
        val tok = i.out.split('\n').firstOrNull { it.matches(Regex("\\d+:\\d+( [0-7]{3,4})?")) }?.split(' ') ?: emptyList()
        val own = tok.getOrNull(0) ?: ""
        val mode = tok.getOrNull(1) ?: ""
        val pr = RootShell.start("cat > ${RootShell.q(tmp)}")
        var ok = false
        try {
            val o = pr.outputStream
            val buf = ByteArray(CHUNK)
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("connection lost")
                o.write(buf, 0, n)
                left -= n
                cb?.invoke(n)
            }
            o.close()
            val code = pr.waitFor()
            if (code != 0) failOf(RootShell.Out(code, "", try { pr.errorStream.bufferedReader().readText() } catch (_: IOException) { "" }))
            val fin = run("t=${RootShell.q(tmp)}; p=${RootShell.q(p)}; " +
                (if (own.isNotEmpty()) "chown $own \"§t\" 2>/dev/null; " else "") +
                (if (mode.isNotEmpty()) "chmod $mode \"§t\" 2>/dev/null; " else "") + "mv -f \"§t\" \"§p\"")
            if (fin.code != 0) failOf(fin)
            ok = true
        } finally {
            try { pr.destroy() } catch (_: Exception) {}
            if (!ok) try { RootShell.exec("rm -f " + RootShell.q(tmp), 15) } catch (_: Exception) {}
        }
    }

    override fun mkdir(v: String) {
        val p = real(v)
        val o = run("p=${RootShell.q(p)}; [ -d \"§p\" ] && exit 0; [ -e \"§p\" ] && exit 9; pp=§(dirname \"§p\"); mkdir -p \"§p\" || exit 1; chown §(stat -c '%u:%g' \"§pp\") \"§p\" 2>/dev/null; exit 0")
        when (o.code) {
            0 -> {}
            9 -> throw Exists("a file with that name exists")
            else -> failOf(o)
        }
    }

    private val protectedPaths = setOf("/", "/system", "/system_ext", "/vendor", "/product", "/odm", "/apex", "/data", "/data/data", "/data/app", "/data/user",
        "/data/user_de", "/data/media", "/data/misc", "/data/system", "/proc", "/sys", "/dev", "/storage", "/sdcard", "/mnt", "/cache", "/metadata", "/config",
        "/acct", "/etc", "/bin", "/sbin", "/init", "/lib", "/linkerconfig", "/storage/emulated", "/storage/emulated/0")

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val p = real(v)
        if (p in protectedPaths) throw Denied("Refusing to delete a system folder as a whole - open it and delete what is inside")
        val o = run("p=${RootShell.q(p)}; [ -e \"§p\" ] || [ -L \"§p\" ] || exit 2; rm -rf \"§p\" 2>&1; if [ -e \"§p\" ] || [ -L \"§p\" ]; then exit 5; fi; exit 0", 900)
        when (o.code) {
            0 -> progress?.invoke(vbase(p))
            2 -> throw NotFound("No such file or directory")
            5 -> throw IOException("Could not delete " + vbase(p) + " (in use or on a read-only part of the system?)")
            else -> failOf(o)
        }
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        val p = real(v)
        if (p == "/") throw Denied("cannot rename the root")
        val caseOnly = newName.equals(vbase(p), true)
        val o = run("p=${RootShell.q(p)}; d=§(dirname \"§p\")/${RootShell.q(newName)}; [ -e \"§p\" ] || [ -L \"§p\" ] || exit 2; " +
            "if [ -e \"§d\" ] || [ -L \"§d\" ]; then " + (if (caseOnly) "[ \"§p\" -ef \"§d\" ] || exit 9; " else "exit 9; ") + "fi; mv \"§p\" \"§d\"")
        when (o.code) {
            0 -> {}
            2 -> throw NotFound("No such file or directory")
            9 -> throw Exists("name already exists")
            else -> failOf(o)
        }
    }

    override fun move(v: String, toV: String) {
        val s = real(v)
        val d = real(toV)
        if (s == "/") throw Denied("cannot move the root")
        val o = run("s=${RootShell.q(s)}; d=${RootShell.q(d)}; [ -e \"§s\" ] || [ -L \"§s\" ] || exit 2; [ -d \"§d\" ] && d=\"§d/§(basename \"§s\")\"; " +
            "[ \"§s\" = \"§d\" ] && exit 0; case \"§d/\" in \"§s\"/*) exit 8;; esac; if [ -e \"§d\" ] || [ -L \"§d\" ]; then exit 9; fi; mv \"§s\" \"§d\"", 900)
        when (o.code) {
            0 -> {}
            2 -> throw NotFound("No such file or directory")
            8 -> throw BadReq("cannot put a folder inside itself")
            9 -> throw Exists("name already exists")
            else -> failOf(o)
        }
    }
}
