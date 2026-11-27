package com.lanshare.app

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import android.view.WindowManager
import com.lanshare.app.core.LumenFrameStats
import java.util.Locale

/** Explicit five-second measurement of MainActivity's window, including the native list.
 * All state and callbacks are on the main looper; no listeners or recurring work while idle.
 * Only aggregates are retained, in memory; no filenames, content, or screenshots. */
object LumenFrameProbe {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var armedUntil = 0L
    private var pending: Runnable? = null
    private var active: Session? = null
    private var last = "No frame test recorded."
    private const val SAMPLE_MS = 5000L
    private const val START_DELAY_MS = 2000L

    fun arm() {
        if (active != null || pending != null) return
        armedUntil = SystemClock.elapsedRealtime() + 15000L
        last = "Armed. Return to the file browser within 15 seconds."
    }

    fun report(): String = when {
        active != null -> "Recording window frames…"
        pending != null -> "Frame test starts shortly."
        armedUntil != 0L && SystemClock.elapsedRealtime() > armedUntil -> "Test expired before the file browser resumed. Start again."
        else -> last
    }

    fun resumed(a: Activity) {
        if (a !is MainActivity || armedUntil == 0L) return
        val valid = SystemClock.elapsedRealtime() <= armedUntil
        armedUntil = 0L
        if (!valid) { last = "Test expired. Start again."; return }
        val task = Runnable {
            pending = null
            if (!a.isFinishing && !a.isDestroyed) start(a)
        }
        pending = task
        handler.postDelayed(task, START_DELAY_MS)
    }

    fun paused(a: Activity) {
        if (a !is MainActivity) return
        pending?.let { handler.removeCallbacks(it); pending = null; last = "Test interrupted before recording." }
        active?.takeIf { it.activity === a }?.let { finish(it, true) }
    }

    private fun secure(a: Activity) = a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun refresh(a: Activity): Float = a.window.decorView.display?.refreshRate
        ?.takeIf { it.isFinite() && it > 0f } ?: 60f

    private class Session(val activity: Activity, val hz: Float, val glass: Int, val saver: Boolean,
                          val reduced: Boolean, val started: Long) {
        val stats = LumenFrameStats()
        var fallbackFrames = 0
        var rateChanged = false
        lateinit var listener: Window.OnFrameMetricsAvailableListener
        lateinit var end: Runnable
    }

    private fun start(a: Activity) {
        if (secure(a) || !a.window.decorView.isHardwareAccelerated) {
            last = "Frame test unavailable for secure or non-hardware-accelerated windows."
            return
        }
        val s = Session(a, refresh(a), LumenGlass.step(a), LumenPrefs.saver(a),
            LumenPrefs.reduceMotion(a), SystemClock.elapsedRealtime())
        s.listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            if (active === s) {
                if (secure(a)) {
                    finish(s, true)
                } else {
                    val hz = refresh(a)
                    if (kotlin.math.abs(hz - s.hz) > 0.5f) s.rateChanged = true
                    val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
                    val budget = if (deadline > 0) deadline else {
                        s.fallbackFrames++
                        (1_000_000_000.0 / hz).toLong()
                    }
                    s.stats.record(metrics.getMetric(FrameMetrics.TOTAL_DURATION), budget, dropped)
                }
            }
        }
        s.end = Runnable { finish(s, false) }
        active = s
        try {
            a.window.addOnFrameMetricsAvailableListener(s.listener, handler)
            handler.postDelayed(s.end, SAMPLE_MS)
        } catch (_: Exception) {
            active = null
            try { a.window.removeOnFrameMetricsAvailableListener(s.listener) } catch (_: Exception) { }
            last = "Android window frame metrics are unavailable."
        }
    }

    private fun finish(s: Session, interrupted: Boolean) {
        if (active !== s) return
        active = null
        handler.removeCallbacks(s.end)
        try { s.activity.window.removeOnFrameMetricsAvailableListener(s.listener) } catch (_: Exception) { }
        val v = s.stats.snapshot()
        fun ms(n: Long) = String.format(Locale.US, "%.2f", n / 1_000_000.0)
        val pct = if (v.frames == 0) "n/a" else String.format(Locale.US, "%.1f%%", v.latePercent)
        last = buildString {
            append(if (interrupted) "Interrupted sample" else "Completed sample")
            append(" · " + (SystemClock.elapsedRealtime() - s.started) + " ms\n")
            append("Display at start: " + s.hz + " Hz; window glass policy: F" + s.glass + "\n")
            append("Battery saver: " + s.saver + "; reduced motion: " + s.reduced + "\n")
            append("Measured frames: " + v.frames + "\n")
            append("Over frame budget: " + v.late + " (" + pct + ")\n")
            if (v.frames > 0) append("Frame time (ms): median " + ms(v.medianNs) + ", p95 " + ms(v.p95Ns) + ", worst " + ms(v.maxNs) + "\n")
            append("Lost metric reports: " + v.lostReports + "; invalid: " + v.invalid + "; overflow: " + v.overflow + "\n")
            append("Refresh-rate fallback frames: " + s.fallbackFrames + "\n")
            if (s.rateChanged) append("Refresh rate changed during measurement.\n")
            if (v.frames < 30 || v.lostReports > 0 || interrupted || v.invalid > 0 || v.overflow > 0)
                append("Limited sample; repeat while continuously scrolling.\n")
            append("Window render times, not physical display drops or GPU utilization. Idle frames are not generated. Glass policy is not a guarantee of live blur.")
        }
    }
}
