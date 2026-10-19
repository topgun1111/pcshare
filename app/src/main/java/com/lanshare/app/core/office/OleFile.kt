package com.lanshare.app.core.office

import java.io.File

/**
 * Minimal OLE2 / Compound File Binary reader (read-only, whole file in memory, 64 MB cap).
 * Used by .xls (Workbook stream) and later .doc (WordDocument / 0Table / 1Table). Also detects encrypted OOXML (EncryptedPackage).
 * No android.* imports.
 */
class OleFile(file: File) {
    private val b: ByteArray
    private val shift: Int
    private val miniShift: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val dir = ArrayList<Entry>()
    private var miniStream: ByteArray = ByteArray(0)

    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        if (file.length() > MAX) throw IllegalArgumentException("File too large")
        b = file.readBytes()
        if (!isOle(b)) throw IllegalArgumentException("Not an OLE2 file")
        shift = u16(0x1E); miniShift = u16(0x20); miniCutoff = i32(0x38)
        if (shift !in 7..16 || miniShift !in 5..12) throw IllegalArgumentException("Bad OLE header")
        // FAT sector list: 109 in header + DIFAT chain
        val fatSecs = ArrayList<Int>()
        for (k in 0 until minOf(109, i32(0x2C))) fatSecs.add(i32(0x4C + k * 4))
        var dif = i32(0x44); var left = i32(0x48); val per = (1 shl shift) / 4 - 1
        while (left-- > 0 && dif >= 0 && fatSecs.size < 200_000) {
            val off = secOff(dif); if (off + (1 shl shift) > b.size) break
            for (k in 0 until per) fatSecs.add(i32(off + k * 4))
            dif = i32(off + per * 4)
        }
        val ids = ArrayList<Int>()
        for (s in fatSecs) { val off = secOff(s); if (s < 0 || off + (1 shl shift) > b.size) continue; for (k in 0 until (1 shl shift) / 4) ids.add(i32(off + k * 4)) }
        fat = ids.toIntArray()
        // directory
        val d = chain(i32(0x30))
        var p = 0
        while (p + 128 <= d.size) {
            val nl = ((d[p + 64].toInt() and 0xFF) or ((d[p + 65].toInt() and 0xFF) shl 8)).coerceIn(0, 64)
            val name = if (nl >= 2) String(d, p, nl - 2, Charsets.UTF_16LE) else ""
            val type = d[p + 66].toInt()
            val st = le32(d, p + 116); val sz = le32(d, p + 120).toLong() and 0xFFFFFFFFL
            dir.add(Entry(name, type, st, sz)); p += 128
        }
        miniFat = intsOf(chain(i32(0x3C)))
        dir.firstOrNull { it.type == 5 }?.let { miniStream = chain(it.start, it.size) }
    }

    val entries: List<Entry> get() = dir
    fun has(name: String) = find(name) != null
    private fun find(name: String) = dir.firstOrNull { it.type == 2 && it.name.equals(name, true) }

    /** Whole stream or null when absent. */
    fun open(name: String): ByteArray? {
        val e = find(name) ?: return null
        if (e.size > MAX) throw IllegalArgumentException("Stream too large")
        return if (e.size < miniCutoff) miniRead(e.start, e.size) else chain(e.start, e.size)
    }

    private fun secOff(s: Int): Int = ((s.toLong() + 1) shl shift).let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }

    private fun chain(start: Int, size: Long = -1): ByteArray {
        val ss = 1 shl shift
        val out = java.io.ByteArrayOutputStream()
        var s = start; var n = 0
        while (s >= 0 && s < fat.size + 1 && n++ < fat.size + 1) {
            val off = secOff(s); if (off + ss > b.size) { if (off < b.size) out.write(b, off, b.size - off); break }
            out.write(b, off, ss)
            s = if (s < fat.size) fat[s] else -2
            if (size >= 0 && out.size() >= size) break
        }
        val r = out.toByteArray()
        return if (size in 0 until r.size.toLong()) r.copyOf(size.toInt()) else r
    }

    private fun miniRead(start: Int, size: Long): ByteArray {
        val ms = 1 shl miniShift
        val out = java.io.ByteArrayOutputStream()
        var s = start; var n = 0
        while (s >= 0 && s < miniFat.size && n++ <= miniFat.size && out.size() < size) {
            val off = s * ms; if (off + ms > miniStream.size) break
            out.write(miniStream, off, ms); s = miniFat[s]
        }
        val r = out.toByteArray()
        return if (r.size > size) r.copyOf(size.toInt()) else r
    }

    private fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun i32(o: Int) = le32(b, o)
    private fun intsOf(a: ByteArray) = IntArray(a.size / 4) { le32(a, it * 4) }

    companion object {
        const val MAX = 64 * 1024 * 1024
        private val SIG = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        fun isOle(h: ByteArray) = h.size >= 512 && (0 until 8).all { h[it] == SIG[it] }
        fun isOle(f: File): Boolean = try { f.inputStream().use { s -> val h = ByteArray(8); s.read(h) == 8 && (0 until 8).all { h[it] == SIG[it] } } } catch (_: Throwable) { false }
        fun le32(a: ByteArray, o: Int): Int =
            if (o < 0 || o + 4 > a.size) -1 else (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8) or ((a[o + 2].toInt() and 0xFF) shl 16) or ((a[o + 3].toInt() and 0xFF) shl 24)
    }
}
