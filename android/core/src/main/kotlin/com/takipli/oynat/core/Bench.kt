package com.takipli.oynat.core

import kotlin.math.ceil

/** Summary of per-run timings in milliseconds (`p95` = nearest rank). */
data class BenchStats(
    val n: Int,
    val meanMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val minMs: Double,
    val maxMs: Double,
) {
    /** Throughput at the mean time per frame. */
    val fps: Double get() = if (meanMs > 0.0) 1000.0 / meanMs else 0.0
}

fun benchStats(ms: List<Double>): BenchStats {
    require(ms.isNotEmpty()) { "no timings" }
    val s = ms.sorted()
    val n = s.size
    val median = if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    val p95 = s[ceil(0.95 * n).toInt().coerceIn(1, n) - 1]
    return BenchStats(n, s.sum() / n, median, p95, s.first(), s.last())
}

/** One line for the "Bench lines" list in `handover-android.md`. */
fun benchLine(
    device: String,
    model: String,
    provider: String,
    inputSize: Int,
    image: String,
    stats: BenchStats,
): String = "%s | %s | %s | %d | %s | %.1f ms/frame (median %.1f, p95 %.1f, n=%d) | %.1f fps".format(
    java.util.Locale.ROOT, device, model, provider, inputSize, image,
    stats.meanMs, stats.medianMs, stats.p95Ms, stats.n, stats.fps,
)

/** COCO-17 skeleton edges (joint index pairs) for drawing. */
val COCO_SKELETON: List<Pair<Int, Int>> = listOf(
    0 to 1, 0 to 2, 1 to 3, 2 to 4, // face
    5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10, // shoulders, arms
    5 to 11, 6 to 12, 11 to 12, // torso
    11 to 13, 13 to 15, 12 to 14, 14 to 16, // legs
)
