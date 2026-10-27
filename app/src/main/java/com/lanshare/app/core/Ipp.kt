package com.lanshare.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer

/** Minimal IPP 1.1 client (RFC 8010 / 8011): Get-Printer-Attributes and Print-Job over plain HTTP, for Wi-Fi printers found by [WifiPrinters]. */
object Ipp {
    private const val OP_PRINT_JOB = 0x0002
    private const val OP_GET_PRINTER_ATTRS = 0x000B

    class Attr(val tag: Int, val v: ByteArray)

    class Resp(val status: Int, private val attrs: Map<String, List<Attr>>, private val cols: Map<String, List<Map<String, String>>> = emptyMap()) {
        val ok: Boolean get() = status < 0x100
        fun strs(n: String): List<String> = attrs[n]?.map { String(it.v, Charsets.UTF_8) } ?: emptyList()
        fun int(n: String): Int? = attrs[n]?.firstOrNull()?.v?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int }
        /** every 4-byte integer value of the attribute (e.g. media-left-margin-supported: 1/100 mm values) */
        fun ints(n: String): List<Int> = attrs[n]?.mapNotNull { a -> a.v.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int } } ?: emptyList()
        fun bool(n: String): Boolean? = attrs[n]?.firstOrNull()?.v?.takeIf { it.size == 1 }?.let { it[0].toInt() != 0 }
        /** resolution values (9 bytes: x, y, unit) -> the x dpi of each */
        /** collection values (media-col-ready ...) flattened: one map per value, nested members as "media-size.x-dimension" */
        fun colls(n: String): List<Map<String, String>> = cols[n] ?: emptyList()
        /** readable dump of the named attributes (strings and collections) - shown to the user so a printer's tray data can be checked */
        fun dump(names: List<String>): String {
            val sb = StringBuilder()
            for (n in names) {
                val a = strs(n); if (a.isNotEmpty()) sb.append(n).append(" = ").append(a.joinToString(" | ") { it.take(200) }).append('\n')
                colls(n).forEachIndexed { i, m -> sb.append(n).append('[').append(i).append("] = ").append(m.entries.joinToString(", ") { it.key + "=" + it.value }).append('\n') }
            }
            return sb.toString().trim()
        }
        fun dpis(n: String): List<Int> = attrs[n]?.mapNotNull { a -> a.v.takeIf { it.size == 9 }?.let { ByteBuffer.wrap(it).int } } ?: emptyList()
    }

    /** What the user asked for; null / empty = leave it to the printer. [ranges] = list of [from, to] (1-based). */
    class JobOpts(val copies: Int = 1, val sides: String? = null, val color: String? = null, val media: String? = null,
                  val scaling: String? = null, val ranges: List<IntArray> = emptyList(),
                  /** paper tray (media-source keyword, e.g. "tray-2") + the size in 1/100 mm [x, y]: sent as media-col instead of "media" */
                  val source: String? = null, val srcDim: IntArray? = null,
                  /** best-effort "quiet": IPP has no standard quiet attribute, so Quiet = print-quality high (slower head, usually quieter), Normal = print-quality normal (3 = draft, 4 = normal, 5 = high) */
                  val quality: Int? = null)

    private class Req(op: Int, uri: String) {
        val o = ByteArrayOutputStream()
        init {
            o.write(1); o.write(1)          // IPP 1.1
            w16(op); w32(1)                 // operation, request id
            o.write(0x01)                   // operation-attributes group
            str(0x47, "attributes-charset", "utf-8")
            str(0x48, "attributes-natural-language", "en")
            str(0x45, "printer-uri", uri)
            str(0x42, "requesting-user-name", "LANShare")
        }
        fun w16(v: Int) { o.write((v shr 8) and 255); o.write(v and 255) }
        fun w32(v: Int) { w16(v ushr 16); w16(v and 0xFFFF) }
        fun attr(tag: Int, name: String, value: ByteArray) {
            o.write(tag)
            val n = name.toByteArray(Charsets.UTF_8)
            w16(n.size); o.write(n)
            w16(value.size); o.write(value)
        }
        fun str(tag: Int, name: String, v: String) = attr(tag, name, v.toByteArray(Charsets.UTF_8))
        fun int(name: String, v: Int) = attr(0x21, name, ByteBuffer.allocate(4).putInt(v).array())
        fun strs(tag: Int, name: String, vs: List<String>) { vs.forEachIndexed { i, v -> attr(tag, if (i == 0) name else "", v.toByteArray(Charsets.UTF_8)) } }
        fun group(tag: Int) { o.write(tag) }
        fun finish(): ByteArray { o.write(0x03); return o.toByteArray() }
    }

    fun printerAttrs(p: WifiPrinters.P): Resp {
        val rq = Req(OP_GET_PRINTER_ATTRS, p.uri)
        rq.strs(0x44, "requested-attributes", listOf("printer-state", "printer-state-reasons", "printer-name", "printer-make-and-model",
            "document-format-supported", "sides-supported", "print-color-mode-supported", "printer-is-accepting-jobs",
            "media-ready", "media-default", "media-supported", "media-source-supported", "media-col-ready", "printer-input-tray", "media-type-supported",
            "pwg-raster-document-resolution-supported", "pwg-raster-document-sheet-back", "pwg-raster-document-type-supported",
            "media-left-margin-supported", "media-right-margin-supported", "media-top-margin-supported", "media-bottom-margin-supported"))
        return call(p, rq.finish(), null, 0, 8000, null)
    }

    /** Sends [file] as one job. [mime] = application/pdf or image/jpeg. */
    fun printJob(p: WifiPrinters.P, jobName: String, mime: String, jo: JobOpts, file: File, onSent: ((Int) -> Unit)? = null): Resp {
        probeTls(p)   // the file stream cannot be replayed: learn first whether the printer wants TLS (HTTP 426), then send the job once
        val rq = Req(OP_PRINT_JOB, p.uri)
        rq.str(0x42, "job-name", jobName.take(100))
        rq.str(0x49, "document-format", mime)
        val hasJob = jo.copies > 1 || jo.sides != null || jo.color != null || jo.media != null || jo.source != null || jo.quality != null || jo.scaling != null || jo.ranges.isNotEmpty()
        if (hasJob) {
            rq.group(0x02)   // job-attributes group
            if (jo.copies > 1) rq.int("copies", jo.copies)
            jo.sides?.let { rq.str(0x44, "sides", it) }
            jo.color?.let { rq.str(0x44, "print-color-mode", it) }
            jo.media?.let { rq.str(0x44, "media", it) }
            jo.source?.let { src ->   // media-col { media-size { x-dimension, y-dimension }, media-source }
                rq.attr(0x34, "media-col", ByteArray(0))
                jo.srcDim?.let { d ->
                    rq.attr(0x4A, "", "media-size".toByteArray()); rq.attr(0x34, "", ByteArray(0))
                    rq.attr(0x4A, "", "x-dimension".toByteArray()); rq.attr(0x21, "", ByteBuffer.allocate(4).putInt(d[0]).array())
                    rq.attr(0x4A, "", "y-dimension".toByteArray()); rq.attr(0x21, "", ByteBuffer.allocate(4).putInt(d[1]).array())
                    rq.attr(0x37, "", ByteArray(0))
                }
                rq.attr(0x4A, "", "media-source".toByteArray()); rq.attr(0x44, "", src.toByteArray())
                rq.attr(0x37, "", ByteArray(0))
            }
            jo.quality?.let { rq.attr(0x23, "print-quality", ByteBuffer.allocate(4).putInt(it).array()) }   // 0x23 = enum
            jo.scaling?.let { rq.str(0x44, "print-scaling", it) }
            jo.ranges.forEachIndexed { i, r ->
                rq.attr(0x33, if (i == 0) "page-ranges" else "", ByteBuffer.allocate(8).putInt(r[0]).putInt(r[1]).array())
            }
        }
        val head = rq.finish()
        return file.inputStream().use { call(p, head, it, file.length(), 180_000, onSent) }
    }

    private val probed: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** One tiny plain request per printer: if it answers 426 (Upgrade Required, e.g. Epson) it is switched to IPP over TLS from then on. */
    private fun probeTls(p: WifiPrinters.P) {
        if (p.viaPeer != null || !probed.add("${p.host}:${p.port}")) return
        try { printerAttrs(p) } catch (_: Exception) { probed.remove("${p.host}:${p.port}") }
    }

    private fun call(p: WifiPrinters.P, req: ByteArray, data: InputStream?, dataLen: Long, timeoutMs: Int, onSent: ((Int) -> Unit)?): Resp {
        // a header-only request (attributes query) that gets 426 is repeated once over TLS; the printer is remembered
        if (p.viaPeer == null && data == null && !Http.tlsHosts.contains("${p.host}:${p.port}")) {
            val r0 = Http.request(p.host, p.port, "POST", "/" + p.path, mapOf("Content-Type" to "application/ipp", "Accept" to "application/ipp"), timeoutMs, ByteArrayInputStream(req), req.size.toLong())
            val st = r0.status
            if (st != 426) try { if (st != 200) throw IOException("the printer answered HTTP $st"); return parse(readBody(r0)) } finally { r0.close() }
            r0.close()
            Http.tlsHosts.add("${p.host}:${p.port}")
            probed.add("${p.host}:${p.port}")
        }
        val head = ByteArrayInputStream(req)
        val body: InputStream = if (data == null) head else SequenceInputStream(head, data)
        val hdr = mapOf("Content-Type" to "application/ipp", "Accept" to "application/ipp")
        val vp = p.viaPeer
        val r = if (vp != null) {   // a printer on another device: that device forwards the raw IPP request (see [forward])
            val peer = Core.disc.get(vp) ?: throw IOException("the device that has this printer is offline")
            Http.request(peer.ip, peer.port, "POST", "/p/ipp?id=" + java.net.URLEncoder.encode(p.origId, "UTF-8"), hdr, timeoutMs, body, req.size + dataLen, onSent = onSent)
        } else Http.request(p.host, p.port, "POST", "/" + p.path, hdr, timeoutMs, body, req.size + dataLen, onSent = onSent, tls = Http.tlsHosts.contains("${p.host}:${p.port}"))
        try {
            if (r.status != 200) throw IOException(if (vp != null) "the other device could not reach the printer (HTTP ${r.status}) - is its LANShare up to date?" else "the printer answered HTTP ${r.status}")
            return parse(readBody(r))
        } finally { r.close() }
    }

    /** Device side of a remote print: sends one raw IPP request ([len] bytes of [body]) to a printer discovered here and returns the printer's raw answer. */
    fun forward(p: WifiPrinters.P, body: InputStream, len: Long): ByteArray {
        val r = Http.request(p.host, p.port, "POST", "/" + p.path, mapOf("Content-Type" to "application/ipp", "Accept" to "application/ipp"), 170_000, body, len,
            tls = Http.tlsHosts.contains("${p.host}:${p.port}"))
        try {
            if (r.status != 200) throw IOException("the printer answered HTTP ${r.status}")
            return readBody(r)
        } finally { r.close() }
    }

    private fun readBody(r: HttpResp): ByteArray {
        val max = 1 shl 20
        if (r.headers["transfer-encoding"]?.contains("chunked", true) == true) {
            val out = ByteArrayOutputStream()
            val ins = r.body
            val buf = ByteArray(8192)
            while (true) {
                val line = readLine(ins) ?: break
                val n = line.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (n == 0) break
                var left = n
                while (left > 0) {
                    val k = ins.read(buf, 0, minOf(buf.size, left))
                    if (k < 0) break
                    if (out.size() < max) out.write(buf, 0, k)
                    left -= k
                }
                readLine(ins)   // CRLF after every chunk
            }
            return out.toByteArray()
        }
        return r.readUpTo(max)
    }

    private fun parse(b: ByteArray): Resp {
        if (b.size < 9) throw IOException("the printer sent an empty answer")
        val bb = ByteBuffer.wrap(b)
        bb.short                                   // version
        val status = bb.short.toInt() and 0xFFFF
        bb.int                                     // request id
        val map = LinkedHashMap<String, MutableList<Attr>>()
        val cols = LinkedHashMap<String, MutableList<Map<String, String>>>()
        var cur: String? = null
        var depth = 0                              // inside a collection value (media-col ...): members are collected into [curCol]
        var colAttr: String? = null
        var curCol: LinkedHashMap<String, String>? = null
        val pre = ArrayList<String>()              // names of the enclosing member collections ("media-size")
        var mem = ""
        while (bb.hasRemaining()) {
            val tag = bb.get().toInt() and 0xFF
            if (tag == 0x03) break
            if (tag < 0x10) { cur = null; depth = 0; pre.clear(); continue }   // group delimiter
            if (bb.remaining() < 2) break
            val nl = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < nl + 2) break
            val nm = if (nl > 0) ByteArray(nl).also { bb.get(it) } else null
            val vl = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < vl) break
            val v = ByteArray(vl).also { bb.get(it) }
            if (tag == 0x34) {
                if (depth == 0) { if (nm != null) colAttr = String(nm, Charsets.UTF_8); curCol = LinkedHashMap(); cur = null }
                else pre.add(mem)
                depth++; continue
            }
            if (tag == 0x37) {
                if (depth > 1) { depth--; if (pre.isNotEmpty()) pre.removeAt(pre.size - 1) }
                else if (depth == 1) { depth = 0; val ca = colAttr; val cc = curCol; if (ca != null && cc != null) cols.getOrPut(ca) { ArrayList() }.add(cc); curCol = null }
                continue
            }
            if (depth > 0) {
                if (tag == 0x4A) { mem = String(v, Charsets.UTF_8); continue }
                val key = (pre + mem).joinToString(".")
                val txt = when {
                    (tag == 0x21 || tag == 0x23) && v.size == 4 -> ByteBuffer.wrap(v).int.toString()
                    tag == 0x22 && v.size == 1 -> (v[0].toInt() != 0).toString()
                    else -> String(v, Charsets.UTF_8)
                }
                curCol?.let { it[key] = if (it.containsKey(key)) it[key] + "," + txt else txt }
                continue
            }
            if (nm != null) cur = String(nm, Charsets.UTF_8)
            val c = cur ?: continue
            map.getOrPut(c) { ArrayList() }.add(Attr(tag, v))
        }
        return Resp(status, map, cols)
    }

    fun statusText(s: Int): String = "the printer refused the job (" + when (s) {
        0x0400 -> "bad request"
        0x0401 -> "forbidden"
        0x0402, 0x0403 -> "login required"
        0x0404 -> "not possible"
        0x0405 -> "timeout"
        0x0406 -> "printer not found at that address"
        0x0408, 0x0409 -> "file too large"
        0x040A -> "file format not supported"
        0x040B -> "options not supported"
        0x040E -> "conflicting options"
        0x0506 -> "not accepting jobs"
        0x0507 -> "busy"
        0x0502 -> "service unavailable"
        else -> "code 0x" + Integer.toHexString(s)
    } + ")"
}
