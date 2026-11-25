package com.lanshare.app.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The foreground service reopens a persisted batch when its original peer reappears. */
internal class PendingUpload(context: Context, peer: Peer, val items: JSONArray) {
    private val prefs = context.getSharedPreferences("pcshare-pending-batches",Context.MODE_PRIVATE)
    private val manifest = JSONObject().put("peer",peer.id).put("items",items).toString()
    private val key = MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray()).joinToString("") { "%02x".format(it) }
    var saved = prefs.contains(key)
        private set

    fun record() {
        if (!saved && !prefs.edit().putString(key,manifest).commit()) throw IOException("Cannot save batch")
        saved = true
        attempted.add(key)
    }

    fun clear() {
        if (saved && !prefs.edit().remove(key).commit()) throw IOException("Cannot clear batch")
        saved = false
        attempted.remove(key)
    }

    companion object {
        private val attempted = ConcurrentHashMap.newKeySet<String>()
        private val checking = AtomicBoolean()

        fun item(source: String, path: String, size: Long, directory: Boolean = false) =
            JSONObject().put("source",source).put("path",path).put("size",size).put("dir",directory)

        fun recover(context: Context) {
            if (!checking.compareAndSet(false,true)) return
            Thread({
                try {
                    val prefs = context.getSharedPreferences("pcshare-pending-batches",Context.MODE_PRIVATE)
                    for ((key,raw) in prefs.all) {
                        if (key in attempted) continue
                        val job = JSONObject(raw as String)
                        val peerId = job.getString("peer")
                        val peer = PcText.get(peerId) ?: Core.discOrNull()?.get(peerId) ?: continue
                        val items = job.getJSONArray("items")
                        val entries = (0 until items.length()).map { items.getJSONObject(it) }
                        val state = try { PcTransferNotice.beginOutgoing("send:"+key,peer.name,entries.first().getString("path"),
                            entries.sumOf { it.getLong("size") },entries.size) } catch (_: IOException) { return@Thread }
                        attempted.add(key)
                        val batch = PendingUpload(context,peer,items)
                        try {
                            val upload = TransferUpload(context,peer,state,batch)
                            upload.requireV2()
                            for (entry in entries) {
                                PcTransferNotice.awaitOutgoing(state)
                                val rel = entry.getString("path")
                                val source = entry.getString("source")
                                PcTransferNotice.outgoingFile(state,rel)
                                if (entry.getBoolean("dir")) {
                                    if (!upload.directory(rel)) throw IOException("Peer does not support resumed directory transfers")
                                } else {
                                    val open: () -> InputStream = {
                                        if (source.startsWith("/")) {
                                            val file = File(source)
                                            val root = Core.local.root.canonicalPath.trimEnd('/')+"/"
                                            if (file.canonicalPath != source || !source.startsWith(root)) throw IOException("Source moved")
                                            file.inputStream()
                                        } else context.contentResolver.openInputStream(Uri.parse(source))
                                            ?: throw IOException("Cannot reopen attachment")
                                    }
                                    if (!upload.send(rel,source,entry.getLong("size"),open) { n -> PcTransferNotice.outgoingBytes(state,n) })
                                        throw IOException("Peer no longer supports resume")
                                }
                                PcTransferNotice.outgoingItemDone(state)
                            }
                            upload.batchDone()
                            PcTransferNotice.endOutgoing(state,true)
                        } catch (e: Exception) {
                            if (state.cancelled) batch.clear()
                            PcTransferNotice.endOutgoing(state,false,e.message)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PCShare","Could not reopen pending batch",e)
                } finally { checking.set(false) }
            },"pcshare-resume").apply { isDaemon = true; start() }
        }
    }
}
