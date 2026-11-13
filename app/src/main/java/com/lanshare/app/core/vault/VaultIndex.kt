package com.lanshare.app.core.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Encrypted tree id -> node. Durability = snapshot `i` (+ `i.bak`) and append-only encrypted journal `j`.
 * Every mutation is one fsync'd journal record (PUT node / DEL id); a snapshot is written every [CHECKPOINT] ops and on [close],
 * then the journal is truncated. Records with seq <= snapshot counter are ignored, so a crash at any point replays to a consistent state.
 */
class VaultIndex(private val root: File, private val key: ByteArray) {
    private val nodes = HashMap<String, VaultNode>()
    private var seq = 0L
    private var dirty = 0
    private val fi = File(root, "i"); private val fb = File(root, "i.bak"); private val ft = File(root, "i.tmp"); private val fj = File(root, "j")

    companion object { const val CHECKPOINT = 64; private const val PUT = 1; private const val DEL = 2 }

    val all: Collection<VaultNode> get() = nodes.values
    operator fun get(id: String) = nodes[id]

    // ---------- load ----------
    fun load() {
        nodes.clear(); seq = 0
        var ok = false
        for (f in listOf(fi, fb)) if (f.exists()) { try { readSnapshot(f); ok = true; break } catch (e: Exception) { nodes.clear(); seq = 0 } }
        if (!ok && (fi.exists() || fb.exists())) throw VaultCorruptException("index unreadable")
        replay()
    }

    private fun readSnapshot(f: File) {
        val b = f.readBytes()
        if (b.size < 1 + 8 + 12 + VaultFormat.TAG || b[0].toInt() != VaultFormat.INDEX_VER) throw VaultCorruptException("index header")
        val ctr = ByteBuffer.wrap(b, 1, 8).long
        val pt = VaultCrypto.open(key, b.copyOfRange(9, 21), snapAad(ctr), b, 21, b.size - 21)
        DataInputStream(ByteArrayInputStream(pt)).use { i -> repeat(i.readInt()) { val n = VaultNode.read(i); nodes[n.id] = n } }
        seq = ctr
    }

    private fun snapAad(ctr: Long) = ByteBuffer.allocate(9).put(VaultFormat.INDEX_VER.toByte()).putLong(ctr).array()
    private fun jAad(s: Long) = ByteBuffer.allocate(9).put('j'.code.toByte()).putLong(s).array()

    private fun replay() {
        if (!fj.exists()) return
        DataInputStream(ByteArrayInputStream(fj.readBytes())).use { i ->
            try {
                while (true) {
                    val len = i.readInt(); if (len < VaultFormat.TAG || len > 1 shl 20) break
                    val n = ByteArray(12).also { i.readFully(it) }; val s = i.readLong(); val ct = ByteArray(len).also { i.readFully(it) }
                    val pt = try { VaultCrypto.open(key, n, jAad(s), ct) } catch (e: Exception) { break }   // torn tail: stop
                    if (s <= seq) continue
                    if (s != seq + 1) break
                    apply(pt); seq = s
                }
            } catch (e: EOFException) { /* torn tail */ }
        }
    }

    private fun apply(pt: ByteArray) {
        DataInputStream(ByteArrayInputStream(pt)).use { i ->
            when (i.readByte().toInt()) {
                PUT -> VaultNode.read(i).let { nodes[it.id] = it }
                DEL -> nodes.remove(VaultFormat.hex(ByteArray(16).also { b -> i.readFully(b) }))
                else -> {}
            }
        }
    }

    // ---------- mutate ----------
    sealed class Op { class Put(val n: VaultNode) : Op(); class Del(val id: String) : Op() }

    /** Journals [ops] (fsync) then applies them in memory. */
    @Synchronized fun commit(ops: List<Op>) {
        if (ops.isEmpty()) return
        val bo = ByteArrayOutputStream(); val recs = ArrayList<Pair<Long, ByteArray>>()
        var s = seq
        for (op in ops) {
            val pb = ByteArrayOutputStream(); val d = DataOutputStream(pb)
            when (op) { is Op.Put -> { d.writeByte(PUT); op.n.write(d) }; is Op.Del -> { d.writeByte(DEL); d.write(VaultFormat.unhex(op.id)) } }
            s++; val nonce = VaultCrypto.rand(12); val ct = VaultCrypto.seal(key, nonce, jAad(s), pb.toByteArray())
            val o = DataOutputStream(bo); o.writeInt(ct.size); o.write(nonce); o.writeLong(s); o.write(ct); recs.add(s to pb.toByteArray())
        }
        FileOutputStream(fj, true).use { it.write(bo.toByteArray()); it.fd.sync() }
        for ((_, pt) in recs) apply(pt)
        seq = s; dirty += ops.size
        if (dirty >= CHECKPOINT) checkpoint()
    }

    @Synchronized fun checkpoint() {
        val bo = ByteArrayOutputStream(); val d = DataOutputStream(bo)
        d.writeInt(nodes.size); nodes.values.forEach { it.write(d) }
        val nonce = VaultCrypto.rand(12)
        val ct = VaultCrypto.seal(key, nonce, snapAad(seq), bo.toByteArray())
        FileOutputStream(ft).use { it.write(VaultFormat.INDEX_VER); it.write(ByteBuffer.allocate(8).putLong(seq).array()); it.write(nonce); it.write(ct); it.fd.sync() }
        if (fi.exists()) { fb.delete(); if (!fi.renameTo(fb)) throw IOException("index rotate") }
        if (!ft.renameTo(fi)) throw IOException("index commit")
        FileOutputStream(fj).use { it.fd.sync() }                 // truncate journal only after the snapshot is in place
        dirty = 0
    }

    fun close() { if (dirty > 0 || !fi.exists()) checkpoint() }
}
