package com.lanshare.app.core

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Private bounded files, one writer per Android process. No Android dependencies. */
internal class ErrorLogStore(private val directory: File, private val process: String,
                             private val limit: Long = 1024L * 1024L) {
    init {
        require(process in listOf("main", "pdfbuild"))
        require(limit > 0)
    }

    private fun current() = File(directory, "$process.log")
    private fun previous() = File(directory, "$process.previous.log")

    @Synchronized fun append(line: String) {
        val bytes = (line.trimEnd('\n', '\r') + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > limit) return
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return
        val f = current()
        if (f.length() + bytes.size > limit) {
            val old = previous()
            if (old.exists() && !old.delete()) return
            if (f.exists() && !f.renameTo(old)) return
        }
        f.appendBytes(bytes)
    }

    /** Read at most maxBytes of each process; never split a UTF-8 log record. */
    @Synchronized fun recent(maxBytes: Int = 80_000): String {
        require(maxBytes > 0)
        val sections = ArrayList<String>()
        for (p in listOf("main", "pdfbuild")) {
            val files = listOf(File(directory, "$p.previous.log"), File(directory, "$p.log"))
            val lines = files.mapNotNull { f ->
                try {
                    if (!f.isFile) null else RandomAccessFile(f, "r").use { r ->
                        val end = r.length()
                        val start = maxOf(0L, end - maxBytes)
                        r.seek(start)
                        val b = ByteArray((end - start).toInt())
                        r.readFully(b)
                        val t = String(b, Charsets.UTF_8)
                        val complete = if (start > 0) t.substringAfter('\n', "") else t
                        if (complete.endsWith('\n')) complete else complete.substringBeforeLast('\n', "").let { if (it.isEmpty()) "" else "$it\n" }
                    }
                } catch (_: java.io.FileNotFoundException) { null } // other process rotated during snapshot
            }.joinToString("")
            if (lines.isNotBlank()) sections.add("=== $p ===\n" + lines.lineSequence().filter { it.isNotBlank() }.toList().takeLast(150).asReversed().joinToString("\n"))
        }
        return sections.joinToString("\n\n")
    }

    /** Export the full retained files, not the shortened on-screen view. */
    @Synchronized fun export(out: OutputStream) {
        for (p in listOf("main", "pdfbuild")) {
            out.write("=== $p (oldest first) ===\n".toByteArray(Charsets.UTF_8))
            for (f in listOf(File(directory, "$p.previous.log"), File(directory, "$p.log"))) {
                try {
                    if (f.isFile) f.inputStream().use { input ->
                        // A concurrently appending second process cannot make this export unbounded.
                        var left = minOf(f.length(), limit)
                        val b = ByteArray(8192)
                        while (left > 0) {
                            val n = input.read(b, 0, minOf(left, b.size.toLong()).toInt())
                            if (n <= 0) break
                            out.write(b, 0, n)
                            left -= n
                        }
                    }
                } catch (e: java.io.FileNotFoundException) {
                    // Only ignore a rotated source file, never an error writing the export destination.
                    if (f.exists()) throw e
                }
            }
            out.write("\n".toByteArray(Charsets.UTF_8))
        }
    }
}

/** Redact common credential forms; never use this to log request bodies or document contents. */
internal object ErrorLogPrivacy {
    private val credentials = Regex("""(?i)\b(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|passphrase|secret|access[_-]?token|refresh[_-]?token|api[_-]?key|client[_-]?secret|token|pin)\b["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\r\n,;}&]+)""")
    private val url = Regex("""(?i)\b(?:https?|smb|sftp|ftp)://[^\s<>"']+""")
    private val bearer = Regex("""(?i)\b(?:Bearer|Basic)\s+[A-Za-z0-9+/_=.-]+""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val vault = Regex("""(?i)(?:\bvault\b|\.lsv(?:[/\\]|\b)|\blockdown\b)""")

    fun clean(text: String, sensitive: Boolean = false): String {
        if (sensitive || vault.containsMatchIn(text)) return "[private details omitted]"
        var s = text.take(4096)
        s = url.replace(s, "[URL omitted]")
        s = credentials.replace(s, "[credential omitted]")
        s = bearer.replace(s, "[credential omitted]")
        s = jwt.replace(s, "[credential omitted]")
        return s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').take(1000)
    }

    fun sensitiveClass(name: String): Boolean {
        val s = name.lowercase(java.util.Locale.ROOT)
        return s.contains("vault") || s.contains("gauth") || s.contains("settingscloud") ||
            s.contains("settingsio") || s.contains("gdrive") || s.contains("gdapi") || s.contains("rootShell".lowercase(java.util.Locale.ROOT))
    }
}
