package com.lanshare.app.core.usearch

/**
 * Walk rules with the user's folders (settings us_incl / us_excl, as ABSOLUTE paths) on top of the default rules (decision D9).
 * include empty = everything; include not empty = only inside those folders (their ancestor folders are listed so the tree stays connected,
 * but files that sit in an ancestor folder are left out). exclude wins over include. Pure JVM.
 */
class UserRules(
    include: List<String> = emptyList(),
    exclude: List<String> = emptyList(),
    omitNames: Set<String> = setOf(".thumbnails", ".cache")
) : WalkRules {
    private val inc = include.map { it.trimEnd('/') }.filter { it.isNotEmpty() }
    private val base = DefaultRules(exclude, omitNames)

    private fun inside(p: String) = inc.any { p == it || p.startsWith("$it/") }
    private fun ancestor(p: String) = inc.any { it.startsWith("$p/") }

    override fun skip(absPath: String, relPath: String, name: String, isDir: Boolean): Int {
        val b = base.skip(absPath, relPath, name, isDir)
        if (b == Skip.OMIT) return b
        if (inc.isEmpty()) return b
        if (inside(absPath)) return b
        if (isDir && ancestor(absPath)) return Skip.NONE
        return Skip.OMIT
    }
}

/**
 * The Hide button's list (virtual paths below the storage root, ui.html HGAL) applied to absolute paths.
 * A path is hidden when it, or a folder above it, is in the list. Search applies it per query (the list changes without a rescan).
 */
class HiddenFilter(rootPath: String, private val hidden: Set<String>) {
    private val root = rootPath.trimEnd('/')
    fun isHidden(abs: String): Boolean {
        if (hidden.isEmpty()) return false
        if (abs != root && !abs.startsWith("$root/")) return false   // other volumes: the Hide list only knows the main storage
        val v = abs.substring(root.length).ifEmpty { "/" }
        if (hidden.contains(v)) return true
        var i = v.lastIndexOf('/')
        while (i > 0) { if (hidden.contains(v.substring(0, i))) return true; i = v.lastIndexOf('/', i - 1) }
        return false
    }
}
