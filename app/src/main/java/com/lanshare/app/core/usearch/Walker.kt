package com.lanshare.app.core.usearch

import java.io.File
import java.io.IOException

/** What a [WalkRules] decides for one directory entry. */
object Skip {
    const val NONE = 0         // index it and walk into it
    const val NO_DESCEND = 1   // index the entry itself, but do not walk below it
    const val OMIT = 2         // leave it out completely
}

interface WalkRules {
    /** absPath = full path, relPath = path below the volume root with "/" separators, name = last component. */
    fun skip(absPath: String, relPath: String, name: String, isDir: Boolean): Int
}

/**
 * Default rules (decision D9): Android/data and Android/obb are listed but not entered; .thumbnails and .cache folders are left out;
 * user-excluded folders (absolute paths, us_excl) are left out with everything below. Part 3 adds the hidden list and the Bin folder
 * by passing more entries in [exclude] or by wrapping these rules.
 */
class DefaultRules(
    private val exclude: List<String> = emptyList(),
    private val omitNames: Set<String> = setOf(".thumbnails", ".cache")
) : WalkRules {
    override fun skip(absPath: String, relPath: String, name: String, isDir: Boolean): Int {
        if (isDir) {
            if (omitNames.contains(name)) return Skip.OMIT
            for (e in exclude) { val x = e.trimEnd('/'); if (absPath == x || absPath.startsWith("$x/")) return Skip.OMIT }
            if (relPath.equals("Android/data", ignoreCase = true) || relPath.equals("Android/obb", ignoreCase = true)) return Skip.NO_DESCEND
        }
        return Skip.NONE
    }
}

/** Live counters of a scan; read them from any thread. Set cancelled = true to stop the walk (the index keeps what it already has). */
class ScanState {
    @Volatile var files = 0L
    @Volatile var dirs = 0L
    @Volatile var unreadable = 0L
    @Volatile var current = ""
    @Volatile var cancelled = false
    fun cancel() { cancelled = true }
}

/** True when f is a symbolic link. nio where it exists (API 26+), canonical-path comparison otherwise (same idea as LocalFs.isLink). */
fun defaultIsLink(f: File): Boolean {
    try { return java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (_: Throwable) { }
    return try {
        val par = f.parentFile
        par != null && File(par.canonicalFile, f.name).let { it.canonicalPath != it.absolutePath }
    } catch (_: IOException) { false }
}

/**
 * Iterative directory walker that fills a [NameIndex]. Never follows symbolic links (a linked folder is listed, not entered),
 * stops at depth [MAX_DEPTH], counts unreadable folders, and can be cancelled through [state]. No android imports: pass LocalFs.isLink as [isLink] on the phone.
 */
class Walker(
    private val idx: NameIndex,
    private val rules: WalkRules = DefaultRules(),
    private val isLink: (File) -> Boolean = ::defaultIsLink,
    val state: ScanState = ScanState()
) {
    companion object { const val MAX_DEPTH = 64 }

    private class Frame(val dir: File, val id: Int, val rel: String, val depth: Int)

    /** Scans a whole volume. An existing root of the same path is emptied and refilled. Returns the root id. */
    fun scanVolume(root: File): Int {
        var rid = idx.findRoot(root.path)
        if (rid == NameIndex.NONE) rid = idx.addRoot(root.path) else idx.removeChildren(rid)
        idx.update(rid, 0L, root.lastModified() / 1000)
        walk(root, rid, "")
        return rid
    }

    /** Re-reads one folder (and everything below): its old children are dropped first. [rel] = path of the folder below its volume root ("" for the root). */
    fun rescanDir(dirId: Int, dir: File, rel: String) {
        idx.removeChildren(dirId)
        idx.update(dirId, 0L, dir.lastModified() / 1000)
        walk(dir, dirId, rel)
    }

    private fun walk(start: File, startId: Int, startRel: String) {
        val stack = ArrayList<Frame>()
        stack.add(Frame(start, startId, startRel, 0))
        while (stack.isNotEmpty() && !state.cancelled) {
            val f = stack.removeAt(stack.size - 1)
            state.current = f.dir.path
            val kids = f.dir.listFiles()
            if (kids == null) { state.unreadable++; continue }
            for (k in kids) {
                val name = k.name
                val isDir = k.isDirectory
                val rel = if (f.rel.isEmpty()) name else f.rel + "/" + name
                val act = rules.skip(k.path, rel, name, isDir)
                if (act == Skip.OMIT) continue
                if (isDir) {
                    val id = idx.add(f.id, name, true, 0L, k.lastModified() / 1000)
                    state.dirs++
                    if (act == Skip.NONE && f.depth < MAX_DEPTH && !isLink(k)) stack.add(Frame(k, id, rel, f.depth + 1))
                } else {
                    idx.add(f.id, name, false, k.length(), k.lastModified() / 1000)
                    state.files++
                }
            }
        }
    }
}
