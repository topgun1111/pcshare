package com.lanshare.app.core.vault

import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Single enforcement point: is a path inside a vault (`.lsv`) or the plaintext vault cache?
 * Pure JVM. Case-insensitive + NFC/NFKC folded (FAT/exFAT SD cards are case-insensitive),
 * `.`/`..`/%2e/backslash/symlinks resolved. Callers: LocalFs.real/lreal, FTP, HTTP routes, intents.
 */
object VaultGuard {
    const val DIR = ".lsv"
    /** App-private plaintext temp dir for viewers (Part 3); set once at app start. Empty = none. */
    @Volatile var cacheRoot: String = ""

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun hasVaultSegment(p: String): Boolean =
        p.replace('\\', '/').split('/').any { fold(it).trimEnd('.', ' ') == DIR }

    private fun dec(s: String): String = try {
        var x = s
        repeat(2) { if (x.contains('%')) x = java.net.URLDecoder.decode(x.replace("+", "%2B"), "UTF-8") }
        x
    } catch (_: Exception) { s }

    /** Lexical test (no I/O): any path segment is `.lsv` after decoding/folding. */
    fun isVaultName(path: String): Boolean {
        val d = dec(path)
        if (hasVaultSegment(d)) return true
        val c = cacheRoot
        return c.isNotEmpty() && fold(d.replace('\\', '/')).startsWith(fold(c.trimEnd('/')) + "/vt")
    }

    /** Full test: lexical, then canonical path (follows symlinks), then the cache dir. */
    fun isVaultPath(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        if (isVaultName(path)) return true
        return try {
            val canon = File(path).canonicalPath
            hasVaultSegment(canon) || isVaultName(canon)
        } catch (_: Exception) { true }   // cannot resolve -> refuse (fail closed)
    }

    fun isVaultFile(f: File): Boolean = isVaultPath(f.path)

    /** Hide `.lsv` from a directory listing. */
    fun visible(name: String): Boolean = fold(name).trimEnd('.', ' ') != DIR
}
