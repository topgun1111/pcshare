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

    class Resp(val status: Int, private val attrs: Map<String, List<Attr>>) {
        val ok: Boolean get() = status < 0x100
        fun strs(n: String): List<String> = attrs[n]?.map { String(it.v, Charsets.UTF_8) } ?: emptyList()
        fun int(n: String): Int? = attrs[n]?.firstOrNull()?.v?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int }
        fun bool(n: String): Boolean? = attrs[n]?.firstOrNull()?.v?.takeIf { it.size == 1 }?.let { it[0].toInt() != 0 }
    }

    /** What the user asked for; null / empty = leave it to the printer. [ranges] = list of [from, to] (1-based). */
    class JobOpts(val copies: Int = 1, val sides: String? = null, val color: String? = null, val media: String? = null,
                  val scaling: String? = null, val ranges: List<IntArray> = emptyList())

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
            "media-ready", "media-default", "media-supported"))
        return call(p, rq.finish(), null, 0, 8000, null)
    }

    /** Sends [file] as one job. [mime] = application/pdf or image/jpeg. */
    fun printJob(p: WifiPrinters.P, jobName: String, mime: String, jo: JobOpts, file: File, onSent: ((Int) -> Unit)? = null): Resp {
        val rq = Req(OP_PRINT_JOB, p.uri)
        rq.str(0x42, "job-name", jobName.take(100))
        rq.str(0x49, "document-format", mime)
        val hasJob = jo.copies > 1 || jo.sides != null || jo.color != null || jo.media != null || jo.scaling != null || jo.ranges.isNotEmpty()
        if (hasJob) {
            rq.group(0x02)   // job-attributes group
            if (jo.copies > 1) rq.int("copies", jo.copies)
            jo.sides?.let { rq.str(0x44, "sides", it) }
            jo.color?.let { rq.str(0x44, "print-color-mode", it) }
            jo.media?.let { rq.str(0x44, "media", it) }
            jo.scaling?.let { rq.str(0x44, "print-scaling", it) }
            jo.ranges.forEachIndexed { i, r ->
                rq.attr(0x33, if (i == 0) "page-ranges" else "", ByteBuffer.allocate(8).putInt(r[0]).putInt(r[1]).array())
            }
        }
        val head = rq.finish()
        return file.inputStream().use { call(p, head, it, file.length(), 180_000, onSent) }
    }

    private fun call(p: WifiPrinters.P, req: ByteArray, data: InputStream?, dataLen: Long, timeoutMs: Int, onSent: ((Int) -> Unit)?): Resp {
        val head = ByteArrayInputStream(req)
        val body: InputStream = if (data == null) head else SequenceInputStream(head, data)
        val r = Http.request(p.host, p.port, "POST", "/" + p.path, mapOf("Content-Type" to "application/ipp", "Accept" to "application/ipp"),
            timeoutMs, body, req.size + dataLen, onSent)
        try {
            if (r.status != 200) throw IOException("the printer answered HTTP ${r.status}")
            return parse(readBody(r))
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
        var cur: String? = null
        var depth = 0                              // inside a collection value (media-col ...): skipped
        while (bb.hasRemaining()) {
            val tag = bb.get().toInt() and 0xFF
            if (tag == 0x03) break
            if (tag < 0x10) { cur = null; continue }   // group delimiter
            if (bb.remaining() < 2) break
            val nl = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < nl + 2) break
            val nm = if (nl > 0) ByteArray(nl).also { bb.get(it) } else null
            val vl = bb.short.toInt() and 0xFFFF
            if (bb.remaining() < vl) break
            val v = ByteArray(vl).also { bb.get(it) }
            if (tag == 0x34) { depth++; cur = null; continue }
            if (tag == 0x37) { if (depth > 0) depth--; continue }
            if (depth > 0) continue
            if (nm != null) cur = String(nm, Charsets.UTF_8)
            val c = cur ?: continue
            map.getOrPut(c) { ArrayList() }.add(Attr(tag, v))
        }
        return Resp(status, map)
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
