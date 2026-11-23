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

    class State internal constructor(
        val key: String, val notificationId: Int, val peer: String,
        var fileName: String, var total: Long, val itemCount: Int
    ) {
        var received: Long = 0L
        var finishedItems: Int = 0
        val started: Long = SystemClock.elapsedRealtime()
        var lastNotified: Long = 0L
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
    fun monitor(ctx: Context, state: State, input: InputStream): InputStream =
        object : FilterInputStream(input) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) advance(ctx, state, 1)
                return b
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Use the wrapped stream directly to avoid counting twice.
                val n = input.read(buffer, offset, length)
                if (n > 0) advance(ctx, state, n)
                return n
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
            state.finishedItems++
            val finished = state.finishedItems >= state.itemCount
            publish(ctx, state, finished, null)
            if (finished) sessions.remove(state.key, state)
        }
    }

    fun failed(ctx: Context, state: State, message: String?) {
        synchronized(state) {
            publish(ctx, state, true, message?.take(120) ?: "Transfer failed")
            sessions.remove(state.key, state)
        }
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
