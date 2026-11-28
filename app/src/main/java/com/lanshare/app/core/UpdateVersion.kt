package com.lanshare.app.core

internal object UpdateVersion {
    // The nonnumeric prefix keeps older stable updaters from mistaking a preview for a stable release.
    private val preview = Regex("preview-v([0-9]+\\.[0-9]+)-([0-9]+)-([0-9]+)(?:-[0-9a-f]+)?(?:-lite)?")
    private val stableTag = Regex("v?[0-9]+(?:\\.[0-9]+)+")

    fun isPreview(version: String) = preview.matches(version)

    fun accepts(tag: String, draft: Boolean, prerelease: Boolean, includePreviews: Boolean): Boolean =
        !draft && if (prerelease) includePreviews && isPreview(tag) else stableTag.matches(tag)

    /** Preview runs follow their base stable release and precede the next stable version. */
    fun compare(left: String, right: String): Int {
        val x = parts(left)
        val y = parts(right)
        for (i in 0 until maxOf(x.size, y.size)) {
            val order = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (order != 0) return order
        }
        return 0
    }

    private fun parts(version: String): List<Long> {
        val match = preview.matchEntire(version)
        val base = match?.groupValues?.get(1) ?: version.removePrefix("v").substringBefore('-')
        val parts = base.split('.').map { it.toLongOrNull() ?: 0L }.toMutableList()
        if (match != null) {
            // Stable numeric version parts must finish before the preview run/attempt pair.
            parts.add(0L)
            parts.add(match.groupValues[2].toLongOrNull() ?: 0L)
            parts.add(match.groupValues[3].toLongOrNull() ?: 0L)
        }
        return parts
    }
}
