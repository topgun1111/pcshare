package com.lanshare.app.core

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streaming ZIP writer without the classic format's limits (android.util / java.util.zip.ZipOutputStream on Android stops at
 * 65,535 entries and 4 GB). Entries are written with a data descriptor, so nothing has to be known up front; ZIP64 structures
 * are added only where a value no longer fits (per-entry ZIP64 headers for files of ~4 GB or more, ZIP64 end-of-directory
 * records when the archive has 65,535+ entries or its directory sits beyond 4 GB). Small archives stay plain, classic ZIPs.
 */
class Zip64Writer(stream: OutputStream) : Closeable {
    private class Out(private val o: OutputStream) : OutputStream() {
        var pos = 0L
        override fun write(b: Int) { o.write(b); pos++ }
        override fun write(b: ByteArray, off: Int, len: Int) { o.write(b, off, len); pos += len }
        override fun flush() = o.flush()
        override fun close() = o.close()
    }

    private class Rec(val name: ByteArray, val dir: Boolean, val local64: Boolean, val offset: Long) {
        var crc = 0L
        var csize = 0L
        var usize = 0L
    }

    private val out = Out(stream)
    private val recs = ArrayList<Rec>()
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    private val crc = CRC32()
    private val obuf = ByteArray(1 shl 16)
    private var cur: Rec? = null
    private var finished = false
    private val dosTime: Int
    private val dosDate: Int

    init {
        val c = Calendar.getInstance()
        val y = (c.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
        dosDate = (y shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        dosTime = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
    }

    private fun u16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
    private fun u32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xff).toInt()) }
    private fun u64(v: Long) { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xff).toInt()) }

    /** A folder entry ([name] without the trailing slash). */
    fun putDir(name: String) {
        check(cur == null && !finished)
        val nb = (name.trimEnd('/') + "/").toByteArray(Charsets.UTF_8)
        val r = Rec(nb, true, false, out.pos)
        u32(0x04034b50L); u16(20); u16(0x0800); u16(0); u16(dosTime); u16(dosDate)
        u32(0); u32(0); u32(0); u16(nb.size); u16(0)
        out.write(nb)
        recs.add(r)
    }

    /**
     * Starts a file entry. [expectedSize] (the size seen when the folder was walked) decides whether the local header gets
     * ZIP64 fields; [store] writes level-0 deflate blocks (already-compressed content), which is much faster.
     */
    fun beginFile(name: String, expectedSize: Long, store: Boolean) {
        check(cur == null && !finished)
        val nb = name.toByteArray(Charsets.UTF_8)
        val z64 = expectedSize >= BIG
        val r = Rec(nb, false, z64, out.pos)
        u32(0x04034b50L); u16(if (z64) 45 else 20); u16(0x0808); u16(8); u16(dosTime); u16(dosDate)
        u32(0); u32(if (z64) MAX32 else 0); u32(if (z64) MAX32 else 0)   // CRC and sizes follow in the data descriptor
        u16(nb.size); u16(if (z64) 20 else 0)
        out.write(nb)
        if (z64) { u16(1); u16(16); u64(0); u64(0) }
        cur = r
        crc.reset()
        deflater.reset()
        deflater.setLevel(if (store) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
    }

    fun write(b: ByteArray, off: Int, len: Int) {
        val r = cur ?: throw IllegalStateException("no open entry")
        if (len <= 0) return
        crc.update(b, off, len)
        r.usize += len
        deflater.setInput(b, off, len)
        while (!deflater.needsInput()) drain(r)
    }

    private fun drain(r: Rec) {
        val n = deflater.deflate(obuf, 0, obuf.size)
        if (n > 0) { out.write(obuf, 0, n); r.csize += n }
    }

    fun endFile() {
        val r = cur ?: throw IllegalStateException("no open entry")
        deflater.finish()
        while (!deflater.finished()) drain(r)
        r.crc = crc.value
        if (!r.local64 && (r.usize >= MAX32 || r.csize >= MAX32))
            throw IOException("${String(r.name, Charsets.UTF_8)} grew beyond 4 GB while it was being zipped")
        u32(0x08074b50L); u32(r.crc)
        if (r.local64) { u64(r.csize); u64(r.usize) } else { u32(r.csize); u32(r.usize) }
        recs.add(r)
        cur = null
    }

    /** Writes the central directory and the end records. The writer must be [close]d afterwards. */
    fun finish() {
        check(cur == null)
        if (finished) return
        finished = true
        val cdStart = out.pos
        for (r in recs) {
            val bigU = r.usize >= MAX32
            val bigC = r.csize >= MAX32
            val bigO = r.offset >= MAX32
            val extraLen = (if (bigU) 8 else 0) + (if (bigC) 8 else 0) + (if (bigO) 8 else 0)
            val z64 = r.local64 || extraLen > 0
            u32(0x02014b50L); u16(45); u16(if (z64) 45 else 20)
            u16(if (r.dir) 0x0800 else 0x0808); u16(if (r.dir) 0 else 8); u16(dosTime); u16(dosDate)
            u32(r.crc); u32(if (bigC) MAX32 else r.csize); u32(if (bigU) MAX32 else r.usize)
            u16(r.name.size); u16(if (extraLen > 0) extraLen + 4 else 0); u16(0); u16(0); u16(0)
            u32(if (r.dir) 0x10L else 0L); u32(if (bigO) MAX32 else r.offset)
            out.write(r.name)
            if (extraLen > 0) {
                u16(1); u16(extraLen)
                if (bigU) u64(r.usize)
                if (bigC) u64(r.csize)
                if (bigO) u64(r.offset)
            }
        }
        val cdEnd = out.pos
        val cdSize = cdEnd - cdStart
        val n = recs.size.toLong()
        val need64 = n >= 0xFFFFL || cdSize >= MAX32 || cdStart >= MAX32
        if (need64) {
            u32(0x06064b50L); u64(44); u16(45); u16(45); u32(0); u32(0); u64(n); u64(n); u64(cdSize); u64(cdStart)
            u32(0x07064b50L); u32(0); u64(cdEnd); u32(1)
        }
        u32(0x06054b50L); u16(0); u16(0)
        u16(if (need64) 0xFFFF else n.toInt()); u16(if (need64) 0xFFFF else n.toInt())
        u32(if (need64) MAX32 else cdSize); u32(if (need64) MAX32 else cdStart)
        u16(0)
        out.flush()
    }

    override fun close() {
        try { deflater.end() } finally { out.close() }
    }

    private companion object {
        const val MAX32 = 0xFFFFFFFFL
        const val BIG = 0xFC000000L   // from here on a file gets ZIP64 headers up front (leaves room for deflate's worst-case growth)
    }
}
