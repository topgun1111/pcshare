package com.lanshare.app.core

import com.lanshare.app.core.vault.VaultNode
import com.lanshare.app.core.vault.VaultReader
import com.lanshare.app.core.vault.VaultSession
import com.lanshare.app.core.vault.VaultStore
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** Decrypting, seekable view of one vault file. Nothing is ever written to disk in plain text. */
class VaultSource(private val r: VaultReader) : Source() {
    override val size: Long get() = r.length
    override val seekable: Boolean get() = true
    override fun seek(pos: Long) = r.seek(pos.coerceIn(0, r.length))
    override fun read(): Int = r.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = r.read(b, off, len)
    override fun skip(n: Long): Long = r.skip(n)
    override fun close() = r.close()
}

/**
 * VAULT MODE of the file manager: the unlocked vault as one browsable place (dev id "vault"). Virtual path "/" = the vault root.
 * Same [Endpoint] contract as [LocalFs], so browsing, copy / cut / paste jobs (also between this phone and the vault), rename, new folder,
 * delete, search and details work unchanged. Everything goes through [VaultStore]: names, sizes and bytes stay encrypted at rest.
 * Only the app's own screen may use it ([RootShell.untrusted]) and only while the vault is unlocked. Deleting is permanent (no recycle bin).
 * Not wrapped in [ArcEp]: archives are never opened inside the vault (that would need plain-text temp files).
 */
class VaultFs : Endpoint {
    override val id = "vault"
    override val name = "Vault"

    private fun st(): VaultStore {
        if (RootShell.untrusted.get() == true) throw Denied("The vault is only available from the app's own screen")
        return VaultSession.store?.takeIf { it.isOpen } ?: throw Denied("Vault is locked")
    }

    private fun segs(v: String) = vnorm(v).split('/').filter { it.isNotEmpty() }

    /** The node at [v]; null = the vault root. */
    private fun node(s: VaultStore, v: String): VaultNode? {
        var parent: String? = null; var n: VaultNode? = null
        for (seg in segs(v)) { n = s.list(parent).firstOrNull { it.name == seg } ?: throw NotFound("No such file or directory"); parent = n.id }
        return n
    }

    private fun dirId(s: VaultStore, v: String): String? {
        val n = node(s, v)
        if (n != null && !n.dir) throw IOException("Not a directory")
        return n?.id
    }

    private fun checkName(n: String) { if (n.isEmpty() || '/' in n || '\\' in n || n == "." || n == "..") throw BadReq("invalid name") }

    private fun kids(all: List<VaultNode>): Map<String?, List<VaultNode>> = all.groupBy { it.parent }

    override fun ls(v: String): List<Item> {
        val s = st(); val id = dirId(s, v)
        val all = s.allNodes(); val by = kids(all)
        return (by[id] ?: emptyList()).map { Item(it.name, it.dir, if (it.dir) 0L else it.size, it.mtime / 1000, if (it.dir) (by[it.id]?.size ?: 0) else null) }
    }

    override fun lsFast(v: String): List<Item> = ls(v)

    override fun search(v: String, q: String): SearchResult {
        val s = st(); val id = dirId(s, v); val base = vnorm(v)
        val all = s.allNodes(); val by = kids(all); val ql = q.lowercase()
        val out = ArrayList<Item>(); var partial = false
        val cap = if (base == "/") 20_000 else 300
        val stack = ArrayDeque<Pair<String?, String>>(); stack.addLast(id to base)
        while (stack.isNotEmpty() && !partial) {
            val (pid, ppath) = stack.removeLast()
            for (n in by[pid] ?: emptyList()) {
                val p = vjoin(ppath, n.name)
                if (n.name.lowercase().contains(ql)) { out.add(Item(n.name, n.dir, if (n.dir) 0L else n.size, n.mtime / 1000, null, p)); if (out.size >= cap) { partial = true; break } }
                if (n.dir) stack.addLast(n.id to p)
            }
        }
        out.sortWith(compareBy<Item>({ !it.dir }, { it.name.lowercase() }))
        return SearchResult(out, partial)
    }

    override fun names(v: String): MutableSet<String> =
        try { val s = st(); s.list(dirId(s, v)).map { it.name }.toMutableSet() } catch (_: NotFound) { mutableSetOf() }

    override fun walk(v: String): List<WalkItem> {
        val s = st(); val n = node(s, v)
        if (n != null && !n.dir) return listOf(WalkItem("", false, n.size))
        val by = kids(s.allNodes())
        val res = arrayListOf(WalkItem("", true, 0))
        val stack = ArrayDeque<Pair<String?, String>>(); stack.addLast(n?.id to "")
        while (stack.isNotEmpty()) {   // a folder's entry is added before its children: mkdir always precedes its files
            val (pid, rel) = stack.removeLast()
            for (k in by[pid] ?: emptyList()) {
                val r = if (rel.isEmpty()) k.name else rel + "/" + k.name
                if (k.dir) { res.add(WalkItem(r, true, 0)); stack.addLast(k.id to r) } else res.add(WalkItem(r, false, k.size))
            }
        }
        return res
    }

    override fun open(v: String): Source {
        val s = st(); val n = node(s, v) ?: throw IOException("Is a directory")
        if (n.dir) throw IOException("Is a directory")
        return VaultSource(s.open(n.id))
    }

    override fun write(v: String, input: InputStream, size: Long, cb: ((Int) -> Unit)?) {
        val s = st()
        if (size < 0) throw BadReq("missing size")
        val name = vbase(v); checkName(name)
        val parent = dirIdMake(s, vdir(v))
        val free = try { s.root.usableSpace } catch (_: Exception) { 0L }
        if (size > 0 && free > 0 && size + (5L shl 20) > free) throw Full("Not enough free space on the phone (needs ${size / 1048576} MB, ${free / 1048576} MB free)")
        val old = s.list(parent).firstOrNull { !it.dir && it.name == name }
        if (s.list(parent).any { it.dir && it.name == name }) throw Exists("a folder with this name already exists")
        val counted = object : InputStream() {
            var left = size
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = input.read(b, off, minOf(len.toLong(), left).toInt())
                if (n < 0) throw IOException("connection lost")
                left -= n; cb?.invoke(n); return n
            }
        }
        val (nn, _) = s.addStream(counted, parent, name, System.currentTimeMillis(), mimeFor(name))   // the old file (if any) still exists: the new one gets a temporary "name (2)"
        if (old != null) { s.delete(old.id); s.rename(nn.id, name) }
    }

    /** Folder id for [v], creating missing folders on the way (like mkdirs). */
    private fun dirIdMake(s: VaultStore, v: String): String? {
        var parent: String? = null
        for (seg in segs(v)) {
            val e = s.list(parent).firstOrNull { it.name == seg }
            parent = when { e == null -> s.mkdir(parent, seg).id; e.dir -> e.id; else -> throw Exists("a file with this name already exists") }
        }
        return parent
    }

    override val setsMtime: Boolean get() = true
    override fun setMtime(v: String, sec: Long) { if (sec > 0) try { val s = st(); node(s, v)?.let { s.setMtime(it.id, sec * 1000) } } catch (_: Exception) {} }

    override fun mkdir(v: String) { dirIdMake(st(), v) }

    override fun remove(v: String, progress: ((String) -> Unit)?) {
        val s = st(); val n = node(s, v) ?: throw Denied("cannot delete the vault itself")
        s.delete(n.id); progress?.invoke(n.name)
    }

    override fun rename(v: String, newName: String) {
        checkName(newName)
        val s = st(); val n = node(s, v) ?: throw Denied("cannot rename the vault itself")
        if (s.list(n.parent).any { it.id != n.id && it.name == newName }) throw Exists("name already exists")
        s.rename(n.id, newName)
    }

    override fun move(v: String, toV: String) {
        val s = st(); val n = node(s, v) ?: throw Denied("cannot move the vault itself")
        val dst = try { node(s, toV) } catch (_: NotFound) { null }
        val parent: String?; val name: String
        if (dst != null && dst.dir) { parent = dst.id; name = n.name } else { parent = dirIdMake(s, vdir(toV)); name = vbase(toV); checkName(name) }
        if (parent == n.parent && name == n.name) return
        if (s.list(parent).any { it.id != n.id && it.name == name }) throw Exists("name already exists")
        var cur = n
        if (parent != n.parent) cur = s.move(n.id, parent)
        if (cur.name != name) s.rename(cur.id, name)
    }

    override fun stat(v: String): JSONObject {
        val s = st(); val n = node(s, v)
        val o = JSONObject().put("name", n?.name ?: "/").put("path", vnorm(v)).put("dir", n?.dir ?: true)
            .put("size", if (n == null || n.dir) 0L else n.size).put("mtime", (n?.mtime ?: System.currentTimeMillis()) / 1000)
            .put("readonly", false).put("hidden", false).put("link", false)
        if (n == null || n.dir) {
            val by = kids(s.allNodes()); var files = 0L; var folders = 0L; var bytes = 0L
            val stack = ArrayDeque<String?>(); stack.addLast(n?.id)
            while (stack.isNotEmpty()) for (k in by[stack.removeLast()] ?: emptyList()) { if (k.dir) { folders++; stack.addLast(k.id) } else { files++; bytes += k.size } }
            o.put("files", files).put("folders", folders).put("total", bytes).put("partial", false)
        }
        return o
    }

    override fun space(v: String): Pair<Long, Long>? = try {
        val r = st().root; val total = r.totalSpace
        if (total > 0) r.usableSpace to total else null
    } catch (_: Exception) { null }
}
