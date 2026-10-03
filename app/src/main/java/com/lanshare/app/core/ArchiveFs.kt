package com.lanshare.app.core

import com.github.junrar.Archive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/*
 * Archives (ZIP / RAR) as folders.
 *
 * A virtual path "/Download/a.zip!/docs/x.txt" means "docs/x.txt inside /Download/a.zip": the segment that ends with '!' is the
 * archive itself. Everything that already works on folders (browse, search, download, copy to another folder / device, send,
 * print, details) therefore works inside archives. Archives are read-only; "extract" is simply a copy of "a.zip!" out of it.
 */

private val ARC_EXT = setOf("zip", "rar", "cbz", "cbr")

fun isArcName(n: String): Boolean {
    val i = n.lastIndexOf('.')
    return i > 0 && n.substring(i + 1).lowercase() in ARC_EXT
}

/** "/a/b.zip!/x/y" -> ("/a/b.zip", "/x/y"); null when [v] is not inside an archive. */
fun arcSplit(v: String): Pair<String, String>? {
    val parts = vnorm(v).split('/')   // parts[0] is "" (leading slash)
    for (i in 1 until parts.size) {
        val s = parts[i]
        if (s.length > 1 && s.endsWith("!") && isArcName(s.dropLast(1))) {
            val arc = parts.subList(0, i).joinToString("/") + "/" + s.dropLast(1)
            val inner = parts.subList(i + 1, parts.size).joinToString("/")
            return arc to vnorm("/$inner")
        }
    }
    return null
}

/** Name a copied item gets at its destination: the archive root "a.zip!" becomes the folder "a". */
fun destName(p: String): String {
    val b = vbase(p)
    return if (b.length > 1 && b.endsWith("!") && isArcName(b.dropLast(1))) splitExt(b.dropLast(1)).first else b
}

class AEntry(val path: String, val dir: Boolean, val size: Long, val mtime: Long, val raw: String) {
    val name: String get() = path.substringAfterLast('/')
}

class ArcIndex(val file: File, val rar: Boolean, val charset: Charset) {
    val all = LinkedHashMap<String, AEntry>()           // "dir/sub/file.txt" -> entry (no leading slash)
    val kids = HashMap<String, ArrayList<AEntry>>()     // "" = archive root, "dir/sub" -> its direct children

    fun add(rawName: String, dir: Boolean, size: Long, mtime: Long) {
        val parts = rawName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return   // never trust names that climb out of the folder
        var acc = ""
        for (i in parts.indices) {
            val parent = acc
            acc = if (acc.isEmpty()) parts[i] else acc + "/" + parts[i]
            if (all.containsKey(acc)) continue
            val last = i == parts.size - 1
            val isDir = !last || dir
            val e = AEntry(acc, isDir, if (isDir) 0L else maxOf(size, 0L), mtime, if (last) rawName else "")
            all[acc] = e
            kids.getOrPut(parent) { ArrayList() }.add(e)
        }
    }

    companion object {
        fun read(file: File, rar: Boolean): ArcIndex {
            if (rar) {
                val ix = ArcIndex(file, true, Charsets.UTF_8)
                try {
                    Archive(file).use { a ->
                        for (h in a.fileHeaders) ix.add(h.fileName, h.isDirectory, h.fullUnpackSize, (h.mTime?.time ?: 0L) / 1000)
                    }
                } catch (e: IOException) { throw IOException(rarMsg(e))
                } catch (e: Exception) { throw IOException(rarMsg(e)) }
                return ix
            }
            // names are UTF-8 in modern archives; older Windows tools wrote the Turkish code page
            for (cs in listOf(Charsets.UTF_8, Charset.forName("windows-1254"))) {
                try {
                    val ix = ArcIndex(file, false, cs)
                    ZipFile(file, cs).use { z ->
                        val en = z.entries()
                        while (en.hasMoreElements()) {
                            val e = en.nextElement()
                            ix.add(e.name, e.isDirectory, e.size, if (e.time > 0) e.time / 1000 else 0L)
                        }
                    }
                    return ix
                } catch (e: IllegalArgumentException) { continue
                } catch (e: ZipException) { throw IOException("not a valid ZIP file: " + errText(e)) }
            }
            throw IOException("could not read the file names of this ZIP")
        }

        fun rarMsg(e: Exception): String {
            val m = (e.message ?: "") + " " + e.javaClass.simpleName
            return when {
                m.contains("v5", true) || m.contains("rar5", true) -> "RAR5 archives are not supported yet (ZIP and RAR 4 work)"
                m.contains("password", true) || m.contains("encrypt", true) -> "this archive is password-protected"
                else -> "cannot read this RAR file: " + errText(e)
            }
        }
    }
}

object ArcStore {
    private const val CAP = 1536L shl 20   // cache limit: extracted files + archives fetched from SMB
    private val idx = object : LinkedHashMap<String, ArcIndex>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArcIndex>?) = size > 6
    }
    private val lock = Any()
    private val xlock = Any()

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Called once at startup: leftovers of the last run are of no use. */
    fun cleanOnStart() { try { Core.cacheDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {} }

    private fun trim(need: Long) {
        val dir = Core.cacheDir
        dir.mkdirs()
        val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".part") }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total + need <= CAP) break
            val l = f.length()
            if (f.delete()) total -= l
        }
        val free = try { dir.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
        if (need > 0 && need + (50L shl 20) > free) throw Full("Not enough free space to open this (needs ${need / 1048576} MB)")
    }

    /** A real file for the archive: the file itself on this device, a cached copy when it lives on an SMB share. */
    private fun materialize(base: Endpoint, ap: String): File {
        if (base is LocalFs) {
            val f = base.real(ap)
            if (!f.isFile) throw NotFound("No such archive")
            return f
        }
        val st = base.stat(ap)
        val sz = st.optLong("size")
        val mt = st.optLong("mtime")
        val ext = ap.substringAfterLast('.', "zip").lowercase()
        val f = File(Core.cacheDir, "src_" + sha1(base.id + "|" + ap + "|" + sz + "|" + mt) + "." + ext)
        synchronized(xlock) {
            if (f.isFile && f.length() == sz) { f.setLastModified(System.currentTimeMillis()); return f }
            trim(sz)
            val tmp = File(f.path + ".part")
            try {
                base.open(ap).use { s -> tmp.outputStream().use { o -> s.copyTo(o, 1 shl 20) } }
                if (!tmp.renameTo(f)) throw IOException("could not store the archive")
            } finally { if (tmp.exists()) tmp.delete() }
        }
        return f
    }

    fun index(base: Endpoint, ap: String): ArcIndex {
        val f = materialize(base, ap)
        val key = f.path + "|" + f.length() + "|" + f.lastModified()
        synchronized(lock) { idx[key]?.let { return it } }
        val rar = ap.substringAfterLast('.', "").lowercase().let { it == "rar" || it == "cbr" }
        val ix = ArcIndex.read(f, rar)
        synchronized(lock) { idx[key] = ix }
        return ix
    }

    /** Extracts one entry into the cache (once) and returns a seekable stream, so video / pdf viewers can scrub inside archives. */
    fun extract(ix: ArcIndex, e: AEntry): Source {
        val out = File(Core.cacheDir, "e_" + sha1(ix.file.path + "|" + ix.file.length() + "|" + ix.file.lastModified() + "|" + e.path))
        synchronized(xlock) {
            if (!out.isFile) {
                trim(e.size)
                val tmp = File(out.path + ".part")
                try {
                    tmp.outputStream().buffered(1 shl 16).use { o -> if (ix.rar) rarExtract(ix.file, e.raw, o) else zipExtract(ix, e.raw, o) }
                    if (!tmp.renameTo(out)) throw IOException("could not store the extracted file")
                } finally { if (tmp.exists()) tmp.delete() }
            }
            out.setLastModified(System.currentTimeMillis())
            return LocalSource(RandomAccessFile(out, "r"), out.length())
        }
    }

    private fun zipExtract(ix: ArcIndex, raw: String, o: OutputStream) {
        try {
            ZipFile(ix.file, ix.charset).use { z ->
                val e = z.getEntry(raw) ?: throw NotFound("entry not found in the archive")
                z.getInputStream(e).use { it.copyTo(o, 1 shl 16) }
            }
        } catch (e: ZipException) {
            throw IOException("cannot extract this file (password-protected or damaged): " + errText(e))
        }
    }

    private fun rarExtract(file: File, raw: String, o: OutputStream) {
        try {
            Archive(file).use { a ->
                val h = a.fileHeaders.firstOrNull { it.fileName == raw } ?: throw NotFound("entry not found in the archive")
                if (h.isEncrypted) throw IOException("this file is password-protected")
                a.extractFile(h, o)
            }
        } catch (e: IOException) { throw e
        } catch (e: NotFound) { throw e
        } catch (e: Exception) { throw IOException(ArcIndex.rarMsg(e)) }
    }
}

/**
 * Wraps any endpoint: paths that point into an archive are answered from the archive, everything else goes straight to [base].
 * Another LANShare device is not wrapped - it answers archive paths itself (it runs the same code).
 */
class ArcEp(val base: Endpoint) : Endpoint by base {
    private fun ar(v: String): Pair<String, String>? = if (base is RemoteFs) null else arcSplit(v)
    private fun key(inner: String) = inner.trim('/')
    private fun ro(): Nothing = throw Denied("Archives are read-only - copy or extract files out of it")
    private fun need(ix: ArcIndex, k: String): AEntry = ix.all[k] ?: throw NotFound("No such file or folder in the archive")

    override fun ls(v: String): List<Item> {
        val a = ar(v) ?: return base.ls(v)
        val ix = ArcStore.index(base, a.first)
        val k = key(a.second)
        if (k.isNotEmpty() && need(ix, k).dir.not()) throw IOException("Not a directory")
        return (ix.kids[k] ?: ArrayList<AEntry>()).map { e ->
            Item(e.name, e.dir, e.size, e.mtime, if (e.dir) (ix.kids[e.path]?.size ?: 0) else null)
        }
    }

    override fun search(v: String, q: String): SearchResult {
        val a = ar(v) ?: return base.search(v, q)
        val ix = ArcStore.index(base, a.first)
        val k = key(a.second)
        val prefix = if (k.isEmpty()) "" else "$k/"
        val ql = q.lowercase()
        val out = ArrayList<Item>()
        var partial = false
        for (e in ix.all.values) {
            if (!e.path.startsWith(prefix) || !e.name.lowercase().contains(ql)) continue
            out.add(Item(e.name, e.dir, e.size, e.mtime, null, vnorm(a.first + "!/" + e.path)))
            if (out.size >= 300) { partial = true; break }
        }
        out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    override fun names(v: String): MutableSet<String> {
        if (ar(v) == null) return base.names(v)
        return try { ls(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }
    }

    override fun walk(v: String): List<WalkItem> {
        val a = ar(v) ?: return base.walk(v)
        val ix = ArcStore.index(base, a.first)
        val k = key(a.second)
        if (k.isNotEmpty()) {
            val e = need(ix, k)
            if (!e.dir) return listOf(WalkItem("", false, e.size))
        }
        val cut = if (k.isEmpty()) 0 else k.length + 1
        val res = arrayListOf(WalkItem("", true, 0))
        val stack = ArrayDeque<String>()   // a folder's entries are added before its children, so mkdir always precedes its files
        stack.addLast(k)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (c in ix.kids[d] ?: ArrayList<AEntry>()) {
                res.add(WalkItem(c.path.substring(cut), c.dir, if (c.dir) 0L else c.size))
                if (c.dir) stack.addLast(c.path)
            }
        }
        return res
    }

    override fun open(v: String): Source {
        val a = ar(v) ?: return base.open(v)
        val ix = ArcStore.index(base, a.first)
        val e = need(ix, key(a.second))
        if (e.dir) throw IOException("Is a directory")
        return ArcStore.extract(ix, e)
    }

    override fun stat(v: String): JSONObject {
        val a = ar(v) ?: return base.stat(v)
        val ix = ArcStore.index(base, a.first)
        val k = key(a.second)
        val o = JSONObject().put("path", vnorm(v)).put("readonly", true).put("hidden", false).put("link", false)
        val e = if (k.isEmpty()) null else need(ix, k)
        if (e == null || e.dir) {
            val prefix = if (k.isEmpty()) "" else "$k/"
            var files = 0L; var folders = 0L; var total = 0L
            for (x in ix.all.values) if (x.path.startsWith(prefix)) { if (x.dir) folders++ else { files++; total += x.size } }
            o.put("name", if (e == null) vbase(a.first) else e.name).put("dir", true).put("size", 0L)
                .put("mtime", e?.mtime ?: (ix.file.lastModified() / 1000))
                .put("files", files).put("folders", folders).put("total", total).put("partial", false)
        } else {
            o.put("name", e.name).put("dir", false).put("size", e.size).put("mtime", e.mtime)
        }
        return o
    }

    override fun space(v: String): Pair<Long, Long>? = base.space(ar(v)?.first ?: v)

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) { if (ar(v) != null) ro(); base.write(v, input, size, cb) }
    override fun mkdir(v: String) { if (ar(v) != null) ro(); base.mkdir(v) }
    override fun remove(v: String, progress: ((String) -> Unit)?) { if (ar(v) != null) ro(); base.remove(v, progress) }
    override fun rename(v: String, newName: String) { if (ar(v) != null) ro(); base.rename(v, newName) }
    override fun move(v: String, toV: String) { if (ar(v) != null || ar(toV) != null) ro(); base.move(v, toV) }
}
