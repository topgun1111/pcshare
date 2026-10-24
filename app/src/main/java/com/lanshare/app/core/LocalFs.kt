package com.lanshare.app.core

import android.os.Build
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject

class LocalSource(private val f: RandomAccessFile, override val size: Long) : Source() {
    override val seekable: Boolean get() = true
    override fun seek(pos: Long) = f.seek(pos)
    override fun read(): Int = f.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = f.read(b, off, len)
    override fun close() = f.close()
}

private const val PAR_MIN = 150      // folders with fewer entries are read on the calling thread
private const val PAR_THREADS = 4

/** Files on this device, exposed as virtual paths rooted at '/'. */
class LocalFs(rootPath: String) : Endpoint {
    override val id = "local"
    override val name = "This device"
    val root: File = File(rootPath).canonicalFile

    fun real(v: String): File {
        val p = File(root, vnorm(v).trimStart('/')).canonicalFile
        if (p != root && !p.path.startsWith(root.path.trimEnd('/') + "/")) throw Denied("outside the shared folder")
        return p
    }

    /** Like [real], but the LAST component is not resolved: a symlink stays the link itself (delete / rename / move act on the link, not its target). */
    fun lreal(v: String): File {
        val n = vnorm(v)
        if (n == "/") return real(v)
        return File(real(vdir(n)), vbase(n))
    }

    private fun fsize(f: File): Long {
        val l = f.length()
        if (l > 0) return l
        return try { RandomAccessFile(f, "r").use { it.length() } } catch (_: IOException) { 0L }
    }

    fun isLink(f: File): Boolean =
        if (Build.VERSION.SDK_INT >= 26) Files.isSymbolicLink(f.toPath())
        else try { val par = f.parentFile; par != null && File(par.canonicalFile, f.name).let { it.canonicalPath != it.absolutePath } }
        catch (_: IOException) { false }

    private val statPool = java.util.concurrent.Executors.newFixedThreadPool(PAR_THREADS) { r -> Thread(r, "stat").also { it.isDaemon = true } }

    override fun ls(v: String): List<Item> = ls(v, true)

    /** [counts]=false skips the "N items" count of sub-folders (one extra directory read each - slow on Android's FUSE storage); see [counts]. */
    fun ls(v: String, counts: Boolean): List<Item> {
        val d = real(v)
        if (!d.exists()) throw NotFound("No such file or directory")
        val files = d.listFiles() ?: throw (if (d.isDirectory) Denied("Permission denied") else IOException("Not a directory"))
        val n = files.size
        val slots = arrayOfNulls<Item>(n)
        val modern = Build.VERSION.SDK_INT >= 26
        fun one(i: Int) {   // never hide an entry just because stat() is refused (Android storage quirks)
            val f = files[i]
            var dir: Boolean; var size: Long; var mt: Long
            val a = if (modern) try { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java) } catch (_: Exception) { null } else null
            if (a != null) { dir = a.isDirectory; size = if (dir) 0L else a.size(); mt = a.lastModifiedTime().toMillis() / 1000 }   // ONE stat instead of three
            else { dir = f.isDirectory; size = if (dir) 0L else f.length(); mt = f.lastModified() / 1000 }
            val cache = dir && (f.name.equals("thumbnails", true) || File(f, ".nomedia").exists())   // cache folder: hidden together with the dot-folders
            slots[i] = Item(f.name, dir, size, mt, if (dir && counts) f.list()?.size else null, null, cache)
        }
        if (n < PAR_MIN) for (i in 0 until n) one(i)
        else {   // every stat is a round trip into Android's storage layer: several at once overlap their waiting (big folders)
            val per = (n + PAR_THREADS - 1) / PAR_THREADS
            (0 until PAR_THREADS).map { c -> statPool.submit { for (i in c * per until minOf(n, (c + 1) * per)) one(i) } }.forEach { it.get() }
        }
        val out = ArrayList<Item>(n)
        for (x in slots) if (x != null) out.add(x)
        if (out.isEmpty() && Core.storageOk == false) throw Denied(STORAGE_MSG)
        return out
    }

    /** Item counts of the sub-folders of v, read in parallel (the list itself is shown first, the counts follow). */
    fun counts(v: String): Map<String, Int> {
        val d = real(v)
        val dirs = d.listFiles()?.filter { it.isDirectory } ?: return emptyMap()
        val res = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            dirs.map { f -> pool.submit { f.list()?.size?.let { res[f.name] = it } } }.forEach { try { it.get() } catch (_: Exception) {} }
        } finally { pool.shutdownNow() }
        return res
    }

    private val statCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Long, Long, Long>>()   // folder -> (time, files, bytes)

    /** Number of files and total bytes below folder v (start menu tiles). Symlinks are not followed, hidden files count. Cached for a minute; stops after [secs] seconds and then returns what it has (partial = true). */
    fun stat(v: String, secs: Int = 8): Triple<Long, Long, Boolean> {
        val key = vnorm(v)
        statCache[key]?.let { if (System.currentTimeMillis() - it.first < 60_000) return Triple(it.second, it.third, false) }
        val end = System.currentTimeMillis() + secs * 1000L
        var files = 0L
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(real(v))
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (f in dir.listFiles() ?: continue) {
                if (f.isDirectory) { if (!isLink(f)) stack.addLast(f) } else { files++; bytes += f.length() }
            }
            if (System.currentTimeMillis() > end) return Triple(files, bytes, true)
        }
        statCache[key] = Triple(System.currentTimeMillis(), files, bytes)
        return Triple(files, bytes, false)
    }

    /** Recursive name search below folder v (case-insensitive substring). Capped by count and time. */
    fun search(v: String, q: String, limit: Int = 300, secs: Int = 15): SearchResult {
        val ql = q.lowercase()
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + secs * 1000L
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(real(v) to vnorm(v))
        while (stack.isNotEmpty()) {
            val (dir, vdir) = stack.removeLast()
            val kids = dir.listFiles() ?: emptyArray()
            val dirs = kids.filter { it.isDirectory }
            for (f in dirs + kids.filter { !it.isDirectory }) {   // like os.walk: folders first, then files
                if (!f.name.lowercase().contains(ql)) continue
                val d = f.isDirectory
                out.add(Item(f.name, d, if (d) 0L else f.length(), f.lastModified() / 1000, null, vjoin(vdir, f.name)))
                if (out.size >= limit) return SearchResult(out, true)
            }
            for (d in dirs.reversed()) if (!isLink(d)) stack.addLast(d to vjoin(vdir, d.name))
            if (System.currentTimeMillis() > end) return SearchResult(out, true)
        }
        out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, false)
    }

    /** Part 8: like [search] but matches the TEXT of office files (docx/xlsx/doc/xls/odt/ods) instead of the name. Slow: tight caps; non-office files are skipped. */
    fun searchOfficeContent(v: String, q: String, limit: Int = 100, secs: Int = 20): SearchResult {
        val out = ArrayList<Item>()
        val end = System.currentTimeMillis() + secs * 1000L
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(real(v) to vnorm(v))
        while (stack.isNotEmpty()) {
            val (dir, vdir) = stack.removeLast()
            val kids = dir.listFiles() ?: emptyArray()
            for (f in kids) {
                if (System.currentTimeMillis() > end) return SearchResult(out, true)
                if (f.isDirectory || !com.lanshare.app.core.office.OfficeExport.containsOrFalse(f, q)) continue
                out.add(Item(f.name, false, f.length(), f.lastModified() / 1000, null, vjoin(vdir, f.name)))
                if (out.size >= limit) return SearchResult(out, true)
            }
            for (d in kids.filter { it.isDirectory }.reversed()) if (!isLink(d)) stack.addLast(d to vjoin(vdir, d.name))
        }
        out.sortBy { it.name.lowercase() }
        return SearchResult(out, false)
    }

    /** Started at the storage root = "search everything": far higher result and time limits than a search inside one folder. */
    override fun search(v: String, q: String) =
        if (vnorm(v) == "/") search(v, q, 20_000, 80) else search(v, q, 300, 15)

    override fun lsFast(v: String): List<Item> = ls(v, false)

    override fun names(v: String): MutableSet<String> =
        try {
            val d = real(v)
            if (!d.exists()) mutableSetOf() else (d.list() ?: throw (if (d.isDirectory) Denied("Permission denied") else IOException("Not a directory"))).toMutableSet()   // names only: no stat, no counts
        } catch (_: IOException) { mutableSetOf() }

    override fun walk(v: String): List<WalkItem> {
        val base = real(v)
        if (base.isFile) return listOf(WalkItem("", false, fsize(base)))
        val res = arrayListOf(WalkItem("", true, 0))
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(base to "")
        while (stack.isNotEmpty()) {   // a folder's entries are added before its children: mkdir always precedes its files
            val (dir, rel) = stack.removeLast()
            val kids = dir.listFiles()
            if (kids == null) { res.add(WalkItem(if (rel.isEmpty()) dir.name else rel, false, 0, true)); continue }   // unreadable folder
            for (k in kids) {
                val r = if (rel.isEmpty()) k.name else rel + "/" + k.name
                if (isLink(k)) { res.add(WalkItem(r, false, 0, true)); continue }   // links are never followed
                if (k.isDirectory) { res.add(WalkItem(r, true, 0)); stack.addLast(k to r) }
                else res.add(WalkItem(r, false, fsize(k)))
            }
        }
        return res
    }

    /** Files / folders / bytes below [base] (links are not followed). Stops after [secs] seconds or [cap] entries; [partial] says so. */
    private fun tree(base: File, secs: Int = 12, cap: Int = 400_000): Triple<LongArray, Boolean, Unit> {
        val tot = LongArray(3)   // files, folders, bytes
        val end = System.currentTimeMillis() + secs * 1000L
        val stack = ArrayDeque<File>()
        stack.addLast(base)
        var seen = 0
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val kids = dir.listFiles() ?: continue
            for (k in kids) {
                if (isLink(k)) continue
                if (k.isDirectory) { tot[1]++; stack.addLast(k) } else { tot[0]++; tot[2] += fsize(k) }
                if (++seen >= cap) return Triple(tot, true, Unit)
            }
            if (System.currentTimeMillis() > end) return Triple(tot, true, Unit)
        }
        return Triple(tot, false, Unit)
    }

    override fun stat(v: String): JSONObject {
        val f = lreal(v)
        val link = isLink(f)
        if (!f.exists() && !link) throw NotFound("No such file or directory")
        val dir = f.isDirectory
        val o = JSONObject().put("name", if (f == root) "/" else f.name).put("path", vnorm(v)).put("dir", dir)
            .put("size", if (dir) 0L else fsize(f)).put("mtime", f.lastModified() / 1000)
        if (Build.VERSION.SDK_INT >= 26) try {
            val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            o.put("ctime", a.creationTime().toMillis() / 1000).put("atime", a.lastAccessTime().toMillis() / 1000)
        } catch (_: Exception) {}
        o.put("readonly", !f.canWrite()).put("hidden", f.name.startsWith(".")).put("link", link)
        if (dir) {
            val (t, partial, _) = tree(f)
            o.put("files", t[0]).put("folders", t[1]).put("total", t[2]).put("partial", partial)
        } else {
            val m = Media.info(f, f.name)
            for (k in m.keys()) o.put(k, m.get(k))
        }
        return o
    }

    override fun space(v: String): Pair<Long, Long>? = try {
        val f = real(v).let { if (it.exists()) it else root }
        val total = f.totalSpace
        if (total > 0) f.usableSpace to total else null
    } catch (_: Exception) { null }

    override fun open(v: String): Source {
        val p = real(v)
        if (!p.exists()) throw NotFound("No such file or directory")
        if (p.isDirectory) throw IOException("Is a directory")
        try {
            return LocalSource(RandomAccessFile(p, "r"), fsize(p))
        } catch (e: FileNotFoundException) {
            throw if (p.exists()) Denied("Permission denied") else NotFound("No such file or directory")
        }
    }

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        if (size < 0) throw BadReq("missing size")
        val p = real(v)
        try {   // refuse up front instead of failing at 95 % of a big transfer (keeps 5 MB spare for Android itself)
            val free = root.usableSpace
            if (size > 0 && free > 0 && size + (5L shl 20) > free)
                throw Full("Not enough free space on $name (needs ${size / 1048576} MB, ${free / 1048576} MB free)")
        } catch (e: SecurityException) {}
        p.parentFile?.mkdirs()
        val tmp = File(p.path + ".lspart")
        try {
            tmp.outputStream().use { o ->
                val buf = ByteArray(CHUNK)
                var left = size
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("connection lost")
                    o.write(buf, 0, n)
                    left -= n
                    cb?.invoke(n)
                }
            }
            if (!tmp.renameTo(p)) {   // keep the old file until the new one is in place
                val bak = File(p.path + ".lsold")
                val hadOld = p.exists() && p.renameTo(bak)
                if (!tmp.renameTo(p)) { if (hadOld) bak.renameTo(p); throw IOException("could not move the file into place") }
                if (hadOld) bak.delete()
            }
            IndexManager.changed(p)   // name index (Universal Search)
        } finally {
            if (tmp.exists()) tmp.delete()
            Partials.forget(tmp.path)
        }
    }

    override val setsMtime: Boolean get() = true
    override fun setMtime(v: String, sec: Long) { if (sec > 0) try { val f = real(v); f.setLastModified(sec * 1000); IndexManager.changed(f) } catch (_: Exception) {} }

    override val resumable: Boolean get() = true

    override fun partial(v: String, tag: String): Long {
        val tmp = File(real(v).path + ".lspart")
        return if (tmp.isFile && Partials.matches(tmp.path, tag)) tmp.length() else 0L
    }

    override fun writeResume(v: String, input: InputStream, size: Long, offset: Long, tag: String, cb: ((Int) -> Unit)?) {
        if (size < 0) throw BadReq("missing size")
        if (offset < 0 || offset > size) throw BadReq("bad resume offset")
        val p = real(v)
        try {   // only what is still missing needs room
            val free = root.usableSpace
            val need = size - offset
            if (need > 0 && free > 0 && need + (5L shl 20) > free)
                throw Full("Not enough free space on $name (needs ${need / 1048576} MB more, ${free / 1048576} MB free)")
        } catch (e: SecurityException) {}
        p.parentFile?.mkdirs()
        val tmp = File(p.path + ".lspart")
        if (offset > 0 && !(tmp.isFile && tmp.length() == offset && Partials.matches(tmp.path, tag)))
            throw BadReq("resume offset mismatch (have ${if (tmp.isFile) tmp.length() else 0})")   // the sender asks again and continues from there
        Partials.put(tmp.path, tag)
        var keep = true
        try {
            java.io.FileOutputStream(tmp, offset > 0).use { o ->   // append when continuing, else start over
                val buf = ByteArray(CHUNK)
                var left = size - offset
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("connection lost")
                    o.write(buf, 0, n)
                    left -= n
                    cb?.invoke(n)
                }
                o.flush()
                try { o.fd.sync() } catch (_: Exception) {}
            }
            if (!tmp.renameTo(p)) {   // keep the old file until the new one is in place
                val bak = File(p.path + ".lsold")
                val hadOld = p.exists() && p.renameTo(bak)
                if (!tmp.renameTo(p)) { if (hadOld) bak.renameTo(p); throw IOException("could not move the file into place") }
                if (hadOld) bak.delete()
            }
            keep = false
            IndexManager.changed(p)
        } catch (e: Cancelled) { keep = false; throw e
        } finally {
            if (!keep) { if (tmp.exists()) tmp.delete(); Partials.forget(tmp.path) }
        }
    }

    override fun mkdir(v: String) {
        val d = real(v)
        if (!d.mkdirs() && !d.isDirectory) throw IOException("could not create the folder")
        IndexManager.changed(d)
    }

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val p = lreal(v)
        if (p == root) throw Denied("cannot delete the shared root")
        if (!p.exists() && !isLink(p)) throw NotFound("No such file or directory")
        val fails = ArrayList<String>()
        try { delRec(p, fails, progress) } finally { IndexManager.changed(p) }   // also after a cancel / partial failure: whatever went is dropped from the name index
        if (fails.isNotEmpty())
            throw IOException("Could not delete " + (if (fails.size == 1) fails[0] else fails.size.toString() + " items (" + fails.take(3).joinToString("; ") + (if (fails.size > 3) "; ..." else "") + ")"))
    }

    /** Best-effort recursive delete: links are removed themselves (never followed), failures are collected and the rest carries on. */
    private fun delRec(f: File, fails: MutableList<String>, progress: ((String) -> Unit)? = null) {
        if (!isLink(f) && f.isDirectory) {
            val kids = f.listFiles()
            if (kids == null) { fails.add(f.name + ": cannot read folder"); return }
            val before = fails.size
            for (k in kids) delRec(k, fails, progress)
            if (fails.size > before) return   // something inside stayed, so the folder cannot go either
        }
        if (!f.delete() && (f.exists() || isLink(f))) fails.add(f.name + ": " + (if (f.canWrite()) "in use" else "not allowed"))
        else progress?.invoke(f.name)
    }

    override fun rename(v: String, newName: String) {
        if (newName.isEmpty() || '/' in newName || '\\' in newName || newName == "." || newName == "..") throw BadReq("invalid name")
        val p = lreal(v)
        if (p == root) throw Denied("cannot rename the shared root")
        val dst = File(p.parentFile, newName)
        if (dst.exists() || isLink(dst)) {
            // case-only rename (a.jpg -> A.jpg) on a case-insensitive volume: the "other" name is this very file
            val same = newName != p.name && newName.equals(p.name, true) && Build.VERSION.SDK_INT >= 26 &&
                try { Files.isSameFile(dst.toPath(), p.toPath()) } catch (_: Exception) { false }
            if (!same) throw Exists("name already exists")
        }
        if (!p.renameTo(dst)) throw IOException("could not rename")
        IndexManager.changed(p); IndexManager.changed(dst)
    }

    override fun move(v: String, toV: String) {
        val s = lreal(v)                                // a link is moved itself
        if (!s.exists() && !isLink(s)) throw NotFound("No such file or directory")
        var d = real(toV)
        if (d.isDirectory) d = File(d, s.name)         // shutil.move: into an existing folder
        if (d.path == s.path) return                    // onto itself: nothing to do
        if (!isLink(s) && s.isDirectory && d.path.startsWith(s.path.trimEnd('/') + "/")) throw BadReq("cannot put a folder inside itself")
        if (d.exists() || isLink(d)) throw Exists("name already exists")   // same answer on every volume (rename would silently replace a file)
        try {
        if (!s.renameTo(d)) {                           // e.g. across volumes: copy + delete
            if (isLink(s) || !s.isDirectory) {
                if (isLink(s)) throw IOException("could not move the link")
                s.copyTo(d, false)
            } else if (!s.copyRecursively(d, false)) { d.deleteRecursively(); throw IOException("could not copy the folder") }
            val fails = ArrayList<String>()
            delRec(s, fails)   // only reached when the copy above worked completely
            if (fails.isNotEmpty()) throw IOException("copied, but the original could not be removed: " + fails.take(3).joinToString("; "))
        }
        } finally { IndexManager.changed(s); IndexManager.changed(d) }   // also after a half-done cross-volume move
    }
}
