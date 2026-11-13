package com.lanshare.app.core.vault

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * "Restore to original location": decrypts vault items back to the path they were locked from ([VaultNode.orig]).
 * Pure JVM. Nothing is ever overwritten (a taken name gets " (2)"), the vault copy is removed only after the written file was
 * re-read and matched byte for byte (length + SHA-256), and a failed item simply stays in the vault.
 * Items without a recorded origin (share sheet / picker imports) go to [fallback] below their vault folder structure.
 */
object VaultRestore {
    class Result {
        var restored = 0
        var failed = 0
        val problems = ArrayList<String>()
        val files = ArrayList<File>()
    }

    /** Where [n] should land: its own origin, else (parent's destination) / name, else [fallback] / path inside the vault. Never inside a vault folder, never with "..". */
    fun destOf(st: VaultStore, n: VaultNode, fallback: File): File {
        val names = ArrayList<String>(); var base: File? = null; var cur: VaultNode? = n
        while (cur != null && base == null) {
            if (cur.orig.isNotEmpty()) base = File(cur.orig) else { names.add(cur.name); cur = cur.parent?.let { st.get(it) } }
        }
        val rel = names.asReversed()
        var f = base ?: fallback
        for (x in rel) f = File(f, x)
        if (safe(f)) return f
        if (rel.isEmpty()) return File(fallback, n.name)
        var g = fallback; for (x in rel) g = File(g, x)
        return g
    }

    private fun safe(f: File): Boolean {
        val p = f.path.replace('\\', '/')
        return f.isAbsolute && p.split('/').none { it == ".." } && !VaultGuard.isVaultName(p)
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var i = 2; while (true) { f = File(dir, name.substring(0, dot) + " ($i)" + name.substring(dot)); if (!f.exists()) return f; i++ }
    }

    private fun sha(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(64 * 1024)
        FileInputStream(f).use { i -> while (true) { val r = i.read(b); if (r < 0) break; md.update(b, 0, r) } }
        return md.digest()
    }

    /** Restores [ids] (files and / or folders). [onFile] gets every restored file (MediaScanner), [progress] the node being handled. */
    fun run(st: VaultStore, ids: List<String>, fallback: File, cancel: () -> Boolean = { false },
            onFile: (File) -> Unit = {}, progress: (VaultNode) -> Unit = {}): Result {
        val r = Result()
        for (id in ids) { if (cancel()) break; val n = st.get(id) ?: continue; node(st, n, fallback, r, cancel, onFile, progress) }
        return r
    }

    /** @return true when the node (and everything below it) is out of the vault. */
    private fun node(st: VaultStore, n: VaultNode, fb: File, r: Result, cancel: () -> Boolean, onFile: (File) -> Unit, progress: (VaultNode) -> Unit): Boolean {
        if (n.dir) {
            var all = true
            for (c in st.list(n.id)) { if (cancel()) { all = false; break }; if (!node(st, c, fb, r, cancel, onFile, progress)) all = false }
            if (!all) return false
            return try { destOf(st, n, fb).mkdirs(); st.delete(n.id); true }   // an emptied (or empty) folder: recreate it on disk, then drop the vault node
            catch (e: IllegalStateException) { throw e } catch (e: Exception) { false }
        }
        progress(n)
        return try { file(st, n, fb, onFile); r.restored++; true }
        catch (e: IllegalStateException) { throw e }   // vault locked mid-job: the caller stops, nothing else is touched
        catch (e: Exception) { r.failed++; r.problems.add(n.name + " (" + (e.message ?: e.javaClass.simpleName) + ")"); false }
    }

    private fun file(st: VaultStore, n: VaultNode, fb: File, onFile: (File) -> Unit) {
        var dest = destOf(st, n, fb)
        var dir = dest.parentFile ?: fb
        if (!(dir.isDirectory || dir.mkdirs())) { dir = fb; dest = File(fb, n.name); if (!(dir.isDirectory || dir.mkdirs())) throw IOException("cannot create the destination folder") }
        val free = try { dir.usableSpace } catch (_: Exception) { 0L }
        if (n.size > 0 && free > 0 && n.size + (5L shl 20) > free) throw IOException("not enough free space")
        val fin = unique(dir, dest.name)
        val tmp = File(dir, "." + fin.name + ".lsvpart")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp).use { o ->
                st.open(n.id).use { rd -> val b = ByteArray(64 * 1024); while (true) { val c = rd.read(b); if (c < 0) break; md.update(b, 0, c); o.write(b, 0, c) } }
                o.fd.sync()
            }
            if (tmp.length() != n.size || !MessageDigest.isEqual(md.digest(), sha(tmp))) throw IOException("written copy does not match")
            val target = if (fin.exists()) unique(dir, dest.name) else fin   // lost a race for the name: take the next free one
            if (!tmp.renameTo(target)) throw IOException("could not move the file into place")
            if (n.mtime > 0) target.setLastModified(n.mtime)
            st.delete(n.id)                                                   // only now: the plain copy is verified
            onFile(target)
        } catch (e: Throwable) { tmp.delete(); throw e }
    }
}
