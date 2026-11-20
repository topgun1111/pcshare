package com.lanshare.app.core

import java.io.EOFException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.DataFormatException
import java.util.zip.ZipException

/** Plain-words messages for archive and download problems (what the toasts / download cards show instead of library or URL text). */
object ArcErr {
    private val HINTS = listOf("unexpected end", "end of zlib", "truncated", "premature", "corrupt", "damaged", "invalid", "crc", "checksum",
        "too short", "not a valid", "central directory", "zip end header", "unexpected eof", "malformed", "incomplete", "bad ")

    private fun locked(e: Throwable): Boolean {
        val m = ((e.message ?: "") + " " + e.javaClass.simpleName).lowercase()
        return m.contains("password") || m.contains("encrypt")
    }

    /** True when [e] (or its cause) looks like a damaged / cut-off archive rather than a network or permission problem. */
    fun damaged(e: Throwable): Boolean {
        if (e is EOFException || e is ZipException || e is DataFormatException) return true
        var t: Throwable? = e
        var n = 0
        while (t != null && n < 4) {
            val m = (t.message ?: "").lowercase()
            if (HINTS.any { m.contains(it) }) return true
            t = t.cause; n++
        }
        return false
    }

    /** [what] = "ZIP file" / "7Z archive" ...; [entry] = the file inside it that failed (null = the archive as a whole). */
    fun friendly(e: Throwable, what: String = "archive", entry: String? = null): String {
        if (locked(e)) return "This $what is password-protected - LANShare cannot open it."
        val tech = errText(e).replace('\n', ' ').take(90)
        return if (entry != null)
            "\"$entry\" cannot be unpacked: the $what is damaged or cut off at this file. Other files in it may still work. Copy the archive again, or repair it with an archive tool. ($tech)"
        else
            "This $what is damaged or incomplete - it may have been cut off while it was copied or downloaded. Copy it again, or repair it with an archive tool. ($tech)"
    }

    /** What the download card / notification says when a download fails. The server's own reason (it answers errors as JSON) is already plain words and passes through unchanged. */
    fun download(e: Throwable): String = when {
        e is FileNotFoundException -> "The file was not found - it was moved, renamed or deleted. Refresh the folder and try again."
        e is SocketTimeoutException -> "The other device stopped answering (timed out). Check that it is awake and on the same network, then try again."
        e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ->
            "Cannot reach the other device. Is it on, with LANShare open, on the same Wi-Fi or Tailscale?"
        e is EOFException || e is SocketException -> "The connection was interrupted before the file finished. Try again."
        else -> errText(e)
    }
}
