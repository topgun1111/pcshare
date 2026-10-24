package com.lanshare.app.core.usearch

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Packed in-memory index of every file and folder name (decision D1). Pure JVM, no android imports.
 *
 * Entries live in "slots" 0 until slotCount(); the slot number is the entry id. Ids are never reused after remove(),
 * so other stores can key on them. compact() renumbers (and bumps epoch()); everybody holding ids must remap.
 * A root entry has no parent; its name is the full path of the volume (for example the internal storage folder).
 * Children are kept in a linked list (firstChild / nextSibling), names in two byte pools: original UTF-8 and folded UTF-8 (Fold.fold, diacritics stripped).
 * The folded pool is a superset filter: when the Diacritics toggle is on, Part 2 first filters on the folded text and then verifies on the original name.
 *
 * Thread safety: many readers, one writer at a time (ReentrantReadWriteLock). Scans take the read lock per call, so long scans must be chunked (see scanRange).
 */
class NameIndex(initialCapacity: Int = 1024) {
    companion object {
        const val F_DIR = 1
        const val F_DEAD = 2
        const val NONE = -1
        private const val MAX_SLOTS = 20_000_000

        /** Reads what writeTo wrote. Throws IOException (or EOFException) on anything that does not look right. */
        fun readFrom(inp: DataInputStream): NameIndex {
            val epoch = inp.readInt()
            val slots = inp.readInt()
            val live = inp.readInt()
            if (slots < 0 || slots > MAX_SLOTS || live < 0 || live > slots) throw IOException("bad counts")
            val ix = NameIndex(minOf(slots, 1 shl 20) + 16)   // grows on demand: a corrupt count must not allocate gigabytes up front
            ix.epoch = epoch
            var liveSeen = 0
            for (id in 0 until slots) {
                val p = inp.readInt()
                val fl = inp.readByte().toInt()
                val sz = inp.readLong()
                val mt = inp.readLong()
                val len = inp.readUnsignedShort()
                ix.ensureSlots(id + 1)
                ix.slots = id + 1
                ix.parent[id] = p
                ix.flags[id] = fl.toByte()
                ix.size[id] = sz
                ix.mtime[id] = mt
                ix.firstChild[id] = NONE
                ix.nextSib[id] = NONE
                if (len > 0) {
                    ix.ensurePool(len)
                    inp.readFully(ix.pool, ix.poolUsed, len)
                    ix.nOff[id] = ix.poolUsed
                    ix.nLen[id] = len
                    ix.poolUsed += len
                    val fb = Fold.fold(String(ix.pool, ix.nOff[id], len, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    ix.ensureFPool(fb.size)
                    System.arraycopy(fb, 0, ix.fpool, ix.fpoolUsed, fb.size)
                    ix.fOff[id] = ix.fpoolUsed
                    ix.fLen[id] = fb.size
                    ix.fpoolUsed += fb.size
                }
                if ((fl and F_DEAD) == 0) { liveSeen++; if ((fl and F_DIR) != 0) ix.dirs++ }
            }
            if (liveSeen != live) throw IOException("live count mismatch")
            ix.live = liveSeen
            // link children (ids are visited in order, so every parent already has its slot)
            for (id in 0 until slots) {
                if ((ix.flags[id].toInt() and F_DEAD) != 0) continue
                val p = ix.parent[id]
                if (p == NONE) { ix.nextSib[id] = ix.rootFirst; ix.rootFirst = id }
                else {
                    if (p < 0 || p >= slots || (ix.flags[p].toInt() and (F_DEAD or F_DIR)) != F_DIR) throw IOException("bad parent")
                    ix.nextSib[id] = ix.firstChild[p]; ix.firstChild[p] = id
                }
            }
            return ix
        }
    }

    private val rw = ReentrantReadWriteLock()
    private var cap = maxOf(16, initialCapacity)
    private var parent = IntArray(cap)
    private var firstChild = IntArray(cap)
    private var nextSib = IntArray(cap)
    private var flags = ByteArray(cap)
    private var size = LongArray(cap)
    private var mtime = LongArray(cap)     // seconds since epoch
    private var nOff = IntArray(cap)
    private var nLen = IntArray(cap)
    private var fOff = IntArray(cap)
    private var fLen = IntArray(cap)
    private var pool = ByteArray(cap * 24)
    private var poolUsed = 0
    private var fpool = ByteArray(cap * 24)
    private var fpoolUsed = 0
    private var slots = 0
    private var live = 0
    private var dirs = 0
    private var rootFirst = NONE
    private var epoch = 0

    // ---------- locking helpers ----------
    private inline fun <T> rd(f: () -> T): T { val l = rw.readLock(); l.lock(); try { return f() } finally { l.unlock() } }
    private inline fun <T> wr(f: () -> T): T { val l = rw.writeLock(); l.lock(); try { return f() } finally { l.unlock() } }

    // ---------- growth (write lock held, or construction) ----------
    private fun ensureSlots(need: Int) {
        if (need <= cap) return
        var nc = cap
        while (nc < need) nc = if (nc < 1 shl 20) nc * 2 else nc + (nc shr 1)
        parent = parent.copyOf(nc); firstChild = firstChild.copyOf(nc); nextSib = nextSib.copyOf(nc)
        flags = flags.copyOf(nc); size = size.copyOf(nc); mtime = mtime.copyOf(nc)
        nOff = nOff.copyOf(nc); nLen = nLen.copyOf(nc); fOff = fOff.copyOf(nc); fLen = fLen.copyOf(nc)
        cap = nc
    }

    private fun ensurePool(extra: Int) {
        val need = poolUsed + extra
        if (need <= pool.size) return
        var nc = pool.size
        while (nc < need) nc = nc + (nc shr 1) + 64
        pool = pool.copyOf(nc)
    }

    private fun ensureFPool(extra: Int) {
        val need = fpoolUsed + extra
        if (need <= fpool.size) return
        var nc = fpool.size
        while (nc < need) nc = nc + (nc shr 1) + 64
        fpool = fpool.copyOf(nc)
    }

    private fun setNameU(id: Int, name: String) {
        val b = name.toByteArray(Charsets.UTF_8)
        val fb = Fold.fold(name).toByteArray(Charsets.UTF_8)
        ensurePool(b.size); ensureFPool(fb.size)
        System.arraycopy(b, 0, pool, poolUsed, b.size)
        System.arraycopy(fb, 0, fpool, fpoolUsed, fb.size)
        nOff[id] = poolUsed; nLen[id] = b.size; poolUsed += b.size
        fOff[id] = fpoolUsed; fLen[id] = fb.size; fpoolUsed += fb.size
    }

    private fun alive(id: Int) = id in 0 until slots && (flags[id].toInt() and F_DEAD) == 0

    private fun linkU(id: Int, p: Int) {
        parent[id] = p
        if (p == NONE) { nextSib[id] = rootFirst; rootFirst = id } else { nextSib[id] = firstChild[p]; firstChild[p] = id }
    }

    private fun unlinkU(id: Int) {
        val p = parent[id]
        var cur = if (p == NONE) rootFirst else firstChild[p]
        var prev = NONE
        while (cur != NONE && cur != id) { prev = cur; cur = nextSib[cur] }
        if (cur == NONE) return
        val nx = nextSib[id]
        if (prev == NONE) { if (p == NONE) rootFirst = nx else firstChild[p] = nx } else nextSib[prev] = nx
        nextSib[id] = NONE
    }

    private fun addU(p: Int, name: String, dir: Boolean, sz: Long, mt: Long): Int {
        ensureSlots(slots + 1)
        val id = slots++
        flags[id] = (if (dir) F_DIR else 0).toByte()
        size[id] = sz; mtime[id] = mt
        firstChild[id] = NONE; nextSib[id] = NONE
        setNameU(id, name)
        linkU(id, p)
        live++
        if (dir) dirs++
        return id
    }

    private fun killU(id: Int) {   // marks one entry dead (already unlinked or part of a removed subtree)
        if ((flags[id].toInt() and F_DIR) != 0) dirs--
        flags[id] = (flags[id].toInt() or F_DEAD).toByte()
        live--
    }

    private fun removeSubtreeU(id: Int): Int {
        var removed = 0
        val stack = ArrayList<Int>()
        stack.add(id)
        while (stack.isNotEmpty()) {
            val x = stack.removeAt(stack.size - 1)
            var c = firstChild[x]
            while (c != NONE) { stack.add(c); c = nextSib[c] }
            firstChild[x] = NONE
            killU(x)
            removed++
        }
        return removed
    }

    // ---------- mutation ----------
    /** Adds a volume root (name = full path). Returns its id. */
    fun addRoot(path: String): Int = wr { addU(NONE, path, true, 0L, 0L) }

    /** Adds a child below [parentId] (must be a live folder). Returns the new id. */
    fun add(parentId: Int, name: String, dir: Boolean, size: Long, mtimeSec: Long): Int = wr {
        if (!alive(parentId) || (flags[parentId].toInt() and F_DIR) == 0) throw IllegalArgumentException("bad parent $parentId")
        addU(parentId, name, dir, size, mtimeSec)
    }

    /** Removes an entry and everything below it. Returns the number of entries removed (0 if it was not live). */
    fun remove(id: Int): Int = wr {
        if (!alive(id)) 0 else { unlinkU(id); removeSubtreeU(id) }
    }

    /** Removes everything below a folder but keeps the folder. Returns the number removed. */
    fun removeChildren(id: Int): Int = wr {
        var n = 0
        if (alive(id)) {
            var c = firstChild[id]
            while (c != NONE) { val nx = nextSib[c]; n += removeSubtreeU(c); c = nx }
            firstChild[id] = NONE
        }
        n
    }

    fun rename(id: Int, newName: String): Boolean = wr {
        if (!alive(id)) false else { setNameU(id, newName); true }
    }

    /** Moves an entry below another live folder (optionally renaming it). Refuses roots, moves into itself / its own subtree, and non-folder targets. */
    fun move(id: Int, newParent: Int, newName: String? = null): Boolean = wr {
        if (!alive(id) || !alive(newParent) || parent[id] == NONE || (flags[newParent].toInt() and F_DIR) == 0) false
        else {
            var x = newParent
            var bad = false
            while (x != NONE) { if (x == id) { bad = true; break }; x = parent[x] }
            if (bad) false else {
                unlinkU(id); linkU(id, newParent)
                if (newName != null) setNameU(id, newName)
                true
            }
        }
    }

    fun update(id: Int, newSize: Long, newMtimeSec: Long): Boolean = wr {
        if (!alive(id)) false else { size[id] = newSize; mtime[id] = newMtimeSec; true }
    }

    /**
     * Drops dead slots and the unused name bytes. Renumbers: returns map[oldId] = newId (or -1), and bumps epoch().
     * Everything that stored ids (content store, result lists) must remap or be rebuilt.
     */
    fun compact(): IntArray = wr {
        val map = IntArray(slots) { NONE }
        var nn = 0
        for (i in 0 until slots) if ((flags[i].toInt() and F_DEAD) == 0) map[i] = nn++
        val ncap = maxOf(16, nn + 16)
        val np = IntArray(ncap); val nf = IntArray(ncap); val ns = IntArray(ncap)
        val nfl = ByteArray(ncap); val nsz = LongArray(ncap); val nmt = LongArray(ncap)
        val noff = IntArray(ncap); val nlen = IntArray(ncap); val nfo = IntArray(ncap); val nfln = IntArray(ncap)
        val npool = ByteArray(maxOf(64, poolUsed)); val nfpool = ByteArray(maxOf(64, fpoolUsed))
        var pu = 0; var fu = 0
        for (i in 0 until slots) {
            val j = map[i]
            if (j == NONE) continue
            np[j] = if (parent[i] == NONE) NONE else map[parent[i]]
            nfl[j] = flags[i]; nsz[j] = size[i]; nmt[j] = mtime[i]
            nf[j] = NONE; ns[j] = NONE
            System.arraycopy(pool, nOff[i], npool, pu, nLen[i]); noff[j] = pu; nlen[j] = nLen[i]; pu += nLen[i]
            System.arraycopy(fpool, fOff[i], nfpool, fu, fLen[i]); nfo[j] = fu; nfln[j] = fLen[i]; fu += fLen[i]
        }
        var nroot = NONE
        for (j in nn - 1 downTo 0) {   // link in reverse so siblings end up in ascending id order
            val p = np[j]
            if (p == NONE) { ns[j] = nroot; nroot = j } else { ns[j] = nf[p]; nf[p] = j }
        }
        parent = np; firstChild = nf; nextSib = ns; flags = nfl; size = nsz; mtime = nmt
        nOff = noff; nLen = nlen; fOff = nfo; fLen = nfln
        pool = npool; poolUsed = pu; fpool = nfpool; fpoolUsed = fu
        cap = ncap; slots = nn; rootFirst = nroot; epoch++
        map
    }

    // ---------- queries ----------
    fun count(): Int = rd { live }
    fun dirCount(): Int = rd { dirs }
    fun slotCount(): Int = rd { slots }
    fun deadCount(): Int = rd { slots - live }
    fun epoch(): Int = rd { epoch }
    fun isLive(id: Int): Boolean = rd { alive(id) }
    fun isDir(id: Int): Boolean = rd { alive(id) && (flags[id].toInt() and F_DIR) != 0 }
    fun parentOf(id: Int): Int = rd { if (id in 0 until slots) parent[id] else NONE }
    fun sizeOf(id: Int): Long = rd { if (id in 0 until slots) size[id] else 0L }
    fun mtimeOf(id: Int): Long = rd { if (id in 0 until slots) mtime[id] else 0L }
    fun nameOf(id: Int): String = rd { if (id in 0 until slots) String(pool, nOff[id], nLen[id], Charsets.UTF_8) else "" }
    fun foldedOf(id: Int): String = rd { if (id in 0 until slots) String(fpool, fOff[id], fLen[id], Charsets.UTF_8) else "" }

    /** Full path of an entry: root name + "/" + names. Empty string for an unknown id. */
    fun pathOf(id: Int): String = rd {
        if (!alive(id)) "" else {
            val chain = ArrayList<Int>()
            var x = id
            var guard = 0
            while (x != NONE && guard++ < 4096) { chain.add(x); x = parent[x] }
            val sb = StringBuilder()
            for (k in chain.indices.reversed()) {
                val c = chain[k]
                val nm = String(pool, nOff[c], nLen[c], Charsets.UTF_8)
                if (sb.isEmpty()) sb.append(nm) else { if (sb[sb.length - 1] != '/') sb.append('/'); sb.append(nm) }
            }
            sb.toString()
        }
    }

    fun findRoot(path: String): Int = rd {
        var r = rootFirst
        var found = NONE
        while (r != NONE) { if (nameEquals(r, path.toByteArray(Charsets.UTF_8))) { found = r; break }; r = nextSib[r] }
        found
    }

    /** Looks a full path up (case-sensitive, like the Linux file system). Longest matching root wins. -1 when absent. */
    fun find(path: String): Int = rd {
        var best = NONE
        var bestLen = -1
        var r = rootFirst
        while (r != NONE) {
            val rn = String(pool, nOff[r], nLen[r], Charsets.UTF_8).trimEnd('/')
            if ((path == rn || path.startsWith("$rn/")) && rn.length > bestLen) { best = r; bestLen = rn.length }
            r = nextSib[r]
        }
        if (best == NONE) NONE else {
            var cur = best
            val rest = path.substring(bestLen).split('/').filter { it.isNotEmpty() }
            for (comp in rest) {
                val cb = comp.toByteArray(Charsets.UTF_8)
                var c = firstChild[cur]
                while (c != NONE && !nameEquals(c, cb)) c = nextSib[c]
                cur = c
                if (cur == NONE) break
            }
            cur
        }
    }

    private fun nameEquals(id: Int, b: ByteArray): Boolean {
        if (nLen[id] != b.size) return false
        val o = nOff[id]
        for (i in b.indices) if (pool[o + i] != b[i]) return false
        return true
    }

    /** Calls f for every live entry id in id order. Holds the read lock for the whole loop: keep f cheap. */
    fun forEachLive(f: (Int) -> Unit) = rd { for (i in 0 until slots) if ((flags[i].toInt() and F_DEAD) == 0) f(i) }

    fun forEachChild(id: Int, f: (Int) -> Unit) = rd {
        if (alive(id)) { var c = firstChild[id]; while (c != NONE) { f(c); c = nextSib[c] } }
    }

    /** Calls f for the entry and every live entry below it (the entry itself first). */
    fun forEachSubtree(id: Int, f: (Int) -> Unit) = rd {
        if (alive(id)) {
            val stack = ArrayList<Int>()
            stack.add(id)
            while (stack.isNotEmpty()) {
                val x = stack.removeAt(stack.size - 1)
                f(x)
                var c = firstChild[x]
                while (c != NONE) { stack.add(c); c = nextSib[c] }
            }
        }
    }

    /**
     * The per-keystroke primitive for Part 2: scans slots from until to (exclusive, clamped) and calls hit(id) for every live entry whose
     * FOLDED name contains [needle] (UTF-8 bytes of an already folded query). Call it in chunks (a few thousand slots) and check the cancel flag in between.
     */
    fun scanRange(from: Int, to: Int, needle: ByteArray, hit: (Int) -> Unit) = rd {
        val end = minOf(to, slots)
        for (i in maxOf(0, from) until end) {
            if ((flags[i].toInt() and F_DEAD) != 0) continue
            if (contains(fpool, fOff[i], fLen[i], needle)) hit(i)
        }
    }

    private fun contains(buf: ByteArray, off: Int, len: Int, nd: ByteArray): Boolean {
        val m = nd.size
        if (m == 0) return true
        if (len < m) return false
        val first = nd[0]
        val last = off + len - m
        var i = off
        while (i <= last) {
            if (buf[i] == first) {
                var k = 1
                while (k < m && buf[i + k] == nd[k]) k++
                if (k == m) return true
            }
            i++
        }
        return false
    }

    /** Heap held by the arrays and pools (capacity, not just the used part). For measurements only. */
    fun approxBytes(): Long = rd {
        cap.toLong() * (4 + 4 + 4 + 1 + 8 + 8 + 4 + 4 + 4 + 4) + pool.size.toLong() + fpool.size.toLong()
    }

    // ---------- persistence primitive (the file wrapper is IndexStore) ----------
    /** Writes epoch, slot count, live count and one record per slot (dead slots as empty records, so ids survive a save / load). */
    fun writeTo(out: DataOutputStream) = rd {
        out.writeInt(epoch)
        out.writeInt(slots)
        out.writeInt(live)
        for (i in 0 until slots) {
            val dead = (flags[i].toInt() and F_DEAD) != 0
            out.writeInt(if (dead) NONE else parent[i])
            out.writeByte(flags[i].toInt())
            out.writeLong(if (dead) 0L else size[i])
            out.writeLong(if (dead) 0L else mtime[i])
            if (dead) out.writeShort(0) else {
                val len = minOf(nLen[i], 65535)
                out.writeShort(len)
                out.write(pool, nOff[i], len)
            }
        }
    }
}
