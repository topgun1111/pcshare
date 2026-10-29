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
            } catch (c: Cancelled) { throw c } catch (_: Exception) { /* odd layout: fall back to the file list below */ }
        }
        val ix = ArcStore.index(base, ap)
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
        return o
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
        return o
    }
}
