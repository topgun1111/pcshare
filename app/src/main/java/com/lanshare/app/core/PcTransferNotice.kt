package com.lanshare.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.lanshare.app.MainActivity
import com.lanshare.app.R
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * One Android notification per incoming PC-to-phone transfer, including
 * grouped folder transfers. Notifications work when the UI is in the background.
 * All data stays on the existing unauthenticated LAN HTTP connection.
 */
object PcTransferNotice {
    private const val CHANNEL = "pcshare_files"
    private val sequence = AtomicInteger(7400)
    private val sessions = ConcurrentHashMap<String, State>()
    private val recent = ConcurrentHashMap<String, Snapshot>()
    private const val RECENT_MS = 15_000L

    /** Immutable progress information for the in-app incoming-transfer ring and its details sheet. */
    data class Snapshot(
        val key: String,
        val peer: String,
        val fileName: String,
        val received: Long,
        val total: Long,
        val finishedItems: Int,
        val itemCount: Int,
        val started: Long,
        val finishedAt: Long,
        val status: String,
        val error: String?,
        val bytesPerSecond: Double,
        val direction: String = "incoming"
    ) {
        val percent: Int get() = if (total > 0L)
            (received.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(0, 100)
        else 0
        val etaSeconds: Long? get() = if ((status == "receiving" || status == "sending") && total > received && bytesPerSecond > 0.0)
            ((total - received).toDouble() / bytesPerSecond).toLong()
        else null
    }

    private fun snapshotOf(s: State, now: Long, status: String, error: String? = null): Snapshot {
        val elapsed = max(0.25, (now - s.started) / 1000.0)
        return Snapshot(
            s.key, s.peer, s.fileName, s.received, s.total,
            s.finishedItems, s.itemCount, s.started,
            if (status == "receiving" || status == "paused" || status == "cancelling") 0L else now, status, error, s.received / elapsed
        )
    }

    /** Thread-safe snapshots; preserve a short finished/failed history so a fast transfer remains tappable. */
    fun snapshots(): List<Snapshot> {
        val now = SystemClock.elapsedRealtime()
        for ((key, value) in recent) {
            if (now - value.finishedAt > RECENT_MS) recent.remove(key, value)
        }
        val live = sessions.values.map { s -> synchronized(s) {
            snapshotOf(s, now, when { s.cancelled -> "cancelling"; s.paused -> "paused"; else -> "receiving" })
        } }
        val outgoingLive = outgoing?.let { s -> synchronized(s) { outgoingSnapshot(s, now) } }
        val all = live + listOfNotNull(outgoingLive) + recent.values
        return all.sortedWith(
            compareBy<Snapshot> { it.status !in listOf("receiving", "sending", "paused", "cancelling") }
                .thenByDescending { it.started }
        )
    }

    class State internal constructor(
        val key: String, val notificationId: Int, val peer: String,
        var fileName: String, var total: Long, val itemCount: Int
    ) {
        var received: Long = 0L
        var finishedItems: Int = 0
        val started: Long = SystemClock.elapsedRealtime()
        var lastNotified: Long = 0L
        @Volatile var paused = false
        @Volatile var cancelled = false
        val gate = java.lang.Object()
        @Volatile var activeInput: InputStream? = null
    }

    fun begin(ctx: Context, key: String, peer: String, fileName: String, total: Long, items: Int): State {
        val state = sessions.computeIfAbsent(key) {
            State(key, sequence.incrementAndGet(), peer, fileName, max(0L, total), items.coerceAtLeast(1))
        }
        synchronized(state) {
            state.fileName = fileName.takeLast(100)
            state.total = max(state.total, max(0L, total))
            publish(ctx, state, false, null)
        }
        return state
    }

    /** Count bytes actually read by LocalFs.write, without buffering the file in memory. */
    fun monitor(ctx: Context, state: State, input: InputStream): InputStream {
        state.activeInput = input
        return object : FilterInputStream(input) {
            override fun read(): Int {
                awaitIncoming(state)
                val b = super.read()
                if (b >= 0) advance(ctx, state, 1)
                return b
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Use the wrapped stream directly to avoid counting twice.
                awaitIncoming(state)
                val n = input.read(buffer, offset, length)
                if (state.cancelled) throw java.io.IOException("Transfer cancelled")
                if (n > 0) advance(ctx, state, n)
                return n
            }
        }
    }

    private fun advance(ctx: Context, state: State, amount: Int) {
        synchronized(state) {
            state.received += amount.toLong()
            val now = SystemClock.elapsedRealtime()
            if (now - state.lastNotified >= 300) publish(ctx, state, false, null)
        }
    }

    fun itemDone(ctx: Context, state: State) {
        synchronized(state) {
            if (state.cancelled) throw java.io.IOException("Transfer cancelled")
            state.finishedItems++
            val finished = state.finishedItems >= state.itemCount
            publish(ctx, state, finished, null)
            if (finished) {
                state.activeInput = null
                recent[state.key] = snapshotOf(state, SystemClock.elapsedRealtime(), "completed")
                sessions.remove(state.key, state)
            }
        }
    }

    fun failed(ctx: Context, state: State, message: String?) {
        synchronized(state) {
            state.activeInput = null
            val reason = message?.take(120) ?: "Transfer failed"
            publish(ctx, state, true, reason)
            recent[state.key] = snapshotOf(state, SystemClock.elapsedRealtime(),
                if (state.cancelled) "cancelled" else "failed", reason)
            sessions.remove(state.key, state)
        }
    }


    /** Cooperative pause/cancel for both incoming and outgoing streams. No new network port. */
    private fun awaitIncoming(s: State) {
        synchronized(s.gate) {
            while (s.paused && !s.cancelled) s.gate.wait(250L)
            if (s.cancelled) throw java.io.IOException("Transfer cancelled")
        }
    }

    /** The active phone-to-PC send shares the same ring as PC-to-phone receives. */
    class Outgoing internal constructor(val key: String, val peer: String, var fileName: String,
                                        val total: Long, val itemCount: Int) {
        val started = SystemClock.elapsedRealtime()
        var sent = 0L
        var items = 0
        @Volatile var paused = false
        @Volatile var cancelled = false
        val gate = java.lang.Object()
    }
    @Volatile private var outgoing: Outgoing? = null

    private fun outgoingSnapshot(s: Outgoing, now: Long): Snapshot {
        val elapsed = max(0.25, (now - s.started) / 1000.0)
        return Snapshot(s.key, s.peer, s.fileName, s.sent, s.total, s.items, s.itemCount,
            s.started, 0L, if (s.cancelled) "cancelling" else if (s.paused) "paused" else "sending",
            null, s.sent / elapsed, "outgoing")
    }

    @Synchronized
    fun beginOutgoing(key: String, peer: String, fileName: String, total: Long, items: Int): Outgoing {
        // Both the explorer's Send to PC action and the Messages attachment
        // button share this single progress/cancellation state. Refuse a
        // second send rather than silently replacing the active transfer.
        if (outgoing != null) throw java.io.IOException("Another file transfer is running")
        val state = Outgoing(key, peer, fileName.takeLast(100), total.coerceAtLeast(0L), items.coerceAtLeast(1))
        outgoing = state
        return state
    }

    fun outgoingFile(state: Outgoing, name: String) {
        synchronized(state) { state.fileName = name.takeLast(100) }
    }

    fun outgoingBytes(state: Outgoing, n: Int) {
        synchronized(state) { state.sent += n.coerceAtLeast(0).toLong() }
    }

    fun outgoingItemDone(state: Outgoing) {
        synchronized(state) { state.items++ }
    }

    /** Called before each read; cancellation aborts the POST and never reports success. */
    fun outgoingStream(state: Outgoing, source: InputStream): InputStream =
        object : FilterInputStream(source) {
            override fun read(): Int { awaitOutgoing(state); return source.read() }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                awaitOutgoing(state)
                return source.read(buffer, offset, length)
            }
        }

    fun awaitOutgoing(state: Outgoing) {
        synchronized(state.gate) {
            while (state.paused && !state.cancelled) state.gate.wait(250L)
            if (state.cancelled) throw java.io.IOException("Transfer cancelled")
        }
    }

    @Synchronized
    fun endOutgoing(state: Outgoing, success: Boolean, message: String? = null) {
        synchronized(state) {
            val now = SystemClock.elapsedRealtime()
            val finished = outgoingSnapshot(state, now).copy(
                finishedAt = now,
                status = if (state.cancelled) "cancelled" else if (success) "completed" else "failed",
                error = if (success) null else message?.take(160)
            )
            recent[state.key] = finished
            if (outgoing === state) outgoing = null
        }
    }

    fun control(key: String, action: String): Boolean {
        val out = outgoing
        if (out != null && out.key == key) {
            synchronized(out.gate) {
                when (action) {
                    "pause" -> if (!out.cancelled) out.paused = true else return false
                    "resume" -> out.paused = false
                    "cancel" -> { out.cancelled = true; out.paused = false }
                    else -> return false
                }
                out.gate.notifyAll()
            }
            return true
        }
        val incoming = sessions[key] ?: return false
        synchronized(incoming.gate) {
            when (action) {
                "pause" -> if (!incoming.cancelled) incoming.paused = true else return false
                "resume" -> incoming.paused = false
                "cancel" -> { incoming.cancelled = true; incoming.paused = false }
                else -> return false
            }
            incoming.gate.notifyAll()
        }
        if (action == "cancel") {
            // Interrupt socket reads as well as paused threads, so cancelled receives
            // cannot remain blocked until the sender's next network packet.
            try { incoming.activeInput?.close() } catch (_: java.io.IOException) {}
        }
        return true
    }

    private fun formatSize(n: Double): String {
        var amount = max(0.0, n)
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var unit = 0
        while (amount >= 1024 && unit < units.lastIndex) {
            amount /= 1024
            unit++
        }
        return if (unit == 0) "${amount.toLong()} B" else String.format(java.util.Locale.US, "%.1f %s", amount, units[unit])
    }

    private fun publish(ctx: Context, state: State, done: Boolean, error: String?) {
        try {
            state.lastNotified = SystemClock.elapsedRealtime()
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "PCShare file transfers", NotificationManager.IMPORTANCE_LOW)
            )
            val total = state.total
            val completed = state.received
            val elapsed = max(0.25, (state.lastNotified - state.started) / 1000.0)
            val rate = completed / elapsed
            val pct = if (total > 0) (completed.toDouble() * 100.0 / total).toInt().coerceIn(0, 100) else 0
            val eta = if (rate > 0.0 && total > 0L) {
                val seconds = ((total - completed).coerceAtLeast(0L) / rate).toLong()
                if (seconds >= 3600) "${seconds / 3600}h ${(seconds % 3600) / 60}m"
                else "${seconds / 60}m ${seconds % 60}s"
            } else "calculating"
            val title = when {
                error != null -> "PC file transfer failed"
                done -> "Files received from PC"
                else -> "Receiving from PC"
            }
            val detail = when {
                error != null -> error
                done -> "${formatSize(completed.toDouble())} received · Downloads/pcshare"
                else -> "${pct}% · ${formatSize(completed.toDouble())} / ${formatSize(total.toDouble())} · ${formatSize(rate)}/s · ETA $eta"
            }
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${state.fileName}\n$detail"))
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .setOngoing(!done)
                .setAutoCancel(done)
                .setProgress(100, if (done && error == null) 100 else pct, !done && total <= 0)
                .build()
            manager.notify(state.notificationId, notification)
        } catch (_: Exception) {
            // Notifications must never abort an incoming network transfer.
        }
    }
}
