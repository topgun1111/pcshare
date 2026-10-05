package com.lanshare.app.core

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Thumbnails for files on OTHER devices / SMB shares (NativeList). Same JPEG (<= 400 px) as [Thumbs] / [VideoThumbs],
 * read through the endpoint's [Source]; disk cache keyed by dev + path + size + mtime so a folder opened twice costs nothing.
 * Pictures are streamed to a temp file (max [IMG_MAX] bytes), videos use ranged reads (seekable sources only).
 */
object RemoteThumbs {
    const val IMG_MAX = 30_000_000L
    private fun root() = File(Cfg.dir ?: File("/data/local/tmp"), "rthumbcache")
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** JPEG bytes + duration in ms (0 for pictures). */
    fun make(dev: String, path: String, size: Long, mtime: Long, video: Boolean): Pair<ByteArray, Long> {
        val key = sha1("$dev|$path|$size|$mtime")
        val dir = File(root(), key.substring(0, 2))
        val jf = File(dir, "$key.jpg"); val df = File(dir, "$key.d")
        try { if (jf.isFile) return jf.readBytes() to (try { df.readText().trim().toLong() } catch (_: Exception) { 0L }) } catch (_: IOException) {}
        val res: Pair<ByteArray, Long> = if (video) VideoThumbs.make(Jobs.ep(dev).open(path)) else image(dev, path)
        try {
            dir.mkdirs()
            val tmp = File(dir, "$key.t${Thread.currentThread().id}")
            tmp.writeBytes(res.first)
            if (!tmp.renameTo(jf)) { jf.delete(); if (!tmp.renameTo(jf)) tmp.delete() }
            if (res.second > 0) df.writeText(res.second.toString())
        } catch (_: IOException) {}
        return res
    }

    private fun image(dev: String, path: String): Pair<ByteArray, Long> {
        val src = Jobs.ep(dev).open(path)
        val tmp = File(root(), "tmp").also { it.mkdirs() }.let { File(it, "i${Thread.currentThread().id}_${System.nanoTime()}") }
        try {
            src.use { ins ->
                if (ins.size > IMG_MAX) throw IOException("picture too large for a thumbnail")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(65536); var total = 0L
                    while (true) { val n = ins.read(buf); if (n <= 0) break; total += n; if (total > IMG_MAX) throw IOException("picture too large for a thumbnail"); out.write(buf, 0, n) }
                }
            }
            return Thumbs.makeUncached(tmp) to 0L
        } finally { tmp.delete() }
    }

    /** Cache over 20 000 files: drop the oldest 8 000 (called rarely, from the pool). */
    fun prune(limit: Int = 20000, drop: Int = 8000) {
        try { val f = root().walkTopDown().filter { it.isFile }.toList(); if (f.size > limit) f.sortedBy { it.lastModified() }.take(drop).forEach { it.delete() } } catch (_: Exception) {}
    }
}
