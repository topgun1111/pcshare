package com.lanshare.app.core.usearch

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

/**
 * names.bin persistence for [NameIndex] (decision D1). Pure JVM.
 *
 * File layout (all numbers big-endian, java.io.DataOutputStream):
 *   int MAGIC (0x55534E49, "USNI") | int VERSION | NameIndex.writeTo body | int END_MAGIC (0x55534E44, "USND") | int CRC32 of everything before END_MAGIC
 * The trailer is written last, after the body was flushed, so a torn write (no trailer, short file, bad CRC) is detected and load() returns null (caller rebuilds).
 * save() writes names.bin.tmp, syncs it, then renames it over names.bin (manifest-last idea of ImgSearch, here a trailer).
 */
object IndexStore {
    const val VERSION = 1
    private const val MAGIC = 0x55534E49
    private const val END_MAGIC = 0x55534E44

    /** Saves the index. Returns the file size in bytes. Throws IOException when the file cannot be written. */
    fun save(idx: NameIndex, file: File): Long {
        val dir = file.absoluteFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create " + dir.path)
        val tmp = File(file.path + ".tmp")
        val fos = FileOutputStream(tmp)
        try {
            val bos = BufferedOutputStream(fos, 1 shl 16)
            val crc = CRC32()
            val dos = DataOutputStream(CheckedOutputStream(bos, crc))
            dos.writeInt(MAGIC)
            dos.writeInt(VERSION)
            idx.writeTo(dos)
            dos.flush()
            val sum = crc.value.toInt()
            val tail = DataOutputStream(bos)   // not checksummed
            tail.writeInt(END_MAGIC)
            tail.writeInt(sum)
            tail.flush()
            fos.fd.sync()
        } finally {
            fos.close()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot replace " + file.path)
        }
        return file.length()
    }

    /** Loads an index, or null when the file is missing, truncated, corrupt or written by another VERSION. Never throws. */
    fun load(file: File): NameIndex? {
        return try {
            if (!file.isFile || file.length() < 16) null
            else FileInputStream(file).use { fis -> readAll(fis) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAll(fis: FileInputStream): NameIndex? {
        val bis = BufferedInputStream(fis, 1 shl 16)
        val crc = CRC32()
        val dis = DataInputStream(CheckedInputStream(bis, crc))
        if (dis.readInt() != MAGIC) return null
        if (dis.readInt() != VERSION) return null
        val idx = NameIndex.readFrom(dis)
        val sum = crc.value.toInt()
        val tail = DataInputStream(bis)   // not checksummed
        if (tail.readInt() != END_MAGIC) return null
        if (tail.readInt() != sum) return null
        return idx
    }
}
