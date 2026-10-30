package com.lanshare.app.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.GregorianCalendar

/** What the central directory of a ZIP says about it (no file data is read). */
class ZipSummary {
    var files = 0L
    var folders = 0L
    var unpacked = 0L
    var packed = 0L
    var largestName = ""
    var largestSize = -1L
    var oldest = 0L          // epoch seconds, 0 = unknown
    var newest = 0L
    var encFiles = 0L
    val encKinds = LinkedHashSet<String>()
    val methods = LinkedHashMap<String, Long>()
    var zip64 = false
    var comment = ""
    var prefix = 0L          // bytes in front of the ZIP (self-extracting stub)
    var multi = false        // one part of a split ZIP
    var checked = 0          // entries whose local header was looked at by the quick check
    val problems = ArrayList<String>()   // what the quick check found wrong (empty = looks fine)
}

/**
 * Pure JVM (no Android classes): reads only the end record and the central directory through [readAt]
 * (position, length) -> bytes, so it works on local files and on ranged reads of other devices / SMB shares.
 */
object ZipInfo {
    private const val CHUNK = 1L shl 20
    private val cp1254: Charset? = try { Charset.forName("windows-1254") } catch (_: Exception) { null }

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)

    private fun dosTime(date: Int, time: Int): Long {
        if (date == 0) return 0L
        return try {
            val mo = ((date shr 5) and 15).coerceIn(1, 12)
            val d = (date and 31).coerceAtLeast(1)
            GregorianCalendar(1980 + (date shr 9), mo - 1, d, (time shr 11).coerceAtMost(23),
                ((time shr 5) and 63).coerceAtMost(59), ((time and 31) * 2).coerceAtMost(59)).timeInMillis / 1000
        } catch (_: Exception) { 0L }
    }

    private fun decode(b: ByteArray, utf8: Boolean): String {
        if (utf8) return String(b, Charsets.UTF_8)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) { String(b, cp1254 ?: Charsets.ISO_8859_1) }
    }

    private fun methodName(m: Int): String = when (m) {
        0 -> "Stored"; 8 -> "Deflate"; 9 -> "Deflate64"; 12 -> "BZip2"; 14 -> "LZMA"; 93 -> "Zstandard"; 95 -> "XZ"; 98 -> "PPMd"
        else -> "Method $m"
    }

    /** Sliding window over the central directory so a huge one is never held in memory at once. */
    private class Win(val readAt: (Long, Int) -> ByteArray, val end: Long) {
        var start = 0L
        var buf = ByteArray(0)

        /** Makes [pos, pos + len) available in [buf] and returns the offset of [pos] in it. */
        fun ensure(pos: Long, len: Int): Int {
            if (pos < start || pos + len > start + buf.size) {
                val n = minOf(maxOf(len.toLong(), CHUNK), end - pos)
                if (n < len) throw IOException("the ZIP central directory is cut off")
                buf = readAt(pos, n.toInt())
                start = pos
            }
            return (pos - start).toInt()
        }
    }

    fun parse(size: Long, readAt: (Long, Int) -> ByteArray): ZipSummary {
        if (size < 22) throw IOException("not a valid ZIP file")
        val tailLen = minOf(size, 65557L).toInt()   // 22-byte end record + up to 64 KB comment
        val tailPos = size - tailLen
        val tail = readAt(tailPos, tailLen)
        var e = -1
        for (i in tailLen - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte() &&
                i + 22 + u16(tail, i + 20) <= tailLen) { e = i; break }
        }
        if (e < 0) throw IOException("not a valid ZIP file (end record not found)")
        val s = ZipSummary()
        val clen = u16(tail, e + 20)
        if (clen > 0) s.comment = decode(tail.copyOfRange(e + 22, e + 22 + clen), false).take(2000).trim()
        s.multi = u16(tail, e + 4) != 0 || u16(tail, e + 6) != 0
        var entries = u16(tail, e + 10).toLong()
        var cdSize = u32(tail, e + 12)
        var cdOff = u32(tail, e + 16)
        var cdEnd = tailPos + e   // where the central directory must end: the end record (or the ZIP64 record before it)
        if (entries == 0xffffL || cdSize == 0xffffffffL || cdOff == 0xffffffffL) {
            if (e >= 20 && u32(tail, e - 20) == 0x07064b50L) {
                val z64pos = u64(tail, e - 12)
                if (u32(tail, e - 20 + 16) > 1L) s.multi = true
                val z = readAt(z64pos, 56)
                if (u32(z, 0) != 0x06064b50L) throw IOException("damaged ZIP64 record")
                entries = u64(z, 32); cdSize = u64(z, 40); cdOff = u64(z, 48)
                cdEnd = z64pos
                s.zip64 = true
            }
        }
        if (cdSize < 0 || cdOff < 0 || cdSize > cdEnd) throw IOException("damaged ZIP central directory")
        // the stored offset is wrong when something sits in front of the ZIP (self-extracting stub): the directory then ends right before the end record
        var cdStart = cdOff
        var shift = 0L
        val sigOk = cdOff + 4 <= size && u32(readAt(cdOff, 4), 0) == 0x02014b50L
        if (!sigOk) {
            cdStart = cdEnd - cdSize
            shift = cdStart - cdOff
            if (cdStart < 0 || cdStart + 4 > size || u32(readAt(cdStart, 4), 0) != 0x02014b50L) {
                if (entries == 0L && cdSize == 0L) return s   // an empty ZIP
                throw IOException("damaged ZIP central directory")
            }
        }
        val w = Win(readAt, cdStart + cdSize)
        val dirs = HashSet<String>()
        fun addDir(p: String) {
            var d = p.trimEnd('/')
            while (d.isNotEmpty() && dirs.add(d)) {
                val i = d.lastIndexOf('/')
                if (i < 0) break
                d = d.substring(0, i)
            }
        }
        var minOff = Long.MAX_VALUE
        // quick check: remember (offset, packed size, name length of the central entry) of every entry; a few are verified after the loop
        val sampOff = ArrayList<Long>(); val sampCs = ArrayList<Long>(); val sampName = ArrayList<String>()
        var p = cdStart
        val stop = cdStart + cdSize
        var n = 0L
        while (p + 46 <= stop) {
            var o = w.ensure(p, 46)
            if (u32(w.buf, o) != 0x02014b50L) break
            val flags = u16(w.buf, o + 8)
            var method = u16(w.buf, o + 10)
            val mtime = dosTime(u16(w.buf, o + 14), u16(w.buf, o + 12))
            var csize = u32(w.buf, o + 20)
            var usize = u32(w.buf, o + 24)
            val nlen = u16(w.buf, o + 28)
            val xlen = u16(w.buf, o + 30)
            val cmlen = u16(w.buf, o + 32)
            var off = u32(w.buf, o + 42)
            val total = 46 + nlen + xlen + cmlen
            if (p + total > stop) break
            o = w.ensure(p, total)
            val b = w.buf
            val xs = o + 46 + nlen
            val xe = xs + xlen
            var aes = 0
            var x = xs
            while (x + 4 <= xe) {
                val id = u16(b, x)
                val len = u16(b, x + 2)
                if (id == 1) {   // ZIP64: only the fields that are maxed out in the header are present, in this order
                    var q = x + 4
                    val qe = minOf(x + 4 + len, xe)
                    if (usize == 0xffffffffL && q + 8 <= qe) { usize = u64(b, q); q += 8 }
                    if (csize == 0xffffffffL && q + 8 <= qe) { csize = u64(b, q); q += 8 }
                    if (off == 0xffffffffL && q + 8 <= qe) { off = u64(b, q) }
                    s.zip64 = true
                } else if (id == 0x9901 && len >= 7 && x + 4 + 7 <= xe) {   // WinZip AES: strength + the real compression method
                    aes = b[x + 8].toInt() and 0xff
                    method = u16(b, x + 9)
                }
                x += 4 + len
            }
            val name = decode(b.copyOfRange(o + 46, o + 46 + nlen), (flags and 0x800) != 0).replace('\\', '/')
            if (!name.endsWith("/") && sampOff.size < 200000) { sampOff.add(off); sampCs.add(csize); sampName.add(name) }
            if (name.endsWith("/")) {
                addDir(name)
            } else {
                s.files++
                s.unpacked += usize
                s.packed += csize
                if (usize > s.largestSize) { s.largestSize = usize; s.largestName = name.substringAfterLast('/') }
                if (mtime > 0) {
                    if (s.oldest == 0L || mtime < s.oldest) s.oldest = mtime
                    if (mtime > s.newest) s.newest = mtime
                }
                val slash = name.lastIndexOf('/')
                if (slash > 0) addDir(name.substring(0, slash))
                val mn = methodName(method)
                s.methods[mn] = (s.methods[mn] ?: 0L) + 1L
                if ((flags and 1) != 0) {
                    s.encFiles++
                    s.encKinds.add(when {
                        aes != 0 -> "AES-" + (when (aes) { 1 -> 128; 2 -> 192; else -> 256 })
                        (flags and 0x40) != 0 -> "PKWARE strong"
                        else -> "ZipCrypto"
                    })
                }
                if (off >= 0L && off < minOff) minOff = off
            }
            p += total
            n++
        }
        if (n == 0L && entries > 0) throw IOException("damaged ZIP central directory")
        if (!s.multi) quickCheck(s, readAt, size, n, entries, cdStart, shift, sampOff, sampCs, sampName)
        s.folders = dirs.size.toLong()
        // a ZIP normally starts with a local header; anything else in front of it is a stub
        val head = readAt(0, 4)
        val h = u32(head, 0)
        if (h != 0x04034b50L && h != 0x06054b50L && h != 0x08074b50L && h != 0x30304b50L) {
            s.prefix = if (shift > 0) shift else if (minOff != Long.MAX_VALUE && minOff > 0) minOff else 0L
        }
        return s
    }

    /**
     * Quick corruption check (no file data is inflated): the entry count must match the end record, and the local header of up to
     * ~24 entries (first, last and evenly spread) must exist where the central directory says, carry the right signature, and
     * its data must end before the central directory starts. A cut-off or overwritten ZIP fails at least one of these.
     */
    private fun quickCheck(s: ZipSummary, readAt: (Long, Int) -> ByteArray, size: Long, n: Long, entries: Long,
                           cdStart: Long, shift: Long, off: List<Long>, cs: List<Long>, name: List<String>) {
        fun bad(m: String) { if (s.problems.size < 5) s.problems.add(m) }
        if (entries != 0xffffL && n < entries) bad("the file list is incomplete (" + n + " of " + entries + " entries could be read)")
        val cnt = off.size
        if (cnt == 0) return
        val picks = LinkedHashSet<Int>()
        for (i in 0 until minOf(cnt, 4)) picks.add(i)
        for (i in maxOf(0, cnt - 4) until cnt) picks.add(i)
        val spread = minOf(cnt, 16)
        for (k in 0 until spread) picks.add((k.toLong() * (cnt - 1) / maxOf(1, spread - 1)).toInt())
        var failed = 0
        for (i in picks) {
            val pos = off[i] + shift
            val label = name[i].substringAfterLast('/')
            try {
                if (pos < 0 || pos + 30 > cdStart) { failed++; if (failed <= 3) bad("\"" + label + "\" points outside the file"); continue }
                val h = readAt(pos, 30)
                if (u32(h, 0) != 0x04034b50L) { failed++; if (failed <= 3) bad("\"" + label + "\" has a damaged header"); continue }
                val end = pos + 30 + u16(h, 26) + u16(h, 28) + cs[i]
                if (end > cdStart) { failed++; if (failed <= 3) bad("\"" + label + "\" is cut off"); continue }
                s.checked++
            } catch (_: IOException) { failed++; if (failed <= 3) bad("\"" + label + "\" could not be read") }
        }
        if (failed > 3) bad((failed - 3).toString() + " more entries look damaged")
        if (size < cdStart) bad("the file is shorter than its directory says")
    }
}
