package com.lanshare.app.core

/** Bounded, allocation-free per-frame measurements. Late means over the reported frame budget, not dropped display frames. */
class LumenFrameStats {
    private val samples = LongArray(4096)
    private var size = 0
    private var late = 0
    private var lost = 0L
    private var invalid = 0
    private var overflow = 0

    fun record(durationNs: Long, budgetNs: Long, droppedReports: Int) {
        lost += droppedReports.coerceAtLeast(0).toLong()
        if (durationNs <= 0 || budgetNs <= 0) { invalid++; return }
        if (size == samples.size) { overflow++; return }
        samples[size++] = durationNs
        if (durationNs > budgetNs) late++
    }

    fun snapshot(): Summary {
        val sorted = samples.copyOf(size).apply { sort() }
        fun percentile(p: Double): Long =
            if (size == 0) 0L else sorted[(kotlin.math.ceil(p * size).toInt() - 1).coerceIn(0, size - 1)]
        return Summary(size, late, lost, invalid, overflow, percentile(0.5), percentile(0.95), sorted.lastOrNull() ?: 0L)
    }

    data class Summary(val frames: Int, val late: Int, val lostReports: Long, val invalid: Int,
                       val overflow: Int, val medianNs: Long, val p95Ns: Long, val maxNs: Long) {
        val latePercent: Double get() = if (frames == 0) 0.0 else 100.0 * late / frames
    }
}
