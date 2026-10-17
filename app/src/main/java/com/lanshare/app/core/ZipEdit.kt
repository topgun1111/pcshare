package com.lanshare.app.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.GregorianCalendar
import java.util.zip.CRC32
import java.util.zip.Deflater

/*
 * Editing a ZIP in place (this phone's own ZIP files): add files / folders, delete entries.
 *
 *  - add:    new entries are written where the old central directory started, then the central directory + end record are
 *            written again. The data of the other entries is not touched or copied (fast, even for a 2 GB archive). If anything
 *            goes wrong (disk full, cancel) the old end of the file is put back, so the ZIP stays as it was.
 *  - delete: the kept entries are copied byte for byte (no unpacking / repacking) into "<name>.edit.part" next to the ZIP; the
 *            original is replaced only when the copy is complete (rename). Needs free space of about the ZIP's size.
 *  - replacing a file of the same name: the new entry is added first, the old one is dropped afterwards.
 *
 * Not supported (clear message): ZIP64 archives (> 4 GB or > 65534 entries), encrypted-archive repair, RAR.
 */
object ZipEdit {
    private val lock = Any()
    private const val SIG_CD = 0x02014b50
    private const val SIG_LOCAL = 0x04034b50
    private const val SIG_EOCD = 0x06054b50
    private const val SIG_DESC = 0x08074b50
    private const val U32 = 0xFFFFFFFFL

    private val NO_PACK = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4", "m4v", "mkv", "mov", "avi", "webm", "3gp",
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "zip", "rar", "7z", "gz", "bz2", "xz", "cbz", "cbr", "apk", "jar",
        "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "pdf")

    private class Rec(val key: String, val dir: Boolean, val cd: ByteArray, val off: Long)
    private class Dir(val recs: List<Rec>, val cdOff: Long, val cdSize: Long, val comment: ByteArray)

    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, p: Int) = (u16(b, p).toLong()) or (u16(b, p + 2).toLong() shl 16)

    private fun le(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun normKey(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw Denied("invalid name")
        return parts.joinToString("/")
    }

    private fun decodeName(b: ByteArray, utf8Flag: Boolean): String {
        if (utf8Flag) return String(b, Charsets.UTF_8)
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {}
        return String(b, Charset.forName("windows-1254"))   // old Windows tools wrote the Turkish code page (same rule as ArcIndex)
    }

    private fun readDir(raf: RandomAccessFile): Dir {
        val len = raf.length()
        if (len < 22) throw IOException("not a valid ZIP file")
        val tailLen = minOf(len, 22L + 65535L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        var p = tailLen - 22
        var found = -1
        while (p >= 0) {
            if (u32(tail, p) == (SIG_EOCD.toLong() and U32)) {
                val cl = u16(tail, p + 20)
                if (p + 22 + cl == tailLen) { found = p; break }
                if (found < 0) found = p
            }
            p--
        }
        if (found < 0) throw IOException("not a valid ZIP file (no end record)")
        p = found
        val total = u16(tail, p + 10)
        val cdSize = u32(tail, p + 12)
        val cdOff = u32(tail, p + 16)
        val cl = u16(tail, p + 20)
        if (total == 0xFFFF || cdSize == U32 || cdOff == U32) throw IOException("ZIP64 archives (over 4 GB) cannot be edited")
        if (cdOff + cdSize > len || cdSize > 256L shl 20) throw IOException("damaged ZIP file (bad directory)")
        val comment = ByteArray(minOf(cl, tailLen - p - 22))
        System.arraycopy(tail, p + 22, comment, 0, comment.size)
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOff)
        raf.readFully(cd)
        val recs = ArrayList<Rec>()
        var q = 0
        while (q + 46 <= cd.size) {
            if (u32(cd, q) != (SIG_CD.toLong() and U32)) throw IOException("damaged ZIP file (bad directory entry)")
            val flags = u16(cd, q + 8)
            val csz = u32(cd, q + 20)
            val usz = u32(cd, q + 24)
            val nl = u16(cd, q + 28)
            val el = u16(cd, q + 30)
            val cm = u16(cd, q + 32)
            val off = u32(cd, q + 42)
            if (csz == U32 || usz == U32 || off == U32) throw IOException("ZIP64 archives (over 4 GB) cannot be edited")
            val n = q + 46 + nl + el + cm
            if (n > cd.size) throw IOException("damaged ZIP file (bad directory entry)")
            val raw = cd.copyOfRange(q + 46, q + 46 + nl)
            val name = decodeName(raw, flags and 0x800 != 0)
            recs.add(Rec(normKey(name), name.endsWith("/"), cd.copyOfRange(q, n), off))
            q = n
        }
        return Dir(recs, cdOff, cdSize, comment)
    }

    private fun dos(): Pair<Int, Int> {
        val c = GregorianCalendar()
        val y = c.get(GregorianCalendar.YEAR)
        if (y < 1980) return 0 to 0x21
        val t = (c.get(GregorianCalendar.HOUR_OF_DAY) shl 11) or (c.get(GregorianCalendar.MINUTE) shl 5) or (c.get(GregorianCalendar.SECOND) / 2)
        val d = ((y - 1980) shl 9) or ((c.get(GregorianCalendar.MONTH) + 1) shl 5) or c.get(GregorianCalendar.DAY_OF_MONTH)
        return t to d
    }

    private fun eocd(entries: Int, cdSize: Long, cdOff: Long, comment: ByteArray): ByteArray {
        val b = le(22 + comment.size)
        b.putInt(SIG_EOCD).putShort(0).putShort(0).putShort(entries.toShort()).putShort(entries.toShort())
            .putInt(cdSize.toInt()).putInt(cdOff.toInt()).putShort(comment.size.toShort()).put(comment)
        return b.array()
    }

    /** All entry paths of the ZIP (files and folders, as the file list shows them). */
    fun keys(file: File): Set<String> = synchronized(lock) { RandomAccessFile(file, "r").use { r -> readDir(r).recs.map { it.key }.toSet() } }

    /**
     * Adds a file ([input] != null) or an empty folder to the ZIP. An entry with the same name is replaced (the old one only after the new one is stored).
     * [cb] gets the number of bytes read from [input] and may throw [Cancelled].
     */
    fun add(file: File, entryPath: String, input: InputStream?, cb: ((Int) -> Unit)? = null) {
        val key = normKey(entryPath)
        if (key.isEmpty()) throw BadReq("no name")
        synchronized(lock) {
            val isDir = input == null
            var oldOffs: Set<Long> = emptySet()
            RandomAccessFile(file, "rw").use { raf ->
                val dir = readDir(raf)
                val same = dir.recs.filter { it.key == key }
                if (isDir) {
                    if (same.any { !it.dir }) throw Denied("A file named \"${key.substringAfterLast('/')}\" already exists here")
                    if (same.isNotEmpty() || dir.recs.any { it.key.startsWith("$key/") }) return   // folder exists already
                } else {
                    if (same.any { it.dir } || dir.recs.any { it.key.startsWith("$key/") }) throw Denied("A folder named \"${key.substringAfterLast('/')}\" already exists here")
                    oldOffs = same.map { it.off }.toSet()
                }
                if (dir.recs.size + 1 > 65534) throw Denied("too many entries for this ZIP (65534 max)")
                val start = dir.cdOff
                val oldTail = ByteArray((raf.length() - start).toInt())   // old central directory + end record
                raf.seek(start)
                raf.readFully(oldTail)
                val ch = raf.channel
                try {
                    ch.position(start)
                    val out = Counting(BufferedOutputStream(Channels.newOutputStream(ch), 1 shl 16))
                    val nameB = (if (isDir) "$key/" else key).toByteArray(Charsets.UTF_8)
                    if (nameB.size > 65535) throw Denied("name too long")
                    val (tm, dt) = dos()
                    val flags = if (isDir) 0x0800 else 0x0808
                    val method = if (isDir) 0 else 8
                    out.write(le(30).putInt(SIG_LOCAL).putShort(20).putShort(flags.toShort()).putShort(method.toShort())
                        .putShort(tm.toShort()).putShort(dt.toShort()).putInt(0).putInt(0).putInt(0)
                        .putShort(nameB.size.toShort()).putShort(0).array())
                    out.write(nameB)
                    var crc = 0L
                    var csize = 0L
                    var usize = 0L
                    if (!isDir) {
                        val ext = key.substringAfterLast('.', "").lowercase()
                        val df = Deflater(if (ext in NO_PACK) Deflater.NO_COMPRESSION else 6, true)
                        val c = CRC32()
                        val buf = ByteArray(1 shl 16)
                        val obuf = ByteArray(1 shl 16)
                        val before = out.count
                        try {
                            while (true) {
                                val n = input!!.read(buf)
                                if (n < 0) break
                                if (n == 0) continue
                                c.update(buf, 0, n)
                                usize += n
                                df.setInput(buf, 0, n)
                                while (!df.needsInput()) { val k = df.deflate(obuf); if (k > 0) out.write(obuf, 0, k) }
                                cb?.invoke(n)
                            }
                            df.finish()
                            while (!df.finished()) { val k = df.deflate(obuf); if (k > 0) out.write(obuf, 0, k) }
                        } finally { df.end() }
                        crc = c.value
                        csize = out.count - before
                        if (usize >= U32 || csize >= U32) throw Denied("this file is too big for a ZIP (4 GB limit)")
                        out.write(le(16).putInt(SIG_DESC).putInt(crc.toInt()).putInt(csize.toInt()).putInt(usize.toInt()).array())
                    }
                    val newCd = le(46 + nameB.size).putInt(SIG_CD).putShort(20).putShort(20).putShort(flags.toShort()).putShort(method.toShort())
                        .putShort(tm.toShort()).putShort(dt.toShort()).putInt(crc.toInt()).putInt(csize.toInt()).putInt(usize.toInt())
                        .putShort(nameB.size.toShort()).putShort(0).putShort(0).putShort(0).putShort(0)
                        .putInt(if (isDir) 0x10 else 0).putInt(start.toInt()).put(nameB).array()
                    val newCdOff = start + out.count
                    if (newCdOff >= U32) throw Denied("this ZIP would be over 4 GB")
                    out.write(oldTail, 0, dir.cdSize.toInt())   // the old central directory, unchanged
                    out.write(newCd)
                    out.write(eocd(dir.recs.size + 1, dir.cdSize + newCd.size, newCdOff, dir.comment))
                    out.flush()
                    ch.truncate(start + out.count)
                } catch (t: Throwable) {
                    try { ch.truncate(start); ch.position(start); raf.seek(start); raf.write(oldTail) } catch (_: Exception) {}   // put the ZIP back as it was
                    throw t
                }
            }
            if (oldOffs.isNotEmpty()) dropRecs(file) { it.off in oldOffs }   // the replaced file is dropped only now
        }
    }

    /** Deletes entries (and everything below a folder entry) from the ZIP. Returns how many entries were removed. */
    fun remove(file: File, entryPaths: List<String>, progress: ((String) -> Unit)? = null): Int {
        val keys = entryPaths.map { normKey(it) }
        if (keys.any { it.isEmpty() }) throw Denied("to delete the whole ZIP, delete the file from its folder")
        var gone = 0
        synchronized(lock) {
            val missing = RandomAccessFile(file, "r").use { r ->
                val ks = readDir(r).recs.map { it.key }
                keys.filter { k -> ks.none { it == k || it.startsWith("$k/") } }
            }
            if (missing.size == keys.size) throw NotFound("No such file or folder in the archive")
            progress?.invoke(keys.first().substringAfterLast('/'))
            gone = dropRecs(file) { r -> keys.any { k -> r.key == k || r.key.startsWith("$k/") } }
        }
        return gone
    }

    /** Rewrites the ZIP without the records [drop] selects (raw copy of the others). */
    private fun dropRecs(file: File, drop: (Rec) -> Boolean): Int {
        val tmp = File(file.parentFile, file.name + ".edit.part")
        try {
            var removed = 0
            RandomAccessFile(file, "r").use { raf ->
                val dir = readDir(raf)
                val gone = dir.recs.filter(drop)
                removed = gone.size
                if (gone.isEmpty()) return 0
                val free = try { file.parentFile.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
                if (free < file.length() + (8L shl 20)) throw Full("Not enough free space to rewrite this ZIP (needs about ${file.length() / 1048576 + 8} MB)")
                val order = dir.recs.map { it.off }.distinct().sorted()
                fun endOf(off: Long): Long { val i = order.indexOf(off); return if (i + 1 < order.size) order[i + 1] else dir.cdOff }
                val goneSet = HashSet<Rec>(gone)
                val keep = dir.recs.filter { it !in goneSet }
                val newOff = HashMap<Long, Long>()
                val buf = ByteArray(1 shl 20)
                tmp.outputStream().buffered(1 shl 16).use { fo ->
                    val out = Counting(fo)
                    val first = order.firstOrNull() ?: dir.cdOff
                    if (first > 0) copyRange(raf, 0, first, out, buf)   // anything in front of the first entry (e.g. a stub) stays
                    for (r in keep.sortedBy { it.off }) {
                        if (newOff.containsKey(r.off)) continue
                        val end = endOf(r.off)
                        if (end <= r.off) throw IOException("damaged ZIP file (overlapping entries)")
                        newOff[r.off] = out.count
                        copyRange(raf, r.off, end, out, buf)
                    }
                    val cdOff = out.count
                    if (cdOff >= U32) throw Denied("this ZIP would be over 4 GB")
                    var cdSize = 0L
                    for (r in keep) {
                        val c = r.cd.copyOf()
                        val o = newOff[r.off] ?: throw IOException("damaged ZIP file")
                        le(4).putInt(o.toInt()).array().copyInto(c, 42)
                        out.write(c)
                        cdSize += c.size
                    }
                    out.write(eocd(keep.size, cdSize, cdOff, dir.comment))
                }
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace the ZIP with the edited copy")
            return removed
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun copyRange(raf: RandomAccessFile, from: Long, to: Long, out: OutputStream, buf: ByteArray) {
        raf.seek(from)
        var left = to - from
        while (left > 0) {
            val n = raf.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("damaged ZIP file (unexpected end)")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private class Counting(private val o: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { o.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); count += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }
}
