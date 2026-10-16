package com.lanshare.app.core

import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/*
 * STORAGE ANALYZER - part 1 of 10: the scan engine (WizTree-like). No UI, no HTTP routes yet (part 2 adds them).
 *
 * What it does: walks a folder of this phone (a sub-folder, or the whole main storage) with a few threads and builds a size tree:
 *   - every folder is a node with the total size / file count / sub-folder count / bytes per file category BELOW it;
 *   - to keep memory small a folder only keeps its [FILES_PER_DIR] largest files as nodes; all other files of that folder are folded into
 *     ONE synthetic node ("agg", name "", `files` = how many) so the sizes still add up exactly;
 *   - empty folders are dropped from `kids` (they still count in `folders`);
 *   - kids are sorted by size, biggest first, so a treemap / list can use them directly;
 *   - the [TOP_FILES] largest files of the whole scan are collected separately (Scan.top).
 * Symlinks are never followed. Sizes are apparent sizes (File.length), like the rest of the app.
 * Folders Android refuses to list (Android/data on API 30+ ...) are counted in Scan.denied and listed (first few) in Scan.deniedSample.
 *
 * Contract for the next parts (do not break without updating them):
 *   StorageScan.start(dir: File, label: String, usedBytes: Long): Scan     -> returns at once, work runs on daemon threads
 *   StorageScan.get(id): Scan?   StorageScan.cancel(id)   StorageScan.latest(dir: File): Scan?  (newest finished scan of that folder)
 *   Scan.state "run"|"done"|"cancel"|"error", Scan.percent() (-1 unknown), Scan.files/dirs/bytes (live counters), Scan.cur (folder being read)
 *   Scan.root (SNode, null until done), Scan.top, Scan.catBytes(), Scan.catFiles(), Scan.find("/DCIM/Camera"), SNode.path()
 * NOT compiled / NOT device-tested (no Android SDK in the authoring environment).
 */

/** File categories (indexes into the category arrays). */
object SCat {
    const val OTHER = 0
    const val IMG = 1
    const val VID = 2
    const val AUD = 3
    const val DOC = 4
    const val ARC = 5
    const val APP = 6
    const val COUNT = 7
    val KEYS = arrayOf("other", "img", "vid", "aud", "doc", "arc", "app")

    private val map = HashMap<String, Int>().also { m ->
        fun add(c: Int, s: String) { for (e in s.split(' ')) m[e] = c }
        add(IMG, "jpg jpeg png gif webp bmp heic heif avif tif tiff svg raw dng cr2 nef arw ico jfif")
        add(VID, "mp4 mkv avi mov wmv flv webm m4v 3gp 3g2 mpg mpeg ts m2ts vob ogv")
        add(AUD, "mp3 m4a aac wav flac ogg oga opus wma amr mid midi aiff ape")
        add(DOC, "pdf doc docx xls xlsx ppt pptx odt ods odp txt rtf csv md epub mobi azw3 djvu html htm xml json log tex")
        add(ARC, "zip rar 7z tar gz tgz bz2 xz zst iso img cab lz")
        add(APP, "apk xapk apks aab obb dex so")
    }

    fun of(name: String): Int {
        val i = name.lastIndexOf('.')
        if (i < 0 || i == name.length - 1 || name.length - i > 6) return OTHER
        return map[name.substring(i + 1).lowercase()] ?: OTHER
    }
}

/** One folder or file of the scan tree. Fields are written by ONE scan task only and read after the scan finished (or never changed again). */
class SNode(@JvmField val name: String, @JvmField val dir: Boolean, @JvmField val parent: SNode?) {
    /** File: its bytes. Folder: everything below (while scanning: only the bytes of the folder's own files). */
    @JvmField var size = 0L
    /** File: 1. Folder: number of files below (also those folded into the "agg" node). "agg" node: how many files it stands for. */
    @JvmField var files = 0L
    /** Folder: number of sub-folders below, empty ones included. */
    @JvmField var folders = 0L
    /** File: SCat category. */
    @JvmField var cat = 0
    /** Synthetic node standing for the many small files of one folder that are not kept one by one (name is ""). */
    @JvmField var agg = false
    /** Folder Android would not let us read. */
    @JvmField var denied = false
    /** Folder: bytes per category below (null = no files). */
    @JvmField var cats: LongArray? = null
    /** Folder: sub-folders, the largest files and the agg node, biggest first. Empty for files. */
    @JvmField var kids: Array<SNode> = NONE

    /** Virtual path below the scan root ("/" for the root, "/DCIM/Camera/a.jpg"). The agg node returns its folder's path. */
    fun path(): String {
        if (parent == null) return "/"
        val parts = ArrayList<String>()
        var n: SNode? = this
        while (n != null && n.parent != null) { if (!(n.agg && n === this)) parts.add(n.name); n = n.parent }
        if (parts.isEmpty()) return "/"
        val sb = StringBuilder()
        for (i in parts.indices.reversed()) sb.append('/').append(parts[i])
        return sb.toString()
    }

    companion object { @JvmField val NONE = arrayOf<SNode>() }
}

/** One scan (running or finished). */
class Scan(val id: String, val dir: File, val label: String, val usedBytes: Long) {
    @Volatile var state = "run"          // run | done | cancel | error
    @Volatile var error: String? = null
    @Volatile var cancel = false
    val files = AtomicLong()             // live counters
    val dirs = AtomicLong()
    val bytes = AtomicLong()
    val denied = AtomicInteger()
    @Volatile var cur = ""               // folder being read now (updated about 6 times a second)
    val t0 = System.currentTimeMillis()
    @Volatile var t1 = 0L                // end time, 0 while running
    @Volatile var root: SNode? = null    // set when the scan is done
    @Volatile var top: List<SNode> = emptyList()   // the largest files of the whole scan, biggest first
    val deniedSample = ConcurrentLinkedQueue<String>()   // up to [StorageScan.DENIED_SAMPLE] folder paths that could not be read
    internal val catF = AtomicLongArray(SCat.COUNT)       // files per category (live)
    internal val pending = AtomicInteger()
    internal val nodes = AtomicInteger()
    internal val topLock = Any()
    internal val topHeap = PriorityQueue<SNode>(StorageScan.TOP_FILES + 1) { a, b -> java.lang.Long.compare(a.size, b.size) }   // min-heap: smallest of the top on top
    @Volatile internal var tick = 0L
    @Volatile internal var topMin = 0L

    /** 0..99 while running (scanned bytes / used bytes of the volume; reaches 100 only when done), -1 when the used size is unknown. */
    fun percent(): Int {
        if (state == "done") return 100
        if (usedBytes <= 0) return -1
        return minOf(99L, bytes.get() * 100 / usedBytes).toInt()
    }

    fun millis(): Long = (if (t1 > 0) t1 else System.currentTimeMillis()) - t0

    /** Bytes per category of the whole scan (empty array of zeros until done). */
    fun catBytes(): LongArray = root?.cats?.copyOf() ?: LongArray(SCat.COUNT)

    /** Files per category of the whole scan (live while running). */
    fun catFiles(): LongArray = LongArray(SCat.COUNT) { catF.get(it) }

    /** Node of a virtual path below the scan root ("/" = root, "/DCIM/Camera"); null when unknown or when the folder was pruned (empty). */
    fun find(rel: String): SNode? {
        var n = root ?: return null
        for (p in vnorm(rel).split('/')) {
            if (p.isEmpty()) continue
            n = n.kids.firstOrNull { it.dir && it.name == p } ?: return null
        }
        return n
    }
}

object StorageScan {
    const val FILES_PER_DIR = 200      // files kept as nodes per folder (the rest is folded into one "agg" node)
    const val TOP_FILES = 100          // largest files of the whole scan
    const val MAX_NODES = 400_000      // after this many kept file nodes new files are only counted (memory guard)
    const val DENIED_SAMPLE = 20
    private const val THREADS = 4
    private const val KEEP_SCANS = 3   // finished scans kept in memory

    private val all = ConcurrentHashMap<String, Scan>()
    private val pool = Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "sscan").also { it.isDaemon = true } }

    fun get(id: String): Scan? = all[id]

    fun cancel(id: String) { all[id]?.cancel = true }

    /** Newest finished ("done") scan of exactly this folder, or null. */
    fun latest(dir: File): Scan? {
        val p = dir.path
        return all.values.filter { it.state == "done" && it.dir.path == p }.maxByOrNull { it.t1 }
    }

    /** A scan of this folder that is running now, or null (so a second tap does not start a second walk). */
    fun running(dir: File): Scan? {
        val p = dir.path
        return all.values.firstOrNull { it.state == "run" && it.dir.path == p }
    }

    /** Starts a scan of [dir] and returns at once. [usedBytes] (used space of the volume, 0 = unknown) only feeds the percentage. */
    fun start(dir: File, label: String, usedBytes: Long): Scan {
        evict()
        val s = Scan(UUID.randomUUID().toString().replace("-", "").take(8), dir, label, usedBytes)
        all[s.id] = s
        val root = SNode("", true, null)
        val ok = try { dir.isDirectory } catch (_: Exception) { false }
        if (!ok) { s.error = "Not a folder"; s.state = "error"; s.t1 = System.currentTimeMillis(); return s }
        if (dir.list() == null) { s.error = "Permission denied"; s.state = "error"; s.t1 = System.currentTimeMillis(); return s }
        s.pending.incrementAndGet()
        pool.execute { task(s, root, dir) }
        return s
    }

    /** Keeps the newest [KEEP_SCANS] finished scans, drops older ones (a finished tree can hold some MB). */
    private fun evict() {
        val done = all.values.filter { it.state != "run" }.sortedByDescending { it.t1 }
        for (i in KEEP_SCANS until done.size) all.remove(done[i].id)
    }

    // ------------------------------------------------------------------ one folder = one task
    private fun task(s: Scan, node: SNode, dir: File) {
        try {
            if (s.cancel) return
            val now = System.currentTimeMillis()
            if (now - s.tick > 150) { s.tick = now; s.cur = dir.path }
            val entries = dir.listFiles()
            if (entries == null) {
                node.denied = true
                if (s.denied.incrementAndGet() <= DENIED_SAMPLE) s.deniedSample.add(node.path())
                return
            }
            s.dirs.incrementAndGet()
            val modern = Build.VERSION.SDK_INT >= 26
            val keepMax = if (s.nodes.get() > MAX_NODES) 0 else FILES_PER_DIR
            val heap = PriorityQueue<SNode>(keepMax + 1) { a, b -> java.lang.Long.compare(a.size, b.size) }   // this folder's largest files
            val subs = ArrayList<SNode>()
            val subFiles = ArrayList<File>()
            val cats = LongArray(SCat.COUNT)
            val catN = IntArray(SCat.COUNT)
            var ownBytes = 0L
            var ownFiles = 0L
            for (f in entries) {
                if (s.cancel) return
                var isDir: Boolean
                var size = 0L
                var link = false
                var a: BasicFileAttributes? = null
                if (modern) a = try { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (_: Exception) { null }
                if (a != null) { isDir = a.isDirectory; link = a.isSymbolicLink; size = if (isDir) 0L else a.size() }
                else { isDir = f.isDirectory; if (isDir) link = Core.local.isLink(f) else size = f.length() }
                if (link) continue                                   // never follow / count links
                if (isDir) {
                    val sub = SNode(f.name, true, node)
                    subs.add(sub); subFiles.add(f)
                    continue
                }
                if (size < 0) size = 0
                val c = SCat.of(f.name)
                ownFiles++; ownBytes += size; cats[c] += size; catN[c]++
                if (size > 0 && keepMax > 0 && (heap.size < keepMax || size > heap.peek().size)) {
                    val fn = SNode(f.name, false, node).also { it.size = size; it.files = 1; it.cat = c }
                    heap.add(fn)
                    if (heap.size > keepMax) heap.poll()
                }
                if (size > 0) offerTop(s, f.name, node, size, c)
            }
            // counters + this folder's own numbers (sub-folder totals are added by the finishing pass)
            s.files.addAndGet(ownFiles); s.bytes.addAndGet(ownBytes)
            for (c in 0 until SCat.COUNT) if (catN[c] > 0) s.catF.addAndGet(c, catN[c].toLong())
            node.size = ownBytes; node.files = ownFiles
            if (ownFiles > 0) node.cats = cats
            // kids: kept files + one agg node for the rest + sub-folders (sorted in the finishing pass)
            val kept = heap.size.toLong()
            var keptBytes = 0L
            for (k in heap) keptBytes += k.size
            val restFiles = ownFiles - kept
            val total = kept.toInt() + (if (restFiles > 0) 1 else 0) + subs.size
            val arr = ArrayList<SNode>(total)
            arr.addAll(heap)
            if (restFiles > 0) arr.add(SNode("", false, node).also { it.agg = true; it.size = ownBytes - keptBytes; it.files = restFiles })
            arr.addAll(subs)
            node.kids = arr.toTypedArray()
            s.nodes.addAndGet(total)
            // sub-folders: one task each
            for (j in subs.indices) {
                if (s.cancel) return
                s.pending.incrementAndGet()
                val sub = subs[j]; val sf = subFiles[j]
                pool.execute { task(s, sub, sf) }
            }
        } catch (t: Throwable) {   // OutOfMemoryError included: stop the whole scan and report it
            if (s.error == null) s.error = if (t is OutOfMemoryError) "Out of memory" else errText(t)
            s.cancel = true
        } finally {
            if (s.pending.decrementAndGet() == 0) finishAsync(s, rootOf(node))
        }
    }

    private fun rootOf(n: SNode): SNode { var r = n; while (r.parent != null) r = r.parent!!; return r }

    private fun offerTop(s: Scan, name: String, parent: SNode, size: Long, cat: Int) {
        if (size <= s.topMin) return   // cheap unlocked check: topMin is 0 until the heap is full, then the smallest kept size
        synchronized(s.topLock) {
            if (s.topHeap.size < TOP_FILES || size > s.topHeap.peek().size) {
                s.topHeap.add(SNode(name, false, parent).also { it.size = size; it.files = 1; it.cat = cat })
                if (s.topHeap.size > TOP_FILES) s.topHeap.poll()
                if (s.topHeap.size >= TOP_FILES) s.topMin = s.topHeap.peek().size
            }
        }
    }

    // ------------------------------------------------------------------ finishing pass (after the last task)
    private fun finishAsync(s: Scan, root: SNode) {
        // deep folders: a thread with a big stack for the recursion (Android's path limit allows ~2000 levels)
        Thread(null, {
            try {
                if (s.error == null && !s.cancel) {
                    fin(root)
                    s.root = root
                    val t = ArrayList(s.topHeap)
                    t.sortByDescending { it.size }
                    s.top = t
                }
            } catch (t: Throwable) {
                if (s.error == null) s.error = if (t is OutOfMemoryError) "Out of memory" else errText(t)
            }
            s.t1 = System.currentTimeMillis()
            s.state = if (s.error != null) "error" else if (s.cancel) "cancel" else "done"
            s.cur = ""
        }, "sscan-fin", 16L shl 20).also { it.isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ part 8b: patching the finished tree after a delete
    /**
     * A FILE of the finished scan was deleted from the storage: take it out of the tree without a rescan. Call it on the main thread
     * (the screen reads the tree there). [n] may be the kept node of the folder or a copy from [Scan.top] (matched by folder + name).
     *   - the node leaves its folder's kids (a copy from "Largest" whose kept node does not exist was folded into the folder's "agg" node:
     *     that one shrinks by one file / the file's bytes, and goes away when it stands for no file any more);
     *   - size / files / cats[cat] are subtracted on the folder and every ancestor; the kids of those are sorted again (sizes changed);
     *   - folders that became empty (size 0, no files, not denied) are dropped from their parent like the scan does ("folders" stays, as in the scan);
     *   - the file leaves [Scan.top] (new list, the field is @Volatile and the list immutable); the live counters follow.
     * Returns the nearest folder that is still in the tree (the file's folder, or the first ancestor that was not dropped), or null when
     * [n] is not a file of this scan / is not found (then nothing was changed). Folders are part 8c.
     */
    fun removeFile(s: Scan, n: SNode): SNode? {
        synchronized(s) { return removeFileLocked(s, n) }
    }

    private fun removeFileLocked(s: Scan, n: SNode): SNode? {
        if (s.root == null || n.dir || n.agg) return null
        val p = n.parent ?: return null
        val size = n.size
        val cat = n.cat.coerceIn(0, SCat.COUNT - 1)
        // 1. the node itself
        val kept = p.kids.firstOrNull { it === n } ?: p.kids.firstOrNull { !it.dir && !it.agg && it.name == n.name }
        if (kept != null) {
            p.kids = p.kids.filter { it !== kept }.toTypedArray()
        } else {
            val agg = p.kids.firstOrNull { it.agg } ?: return null
            agg.size = maxOf(0L, agg.size - size)
            agg.files -= 1
            if (agg.files <= 0L) p.kids = p.kids.filter { it !== agg }.toTypedArray()
        }
        // 2. numbers up the chain
        var a: SNode? = p
        while (a != null) {
            a.size = maxOf(0L, a.size - size)
            a.files = maxOf(0L, a.files - 1)
            a.cats?.let { it[cat] = maxOf(0L, it[cat] - size) }
            a = a.parent
        }
        // 3. folders that became empty are dropped (never the root)
        var near: SNode = p
        while (true) {
            val up = near.parent ?: break
            if (near.size != 0L || near.files != 0L || near.denied) break
            val dead = near
            up.kids = up.kids.filter { it !== dead }.toTypedArray()
            near = up
        }
        // 4. sizes changed: biggest first again
        var b: SNode? = p
        while (b != null) {
            if (b.kids.size > 1) b.kids = b.kids.sortedByDescending { it.size }.toTypedArray()
            b = b.parent
        }
        // 5. the largest-files list and the live counters
        if (s.top.isNotEmpty()) s.top = s.top.filter { !(it.parent === p && it.name == n.name) }
        s.files.addAndGet(-1L); s.bytes.addAndGet(-size); s.catF.addAndGet(cat, -1L)
        return near
    }

    /** Post-order: folder size / files / folders / categories include everything below; kids sorted biggest first; empty folders dropped. */
    private fun fin(n: SNode) {
        var size = n.size
        var files = n.files
        var folders = 0L
        var cats = n.cats
        var dropEmpty = false
        for (k in n.kids) {
            if (!k.dir) continue
            fin(k)
            size += k.size; files += k.files; folders += 1 + k.folders
            val kc = k.cats
            if (kc != null) {
                if (cats == null) cats = LongArray(SCat.COUNT)
                for (c in 0 until SCat.COUNT) cats[c] += kc[c]
            }
            if (k.size == 0L && k.files == 0L && !k.denied) dropEmpty = true
        }
        n.size = size; n.files = files; n.folders = folders; n.cats = cats
        var kids = n.kids
        if (dropEmpty) kids = kids.filter { !(it.dir && it.size == 0L && it.files == 0L && !it.denied) }.toTypedArray()
        if (kids.size > 1) java.util.Arrays.sort(kids) { a, b -> java.lang.Long.compare(b.size, a.size) }
        n.kids = kids
    }
}
