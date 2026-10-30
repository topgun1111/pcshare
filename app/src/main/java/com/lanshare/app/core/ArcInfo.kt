package com.lanshare.app.core

import org.json.JSONArray
import org.json.JSONObject

/** JSON for GET /api/arcinfo?dev=&path=<archive root, "/x/a.zip!">: the strip of chips and the details sheet of the web UI. */
object ArcInfo {
    private fun label(fmt: String) = when (fmt) {
        "rar" -> "RAR"; "7z" -> "7z"; "tar" -> "TAR"; "tgz" -> "TAR.GZ"; "tbz" -> "TAR.BZ2"; "txz" -> "TAR.XZ"
        "gz" -> "GZ"; "bz2" -> "BZ2"; "xz" -> "XZ"; else -> "ZIP"
    }

    fun json(ep: Endpoint, root: String): JSONObject {
        val base = (ep as? ArcEp)?.base ?: ep
        val a = arcSplit(vnorm(root)) ?: throw BadReq("not an archive")
        val ap = a.first
        val name = vbase(ap)
        val fmt = arcFmt(name)
        val st = base.stat(ap)
        val size = st.optLong("size")
        val o = JSONObject().put("fmt", fmt).put("label", label(fmt)).put("ext", name.substringAfterLast('.', "").lowercase())
            .put("name", name).put("size", size).put("mtime", st.optLong("mtime"))
        var zipErr = ""
        if (fmt == "zip") {
            try {   // ZIP: the central directory only (a few ranged reads, also for other devices / shares)
                val s = base.open(ap)
                val z = try {
                    if (!s.seekable) null
                    else ZipInfo.parse(if (s.size > 0) s.size else size) { pos, n ->
                        s.seek(pos)
                        val b = ByteArray(n)
                        var got = 0
                        while (got < n) {
                            val r = s.read(b, got, n - got)
                            if (r < 0) throw java.io.IOException("the ZIP file ended unexpectedly")
                            got += r
                        }
                        b
                    }
                } finally { try { s.close() } catch (_: Exception) {} }
                if (z != null) return fill(o, z)
            } catch (c: Cancelled) { throw c } catch (e: Exception) { zipErr = e.message ?: "" /* odd layout: fall back to the file list below */ }
        }
        val ix = try { ArcStore.index(base, ap) } catch (c: Cancelled) { throw c } catch (e: Exception) {
            // a password is not damage; anything else that stops the file list is
            val m = (e.message ?: "") + " " + e.javaClass.simpleName
            if (m.contains("password", true) || m.contains("encrypt", true)) throw e
            return o.put("files", 0L).put("folders", 0L).put("unpacked", 0L).put("packed", -1L).put("enc", "unknown")
                .put("check", JSONObject().put("state", "bad").put("msg", "The file list cannot be read: " + (e.message ?: "unknown error").take(160)))
        }
        var files = 0L; var folders = 0L; var un = 0L; var enc = 0L
        var big = -1L; var bigName = ""; var old = 0L; var new = 0L
        for (e in ix.all.values) {
            if (e.dir) { folders++; continue }
            files++; un += e.size
            if (e.size > big) { big = e.size; bigName = e.name }
            if (e.mtime > 0) { if (old == 0L || e.mtime < old) old = e.mtime; if (e.mtime > new) new = e.mtime }
            if (e.enc) enc++
        }
        o.put("files", files).put("folders", folders).put("unpacked", un).put("packed", -1L)
            .put("oldest", old).put("newest", new)
            .put("enc", if (enc == 0L) "unknown" else if (enc == files) "all" else "some").put("encFiles", enc)
            .put("encKinds", JSONArray(if (enc > 0) listOf("ZIP") else emptyList<String>()))
        if (big >= 0) o.put("largest", JSONObject().put("name", bigName).put("size", big))
        val pr = ArrayList<String>()
        if (zipErr.isNotEmpty()) pr.add("the ZIP directory is odd or damaged (" + zipErr.take(100) + ")")
        magicProblem(base, ap, fmt, size)?.let { pr.add(it) }
        return o.put("check", checkJson(pr, 0))
    }

    private fun checkJson(problems: List<String>, checked: Int): JSONObject {
        val c = JSONObject().put("checked", checked)
        if (problems.isEmpty()) return c.put("state", "ok")
        return c.put("state", "warn").put("msg", problems.joinToString("; ").replaceFirstChar { it.uppercase() })
    }

    /** Cheap signature / end-marker test for the non-ZIP formats (head bytes, and the XZ footer); null = looks fine or could not be tested. */
    private fun magicProblem(base: Endpoint, ap: String, fmt: String, size: Long): String? {
        if (size <= 0L) return "the archive file is empty"
        try {
            val s = base.open(ap)
            try {
                val h = ByteArray(8)
                var got = 0
                while (got < h.size) { val r = s.read(h, got, h.size - got); if (r < 0) break; got += r }
                fun starts(vararg b: Int) = got >= b.size && b.indices.all { (h[it].toInt() and 0xff) == b[it] }
                val okHead = when (fmt) {
                    "7z" -> starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
                    "gz", "tgz" -> starts(0x1F, 0x8B)
                    "bz2", "tbz" -> starts(0x42, 0x5A, 0x68)
                    "xz", "txz" -> starts(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)
                    "rar" -> starts(0x52, 0x61, 0x72, 0x21)
                    else -> true   // plain TAR has no signature at the start
                }
                if (!okHead) return "the start of the file is not a valid " + label(fmt) + " header"
                if ((fmt == "xz" || fmt == "txz") && s.seekable && size >= 12) {
                    s.seek(size - 2)
                    val a = s.read(); val b = s.read()
                    if (a != 0x59 || b != 0x5A) return "the end of the XZ file is missing (cut off?)"
                }
            } finally { try { s.close() } catch (_: Exception) {} }
        } catch (c: Cancelled) { throw c } catch (_: Exception) { }
        return null
    }

    private fun fill(o: JSONObject, z: ZipSummary): JSONObject {
        o.put("files", z.files).put("folders", z.folders).put("unpacked", z.unpacked).put("packed", z.packed)
            .put("oldest", z.oldest).put("newest", z.newest).put("zip64", z.zip64)
            .put("enc", if (z.encFiles == 0L) "none" else if (z.encFiles == z.files) "all" else "some")
            .put("encFiles", z.encFiles).put("encKinds", JSONArray(z.encKinds.toList()))
        if (z.largestSize >= 0) o.put("largest", JSONObject().put("name", z.largestName).put("size", z.largestSize))
        val ms = JSONArray()
        for ((k, v) in z.methods.entries.sortedByDescending { it.value }) ms.put(JSONArray().put(k).put(v))
        o.put("methods", ms)
        if (z.comment.isNotEmpty()) o.put("comment", z.comment)
        if (z.prefix > 0) o.put("prefix", z.prefix)
        if (z.multi) o.put("multi", true)
        o.put("check", checkJson(z.problems, z.checked))
        return o
    }
}
