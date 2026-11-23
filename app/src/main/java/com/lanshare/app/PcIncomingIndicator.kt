package com.lanshare.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.lanshare.app.core.PcTransferNotice
import com.lanshare.app.core.vault.VaultSession
import kotlin.math.max
import java.util.Locale

/**
 * Floating, Lumen-themed incoming PC transfer ring.
 *
 * Reads the transfer registry in memory; no network requests or WebView polling.
 * The app notification continues to handle progress while the Activity is backgrounded.
 */
class PcIncomingIndicator(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private var entries: List<PcTransferNotice.Snapshot> = emptyList()
    private var running = false
    private var detailDialog: AlertDialog? = null
    private var detailText: TextView? = null

    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            entries = PcTransferNotice.snapshots()
            visibility = if (entries.isEmpty()) GONE else VISIBLE
            if (entries.isNotEmpty()) {
                val active = entries.count { it.status == "receiving" }
                contentDescription = if (VaultSession.unlocked) "Incoming PC transfer" else
                    if (active > 0) "$"+"active incoming PC transfer(s), tap for details" else
                        "Recently received PC files, tap for details"
            }
            detailText?.text = details()
            invalidate()
            handler.postDelayed(this, 450L)
        }
    }

    init {
        isClickable = true
        isFocusable = true
        visibility = GONE
        setOnClickListener { showDetails() }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(refresh)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(refresh)
        detailDialog?.dismiss()
        detailDialog = null
        detailText = null
    }

    private fun dp(n: Float) = n * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return

        val theme = Lumen.of(context)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f - dp(2f)).coerceAtLeast(0f)

        paint.style = Paint.Style.FILL
        paint.color = theme.surfaceHi
        canvas.drawCircle(cx, cy, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = theme.edge
        canvas.drawCircle(cx, cy, radius - dp(0.5f), paint)

        val active = entries.filter { it.status == "receiving" }
        val current = if (active.isNotEmpty()) active else entries.take(1)
        val failed = active.isEmpty() && current.any { it.status == "failed" }
        val complete = active.isEmpty() && !failed
        val allKnown = current.isNotEmpty() && current.all { it.total > 0L }
        val total = current.sumOf { it.total.toDouble() }
        val done = current.sumOf { it.received.toDouble().coerceAtMost(it.total.toDouble()) }
        val percent = if (complete) 100 else if (allKnown && total > 0.0)
            (done * 100.0 / total).toInt().coerceIn(0, 100) else 0

        ring.set(cx - dp(19f), cy - dp(19f), cx + dp(19f), cy + dp(19f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = theme.edge
        canvas.drawArc(ring, -90f, 360f, false, paint)
        paint.color = if (failed) theme.error else if (complete) theme.success else theme.primary
        val start = if (allKnown || complete) -90f else ((SystemClock.uptimeMillis() / 12L) % 360L).toFloat() - 90f
        val sweep = if (allKnown || complete) max(1f, percent * 3.6f) else 82f
        canvas.drawArc(ring, start, sweep, false, paint)
        paint.style = Paint.Style.FILL

        val label = when {
            failed -> "!"
            complete -> "✓"
            !allKnown -> "↓"
            else -> percent.toString() + "%"
        }
        paint.color = theme.ink
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        paint.textSize = dp(if (label.length > 3) 11f else 14f)
        val metrics = paint.fontMetrics
        canvas.drawText(label, cx, cy - (metrics.ascent + metrics.descent) / 2f, paint)

        if (active.size > 1) {
            paint.color = theme.primary
            canvas.drawCircle(cx + dp(18f), cy - dp(18f), dp(9f), paint)
            paint.color = theme.onPrimary
            paint.textSize = dp(10f)
            val mm = paint.fontMetrics
            canvas.drawText(if (active.size > 9) "9+" else active.size.toString(),
                cx + dp(18f), cy - dp(18f) - (mm.ascent + mm.descent) / 2f, paint)
        }
    }

    private fun size(n: Long): String =
        android.text.format.Formatter.formatFileSize(context, n.coerceAtLeast(0L))

    private fun speed(n: Double): String =
        size(n.coerceAtLeast(0.0).toLong()) + "/s"

    private fun eta(seconds: Long?): String = when {
        seconds == null -> "Calculating"
        seconds >= 3600L -> String.format(Locale.US, "%dh %dm", seconds / 3600L, (seconds % 3600L) / 60L)
        else -> String.format(Locale.US, "%dm %ds", seconds / 60L, seconds % 60L)
    }

    private fun details(): String {
        if (VaultSession.unlocked) return "Incoming transfers are active. File details are hidden while the vault is unlocked."
        if (entries.isEmpty()) return "No incoming PC transfers."
        return entries.joinToString("\n\n────────────\n\n") { s ->
            val status = when (s.status) {
                "receiving" -> "Receiving"
                "completed" -> "Completed"
                else -> "Failed"
            }
            buildString {
                append(status).append(" · ").append(s.peer.ifBlank { "PC" })
                append("\nFile: ").append(s.fileName)
                append("\nProgress: ")
                if (s.total > 0L) append(s.percent).append("% · ")
                append(size(s.received))
                if (s.total > 0L) append(" / ").append(size(s.total))
                append("\nItems: ").append(s.finishedItems).append(" / ").append(s.itemCount)
                if (s.status == "receiving") {
                    append("\nSpeed: ").append(speed(s.bytesPerSecond))
                    append("\nETA: ").append(eta(s.etaSeconds))
                }
                if (s.error != null) append("\nError: ").append(s.error)
                append("\nDestination: Downloads/pcshare")
            }
        }
    }

    private fun showDetails() {
        entries = PcTransferNotice.snapshots()
        if (entries.isEmpty()) return
        if (detailDialog?.isShowing == true) return

        val theme = Lumen.of(context)
        val body = TextView(context).apply {
            text = details()
            textSize = 14f
            setTextColor(theme.ink)
            setPadding(dp(22f).toInt(), dp(16f).toInt(), dp(22f).toInt(), dp(18f).toInt())
            setTextIsSelectable(true)
        }
        val scroller = ScrollView(context).apply { addView(body) }
        detailText = body
        val dialog = AlertDialog.Builder(context)
            .setTitle("Incoming transfers")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .create()
        detailDialog = dialog
        dialog.setOnDismissListener {
            if (detailDialog === dialog) {
                detailDialog = null
                detailText = null
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(theme.primary)
    }
}
