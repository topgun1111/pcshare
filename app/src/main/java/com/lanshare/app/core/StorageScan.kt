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
 * Part 9b added: Scan.limited / truncated / loaded / removed, the job entry (progress bubble + notification), adopt(), the heap based node limit,
 * the node budget of the scans kept in memory, and the disk copy of finished results ([StorageStore]).
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
class Scan(val id: String, val dir: File, val label: String, usedBytes: Long) {
    /** Used space of the volume (0 = unknown); only feeds the percentage and "System / other". A remote scan learns it while it runs. */
    @Volatile var usedBytes: Long = usedBytes
    @Volatile var state = "run"          // run | done | cancel | error
    @Volatile var error: String? = null
    @Volatile var cancel = false
    val files = AtomicLong()             // live counters
    val dirs = AtomicLong()
    val bytes = AtomicLong()
    val denied = AtomicInteger()
    @Volatile var cur = ""               // folder being read now (updated about 6 times a second)
    @Volatile var t0 = System.currentTimeMillis()   // var: a result loaded from disk sets it from the saved duration
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
    // part 9b
    /** The scan ran while "All files access" was off (Android 11+): most folders were hidden, so the result is only partial. */
    @Volatile var limited = false
    /** The scan reads the file list of a ZIP / RAR (sizes are the unpacked sizes); [dir] is the archive file itself. Nothing in it can be opened or deleted from the analyzer. */
    @Volatile var arc = false
    /** The scan reads another device (LANShare phone / SMB / FTP / SFTP): read-only like an archive, [dir] is only a key ([StorageScan.remoteKey]), nothing is saved to disk. */
    @Volatile var remote = false
    @Volatile var dev = "local"
    /** The memory guard stopped keeping single files for some folders (they are only counted in the folder's "small files" node; totals stay exact). */
    @Volatile var truncated = false
    /** Result read back from the disk copy ([StorageStore]) instead of scanned in this session. */
    @Volatile var loaded = false
    /** Bytes taken out of the tree by deletes in the analyzer (they are in the recycle bin, still on the volume): "System / other" = used - (root.size + removed). Written under synchronized(scan). */
    @Volatile var removed = 0L
    /** Kept-node budget of this scan (memory guard), set at start from the heap size. */
    @Volatile internal var maxNodes = StorageScan.MAX_NODES
    /** The progress bubble / notification entry of this scan ([Jobs.all] key "sscan:<id>"); null for a loaded result. */
    @Volatile internal var job: Job? = null
    @Volatile internal var saveDirty = false
    internal val saving = java.util.concurrent.atomic.AtomicBoolean()

    /** 0..99 while running (scanned bytes / used bytes of the volume; reaches 100 only when done), -1 when the used size is unknown. */
    fun percent(): Int {
        if (state == "done") return 100
        if (usedBytes <= 0) return -1
        return minOf(99L, bytes.get() * 100 / usedBytes).toInt()
    }

    fun millis(): Long = (if (t1 > 0) t1 else System.currentTimeMillis()) - t0

    /** Bytes per category of the whole scan (empty array of zeros until done). */
    fun catBytes(): LongArray = root?.cats?.copyOf() ?: LongArray(SCat.COUNT)

    /** Files per category of the whole scan (live while running). Part 9a: NOT adjusted after a folder delete (the tree keeps bytes per category, not file counts, and the "small files" nodes do not know their categories); nothing in the UI reads it once a scan is done. */
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
    const val FILES_PER_DIR = 1_000_000 // effectively unlimited: every file is its own node (the "agg" fold only remains as the low-memory guard)
    const val TOP_FILES = 100          // largest files of the whole scan
    const val MAX_NODES = 400_000      // after this many kept file nodes new files are only counted (memory guard)
    const val DENIED_SAMPLE = 20
    private const val THREADS = 4
    private const val KEEP_SCANS = 3   // finished scans kept in memory ...
    private const val NODE_BUDGET = 700_000   // ... but only while their kept nodes together stay below this (the newest scan is always kept)
    const val JOB_PREFIX = "sscan:"

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
        s.limited = Core.storageOk == false
        s.maxNodes = nodeLimit()
        // progress bubble + foreground notification: the scan shows up as a normal job (Cancel there sets the same flag)
        val j = Job("Analyzing " + (if (label.isEmpty()) "storage" else label))
        j.id = JOB_PREFIX + s.id; j.bytes = true; j.total = if (usedBytes > 0) usedBytes else 0L
        s.job = j
        Jobs.all[j.id] = j
        s.pending.incrementAndGet()
        pool.execute { task(s, root, dir) }
        return s
    }

    /** Starts an "analysis" of the archive at virtual path [av] (e.g. "/Download/a.zip"): the tree is built from the archive's file list, nothing is unpacked. */
    fun startArchive(av: String, label: String): Scan {
        evict()
        val f = Core.local.real(av)
        val s = Scan(UUID.randomUUID().toString().replace("-", "").take(8), f, label, 0L)
        s.arc = true
        all[s.id] = s
        val root = SNode("", true, null)
        s.maxNodes = nodeLimit()
        s.pending.incrementAndGet()
        Thread(null, {
            try {
                val ix = ArcStore.index(Core.local, av)
                arcFolder(s, ix, "", root)
            } catch (t: Throwable) {
                if (s.error == null) s.error = if (t is OutOfMemoryError) "Out of memory" else errText(t)
                s.cancel = true
            } finally {
                if (s.pending.decrementAndGet() == 0) finishAsync(s, root)
            }
        }, "sscan-arc", 16L shl 20).also { it.isDaemon = true }.start()
        return s
    }

    /** One folder of an archive (key "" = the archive root): same node layout as [task] makes for a real folder. */
    private fun arcFolder(s: Scan, ix: ArcIndex, key: String, node: SNode) {
        if (s.cancel) return
        val entries = ix.kids[key] ?: emptyList<AEntry>()
        s.dirs.incrementAndGet()
        val keepMax = if (s.nodes.get() > s.maxNodes || lowMemory()) 0 else FILES_PER_DIR
        val heap = PriorityQueue<SNode>(minOf(keepMax, 1024) + 1) { a, b -> java.lang.Long.compare(a.size, b.size) }
        val subs = ArrayList<SNode>()
        val subKeys = ArrayList<String>()
        val cats = LongArray(SCat.COUNT)
        val catN = IntArray(SCat.COUNT)
        var ownBytes = 0L
        var ownFiles = 0L
        for (e in entries) {
            if (e.dir) { subs.add(SNode(e.name, true, node)); subKeys.add(e.path); continue }
            val size = maxOf(e.size, 0L)
            val c = SCat.of(e.name)
            ownFiles++; ownBytes += size; cats[c] += size; catN[c]++
            if (keepMax > 0 && (heap.size < keepMax || size > heap.peek().size)) {
                heap.add(SNode(e.name, false, node).also { it.size = size; it.files = 1; it.cat = c })
                if (heap.size > keepMax) heap.poll()
            }
            if (size > 0) offerTop(s, e.name, node, size, c)
        }
        if (keepMax == 0 && ownFiles > 0) s.truncated = true
        s.files.addAndGet(ownFiles); s.bytes.addAndGet(ownBytes)
        for (c in 0 until SCat.COUNT) if (catN[c] > 0) s.catF.addAndGet(c, catN[c].toLong())
        node.size = ownBytes; node.files = ownFiles
        if (ownFiles > 0) node.cats = cats
        val kept = heap.size.toLong()
        var keptBytes = 0L
        for (k in heap) keptBytes += k.size
        val restFiles = ownFiles - kept
        val arr = ArrayList<SNode>(kept.toInt() + 1 + subs.size)
        arr.addAll(heap)
        if (restFiles > 0) arr.add(SNode("", false, node).also { it.agg = true; it.size = ownBytes - keptBytes; it.files = restFiles })
        arr.addAll(subs)
        node.kids = arr.toTypedArray()
        s.nodes.addAndGet(arr.size)
        for (j in subs.indices) arcFolder(s, ix, subKeys[j], subs[j])
    }


    // ------------------------------------------------------------------ other devices (read-only): LANShare phones, SMB, FTP, SFTP
    /** Stand-in "folder" that identifies a remote scan for [latest] / [running] (never touched on disk). */
    fun remoteKey(dev: String, vpath: String): File = File("/.remote/" + dev.replace('/', '_') + vnorm(vpath))

    /**
     * Starts an analysis of folder [vpath] of device [dev] (a peer id, "smb:..." or "ftp:...") and returns at once. Method per connection type:
     *  - LANShare phone with this feature: the phone scans ITSELF (native speed), this phone only polls and downloads the finished tree (one gzip file);
     *  - LANShare phone of an older build: its server-side walk (one request) is folded into the tree here;
     *  - SMB: folders are listed in parallel (8 requests on the one session); FTP / SFTP: parallel on the 4 pooled connections.
     * The result has the same tree as a local scan, so the screen, treemap and lists work unchanged; open / share / delete are off (Scan.remote).
     */
    fun startRemote(dev: String, vpath: String, label: String): Scan {
        evict()
        val v = vnorm(vpath)
        val s = Scan(UUID.randomUUID().toString().replace("-", "").take(8), remoteKey(dev, v), label, 0L)
        s.remote = true; s.dev = dev
        all[s.id] = s
        s.maxNodes = nodeLimit()
        val j = Job("Analyzing " + (if (label.isEmpty()) "device" else label))
        j.id = JOB_PREFIX + s.id; j.bytes = true; j.total = 0L
        s.job = j
        Jobs.all[j.id] = j
        Thread(null, { runRemote(s, dev, v) }, "sscan-remote", 16L shl 20).also { it.isDaemon = true }.start()
        return s
    }

    private fun runRemote(s: Scan, dev: String, v: String) {
        val root = SNode("", true, null)
        try {
            if (v == "/") try {   // used space feeds the percentage / "System / other"
                Jobs.ep(dev).space(v)?.let { (free, total) -> if (total > 0) { s.usedBytes = maxOf(0L, total - free); s.job?.total = s.usedBytes } }
            } catch (_: Exception) {}
            if (dev.startsWith("smb:") || dev.startsWith("ftp:")) {
                val ep = Jobs.ep(dev)
                val pool = Executors.newFixedThreadPool(if (dev.startsWith("smb:")) 8 else 4) { r -> Thread(null, r, "sscan-r", 2L shl 20).also { it.isDaemon = true } }
                s.pending.incrementAndGet()
                pool.execute { rtask(s, root, v, ep, pool, 0) }
                return   // the last task finishes the scan
            }
            val peer = Core.disc.get(dev) ?: throw java.io.IOException("that device is offline")
            val rfs = RemoteFs(peer)
            when (peerScan(s, rfs, v)) {
                1 -> { finishLoaded(s); return }
                2 -> {}                           // cancelled / failed: finishAsync below reports it
                else -> walkScan(s, rfs, v, root) // older build: server-side walk
            }
        } catch (t: Throwable) {
            if (s.error == null) s.error = if (t is OutOfMemoryError) "Out of memory" else errText(t)
            s.cancel = true
        }
        finishAsync(s, root)
    }

    /** 0 = the phone has no remote-scan route (older build), 1 = its finished tree was copied into [s], 2 = cancelled / failed ([s].error or [s].cancel set). */
    private fun peerScan(s: Scan, rfs: RemoteFs, v: String): Int {
        val st = try { org.json.JSONObject(String(rfs.rpc("sscan", mapOf("path" to v, "rescan" to "1")), Charsets.UTF_8)) } catch (_: NotFound) { return 0 }
        val rid = st.getString("id")
        while (true) {
            mirror(s)
            if (s.cancel) { try { rfs.rpc("sscancancel", mapOf("id" to rid)) } catch (_: Exception) {}; return 2 }
            val p = org.json.JSONObject(String(rfs.rpc("sscanp", mapOf("id" to rid)), Charsets.UTF_8))
            s.files.set(p.optLong("files")); s.dirs.set(p.optLong("dirs")); s.bytes.set(p.optLong("bytes"))
            s.cur = p.optString("cur"); mirror(s)
            when (p.optString("state")) {
                "done" -> break
                "error" -> { s.error = p.optString("error").ifEmpty { "The scan failed on the device" }; s.cancel = true; return 2 }
                "cancel" -> { s.error = "The scan was cancelled on the device"; s.cancel = true; return 2 }
            }
            Thread.sleep(400)
        }
        s.cur = "Downloading the result"
        val d = StorageStore.decode(rfs.rpcBig("sscandl", mapOf("id" to rid)), s.dir) ?: throw java.io.IOException("could not read the result of the device")
        s.files.set(d.files.get()); s.dirs.set(d.dirs.get()); s.bytes.set(d.bytes.get()); s.denied.set(d.denied.get())
        s.deniedSample.addAll(d.deniedSample)
        for (c in 0 until SCat.COUNT) s.catF.set(c, d.catF.get(c))
        s.nodes.set(d.nodes.get()); s.limited = d.limited; s.truncated = d.truncated
        s.root = d.root; s.top = d.top
        return 1
    }

    private fun finishLoaded(s: Scan) {
        s.t1 = System.currentTimeMillis(); s.cur = ""; s.state = "done"
        s.job?.let { j -> j.cur = ""; j.done = j.total; j.end = s.t1; j.state = "done" }
    }

    /** One folder of an SMB / FTP / SFTP share: list it, fold it into the node, queue its sub-folders (depth cap = symlink loops on FTP / SFTP). */
    private fun rtask(s: Scan, node: SNode, v: String, ep: Endpoint, pool: java.util.concurrent.ExecutorService, depth: Int) {
        try {
            if (s.cancel) return
            val now = System.currentTimeMillis()
            if (now - s.tick > 150) { s.tick = now; s.cur = v; mirror(s) }
            val items = try {
                if (depth > 48) throw java.io.IOException("too deep")
                ep.lsFast(v)
            } catch (e: Exception) {
                if (node.parent == null) throw e
                node.denied = true
                if (s.denied.incrementAndGet() <= DENIED_SAMPLE) s.deniedSample.add(node.path())
                return
            }
            for (sub in foldDir(s, node, items)) {
                if (s.cancel) return
                s.pending.incrementAndGet()
                val sv = vjoin(v, sub.name)
                pool.execute { rtask(s, sub, sv, ep, pool, depth + 1) }
            }
        } catch (t: Throwable) {
            if (s.error == null) s.error = if (t is OutOfMemoryError) "Out of memory" else errText(t)
            s.cancel = true
        } finally {
            if (s.pending.decrementAndGet() == 0) { pool.shutdown(); finishAsync(s, rootOf(node)) }
        }
    }

    /** Older LANShare build: its whole tree comes in one /p/walk answer; folded into nodes here without further requests. */
    private fun walkScan(s: Scan, rfs: RemoteFs, v: String, root: SNode) {
        val kids = HashMap<String, ArrayList<Item>>()
        for (w in rfs.walk(v)) {
            if (s.cancel) return
            if (w.rel.isEmpty()) continue
            if (w.skip) { s.denied.incrementAndGet(); continue }
            val i = w.rel.lastIndexOf('/')
            kids.getOrPut(if (i < 0) "" else w.rel.substring(0, i)) { ArrayList() }.add(Item(w.rel.substring(i + 1), w.dir, w.size, 0L))
        }
        val st = ArrayDeque<Pair<SNode, String>>()
        st.addLast(root to "")
        var n = 0
        while (st.isNotEmpty()) {
            if (s.cancel) return
            val (node, key) = st.removeLast()
            for (sub in foldDir(s, node, kids[key] ?: emptyList())) st.addLast(sub to (if (key.isEmpty()) sub.name else key + "/" + sub.name))
            if (++n % 200 == 0) { s.cur = key; mirror(s) }
        }
    }

    /** Same node layout as [task] / [arcFolder] for one listed folder; returns its sub-folder nodes (their entries are folded by the caller). */
    private fun foldDir(s: Scan, node: SNode, items: List<Item>): ArrayList<SNode> {
        s.dirs.incrementAndGet()
        val keepMax = if (s.nodes.get() > s.maxNodes || lowMemory()) 0 else FILES_PER_DIR
        val heap = PriorityQueue<SNode>(minOf(keepMax, 1024) + 1) { a, b -> java.lang.Long.compare(a.size, b.size) }
        val subs = ArrayList<SNode>()
        val cats = LongArray(SCat.COUNT)
        val catN = IntArray(SCat.COUNT)
        var ownBytes = 0L
        var ownFiles = 0L
        for (e in items) {
            if (e.dir) { subs.add(SNode(e.name, true, node)); continue }
            val size = maxOf(e.size, 0L)
            val c = SCat.of(e.name)
            ownFiles++; ownBytes += size; cats[c] += size; catN[c]++
            if (keepMax > 0 && (heap.size < keepMax || size > heap.peek().size)) {
                heap.add(SNode(e.name, false, node).also { it.size = size; it.files = 1; it.cat = c })
                if (heap.size > keepMax) heap.poll()
            }
            if (size > 0) offerTop(s, e.name, node, size, c)
        }
        if (keepMax == 0 && ownFiles > 0) s.truncated = true
        s.files.addAndGet(ownFiles); s.bytes.addAndGet(ownBytes)
        for (c in 0 until SCat.COUNT) if (catN[c] > 0) s.catF.addAndGet(c, catN[c].toLong())
        node.size = ownBytes; node.files = ownFiles
        if (ownFiles > 0) node.cats = cats
        val kept = heap.size.toLong()
        var keptBytes = 0L
        for (k in heap) keptBytes += k.size
        val restFiles = ownFiles - kept
        val arr = ArrayList<SNode>(kept.toInt() + 1 + subs.size)
        arr.addAll(heap)
        if (restFiles > 0) arr.add(SNode("", false, node).also { it.agg = true; it.size = ownBytes - keptBytes; it.files = restFiles })
        arr.addAll(subs)
        node.kids = arr.toTypedArray()
        s.nodes.addAndGet(arr.size)
        return subs
    }

    /** A scan read back from disk ([StorageStore.load]) becomes the newest finished scan of its folder. */
    fun adopt(s: Scan) { evict(); all[s.id] = s }

    /** Kept-node limit from the heap size (about 700 bytes per kept node with its name, array slot and the Largest copy), between 150,000 and [MAX_NODES]. */
    private fun nodeLimit(): Int = (Runtime.getRuntime().maxMemory() / 700L).coerceIn(150_000L, MAX_NODES.toLong()).toInt()

    /** Less than 32 MB of heap left: stop keeping single files, only count them. */
    private fun lowMemory(): Boolean {
        val r = Runtime.getRuntime()
        return r.maxMemory() - (r.totalMemory() - r.freeMemory()) < (32L shl 20)
    }

    /** Copies the live numbers to the job entry (and the job's Cancel to the scan). Called about 6 times a second from the walking threads. */
    private fun mirror(s: Scan) {
        val j = s.job ?: return
        if (j.cancel) s.cancel = true
        j.cur = s.cur
        val t = j.total
        j.done = if (t > 1L) minOf(s.bytes.get(), t * 99 / 100) else s.bytes.get()   // never 100 % before the end
    }

    /** Keeps the newest [KEEP_SCANS] finished scans while their kept nodes together fit [NODE_BUDGET] (the newest is always kept), drops older ones (a finished tree can hold tens of MB). A screen that still shows a dropped scan keeps its own reference. */
    private fun evict() {
        val done = all.values.filter { it.state != "run" }.sortedByDescending { it.t1 }
        var budget = NODE_BUDGET
        for (i in done.indices) {
            val d = done[i]
            val cost = if (d.state == "done") d.nodes.get() else 0
            if (i < KEEP_SCANS && (i == 0 || cost <= budget)) budget -= cost
            else { all.remove(d.id); Jobs.all.remove(JOB_PREFIX + d.id) }
        }
    }

    // ------------------------------------------------------------------ one folder = one task
    private fun task(s: Scan, node: SNode, dir: File) {
        try {
            if (s.cancel) return
            val now = System.currentTimeMillis()
            if (now - s.tick > 150) { s.tick = now; s.cur = dir.path; mirror(s) }
            val entries = dir.listFiles()
            if (entries == null) {
                node.denied = true
                if (s.denied.incrementAndGet() <= DENIED_SAMPLE) s.deniedSample.add(node.path())
                return
            }
            s.dirs.incrementAndGet()
            val modern = Build.VERSION.SDK_INT >= 26
            val keepMax = if (s.nodes.get() > s.maxNodes || lowMemory()) 0 else FILES_PER_DIR   // memory guard (part 9b: limit from the heap size + live check)
            val heap = PriorityQueue<SNode>(minOf(keepMax, 1024) + 1) { a, b -> java.lang.Long.compare(a.size, b.size) }   // this folder's largest files
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
                if (keepMax > 0 && (heap.size < keepMax || size > heap.peek().size)) {
                    val fn = SNode(f.name, false, node).also { it.size = size; it.files = 1; it.cat = c }
                    heap.add(fn)
                    if (heap.size > keepMax) heap.poll()
                }
                if (size > 0) offerTop(s, f.name, node, size, c)
            }
            if (keepMax == 0 && ownFiles > 0) s.truncated = true
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
            val st = if (s.error != null) "error" else if (s.cancel) "cancel" else "done"
            if (st != "done") synchronized(s.topLock) { s.topHeap.clear() }   // nothing is kept of a cancelled / failed scan
            s.state = st
            s.cur = ""
            s.job?.let { j ->
                j.cur = ""; if (st == "done") j.done = j.total
                if (st == "error") j.error = s.error
                j.end = s.t1; j.state = st
            }
            if (st == "done" && !s.arc && !s.remote) StorageStore.saveAsync(s)   // remember the result across process death
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
        // 3 + 4. emptied folders dropped, kids re-sorted
        val near = dropEmptyAndResort(p)
        // 5. the largest-files list and the live counters
        if (s.top.isNotEmpty()) s.top = s.top.filter { !(it.parent === p && it.name == n.name) }
        s.files.addAndGet(-1L); s.bytes.addAndGet(-size); s.catF.addAndGet(cat, -1L)
        s.removed += size
        return near
    }

    /** Folders that became empty are dropped (never the root), then sizes changed: kids biggest first again along the chain. Returns the nearest folder still in the tree. */
    private fun dropEmptyAndResort(p: SNode): SNode {
        var near: SNode = p
        while (true) {
            val up = near.parent ?: break
            if (near.size != 0L || near.files != 0L || near.denied) break
            val dead = near
            up.kids = up.kids.filter { it !== dead }.toTypedArray()
            near = up
        }
        var b: SNode? = p
        while (b != null) {
            if (b.kids.size > 1) b.kids = b.kids.sortedByDescending { it.size }.toTypedArray()
            b = b.parent
        }
        return near
    }

    /** Part 8c: a whole folder [n] left the storage (moved to the bin). Detaches it, subtracts its size / files / folders / categories up the chain, drops emptied ancestors, removes every Largest-list entry below it. Returns the nearest folder still in the tree; null = not a folder of this scan (nothing changed). */
    fun removeNode(s: Scan, n: SNode): SNode? {
        synchronized(s) { return removeNodeLocked(s, n) }
    }

    private fun removeNodeLocked(s: Scan, n: SNode): SNode? {
        if (s.root == null || !n.dir || n.agg) return null
        val p = n.parent ?: return null                       // the scan root cannot be removed
        if (p.kids.none { it === n }) return null             // already detached
        val size = n.size
        val files = n.files
        val folders = 1L + n.folders
        val cats = n.cats
        p.kids = p.kids.filter { it !== n }.toTypedArray()
        var a: SNode? = p
        while (a != null) {
            a.size = maxOf(0L, a.size - size)
            a.files = maxOf(0L, a.files - files)
            a.folders = maxOf(0L, a.folders - folders)
            val ac = a.cats
            if (ac != null && cats != null) for (c in 0 until SCat.COUNT) ac[c] = maxOf(0L, ac[c] - cats[c])
            a = a.parent
        }
        val near = dropEmptyAndResort(p)
        // Largest list: drop every file whose parent chain contains n (identity)
        if (s.top.isNotEmpty()) s.top = s.top.filter { t ->
            var x: SNode? = t.parent
            while (x != null && x !== n) x = x.parent
            x == null
        }
        s.files.addAndGet(-files); s.bytes.addAndGet(-size)   // catF is bytes-only per node: left alone (used while running only)
        s.removed += size
        return near
    }

    /** Part 9a: how many unreadable (denied) folders lie inside [n] (itself included). Their content was never scanned, so the size shown for [n] is only a lower bound. */
    fun deniedBelow(n: SNode): Int {
        var c = if (n.dir && n.denied) 1 else 0
        for (k in n.kids) if (k.dir) c += deniedBelow(k)
        return c
    }

    /**
     * Part 9a (batch delete): [list] without duplicates and without every node that lies inside another node of the list
     * (a file inside a selected folder goes with the folder) and without a second node for the same file (kept node + its Largest-list copy). The agg node and the scan root are dropped. Order kept.
     */
    fun pruneNested(list: List<SNode>): List<SNode> {
        val set = java.util.IdentityHashMap<SNode, Boolean>()
        for (n in list) if (!n.agg && n.parent != null) set[n] = true
        val done = java.util.IdentityHashMap<SNode, Boolean>()
        val twins = HashSet<String>()                       // a kept file node and its copy from the Largest list stand for the same file: only one of them
        val out = ArrayList<SNode>()
        for (n in list) {
            if (!set.containsKey(n) || done.containsKey(n)) continue
            done[n] = true
            if (!n.dir && !twins.add(System.identityHashCode(n.parent).toString() + "/" + n.name)) continue
            var x: SNode? = n.parent
            var nested = false
            while (x != null) { if (set.containsKey(x)) { nested = true; break }; x = x.parent }
            if (!nested) out.add(n)
        }
        return out
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
