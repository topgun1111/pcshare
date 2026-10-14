package com.lanshare.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lanshare.app.core.Cfg
import com.lanshare.app.core.Core
import com.lanshare.app.core.NotFound
import com.lanshare.app.core.Notes
import com.lanshare.app.core.Peer
import com.lanshare.app.core.PcText
import com.lanshare.app.core.RemoteFs
import java.io.IOException

/** Shared by the share-sheet entry, Direct Share, the Quick Settings tile, the notification quick-reply, clipboard sync and the inbox screen: who text goes to, and the sending itself. */
object TextSend {
    const val ACT_SEND = "com.lanshare.app.SEND_CLIP"

    /** Devices that answered a live check: phones / PCs running LANShare, plus PCs running pcprint.py v26+ ("<name> (PC)"). */
    fun online(): List<Peer> {
        PcText.kick()
        val lan = try { Core.discOrNull()?.list()?.filter { it.ok }.orEmpty() } catch (_: Throwable) { emptyList() }
        return lan + PcText.peers()
    }

    /** A device by id (a LANShare peer or a "pc:<ip>" PC); null when unknown / not seen. */
    fun find(id: String): Peer? = try { PcText.get(id) ?: Core.discOrNull()?.get(id) } catch (_: Throwable) { null }

    /** Where text goes without asking: the chosen default if it is online, else - when no default is set - the only online device, else null. */
    fun target(): Peer? {
        val on = online()
        val d = Cfg.defTarget()
        return on.firstOrNull { it.id == d } ?: if (d.isEmpty() && on.size == 1) on[0] else null
    }

    /** Name of the chosen default device (even when it is offline), or null. */
    fun defaultName(): String? {
        val d = Cfg.defTarget()
        if (d.isEmpty()) return null
        return find(d)?.name
    }

    /** Starts the background service (and with it discovery + the server) when the process was killed. */
    fun ensureRunning(ctx: Context) {
        if (Core.discOrNull() != null) return
        try { ContextCompat.startForegroundService(ctx, Intent(ctx, LanShareService::class.java)) } catch (_: Throwable) {}
    }

    /** Sends [text] to [peerId] on a background thread; [done] runs on the main thread with (error message or null, device name). [quiet] = clipboard-sync push. */
    fun send(text: String, peerId: String, quiet: Boolean = false, done: (String?, String) -> Unit) {
        Thread {
            var name = ""
            val err: String? = try {
                val peer = find(peerId) ?: throw IOException("that device is offline")
                name = peer.name
                try { RemoteFs(peer).pushClip(text, Cfg.name, quiet) }
                catch (e: NotFound) {
                    throw IOException(if (peerId.startsWith(PcText.PREFIX)) "pcprint.py on ${peer.name} is older than v26 - run the new file once on that PC"
                                      else "${peer.name} runs an older LANShare - update it to receive text")
                }
                if (!quiet) Notes.add(true, peer.name, text)
                null
            } catch (e: Throwable) { e.message ?: e.toString() }
            Handler(Looper.getMainLooper()).post { done(err, name) }
        }.also { it.isDaemon = true }.start()
    }
}
