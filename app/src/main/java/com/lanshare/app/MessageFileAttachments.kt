package com.lanshare.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.lanshare.app.core.Http
import com.lanshare.app.core.PcTransferNotice
import com.lanshare.app.core.Peer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends files selected from the Messages attachment picker over the SAME
 * /p/pcshare-transfer HTTP POST protocol as the existing Send to PC feature.
 * Both LAN Suite Windows and Android PCShare save received files under
 * Downloads/pcshare; this class does not add a new listener or storage route.
 *
 * Storage Access Framework URIs are streamed directly when their length is
 * known. For providers that don't report a length, a temporary on-disk copy
 * is used because the existing receiver requires an exact Content-Length.
 */
internal object MessageFileAttachments {
    private val sending = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    private data class Part(
        val uri: Uri,
        val name: String,
        val size: Long,
        val staged: File?
    )

    fun send(context: Context, peer: Peer, uris: List<Uri>, done: (String?) -> Unit) {
        if (uris.isEmpty()) {
            main.post { done("No files selected") }
            return
        }
        if (!sending.compareAndSet(false, true)) {
            main.post { done("Another message attachment transfer is already running") }
            return
        }

        val app = context.applicationContext
        Thread({
            val temporaryFiles = ArrayList<File>()
            var progress: PcTransferNotice.Outgoing? = null
            var success = false
            var error: String? = null
            try {
                val files = uris.mapIndexed { index, uri ->
                    prepare(app, uri, index, temporaryFiles)
                }
                val bytes = files.fold(0L) { total, file -> Math.addExact(total, file.size) }
                val session = UUID.randomUUID().toString().replace("-", "")
                val headers = mapOf(
                    "X-Pcshare-Transfer-Id" to session,
                    "X-Pcshare-Total-Bytes" to bytes.toString(),
                    "X-Pcshare-Item-Count" to files.size.toString()
                )
                // Shared with the main explorer's file transfers; using its
                // progress state also gives the normal pause/cancel controls.
                val current = PcTransferNotice.beginOutgoing(
                    "send:" + session, peer.name, files.first().name, bytes, files.size)
                progress = current

                for (item in files) {
                    PcTransferNotice.awaitOutgoing(current)
                    PcTransferNotice.outgoingFile(current, item.name)
                    val url = "/p/pcshare-transfer?path=" +
                        URLEncoder.encode(item.name, "UTF-8")
                    val source = item.staged?.let { FileInputStream(it) }
                        ?: app.contentResolver.openInputStream(item.uri)
                        ?: throw IOException("Cannot read " + item.name)

                    source.use { stream ->
                        PcTransferNotice.outgoingStream(current, stream).use { gated ->
                            Http.request(
                                peer.ip, peer.port, "POST", url, headers,
                                300_000, gated, item.size, 5_000,
                                onSent = { n ->
                                    PcTransferNotice.outgoingBytes(current, n)
                                    PcTransferNotice.awaitOutgoing(current)
                                }
                            ).use { response ->
                                if (response.status !in 200..299)
                                    throw IOException(
                                        "Transfer rejected for " + item.name +
                                        " (HTTP " + response.status + "): " +
                                        String(response.readUpTo(2048), Charsets.UTF_8)
                                    )
                            }
                        }
                    }
                    PcTransferNotice.outgoingItemDone(current)
                }
                success = true
            } catch (failure: Exception) {
                error = failure.message ?: "Attachment transfer failed"
            } finally {
                progress?.let { PcTransferNotice.endOutgoing(it, success, error) }
                for (staged in temporaryFiles) {
                    try { staged.delete() } catch (_: Exception) {}
                }
                sending.set(false)
                val result = error
                main.post { done(result) }
            }
        }, "pcshare-message-attachments").apply {
            isDaemon = true
            start()
        }
    }

    private fun prepare(
        context: Context, uri: Uri, index: Int, temporaryFiles: MutableList<File>
    ): Part {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex))
                        name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex))
                        size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
            // Some document providers omit metadata. Open the URI below.
        }

        val safeName = safeFileName(name ?: uri.lastPathSegment ?: "attachment-${index + 1}")
        if (size < 0) {
            try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                    if (it.length >= 0) size = it.length
                }
            } catch (_: Exception) {}
        }

        if (size >= 0) return Part(uri, safeName, size, null)

        // Never load unknown-sized documents into RAM; stream to a temporary
        // file so the existing receiver gets an accurate Content-Length.
        val staged = File.createTempFile("pcshare-attachment-", ".part", context.cacheDir)
        temporaryFiles.add(staged)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open " + safeName)
        input.use { source ->
            FileOutputStream(staged).use { output -> source.copyTo(output, 131_072) }
        }
        return Part(uri, safeName, staged.length(), staged)
    }

    private fun safeFileName(raw: String): String {
        val filtered = buildString {
            for (ch in raw.take(220)) {
                append(
                    if (ch == '/' || ch == '\\' || ch == ':' ||
                        ch == '\u0000' || ch.code < 32) '_' else ch
                )
            }
        }.trim().trimEnd('.')
        return if (filtered.isEmpty() || filtered == "." || filtered == "..")
            "attachment" else filtered
    }
}
