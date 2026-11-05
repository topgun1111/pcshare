package com.lanshare.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One place for every vibration in the app.
 * - Uses the Vibrator service directly with USAGE_ALARM attributes: Battery saver (and "vibration off in power saving" on
 *   Xiaomi / Samsung / Android 15) mutes touch-feedback vibrations, but alarm-usage vibrations are not muted by it.
 * - Falls back to View.performHapticFeedback (with the "ignore global setting" flag) when no Vibrator is available.
 * - Named effects (tick / click / heavy / success / error / warn) use the phone's own predefined effects (API 29+), with
 *   amplitude-controlled waveforms below that.
 */
object Haptics {
    @Volatile private var vib: Vibrator? = null
    @Volatile private var ctx: Context? = null
    @Volatile var enabled = true

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun init(c: Context) {
        ctx = c.applicationContext
        if (vib == null) vib = try {
            if (Build.VERSION.SDK_INT >= 31) (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        } catch (_: Throwable) { null }
    }

    private fun wave(v: Vibrator, t: LongArray, a: IntArray): VibrationEffect =
        if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(t, a, -1)
        else VibrationEffect.createWaveform(t, -1)

    private fun effect(v: Vibrator, kind: String): VibrationEffect? = try {
        val pre = Build.VERSION.SDK_INT >= 29
        when (kind) {
            "tick" -> if (pre) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else VibrationEffect.createOneShot(8, 90)
            "click" -> if (pre) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else VibrationEffect.createOneShot(14, 160)
            "heavy", "long" -> if (pre) VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK) else VibrationEffect.createOneShot(24, 255)
            "double" -> if (pre) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK) else wave(v, longArrayOf(0, 14, 60, 14), intArrayOf(0, 180, 0, 180))
            "success" -> wave(v, longArrayOf(0, 12, 55, 22), intArrayOf(0, 140, 0, 230))
            "error" -> wave(v, longArrayOf(0, 40, 50, 40, 50, 40), intArrayOf(0, 255, 0, 255, 0, 255))
            "warn" -> wave(v, longArrayOf(0, 30, 70, 30), intArrayOf(0, 220, 0, 220))
            "fail" -> wave(v, longArrayOf(0, 700), intArrayOf(0, 255))   // print failure: one long, full-strength buzz
            "select" -> if (pre) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else VibrationEffect.createOneShot(10, 110)
            else -> null
        }
    } catch (_: Throwable) { null }

    /** [kind]: tick | click | heavy | long | double | success | error | warn | fail (long) | select, or a number of ms. */
    fun play(kind: String) {
        if (!enabled) return
        val v = vib ?: return
        if (Build.VERSION.SDK_INT < 26) { try { @Suppress("DEPRECATION") v.vibrate(kind.toLongOrNull() ?: if (kind == "fail") 700L else if (kind == "tick" || kind == "select") 8L else if (kind == "heavy" || kind == "long") 25L else 14L) } catch (_: Throwable) {}; return }
        try {
            if (!v.hasVibrator()) return
            val ms = kind.toLongOrNull()
            val e = if (ms != null) VibrationEffect.createOneShot(ms.coerceIn(1, 400), VibrationEffect.DEFAULT_AMPLITUDE) else effect(v, kind) ?: return
            @Suppress("DEPRECATION") v.vibrate(e, attrs)
        } catch (_: Throwable) {
            try { @Suppress("DEPRECATION") v.vibrate(if (kind.toLongOrNull() != null) kind.toLong() else 15L) } catch (_: Throwable) {}
        }
    }

    /** WebView navigator.vibrate(pattern): a single ms value, or an on/off pattern array. */
    fun pattern(p: LongArray) {
        if (!enabled || p.isEmpty()) return
        if (p.size == 1) { play(p[0].toString()); return }
        val v = vib ?: return
        if (Build.VERSION.SDK_INT < 26) { try { @Suppress("DEPRECATION") v.vibrate(p, -1) } catch (_: Throwable) {}; return }
        try {
            val t = LongArray(p.size + (p.size and 1)) { if (it < p.size) p[it].coerceIn(0, 500) else 0 }
            val amp = IntArray(t.size) { if (it % 2 == 1) 200 else 0 }
            val e = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(t, amp, -1) else VibrationEffect.createWaveform(t, -1)
            @Suppress("DEPRECATION") v.vibrate(e, attrs)
        } catch (_: Throwable) {}
    }

    /** Native view feedback that survives the system "touch feedback" / battery-saver switches. */
    fun view(v: View, kind: String = "click") {
        if (!enabled) return
        if (vib?.hasVibrator() == true) { play(kind); return }
        val c = when (kind) { "long", "heavy" -> HapticFeedbackConstants.LONG_PRESS; "tick", "select" -> if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.KEYBOARD_TAP; else -> HapticFeedbackConstants.VIRTUAL_KEY }
        @Suppress("DEPRECATION") v.performHapticFeedback(c, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }
}
