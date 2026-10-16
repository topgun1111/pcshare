package com.lanshare.app.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.IdentityHashMap
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * STORAGE ANALYZER - part 9b: "remember the last result". A finished scan is written to the app's cache folder (gzip, one file per scanned folder,
 * the newest [KEEP_FILES] files are kept) so the pill shows the last result at once after the process was killed or the phone restarted;
 * the screen says how old it is and Rescan refreshes it. A result older than [MAX_AGE_MS] is not used.
 *
 * File format (all through DataOutputStream, then gzip): magic, version, scanned path, label, used bytes, end time, duration, files, dirs, bytes, denied,
 * denied sample, limited, truncated, removed, files per category, kept-node count, the tree in pre-order (flags, name, size, files, folders | category,
 * category bytes, kid count, kids), the Largest list as (index of the parent folder in the pre-order of folders, name, size, category).
 *
 * Writing: the tree is serialized into memory under synchronized(scan) (the screen patches the tree under the same lock after a delete; this takes a
 * fraction of a second), then compressed and written outside the lock, to a .tmp file that is renamed. [saveAsync] is called when a scan finishes and
 * after every delete in the analyzer; several calls in a row are merged into one more write. Reading runs on a big-stack thread (deep folders).
 * Everything is best effort: any failure only means "no saved result". NOT compiled / NOT device-tested.
 */
object StorageStore {
    private const val MAGIC = 0x53435331
    private const val VERSION = 1
    private const val KEEP_FILES = 4
    const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    private const val STACK = 16L shl 20

    private fun cacheDir(): File? = try { Core.appCtx?.let { File(it.cacheDir, "sscan").also { d -> d.mkdirs() } } } catch (_: Throwable) { null }

    private fun fileFor(dir: File): File? {
        val d = cacheDir() ?: return null
        val p = dir.path
        return File(d, "s" + Integer.toHexString(p.hashCode()) + "_" + p.length + ".gz")
    }

    /** A recent saved result of exactly this folder may exist (cheap check, nothing is read). */
    fun has(dir: File): Boolean {
        val f = fileFor(dir) ?: return false
        return f.isFile && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS
    }

    // ------------------------------------------------------------------ write
    /** Save the finished scan [s] in the background (merged when called repeatedly). Does nothing for a scan that is not done. */
    fun saveAsync(s: Scan) {
        if (s.state != "done" || s.root == null) return
        s.saveDirty = true
        if (!s.saving.compareAndSet(false, true)) return
        Thread(null, {
            try {
                while (s.saveDirty) {
                    s.saveDirty = false
                    try { write(s) } catch (_: Throwable) {}
                }
            } finally { s.saving.set(false) }
            if (s.saveDirty) saveAsync(s)   // a delete came in between the last check and the flag reset
        }, "sscan-save", STACK).also { it.isDaemon = true }.start()
    }

    private fun write(s: Scan) {
        val f = fileFor(s.dir) ?: return
        val bos = ByteArrayOutputStream(1 shl 20)
        synchronized(s) {
            val root = s.root ?: return
            val o = DataOutputStream(bos)
            o.writeInt(MAGIC); o.writeInt(VERSION)
            o.writeUTF(s.dir.path); o.writeUTF(s.label)
            o.writeLong(s.usedBytes); o.writeLong(s.t1); o.writeLong(s.t1 - s.t0)
            o.writeLong(s.files.get()); o.writeLong(s.dirs.get()); o.writeLong(s.bytes.get()); o.writeInt(s.denied.get())
            val ds = s.deniedSample.toList()
            o.writeInt(ds.size); for (p in ds) o.writeUTF(p)
            o.writeBoolean(s.limited); o.writeBoolean(s.truncated); o.writeLong(s.removed)
            for (c in 0 until SCat.COUNT) o.writeLong(s.catF.get(c))
            o.writeInt(s.nodes.get())
            val idx = IdentityHashMap<SNode, Int>()
            writeNode(o, root, idx)
            val tops = ArrayList<Pair<Int, SNode>>()
            for (t in s.top) { val p = t.parent ?: continue; val pi = idx[p] ?: continue; tops.add(pi to t) }
            o.writeInt(tops.size)
            for ((pi, t) in tops) { o.writeInt(pi); o.writeUTF(t.name); o.writeLong(t.size); o.writeByte(t.cat) }
            o.flush()
        }
        val tmp = File(f.path + ".tmp")
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp), 65536)).use { bos.writeTo(it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        prune()
    }

    private fun writeNode(o: DataOutputStream, n: SNode, idx: IdentityHashMap<SNode, Int>) {
        if (n.dir) idx[n] = idx.size
        val cats = n.cats
        var fl = 0
        if (n.dir) fl = fl or 1
        if (n.agg) fl = fl or 2
        if (n.denied) fl = fl or 4
        if (cats != null) fl = fl or 8
        o.writeByte(fl); o.writeUTF(n.name); o.writeLong(n.size); o.writeLong(n.files)
        if (n.dir) o.writeLong(n.folders) else o.writeByte(n.cat)
        if (cats != null) for (c in 0 until SCat.COUNT) o.writeLong(cats[c])
        if (n.dir) { o.writeInt(n.kids.size); for (k in n.kids) writeNode(o, k, idx) }
    }

    /** Keeps the newest [KEEP_FILES] saved results. */
    private fun prune() {
        val d = cacheDir() ?: return
        val l = d.listFiles { x -> x.name.endsWith(".gz") }?.sortedByDescending { it.lastModified() } ?: return
        for (i in KEEP_FILES until l.size) l[i].delete()
    }

    // ------------------------------------------------------------------ read
    /** The saved result of [dir] as a finished [Scan] (not yet registered: call [StorageScan.adopt]), or null. Blocks: call it off the main thread. */
    fun load(dir: File): Scan? {
        val f = fileFor(dir) ?: return null
        if (!f.isFile) return null
        var res: Scan? = null
        val t = Thread(null, { res = try { read(f, dir) } catch (_: Throwable) { null } }, "sscan-load", STACK)
        t.isDaemon = true; t.start(); t.join()
        return res
    }

    private fun read(f: File, dir: File): Scan? {
        return DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(f), 65536), 65536)).use { i ->
            if (i.readInt() != MAGIC || i.readInt() != VERSION) { f.delete(); return@use null }
            if (i.readUTF() != dir.path) return@use null            // hash clash with another folder
            val label = i.readUTF()
            val used = i.readLong(); val t1 = i.readLong(); val dur = i.readLong()
            if (System.currentTimeMillis() - t1 > MAX_AGE_MS) { f.delete(); return@use null }
            val s = Scan(UUID.randomUUID().toString().replace("-", "").take(8), dir, label, used)
            s.t1 = t1; s.t0 = t1 - dur
            s.files.set(i.readLong()); s.dirs.set(i.readLong()); s.bytes.set(i.readLong()); s.denied.set(i.readInt())
            val nd = i.readInt()
            for (k in 0 until nd) s.deniedSample.add(i.readUTF())
            s.limited = i.readBoolean(); s.truncated = i.readBoolean(); s.removed = i.readLong()
            for (c in 0 until SCat.COUNT) s.catF.set(c, i.readLong())
            s.nodes.set(i.readInt())
            val folders = ArrayList<SNode>()
            val root = readNode(i, null, folders)
            val n = i.readInt()
            val top = ArrayList<SNode>(n)
            for (k in 0 until n) {
                val pi = i.readInt(); val name = i.readUTF(); val size = i.readLong(); val cat = i.readUnsignedByte()
                val p = folders.getOrNull(pi) ?: continue
                top.add(SNode(name, false, p).also { it.size = size; it.files = 1; it.cat = cat })
            }
            s.root = root; s.top = top; s.loaded = true; s.state = "done"
            s
        }
    }

    private fun readNode(i: DataInputStream, parent: SNode?, folders: ArrayList<SNode>): SNode {
        val fl = i.readUnsignedByte()
        val dir = (fl and 1) != 0
        val n = SNode(i.readUTF(), dir, parent)
        n.agg = (fl and 2) != 0
        n.denied = (fl and 4) != 0
        n.size = i.readLong(); n.files = i.readLong()
        if (dir) { n.folders = i.readLong(); folders.add(n) } else n.cat = i.readUnsignedByte()
        if ((fl and 8) != 0) n.cats = LongArray(SCat.COUNT) { i.readLong() }
        if (dir) {
            val k = i.readInt()
            if (k > 0) n.kids = Array(k) { readNode(i, n, folders) }
        }
        return n
    }
}
