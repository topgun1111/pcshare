package com.lanshare.app.core.usearch

import java.io.File

/**
 * Freshness logic of the name index (decision D8). Pure JVM, no android imports, so the JVM self-test covers it with real temp folders.
 *
 *  - [syncDir]    : reconcile ONE folder with the disk (shallow: its own children; a new sub-folder is walked completely). Own file operations call this
 *                   for the parent folder of every path they touched, so a copy of 5000 files costs one listing, not 5000.
 *  - [syncVolume] : the changed-folder delta walk: one stat per indexed folder; only folders whose modification time differs are re-listed.
 *                   Known limit (D8): editing a file's content in place does not change its folder's time, so this walk does not see it.
 */
class DeltaStats {
    @Volatile var dirsChecked = 0
    @Volatile var dirsChanged = 0
    @Volatile var added = 0
    @Volatile var removed = 0
    @Volatile var updated = 0
}

object Delta {
    private const val MAX_DEPTH = 64

    private class Item(val file: File, val dir: Boolean)

    /** The volume root id above [id] (walks the parent chain). */
    private fun rootOf(idx: NameIndex, id: Int): Int {
        var x = id
        var guard = 0
        while (guard++ < 4096) { val p = idx.parentOf(x); if (p == NameIndex.NONE) return x; x = p }
        return NameIndex.NONE
    }

    /** Path of [dir] below its volume root with "/" separators ("" for the root itself). */
    private fun relOf(idx: NameIndex, id: Int, dir: File): String {
        val r = rootOf(idx, id)
        if (r == NameIndex.NONE) return ""
        val rp = idx.pathOf(r).trimEnd('/')
        val p = dir.path
        return if (p.length > rp.length && p.startsWith("$rp/")) p.substring(rp.length + 1) else ""
    }

    /**
     * Reconciles the children of [dir] with the index. If [dir] is not indexed yet, the nearest indexed folder above it is reconciled instead
     * (that lists the new folder and walks it). If [dir] is gone from the disk it is removed from the index (volume roots are kept).
     * Returns true when something changed.
     */
    fun syncDir(idx: NameIndex, dir: File, rules: WalkRules, isLink: (File) -> Boolean = ::defaultIsLink, stats: DeltaStats = DeltaStats(), state: ScanState = ScanState()): Boolean {
        var id = idx.find(dir.path)
        var d = dir
        var guard = 0
        while (id == NameIndex.NONE && guard++ < 256) {   // climb to the nearest indexed folder
            d = d.parentFile ?: return false
            id = idx.find(d.path)
        }
        if (id == NameIndex.NONE || !idx.isDir(id)) return false
        if (!d.isDirectory) {   // the folder itself vanished
            if (idx.parentOf(id) == NameIndex.NONE) return false   // a volume root: maybe just unmounted, leave it
            stats.removed += idx.remove(id)
            return true
        }
        val kids = d.listFiles() ?: return false   // unreadable: keep what we know
        var changed = false
        // existing children by name (collect first: mutation needs the write lock, forEachChild holds the read lock)
        val have = HashMap<String, Int>()
        idx.forEachChild(id) { c -> have[idx.nameOf(c)] = c }
        val rel0 = relOf(idx, id, d)
        val seen = HashSet<String>()
        val walker = Walker(idx, rules, isLink, state)
        for (k in kids) {
            if (state.cancelled) return changed
            val name = k.name
            val isDir = k.isDirectory
            val rel = if (rel0.isEmpty()) name else "$rel0/$name"
            val act = rules.skip(k.path, rel, name, isDir)
            if (act == Skip.OMIT) continue
            seen.add(name)
            val cid = have[name]
            if (cid != null && idx.isDir(cid) != isDir) { stats.removed += idx.remove(cid); changed = true }   // file became folder or the other way round
            else if (cid != null) {
                if (!isDir) {
                    val sz = k.length(); val mt = k.lastModified() / 1000
                    if (idx.sizeOf(cid) != sz || idx.mtimeOf(cid) != mt) { idx.update(cid, sz, mt); stats.updated++; changed = true }
                }
                continue   // folders: their own content is checked by the delta walk (own mtime is NOT touched here)
            }
            if (isDir) {
                val nid = idx.add(id, name, true, 0L, dirStamp(k))
                stats.added++; changed = true
                if (act == Skip.NONE && !isLink(k)) { val before = idx.count(); walker.rescanDir(nid, k, rel); stats.added += idx.count() - before }
            } else {
                idx.add(id, name, false, k.length(), k.lastModified() / 1000)
                stats.added++; changed = true
            }
        }
        for ((name, cid) in have) if (!seen.contains(name)) { stats.removed += idx.remove(cid); changed = true }
        val mt = dirStamp(d)
        if (idx.mtimeOf(id) != mt) { idx.update(id, idx.sizeOf(id), mt); changed = true }
        stats.dirsChecked++
        if (changed) stats.dirsChanged++
        return changed
    }

    /**
     * Delta walk of one indexed volume: every indexed folder is stat-ed; a folder whose time differs from the stored one is reconciled with [syncDir].
     * Sub-folders are always visited (their own time may differ even when the parent's does not). Folders that [rules] list but do not enter are not entered.
     */
    fun syncVolume(idx: NameIndex, root: File, rules: WalkRules, isLink: (File) -> Boolean = ::defaultIsLink, state: ScanState = ScanState()): DeltaStats {
        val st = DeltaStats()
        val rid = idx.findRoot(root.path)
        if (rid == NameIndex.NONE || !root.isDirectory) return st
        class Fr(val id: Int, val f: File, val rel: String, val depth: Int)
        val stack = ArrayList<Fr>()
        stack.add(Fr(rid, root, "", 0))
        while (stack.isNotEmpty() && !state.cancelled) {
            val fr = stack.removeAt(stack.size - 1)
            if (!idx.isLive(fr.id)) continue
            state.current = fr.f.path
            if (!fr.f.isDirectory) { if (fr.depth > 0) { st.removed += idx.remove(fr.id); st.dirsChanged++ }; continue }
            if (idx.mtimeOf(fr.id) != dirStamp(fr.f)) syncDir(idx, fr.f, rules, isLink, st, state) else st.dirsChecked++
            if (!idx.isLive(fr.id) || fr.depth >= MAX_DEPTH) continue
            val subs = ArrayList<Fr>()
            idx.forEachChild(fr.id) { c ->
                if (idx.isDir(c)) {
                    val nm = idx.nameOf(c)
                    val rel = if (fr.rel.isEmpty()) nm else fr.rel + "/" + nm
                    val f = File(fr.f, nm)
                    if (rules.skip(f.path, rel, nm, true) == Skip.NONE && !isLink(f)) subs.add(Fr(c, f, rel, fr.depth + 1))
                }
            }
            stack.addAll(subs)
        }
        return st
    }
}
