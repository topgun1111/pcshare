package com.lanshare.app.core

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date

/*
 * Editing every archive type that can be written on Android: ZIP (see [ZipEdit], in place), 7z, TAR, TAR.GZ / TGZ, TAR.BZ2, TAR.XZ.
 * 7z / TAR are rewritten: the kept entries are copied into "<name>.edit.part" next to the archive (new / replaced file added at the end),
 * and the original is replaced only when the copy is complete (rename). Needs free space of about the archive's size.
 * Not editable (clear message): RAR (no writer exists - format is proprietary), single .gz / .bz2 / .xz (one file, no entries),
 * encrypted 7z, ZIP64.
 */
object ArcEdit {
    private val lock = Any()
    private val EDITABLE = setOf("zip", "7z", "tar", "tgz", "tbz", "txz")

    fun canEdit(fmt: String) = fmt in EDITABLE

    /** Why [fmt] can't be edited (for the error message). */
    fun whyNot(fmt: String): String = when (fmt) {
        "rar" -> "RAR archives cannot be edited (RAR cannot be written on Android) - extract them, or copy the files into a ZIP / 7z"
        "gz", "bz2", "xz" -> "A single compressed file has no entries to edit - extract it first"
        else -> "This archive type cannot be edited"
    }

    private fun key(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return parts.joinToString("/")
    }

    fun add(file: File, fmt: String, entryPath: String, input: InputStream?, cb: ((Int) -> Unit)? = null) {
        if (fmt == "zip") { ZipEdit.add(file, entryPath, input, cb); return }
        if (!canEdit(fmt)) throw Denied(whyNot(fmt))
        val k = ZipEdit.normKey(entryPath)
        if (k.isEmpty()) throw BadReq("no name")
        synchronized(lock) {
            val all = ArcIndex.read(file, fmt).all
            // a parent that is a file blocks the new entry
            var acc = ""
            for (p in k.split('/').dropLast(1)) { acc = if (acc.isEmpty()) p else "$acc/$p"; if (all[acc]?.dir == false) throw Denied("\"$p\" is a file, not a folder") }
            val old = all[k]
            val name = k.substringAfterLast('/')
            if (input == null) {   // new folder
                if (old != null) { if (old.dir) return; throw Denied("A file named \"$name\" already exists here") }
                rewrite(file, fmt, { false }, k, true, null)
                return
            }
            if (old?.dir == true) throw Denied("A folder named \"$name\" already exists here")
            val spool = File.createTempFile("arcadd", ".tmp", Core.cacheDir.also { it.mkdirs() })
            try {
                spool.outputStream().buffered(1 shl 16).use { o ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n < 0) break; if (n == 0) continue; o.write(buf, 0, n); cb?.invoke(n) }
                }
                rewrite(file, fmt, { it == k }, k, false, spool)   // an entry of the same name is replaced
            } finally { spool.delete() }
        }
    }

    /** Deletes entries (and everything below a folder entry). Returns how many entries were removed. */
    fun remove(file: File, fmt: String, entryPaths: List<String>, progress: ((String) -> Unit)? = null): Int {
        if (fmt == "zip") return ZipEdit.remove(file, entryPaths, progress)
        if (!canEdit(fmt)) throw Denied(whyNot(fmt))
        val keys = entryPaths.map { ZipEdit.normKey(it) }
        if (keys.any { it.isEmpty() }) throw Denied("to delete the whole archive, delete the file from its folder")
        synchronized(lock) {
            progress?.invoke(keys.first().substringAfterLast('/'))
            return rewrite(file, fmt, { k -> keys.any { k == it || k.startsWith("$it/") } }, null, false, null, mustRemove = true)
        }
    }

    private fun wrap(fmt: String, o: OutputStream): OutputStream = when (fmt) {
        "tgz" -> GzipCompressorOutputStream(o)
        "tbz" -> BZip2CompressorOutputStream(o)
        "txz" -> XZCompressorOutputStream(o, 4)   // preset 4: small memory use on a phone
        else -> o
    }

    private fun rewrite(file: File, fmt: String, drop: (String) -> Boolean, addKey: String?, addDir: Boolean, addSrc: File?, mustRemove: Boolean = false): Int {
        val free = try { file.parentFile.usableSpace } catch (_: Exception) { Long.MAX_VALUE }
        val need = file.length() + (addSrc?.length() ?: 0L) + (8L shl 20)
        if (free < need) throw Full("Not enough free space to rewrite this archive (needs about ${need / 1048576} MB)")
        val tmp = File(file.parentFile, file.name + ".edit.part")
        try {
            var removed = 0
            if (fmt == "7z") removed = rewrite7z(file, tmp, drop, addKey, addDir, addSrc)
            else removed = rewriteTar(file, tmp, fmt, drop, addKey, addDir, addSrc)
            if (mustRemove && removed == 0) throw NotFound("No such file or folder in the archive")
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("could not replace the archive with the edited copy") }
            return removed
        } catch (e: NotFound) { throw e
        } catch (e: Denied) { throw e
        } catch (e: Full) { throw e
        } catch (e: IOException) {
            throw if (e.message?.contains("password", true) == true || e.message?.contains("encrypt", true) == true) IOException("this archive is password-protected") else e
        } catch (e: Cancelled) { throw e
        } catch (e: Exception) { throw IOException(ArcIndex.otherMsg(fmt, e))
        } finally { if (tmp.exists()) tmp.delete() }
    }

    private fun rewrite7z(file: File, tmp: File, drop: (String) -> Boolean, addKey: String?, addDir: Boolean, addSrc: File?): Int {
        var removed = 0
        val buf = ByteArray(1 shl 16)
        SevenZFile(file).use { z ->
            SevenZOutputFile(tmp).use { out ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val nm = key(e.name ?: continue)
                    if (nm.isEmpty()) continue
                    if (drop(nm)) { removed++; continue }
                    val ne = SevenZArchiveEntry()
                    ne.name = nm
                    ne.setDirectory(e.isDirectory)
                    ne.setHasStream(!e.isDirectory && e.hasStream())
                    if (e.hasLastModifiedDate) { ne.setHasLastModifiedDate(true); ne.lastModifiedDate = e.lastModifiedDate }
                    out.putArchiveEntry(ne)
                    if (ne.hasStream()) while (true) { val n = z.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.closeArchiveEntry()
                }
                if (addKey != null) {
                    val ne = SevenZArchiveEntry()
                    ne.name = addKey
                    ne.setDirectory(addDir)
                    ne.setHasStream(!addDir && (addSrc?.length() ?: 0L) > 0L)
                    ne.setHasLastModifiedDate(true); ne.lastModifiedDate = Date()
                    out.putArchiveEntry(ne)
                    if (addSrc != null && ne.hasStream()) addSrc.inputStream().buffered(1 shl 16).use { i -> while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
                    out.closeArchiveEntry()
                }
                out.finish()
            }
        }
        return removed
    }

    private fun rewriteTar(file: File, tmp: File, fmt: String, drop: (String) -> Boolean, addKey: String?, addDir: Boolean, addSrc: File?): Int {
        var removed = 0
        TarArchiveInputStream(ArcIndex.unpack(fmt, file.inputStream()), "UTF-8").use { t ->
            TarArchiveOutputStream(wrap(fmt, BufferedOutputStream(tmp.outputStream(), 1 shl 16)), "UTF-8").use { out ->
                out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                out.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                while (true) {
                    val e = t.nextTarEntry ?: break
                    val nm = key(e.name)
                    if (nm.isNotEmpty() && drop(nm)) { removed++; continue }
                    out.putArchiveEntry(e)
                    if (e.isFile) t.copyTo(out, 1 shl 16)
                    out.closeArchiveEntry()
                }
                if (addKey != null) {
                    val ne = TarArchiveEntry(if (addDir) "$addKey/" else addKey)
                    ne.modTime = Date()
                    if (addDir) ne.mode = 493 else { ne.mode = 420; ne.size = addSrc?.length() ?: 0L }
                    out.putArchiveEntry(ne)
                    if (!addDir && addSrc != null) addSrc.inputStream().buffered(1 shl 16).use { it.copyTo(out, 1 shl 16) }
                    out.closeArchiveEntry()
                }
                out.finish()
            }
        }
        return removed
    }
}
