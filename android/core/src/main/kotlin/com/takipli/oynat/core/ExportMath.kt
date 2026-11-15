package com.takipli.oynat.core

import kotlin.math.max
import kotlin.math.min

// ---- Part 14: numbers the Media3 Transformer export needs (pure, so they are unit-tested on the JVM). ----

/**
 * A view as a GL matrix for a full-frame video: `[scale, tx, ty]` in NDC (x right, y UP, frame = -1..1), to be applied
 * as translate(tx, ty) then scale(scale, scale). The crop centre goes to the middle and the crop fills the frame.
 * Same centre clamp as `cropRect` / `viewTransform`; zoom never below 1x.
 */
fun exportNdc(v: View): DoubleArray {
    val tr = viewTransform(v) // [s, (0.5 - cx) * s, (0.5 - cy) * s]
    val s = tr[0]
    val cx = 0.5 - tr[1] / s
    val cy = 0.5 - tr[2] / s
    return doubleArrayOf(s, 1.0 - 2.0 * cx, 2.0 * cy - 1.0)
}

/** Video bitrate (bit/s) for the follow-cam output: zoomed frames are detailed, so a bit more than the encoder default. */
fun exportBitrate(w: Int, h: Int, fps: Double, hevc: Boolean): Int {
    val f = if (fps > 0) max(fps, 24.0) else 30.0
    val bps = w.toDouble() * h * f * (if (hevc) 0.07 else 0.11)
    return min(max(bps, 2_000_000.0), 100_000_000.0).toInt()
}
