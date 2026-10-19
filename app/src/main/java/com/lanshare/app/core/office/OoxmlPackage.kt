package com.lanshare.app.core.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Read-only view of an OOXML / ODF container (.xlsx .docx .pptx .ods .odt). Backed by java.util.zip.ZipFile on a LOCAL file
 * (callers copy remote/SAF files to cache first), so random access is cheap and nothing is loaded until asked.
 * Guards: per-part size cap (zip-bomb), path normalisation, case-insensitive lookup.
 */
class OoxmlPackage(private val file: File, private val maxPart: Int = 64 shl 20) : AutoCloseable {
    private val zip = ZipFile(file)
    private val index = HashMap<String, String>()   // lower-case normalised name -> real name

    init {
        val en = zip.entries()
        while (en.hasMoreElements()) { val e = en.nextElement(); if (!e.isDirectory) index[norm(e.name)] = e.name }
    }

    val names: List<String> get() = index.values.toList()
    fun has(name: String) = index.containsKey(norm(name))

    /** Bytes of a part, or null if missing / larger than the cap. */
    fun read(name: String): ByteArray? {
        val real = index[norm(name)] ?: return null
        val e = zip.getEntry(real) ?: return null
        if (e.size > maxPart) return null
        return zip.getInputStream(e).use { ins ->
            val bo = ByteArrayOutputStream(if (e.size in 1..maxPart.toLong()) e.size.toInt() else 8192)
            val buf = ByteArray(64 * 1024); var t = 0
            while (true) { val n = ins.read(buf); if (n < 0) break; t += n; if (t > maxPart) return null; bo.write(buf, 0, n) }
            bo.toByteArray()
        }
    }
    fun xml(name: String): MiniXml? = read(name)?.let { MiniXml(it) }
    fun size(name: String): Long = index[norm(name)]?.let { zip.getEntry(it)?.size } ?: -1L

    /** Parsed `_rels/*.rels` of [part] (e.g. "xl/workbook.xml" -> xl/_rels/workbook.xml.rels): id -> Rel. Targets are resolved to package paths. */
    fun rels(part: String): Map<String, Rel> {
        val dir = part.substringBeforeLast('/', "")
        val base = part.substringAfterLast('/')
        val relPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/$base.rels"
        val x = xml(relPath) ?: return emptyMap()
        val out = LinkedHashMap<String, Rel>()
        while (true) {
            when (x.next()) {
                MiniXml.T.EOF -> break
                MiniXml.T.START -> if (x.name == "Relationship") {
                    val id = x.attr("Id") ?: continue
                    val tgt = x.attr("Target") ?: continue
                    val ext = x.attr("TargetMode").equals("External", true)
                    out[id] = Rel(id, x.attr("Type") ?: "", if (ext) tgt else resolve(dir, tgt), ext)
                }
                else -> {}
            }
        }
        return out
    }

    data class Rel(val id: String, val type: String, val target: String, val external: Boolean) {
        val kind: String get() = type.substringAfterLast('/')
    }

    override fun close() { try { zip.close() } catch (_: Throwable) {} }

    companion object {
        private fun norm(n: String) = n.replace('\\', '/').trimStart('/').lowercase()

        /** Resolve a relationship target against the part's directory ("/xl/x.xml" is package-absolute, "../a" climbs). */
        fun resolve(dir: String, target: String): String {
            if (target.startsWith("/")) return target.trimStart('/')
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
            for (seg in target.replace('\\', '/').split('/')) {
                when (seg) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(seg) }
            }
            return parts.joinToString("/")
        }
    }
}
