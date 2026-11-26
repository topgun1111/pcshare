package com.lanshare.app.core

/** A gallery selection contains files, never directories. Cache one remote listing per parent
 * rather than issuing hundreds of separate /p/walk requests before transferring any bytes. */
internal class GalleryCopyPlan(private val list: (String) -> List<Item>) {
    private val listings = HashMap<String, Result<Map<String, Item>>>()

    fun walk(path: String): List<WalkItem> {
        val p = vnorm(path)
        if (p == "/") throw BadReq("Select a media file, not the storage root")
        val parent = vdir(p)
        val items = listings.getOrPut(parent) {
            try {
                Result.success(list(parent).associateBy { it.name })
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }.getOrThrow()
        val item = items[vbase(p)] ?: throw NotFound("Source file no longer exists")
        if (item.dir) throw BadReq("Source is no longer a file")
        return listOf(WalkItem("", false, item.size.coerceAtLeast(0L)))
    }
}

// Keep planning failures, partially transferred files and untouched entries. Completed
// entries must not be retried. Preserve any name assigned by a previous attempt.
internal fun interruptedCopyItems(
    planned: List<String>,
    visited: Int,
    pending: Map<String, String>,
    planningFailures: Collection<String>,
    forced: Map<String, String>
): Map<String, String?> {
    val left = LinkedHashMap<String, String?>()
    for (path in planningFailures) left[path] = forced[path]
    for ((index, path) in planned.withIndex()) {
        val name = pending[path]
        if (name != null) left[path] = name
        else if (index >= visited && !left.containsKey(path)) left[path] = forced[path]
    }
    return left
}
