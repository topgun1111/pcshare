package com.lanshare.app.core

import android.content.Context
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal class TransferUpload(context: Context, private val peer: Peer, private val progress: PcTransferNotice.Outgoing, private val batch: PendingUpload) {
    companion object { private val pool = Executors.newFixedThreadPool(8) }
    private val jobs = context.getSharedPreferences("pcshare-outbound-v2", Context.MODE_PRIVATE)
    private val expected = peer.id.removePrefix(PcText.PREFIX)
    private val finished = ArrayList<String>()
    @Volatile private var verified: Peer? = null
    private var supportsV2: Boolean? = null
    private class Rejected(val status: Int, message: String): IOException(message)

    private fun request(op: String, ticket: String? = null, query: String = "", sha: String? = null,
                        size: Int = 0, probeTarget: Peer? = null, source: (() -> InputStream)? = null): JSONObject {
        val endpoint = probeTarget ?: verified ?: throw IOException("Peer has not been verified")
        val url = URL("http", endpoint.ip, endpoint.port, "/p/pcshare-v2?op=$op$query")
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 5000
            conn.readTimeout = if (op == "finish") 600000 else 60000
            conn.setRequestProperty("Accept-Encoding","identity")
            if (ticket != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(size)
                conn.setRequestProperty("X-Pcshare-Ticket",ticket)
                if (sha != null) conn.setRequestProperty("X-Pcshare-Sha256",sha)
                conn.outputStream.use { out ->
                    source?.invoke()?.use { input ->
                        TransferV2.hash(input,size.toLong()) { b,n ->
                            PcTransferNotice.awaitOutgoing(progress)
                            out.write(b,0,n)
                        }
                    }
                }
            }
            val status = conn.responseCode
            if (status !in 200..299) throw Rejected(status,"Transfer $op returned HTTP $status")
            val bytes = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (bytes.size()+n > 131072) throw IOException("Oversized response")
                    bytes.write(buf,0,n)
                }
            }
            return JSONObject(bytes.toString("UTF-8"))
        } finally { conn.disconnect() }
    }

    private fun probe(): Boolean {
        val candidate = (PcText.get(peer.id) ?: Core.discOrNull()?.get(peer.id) ?: peer).snapshot()
        val caps = try { request("caps",probeTarget=candidate) } catch (e: Rejected) {
            if (e.status == 404 || e.status == 405) return false
            throw e
        }
        if (caps.getInt("version") != 2 || caps.getInt("chunk") != TransferV2.CHUNK ||
            caps.getString("peer") != expected) throw IOException("Peer identity or protocol changed")
        verified = candidate
        return true
    }

    private fun <T> retry(work: () -> T): T {
        for (attempt in 0..12) {
            PcTransferNotice.awaitOutgoing(progress)
            try {
                if (attempt > 0 && !probe()) throw Rejected(409,"Peer no longer supports resume")
                return work()
            } catch (e: IOException) {
                if (attempt == 12 || (e is Rejected && e.status !in listOf(408,429,502,503,504))) throw e
                repeat(minOf(128, 2 shl attempt)) {
                    PcTransferNotice.awaitOutgoing(progress)
                    Thread.sleep(125)
                }
            }
        }
        throw IOException("Retry limit reached")
    }

    fun requireV2() {
        if (!retry { probe() }) throw IOException("Partial transfer requires the original v2 peer")
        supportsV2 = true
    }

    fun directory(path: String): Boolean {
        if (supportsV2 == null) supportsV2 = retry { probe() }
        if (supportsV2 != true) {
            if (batch.saved) throw IOException("Partial transfer requires the original v2 peer")
            return false
        }
        batch.record()
        val ticket = UUID.randomUUID().toString().replace("-","")
        retry { request("mkdir",ticket,"&path="+URLEncoder.encode(path,"UTF-8")) }
        return true
    }

    fun send(path: String, identity: String, size: Long, open: () -> InputStream, advanced: (Int) -> Unit): Boolean {
        val name = JSONObject().put("peer",peer.id).put("source",identity).put("path",path).toString()
        val key = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val saved = jobs.getString(key,null)?.let { JSONObject(it) }
        if (supportsV2 == null) supportsV2 = retry { probe() }
        if (supportsV2 != true) {
            if (saved != null || batch.saved) throw IOException("Partial transfer requires the original v2 peer")
            return false
        }
        batch.record()
        if (size !in 0L..(64L shl 30)) throw IOException("Invalid transfer size")
        fun at(offset: Long): InputStream {
            val input = open()
            try {
                if (offset > 0 && input is FileInputStream) {
                    try { input.channel.position(Math.addExact(input.channel.position(),offset)); return input } catch (_: IOException) {}
                }
                var left = offset
                val scratch = ByteArray(256 * 1024)
                while (left > 0) {
                    PcTransferNotice.awaitOutgoing(progress)
                    val n = input.read(scratch,0,minOf(left,scratch.size.toLong()).toInt())
                    if (n <= 0) throw IOException("Source shortened")
                    left -= n
                }
                return input
            } catch (e: Throwable) { input.close(); throw e }
        }
        val sha = open().use { input ->
            TransferV2.hash(input,size) { _,_ -> PcTransferNotice.awaitOutgoing(progress) }.also {
                if (input.read() >= 0) throw IOException("Source grew")
            }
        }
        if (saved != null && saved.getString("sha") != sha) throw IOException("Source changed since interruption")
        val ticket = saved?.getString("ticket") ?: UUID.randomUUID().toString().replace("-","")
        val record = saved ?: JSONObject().put("ticket",ticket).put("sha",sha).put("job",name)
        if (!jobs.edit().putString(key,record.toString()).commit())
            throw IOException("Cannot save transfer ticket")
        val chunk = TransferV2.CHUNK
        try {
            var repairs = 0
            val reported = HashSet<Int>()
            while (true) {
                val resume = if (record.optBoolean("confirmed")) "&resume=1" else ""
                val state = retry { request("init",ticket,"&size=$size&sha=$sha$resume&path="+URLEncoder.encode(path,"UTF-8")) }
                if (!record.optBoolean("confirmed") && !jobs.edit().putString(key,record.put("confirmed",true).toString()).commit())
                    throw IOException("Cannot save acknowledgement")
                val have = state.getJSONArray("have")
                val complete = (0 until have.length()).map { have.getInt(it) }.toSet()
                val count = ((size+chunk-1)/chunk).toInt()
                fun length(i: Int) = minOf(chunk.toLong(), size-i.toLong()*chunk).toInt()
                for (i in complete) {
                    if (i !in 0 until count) throw IOException("Invalid completion map")
                    if (reported.add(i)) advanced(length(i))
                }
                val missing = (0 until count).filter { it !in complete }
                run {
                    var pos = 0
                    var workers = 1
                    var lastRate = 0.0
                    while (pos < missing.size) {
                        val wave = missing.subList(pos,minOf(missing.size,pos+workers))
                        val start = System.nanoTime()
                        val tasks = wave.map { i -> Callable {
                            val n = length(i)
                            val offset = i.toLong()*chunk
                            val digest = at(offset).use { TransferV2.hash(it,n.toLong()) }
                            retry { request("chunk",ticket,"&i=$i",digest,n) { at(offset) } }
                            n
                        } }
                        for ((index,result) in pool.invokeAll(tasks).withIndex()) {
                            try { val amount = result.get(); if (reported.add(wave[index])) advanced(amount) } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
                        }
                        val rate = wave.sumOf { length(it).toLong() }.toDouble() / maxOf(1L,System.nanoTime()-start)
                        workers = if (rate >= lastRate*0.97) minOf(8,workers*2) else maxOf(1,workers/2)
                        lastRate = rate
                        pos += wave.size
                    }
                }
                try {
                    if (!retry { request("finish",ticket) }.getBoolean("done")) throw IOException("Missing completion receipt")
                    break
                } catch (e: Rejected) {
                    if (e.status != 409 || repairs++ > 0 || retry { request("status",ticket) }.getJSONArray("have").length() >= count) throw e
                }
            }
            finished.add(key)
            return true
        } catch (e: Throwable) {
            if (progress.cancelled) {
                batch.clear()
                runCatching { request("cancel",ticket) }
                jobs.edit().remove(key).commit()
            }
            throw e
        }
    }

    fun batchDone() {
        batch.clear()
        val edit = jobs.edit()
        finished.forEach { edit.remove(it) }
        if (!edit.commit()) throw IOException("Cannot clear completed transfer tickets")
    }
}
