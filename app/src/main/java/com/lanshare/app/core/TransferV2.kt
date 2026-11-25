package com.lanshare.app.core

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Semaphore

internal object TransferV2 {
    const val CHUNK = 8 * 1024 * 1024
    private val slots = Semaphore(8)
    private val gate = Any()
    private val busy = HashSet<Pair<String, Int>>()
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val ticketPattern = Regex("[0-9a-f]{32}")

    fun hash(input: InputStream, length: Long, write: (ByteArray, Int) -> Unit = { _, _ -> }): String {
        val md = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(256 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(block, 0, minOf(left, block.size.toLong()).toInt())
            if (n <= 0) throw IOException("Incomplete input")
            md.update(block, 0, n)
            write(block, n)
            left -= n
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun target(rel: String): File {
        if (rel.length > 3000 || rel.split('/').any { it.isEmpty() || it == "." || it == ".." ||
                it.any { c -> c == ':' || c == '\\' || c.code < 32 } }) throw BadReq("Invalid path")
        val root = Core.local.real("/Download/pcshare").canonicalFile
        return File(root, rel).canonicalFile.also {
            if (!it.path.startsWith(root.path + File.separator)) throw Denied("Outside receive folder")
        }
    }

    private fun save(journal: AtomicFile, s: JSONObject) {
        val out = journal.startWrite()
        try {
            out.write(s.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
            journal.finishWrite(out)
        } catch (e: Throwable) {
            journal.failWrite(out)
            throw e
        }
    }

    private fun read(journal: AtomicFile) = journal.openRead().bufferedReader().use { JSONObject(it.readText()) }

    fun handle(ex: Exchange, publishGate: Any, reserved: Set<String>) {
        val op = ex.q("op")
        if (op == "caps" && ex.method == "GET") {
            ex.json(JSONObject().put("version", 2).put("chunk", CHUNK).put("peer", Cfg.id))
            return
        }
        if (!slots.tryAcquire()) { ex.fail(503, "Receiver busy"); return }
        try {
            val ticket = ex.header("x-pcshare-ticket") ?: throw BadReq("Missing ticket")
            if (ex.method != "POST" || !ticketPattern.matches(ticket)) throw BadReq("Invalid ticket or method")
            val length = (ex.header("content-length") ?: "0").toLongOrNull() ?: throw BadReq("Invalid length")
            if (length !in 0L..CHUNK.toLong() || (op != "chunk" && length != 0L)) throw BadReq("Invalid length")
            val ctx = Core.appCtx ?: throw IOException("Application not ready")
            val folder = File(ctx.filesDir, "transfer-v2")
            val journal = AtomicFile(File(folder, ticket))
            val i = ex.query["i"]?.toIntOrNull() ?: -1
            val digest = ex.header("x-pcshare-sha256") ?: ""
            var notice: PcTransferNotice.State? = null
            val part = synchronized(gate) {
                folder.mkdirs()
                var s = try { read(journal) } catch (_: java.io.FileNotFoundException) { null }
                if (op == "init") {
                    val path = ex.q("path")
                    val size = ex.q("size").toLongOrNull() ?: throw BadReq("Invalid size")
                    val sha = ex.q("sha")
                    val dst = target(path)
                    if (size !in 0L..(64L shl 30) || !digestPattern.matches(sha)) throw BadReq("Invalid manifest")
                    if (s == null) {
                        val receipts = folder.listFiles() ?: throw IOException("Cannot read journals")
                        for (receipt in receipts) {
                            if (ticketPattern.matches(receipt.name) && receipt.lastModified() < System.currentTimeMillis()-604800000L &&
                                busy.none { it.first == receipt.name }) {
                                val old = AtomicFile(receipt)
                                val oldPart = File(read(old).getString("part"))
                                if (!oldPart.exists() || oldPart.delete()) old.delete()
                            }
                        }
                        if ((folder.list()?.size ?: 1024) >= 1024) { ex.fail(507, "Session limit reached"); return }
                        val parent = dst.parentFile ?: throw BadReq("Invalid destination")
                        parent.mkdirs()
                        if (parent.usableSpace < size + (16 shl 20)) { ex.fail(507, "Insufficient space"); return }
                        val tmp = File(parent, "." + UUID.randomUUID() + ".pcshare-part")
                        if (!tmp.createNewFile()) throw IOException("Cannot create partial file")
                        s = JSONObject().put("path",path).put("size",size).put("sha",sha)
                            .put("part",tmp.path).put("chunks",JSONObject()).put("done",false)
                        save(journal,s)
                    } else if (s.getString("path") != path || s.getLong("size") != size || s.getString("sha") != sha) {
                        ex.fail(409,"Manifest changed"); return
                    }
                }
                val state = s ?: throw NotFound("Unknown session")
                val chunks = state.getJSONObject("chunks")
                val size = state.getLong("size")
                val count = ((size + CHUNK-1) / CHUNK).toInt()
                val tmp = File(state.getString("part"))
                if (!state.getBoolean("done")) {
                    notice = PcTransferNotice.begin(ctx,"v2:"+ticket,ex.remoteIp,state.getString("path"),size,1)
                    val active = requireNotNull(notice)
                    synchronized(active) {
                        active.received = chunks.keys().asSequence().sumOf { minOf(CHUNK.toLong(),size-it.toLong()*CHUNK) }
                    }
                    if (active.cancelled && op != "cancel") {
                        state.put("cancelled",true)
                        save(journal,state)
                        PcTransferNotice.failed(ctx,active,"Cancelled")
                    }
                    if (state.optBoolean("cancelled") && op != "cancel") { ex.fail(409,"Cancelled"); return }
                }
                when (op) {
                    "init", "status" -> {
                        ex.json(JSONObject().put("done",state.getBoolean("done")).put("have",JSONArray(chunks.keys().asSequence().map { it.toInt() }.toList())))
                        return
                    }
                    "cancel" -> {
                        if (busy.any { it.first == ticket }) { ex.fail(503,"Writes active"); return }
                        if (tmp.exists() && !tmp.delete()) throw IOException("Cannot remove partial file")
                        notice?.let { PcTransferNotice.failed(ctx,it,"Cancelled") }
                        journal.delete()
                        ex.json(JSONObject().put("ok",true)); return
                    }
                    "finish" -> {
                        if (!state.getBoolean("done")) {
                            if (chunks.length() != count || busy.any { it.first == ticket }) { ex.fail(409,"Incomplete"); return }
                            val recovered = !tmp.exists() && state.has("final")
                            val src = if (recovered) File(state.getString("final")) else tmp
                            if (src.length() != size || src.inputStream().use { hash(it,size) } != state.getString("sha")) {
                                if (!recovered) {
                                    src.inputStream().use { input ->
                                        for (index in 0 until count) {
                                            val n = minOf(CHUNK.toLong(),size-index.toLong()*CHUNK)
                                            if (hash(input,n) != chunks.optString(index.toString())) chunks.remove(index.toString())
                                        }
                                    }
                                    save(journal,state)
                                }
                                ex.fail(409,"Final digest mismatch"); return
                            }
                            if (!recovered) synchronized(publishGate) {
                                val dst = target(state.getString("path"))
                                val dot = dst.name.lastIndexOf('.')
                                val stem = if (dot > 0) dst.name.substring(0,dot) else dst.name
                                val ext = dst.name.removePrefix(stem)
                                var final = dst
                                var index = 1
                                while (final.exists() || final.path in reserved) {
                                    final = File(dst.parentFile, "$stem ($index)$ext")
                                    index++
                                }
                                state.put("final",final.path)
                                save(journal,state)
                                if (!tmp.renameTo(final)) throw IOException("Cannot publish file")
                            }
                            notice?.let { PcTransferNotice.itemDone(ctx,it) }
                            state.put("done",true)
                            save(journal,state)
                        }
                        ex.json(JSONObject().put("done",true)); return
                    }
                    "chunk" -> {
                        if (i !in 0 until count || length != minOf(CHUNK.toLong(),size-i.toLong()*CHUNK) || !digestPattern.matches(digest))
                            throw BadReq("Invalid chunk")
                        if (chunks.has(i.toString())) {
                            if (chunks.getString(i.toString()) == digest && hash(ex.body,length) == digest) ex.json(JSONObject().put("ok",true))
                            else ex.fail(409,"Chunk changed")
                            return
                        }
                        if (!busy.add(ticket to i)) { ex.fail(503,"Chunk busy"); return }
                    }
                    else -> throw BadReq("Unknown operation")
                }
                tmp
            }
            try {
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(i.toLong()*CHUNK)
                    if (hash(ex.body,length) { b,n -> notice?.let { PcTransferNotice.awaitIncoming(it) }; out.write(b,0,n) } != digest) throw BadReq("Chunk digest mismatch")
                    out.fd.sync()
                }
                synchronized(gate) {
                    val state = read(journal)
                    state.getJSONObject("chunks").put(i.toString(),digest)
                    save(journal,state)
                }
                ex.json(JSONObject().put("ok",true))
            } finally { synchronized(gate) { busy.remove(ticket to i) } }
        } finally { slots.release() }
    }
}
