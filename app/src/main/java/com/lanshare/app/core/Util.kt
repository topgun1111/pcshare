package com.lanshare.app.core

import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

// ---- error types. The HTTP layer maps them to the same status codes the Python server used.
class NotFound(msg: String) : IOException(msg)            // FileNotFoundError -> 404
class Denied(msg: String) : IOException(msg)              // PermissionError   -> 403
class Exists(msg: String) : IOException(msg)              // FileExistsError   -> 409
class BadReq(msg: String) : RuntimeException(msg)         // ValueError/KeyError -> 400
class Cancelled : RuntimeException("cancelled")           // job cancelled by the user

const val CHUNK = 1 shl 20
const val INBOX = "/"            // "Send to..." puts files in the target device's storage root
const val BEACON_PORT = 48555
const val BASE_PORT = 8765

const val STORAGE_MSG = "This phone hides files from LANShare: open Android Settings > Apps > LANShare > " +
    "Permissions > Files and media (or 'All files access') and allow management of all files, then restart the app"

fun errText(e: Throwable): String = (e.message ?: "").ifEmpty { e.javaClass.simpleName }

// ---- virtual paths (always '/'-separated, rooted at '/', never above the root)
fun vnorm(p: String): String {
    val parts = ArrayList<String>()
    for (s in p.replace('\\', '/').split('/')) {
        when (s) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(s)
        }
    }
    return "/" + parts.joinToString("/")
}

fun vjoin(a: String, b: String) = vnorm("$a/$b")
fun vdir(p: String): String = vnorm(p).substringBeforeLast('/').ifEmpty { "/" }
fun vbase(p: String): String = vnorm(p).substringAfterLast('/')

/** posixpath.splitext: ("a.tar", ".gz"); dot-files have no extension. */
fun splitExt(n: String): Pair<String, String> {
    val i = n.lastIndexOf('.')
    return if (i <= 0 || n.substring(0, i).all { it == '.' }) n to "" else n.substring(0, i) to n.substring(i)
}

fun uniqueName(name: String, taken: Set<String>, isDir: Boolean): String {
    if (name !in taken) return name
    val (base, ext) = if (isDir) name to "" else splitExt(name)
    var i = 1
    while ("$base ($i)$ext" in taken) i++
    return "$base ($i)$ext"
}

// ---- mime
private val MIME_FIX = mapOf(
    ".3gp" to "video/3gpp", ".3g2" to "video/3gpp2", ".mkv" to "video/x-matroska", ".mov" to "video/quicktime",
    ".m4v" to "video/mp4", ".mp4" to "video/mp4", ".webm" to "video/webm", ".ts" to "video/mp2t",
    ".heic" to "image/heic", ".heif" to "image/heif", ".jpg" to "image/jpeg", ".jpeg" to "image/jpeg",
    ".png" to "image/png", ".webp" to "image/webp", ".gif" to "image/gif", ".m4a" to "audio/mp4",
)

fun mimeFor(name: String): String {
    val i = name.lastIndexOf('.')
    val ext = if (i > 0) name.substring(i).lowercase() else ""
    MIME_FIX[ext]?.let { return it }
    if (ext.length > 1) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(1))?.let { return it }
    return "application/octet-stream"
}

fun safeInline(mt: String) =
    ((mt.startsWith("image/") || mt.startsWith("video/") || mt.startsWith("audio/")) && !mt.contains("svg")) ||
        mt == "application/pdf" || mt == "text/plain"

// ---- json helpers (org.json drops a key when you put(key, null): use JSONObject.NULL for real nulls)
fun jarr(items: Collection<JSONObject>) = JSONArray(items)

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

// ---- stream helpers
abstract class Source : InputStream() {
    abstract val size: Long
    open val seekable: Boolean get() = false
    open fun seek(pos: Long) {}
}

fun readLine(ins: InputStream, max: Int = 16384): String? {
    val sb = StringBuilder()
    var n = 0
    while (true) {
        val c = ins.read()
        if (c < 0) return if (n == 0) null else sb.toString()
        if (c == '\n'.code) break
        if (c != '\r'.code) sb.append(c.toChar())
        if (++n > max) throw IOException("line too long")
    }
    return sb.toString()
}

/** Reads at most [left] bytes of [ins]; an early end of the underlying stream is an error (lets callers resume). */
class LimitedIn(private val ins: InputStream, private var left: Long) : InputStream() {
    fun remaining() = left
    override fun read(): Int {
        if (left <= 0) return -1
        val c = ins.read()
        if (c < 0) throw java.io.EOFException("connection lost")
        left--
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        if (len == 0) return 0
        val n = ins.read(b, off, minOf(len.toLong(), left).toInt())
        if (n < 0) throw java.io.EOFException("connection lost")
        left -= n
        return n
    }
}
