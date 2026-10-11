package com.lanshare.app.core

import com.github.junrar.Archive
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
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

/** [off]/[csize]/[method]/[enc] are only filled for ZIPs read through ranged reads (see [RangeZip]). */
class AEntry(val path: String, val dir: Boolean, val size: Long, val mtime: Long, val raw: String,
             val off: Long = -1L, val csize: Long = 0L, val method: Int = 0, val enc: Boolean = false) {
    val name: String get() = path.substringAfterLast('/')
}

/** A ZIP that stays on another device / SMB share: only its file list is fetched, entries are read with ranged reads on demand. */
class RemoteZip(val base: Endpoint, val path: String, val mtime: Long)

class ArcIndex(val file: File?, val rar: Boolean, val charset: Charset, val rz: RemoteZip? = null, private val remoteTag: String = "") {
    /** Identity of the archive's content: the names of cached extracted files are derived from it. */
    val tag: String get() = if (file != null) file.path + "|" + file.length() + "|" + file.lastModified() else remoteTag
    val all = LinkedHashMap<String, AEntry>()           // "dir/sub/file.txt" -> entry (no leading slash)
    val kids = HashMap<String, ArrayList<AEntry>>()     // "" = archive root, "dir/sub" -> its direct children

    fun add(rawName: String, dir: Boolean, size: Long, mtime: Long, off: Long = -1L, csize: Long = 0L, method: Int = 0, enc: Boolean = false) {
        val parts = rawName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return   // never trust names that climb out of the folder
        var acc = ""
        for (i in parts.indices) {
            val parent = acc
            acc = if (acc.isEmpty()) parts[i] else acc + "/" + parts[i]
            if (all.containsKey(acc)) continue
            val last = i == parts.size - 1
            val isDir = !last || dir
            val e = AEntry(acc, isDir, if (isDir) 0L else maxOf(size, 0L), mtime, if (last) rawName else "", if (last) off else -1L, csize, method, enc)
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

/** A parse problem in a ZIP's own structure (not a network error): the caller may fall back to downloading the file and using java.util.zip. */
private class ZipFormat(msg: String) : IOException(msg)

/** Counts progress on a [Job] and honours its Cancel button, for streams that are consumed by library code (inflater). */
private class JobIn(private val ins: InputStream, private val job: Job) : InputStream() {
    override fun read(): Int {
        if (job.cancel) throw Cancelled()
        val c = ins.read()
        if (c >= 0) job.done += 1
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (job.cancel) throw Cancelled()
        val n = ins.read(b, off, len)
        if (n > 0) job.done += n
        return n
    }
}

/** Seekable view of bytes [start, start + size) of [inner]: a stored (uncompressed) ZIP entry that is read in place on the other device. */
class WindowSource(private val inner: Source, private val start: Long, override val size: Long) : Source() {
    private var pos = 0L

    init { inner.seek(start) }

    override val seekable: Boolean get() = true

    override fun seek(pos: Long) {
        this.pos = pos.coerceIn(0L, size)
        inner.seek(start + this.pos)
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val left = size - pos
        if (left <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) pos += n
        return n
    }

    override fun close() { inner.close() }
}

/**
 * Reads the file list of a ZIP with a few ranged reads ([Source] is seekable on other LANShare devices and on SMB shares):
 * only the end of the file (end record + central directory) is transferred, never the whole archive.
 */
object RangeZip {
    private const val MAX_CD = 128L shl 20

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)

    private fun readAt(s: Source, pos: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        s.seek(pos)
        var got = 0
        while (got < n) {
            val r = s.read(b, got, n - got)
            if (r < 0) throw ZipFormat("the ZIP file ended unexpectedly")
            got += r
        }
        return b
    }

    private fun decodeName(b: ByteArray, utf8: Boolean, fallback: Charset): String {
        if (utf8) return String(b, Charsets.UTF_8)
        return try {   // old Windows tools wrote the local code page (Turkish: windows-1254) without saying so
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) { String(b, fallback) }
    }

    private fun dosTime(date: Int, time: Int): Long {
        if (date == 0) return 0L
        val mo = ((date shr 5) and 15).coerceIn(1, 12)
        val d = (date and 31).coerceAtLeast(1)
        val c = GregorianCalendar(1980 + (date shr 9), mo - 1, d, (time shr 11).coerceAtMost(23), ((time shr 5) and 63).coerceAtMost(59), ((time and 31) * 2).coerceAtMost(59))
        return c.timeInMillis / 1000
    }

    fun index(s: Source, tag: String, rz: RemoteZip): ArcIndex {
        val size = s.size
        if (size < 22) throw ZipFormat("not a valid ZIP file")
        val tailLen = minOf(size, 65557L).toInt()   // 22-byte end record + up to 64 KB comment
        val tail = readAt(s, size - tailLen, tailLen)
        var e = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte() &&
                i + 22 + u16(tail, i + 20) <= tailLen) { e = i; break }
        }
        if (e < 0) throw ZipFormat("not a valid ZIP file (end record not found)")
        var entries = u16(tail, e + 10).toLong()
        var cdSize = u32(tail, e + 12)
        var cdOff = u32(tail, e + 16)
        if (entries == 0xffffL || cdSize == 0xffffffffL || cdOff == 0xffffffffL) {   // ZIP64: the locator sits right before the end record
            if (e >= 20 && u32(tail, e - 20) == 0x07064b50L) {
                val z = readAt(s, u64(tail, e - 12), 56)
                if (u32(z, 0) != 0x06064b50L) throw ZipFormat("damaged ZIP64 record")
                entries = u64(z, 32); cdSize = u64(z, 40); cdOff = u64(z, 48)
            }
        }
        if (cdSize > MAX_CD) throw IOException("this ZIP has too many files to open over the network - copy it to this device first")
        if (cdOff < 0 || cdOff + cdSize > size) throw ZipFormat("damaged ZIP central directory")
        val cd = readAt(s, cdOff, cdSize.toInt())
        val ix = ArcIndex(null, false, Charsets.UTF_8, rz, tag)
        val cp = Charset.forName("windows-1254")
        var p = 0
        var n = 0L
        while (p + 46 <= cd.size && u32(cd, p) == 0x02014b50L) {
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            var csize = u32(cd, p + 20)
            var usize = u32(cd, p + 24)
            val nlen = u16(cd, p + 28)
            val xlen = u16(cd, p + 30)
            val clen = u16(cd, p + 32)
            var off = u32(cd, p + 42)
            if (p + 46 + nlen + xlen + clen > cd.size) break
            if (usize == 0xffffffffL || csize == 0xffffffffL || off == 0xffffffffL) {   // ZIP64 extra field: only the maxed-out fields are present, in this order
                var x = p + 46 + nlen
                val xe = x + xlen
                while (x + 4 <= xe) {
                    val id = u16(cd, x)
                    val len = u16(cd, x + 2)
                    if (id == 1) {
                        var q = x + 4
                        if (usize == 0xffffffffL && q + 8 <= xe) { usize = u64(cd, q); q += 8 }
                        if (csize == 0xffffffffL && q + 8 <= xe) { csize = u64(cd, q); q += 8 }
                        if (off == 0xffffffffL && q + 8 <= xe) { off = u64(cd, q) }
                        break
                    }
                    x += 4 + len
                }
            }
            val name = decodeName(cd.copyOfRange(p + 46, p + 46 + nlen), (flags and 0x800) != 0, cp)
            ix.add(name, name.endsWith("/") || name.endsWith("\\"), usize, dosTime(u16(cd, p + 14), u16(cd, p + 12)), off, csize, method, (flags and 1) != 0)
            p += 46 + nlen + xlen + clen
            n++
        }
        if (n == 0L && entries > 0) throw ZipFormat("damaged ZIP central directory")
        return ix
    }

    /** Where an entry's data begins: its local header has name/extra lengths of its own, which may differ from the central directory's. */
    fun dataStart(s: Source, e: AEntry): Long {
        val h = readAt(s, e.off, 30)
        if (u32(h, 0) != 0x04034b50L) throw IOException("damaged ZIP entry header")
        return e.off + 30 + u16(h, 26) + u16(h, 28)
    }
}

/**
 * Progress of the long steps that hide inside a folder listing or a file open (fetching an archive from another device, unpacking one
 * big file). The UI polls [current] so the wait is visible and can be cancelled; the jobs are also registered in [Jobs.all], which is
 * what the existing cancel route uses.
 */
object ArcProg {
    private val active = ConcurrentHashMap<String, Job>()

    fun begin(label: String, total: Long): Job {
        val j = Job(label)
        j.total = maxOf(total, 1L)
        j.id = UUID.randomUUID().toString().replace("-", "").take(8)
        Jobs.all[j.id] = j
        active[j.id] = j
        return j
    }

    fun finish(j: Job) {
        active.remove(j.id)
        if (j.state == "run") j.state = "done"
        j.end = System.currentTimeMillis()
    }

    fun current(): JSONObject {
        val j = active.values.firstOrNull() ?: return JSONObject()
        return JSONObject().put("id", j.id).put("label", j.label).put("done", j.done).put("total", j.total)
    }
}

object ArcStore {
    private const val CAP = 1536L shl 20   // cache limit: extracted files + archives fetched from SMB
    private val idx = object : LinkedHashMap<String, ArcIndex>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArcIndex>?) = size > 6
    }
    private val lock = Any()
    private val xlock = Any()
    private val statMemo = HashMap<String, Pair<Long, JSONObject>>()

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

    /** stat of the archive file, remembered for a few seconds: every listing / open would otherwise cost another round trip. */
    private fun statOf(base: Endpoint, ap: String): JSONObject {
        val k = base.id + "|" + ap
        val now = System.currentTimeMillis()
        synchronized(lock) { statMemo[k]?.let { if (now - it.first < 8000) return it.second } }
        val st = base.stat(ap)
        synchronized(lock) { if (statMemo.size > 64) statMemo.clear(); statMemo[k] = now to st }
        return st
    }

    private fun srcName(base: Endpoint, ap: String, sz: Long, mt: Long): String =
        "src_" + sha1(base.id + "|" + ap + "|" + sz + "|" + mt) + "." + ap.substringAfterLast('.', "zip").lowercase()

    private fun pump(s: InputStream, o: OutputStream, job: Job) {
        val buf = ByteArray(1 shl 20)
        while (true) {
            if (job.cancel) throw Cancelled()
            val n = s.read(buf)
            if (n < 0) break
            o.write(buf, 0, n)
            job.done += n
        }
    }

    /**
     * A real file for the archive: the file itself on this device, a cached copy when it lives elsewhere. Copying a big archive takes a
     * while, so it runs under a progress job the UI shows (and can cancel); [jobIn] lets a copy/extract job use its own progress bar.
     */
    private fun materialize(base: Endpoint, ap: String, jobIn: Job? = null): File {
        if (base is LocalFs) {
            val f = base.real(ap)
            if (!f.isFile) throw NotFound("No such archive")
            return f
        }
        val st = statOf(base, ap)
        val sz = st.optLong("size")
        val mt = st.optLong("mtime")
        val f = File(Core.cacheDir, srcName(base, ap, sz, mt))
        synchronized(xlock) {
            if (f.isFile && f.length() == sz) { f.setLastModified(System.currentTimeMillis()); return f }
            trim(sz)
            val job = jobIn ?: ArcProg.begin("Opening " + vbase(ap), sz)
            val tmp = File(f.path + ".part")
            try {
                base.open(ap).use { s -> tmp.outputStream().use { o -> pump(s, o, job) } }
                if (!tmp.renameTo(f)) throw IOException("could not store the archive")
            } catch (c: Cancelled) {
                if (jobIn == null) job.state = "cancel"
                throw c
            } finally {
                if (tmp.exists()) tmp.delete()
                if (jobIn == null) ArcProg.finish(job)
            }
        }
        return f
    }

    /** Fetches the whole archive into the cache up front (a copy of many files out of it is far faster from one local file than one request per file). */
    fun pin(base: Endpoint, ap: String, job: Job) {
        if (base is LocalFs) return
        materialize(base, ap, job)
    }

    /** Size of the archive file on the other device (for the progress bar of [pin]). */
    fun sizeOf(base: Endpoint, ap: String): Long = try { statOf(base, ap).optLong("size") } catch (_: Exception) { 0L }

    fun index(base: Endpoint, ap: String): ArcIndex {
        val rar = ap.substringAfterLast('.', "").lowercase().let { it == "rar" || it == "cbr" }
        if (base !is LocalFs && !rar) {
            // a ZIP on another device / SMB share: read just its file list. Only when that is impossible (odd ZIP layouts) is it downloaded.
            val st = statOf(base, ap)
            val sz = st.optLong("size")
            val mt = st.optLong("mtime")
            val have = File(Core.cacheDir, srcName(base, ap, sz, mt))
            if (!(have.isFile && have.length() == sz)) {
                val tag = "r|" + base.id + "|" + ap + "|" + sz + "|" + mt
                synchronized(lock) { idx[tag]?.let { return it } }
                try {
                    val ix = base.open(ap).use { s -> RangeZip.index(s, tag, RemoteZip(base, ap, mt)) }
                    synchronized(lock) { idx[tag] = ix }
                    return ix
                } catch (_: ZipFormat) { /* fall through to the download path */ }
            }
        }
        val f = materialize(base, ap)
        val key = f.path + "|" + f.length() + "|" + f.lastModified()
        synchronized(lock) { idx[key]?.let { return it } }
        val ix = ArcIndex.read(f, rar)
        synchronized(lock) { idx[key] = ix }
        return ix
    }

    /** Extracts one entry into the cache (once) and returns a seekable stream, so video / pdf viewers can scrub inside archives. */
    fun extract(ix: ArcIndex, e: AEntry): Source {
        val rz = ix.rz
        if (rz != null) return extractRemote(ix, rz, e)
        val f = ix.file ?: throw IOException("the archive file is missing")
        val out = File(Core.cacheDir, "e_" + sha1(ix.tag + "|" + e.path))
        synchronized(xlock) {
            if (!out.isFile) {
                trim(e.size)
                val tmp = File(out.path + ".part")
                try {
                    tmp.outputStream().buffered(1 shl 16).use { o -> if (ix.rar) rarExtract(f, e.raw, o) else zipExtract(f, ix.charset, e.raw, o) }
                    if (!tmp.renameTo(out)) throw IOException("could not store the extracted file")
                } finally { if (tmp.exists()) tmp.delete() }
            }
            out.setLastModified(System.currentTimeMillis())
            return LocalSource(RandomAccessFile(out, "r"), out.length())
        }
    }

    /** An entry of a ZIP that stays on the other device. Stored entries are read in place; compressed ones are unpacked into the cache. */
    private fun extractRemote(ix: ArcIndex, rz: RemoteZip, e: AEntry): Source {
        if (e.enc) throw IOException("this file is password-protected")
        val stored = e.method == 0 || (e.size == 0L && e.csize == 0L)
        if (!stored && e.method != 8) throw IOException("unsupported ZIP compression method (${e.method})")
        val out = File(Core.cacheDir, "e_" + sha1(ix.tag + "|" + e.path))
        if (stored && !out.isFile) {   // nothing is copied: starts at once and seeking (video scrubbing) works
            val s = rz.base.open(rz.path)
            try {
                return WindowSource(s, if (e.size == 0L) 0L else RangeZip.dataStart(s, e), e.size)
            } catch (x: Throwable) {
                try { s.close() } catch (_: Exception) {}
                throw x
            }
        }
        synchronized(xlock) {
            if (!out.isFile) {
                trim(e.size)
                val job = ArcProg.begin("Opening " + e.name, e.csize)
                val tmp = File(out.path + ".part")
                val inf = Inflater(true)
                try {
                    rz.base.open(rz.path).use { s ->
                        s.seek(RangeZip.dataStart(s, e))
                        val ins = InflaterInputStream(JobIn(LimitedIn(s, e.csize), job), inf, 1 shl 16)
                        tmp.outputStream().buffered(1 shl 16).use { o -> ins.copyTo(o, 1 shl 16) }
                    }
                    if (!tmp.renameTo(out)) throw IOException("could not store the extracted file")
                } catch (c: Cancelled) {
                    job.state = "cancel"
                    throw c
                } finally {
                    inf.end()
                    if (tmp.exists()) tmp.delete()
                    ArcProg.finish(job)
                }
            }
            out.setLastModified(System.currentTimeMillis())
            return LocalSource(RandomAccessFile(out, "r"), out.length())
        }
    }

    private fun zipExtract(file: File, charset: Charset, raw: String, o: OutputStream) {
        try {
            ZipFile(file, charset).use { z ->
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
 * Works on any device: for another LANShare phone or an SMB share the archive is fetched once into the cache (so it also works
 * when the other phone still runs an older build that knows nothing about archives).
 */
class ArcEp(val base: Endpoint) : Endpoint by base {
    private fun ar(v: String): Pair<String, String>? = arcSplit(v)
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
                .put("mtime", e?.mtime ?: (ix.rz?.mtime ?: ((ix.file?.lastModified() ?: 0L) / 1000)))
                .put("files", files).put("folders", folders).put("total", total).put("partial", false)
        } else {
            o.put("name", e.name).put("dir", false).put("size", e.size).put("mtime", e.mtime)
        }
        return o
    }

    override fun space(v: String): Pair<Long, Long>? = base.space(ar(v)?.first ?: v)

    /** For a copy of many files out of an archive that lives on another device: fetches the archive once (shown on [job]'s bar) so the files then come from a local copy. */
    fun pin(archive: String, job: Job) {
        if (base is LocalFs) return
        ArcStore.pin(base, archive, job)
    }

    fun archiveSize(archive: String): Long = if (base is LocalFs) 0L else ArcStore.sizeOf(base, archive)

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) { if (ar(v) != null) ro(); base.write(v, input, size, cb) }
    override fun mkdir(v: String) { if (ar(v) != null) ro(); base.mkdir(v) }
    override fun remove(v: String, progress: ((String) -> Unit)?) { if (ar(v) != null) ro(); base.remove(v, progress) }
    override fun rename(v: String, newName: String) { if (ar(v) != null) ro(); base.rename(v, newName) }
    override fun move(v: String, toV: String) { if (ar(v) != null || ar(toV) != null) ro(); base.move(v, toV) }
}
