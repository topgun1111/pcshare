package com.lanshare.app.core

import android.os.Build
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files

class LocalSource(private val f: RandomAccessFile, override val size: Long) : Source() {
    override val seekable: Boolean get() = true
    override fun seek(pos: Long) = f.seek(pos)
    override fun read(): Int = f.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = f.read(b, off, len)
    override fun close() = f.close()
}

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

    private fun fsize(f: File): Long {
        val l = f.length()
        if (l > 0) return l
        return try { RandomAccessFile(f, "r").use { it.length() } } catch (_: IOException) { 0L }
    }

    private fun isLink(f: File): Boolean =
        if (Build.VERSION.SDK_INT >= 26) Files.isSymbolicLink(f.toPath())
        else try { val par = f.parentFile; par != null && File(par.canonicalFile, f.name).let { it.canonicalPath != it.absolutePath } }
        catch (_: IOException) { false }

    override fun ls(v: String): List<Item> {
        val d = real(v)
        if (!d.exists()) throw NotFound("No such file or directory")
        val files = d.listFiles() ?: throw (if (d.isDirectory) Denied("Permission denied") else IOException("Not a directory"))
        val out = ArrayList<Item>(files.size)
        for (f in files) {   // never hide an entry just because stat() is refused (Android storage quirks)
            val dir = f.isDirectory
            out.add(Item(f.name, dir, if (dir) 0L else f.length(), f.lastModified() / 1000,
                if (dir) f.list()?.size else null))
        }
        if (out.isEmpty() && Core.storageOk == false) throw Denied(STORAGE_MSG)
        return out
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

    override fun search(v: String, q: String) = search(v, q, 300, 15)

    override fun names(v: String): MutableSet<String> =
        try { ls(v).map { it.name }.toMutableSet() } catch (_: IOException) { mutableSetOf() }

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
        val p = real(v)
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
            if (!tmp.renameTo(p)) { p.delete(); if (!tmp.renameTo(p)) throw IOException("could not move the file into place") }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    override fun mkdir(v: String) {
        val d = real(v)
        if (!d.mkdirs() && !d.isDirectory) throw IOException("could not create the folder")
    }

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val p = real(v)
        if (p == root) throw Denied("cannot delete the shared root")
        if (!p.exists() && !isLink(p)) throw NotFound("No such file or directory")
        val fails = ArrayList<String>()
        delRec(p, fails, progress)
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
        val p = real(v)
        if (File(p.parentFile, newName).exists()) throw Exists("name already exists")
        if (!p.renameTo(real(vjoin(vdir(v), newName)))) throw IOException("could not rename")
    }

    override fun move(v: String, toV: String) {
        val s = real(v)
        var d = real(toV)
        if (d.isDirectory) d = File(d, s.name)         // shutil.move: into an existing folder
        if (!s.renameTo(d)) {                           // e.g. across volumes: copy + delete
            if (s.isDirectory) { if (!s.copyRecursively(d, false)) throw IOException("could not copy the folder") } else s.copyTo(d, false)
            val fails = ArrayList<String>()
            delRec(s, fails)   // only reached when the copy above worked completely
            if (fails.isNotEmpty()) throw IOException("copied, but the original could not be removed: " + fails.take(3).joinToString("; "))
        }
    }
}
