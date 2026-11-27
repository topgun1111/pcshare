package com.lanshare.app.core

import org.junit.Assert.*
import org.junit.Test

class LumenFrameStatsTest {
    @Test fun empty() {
        val s = LumenFrameStats().snapshot()
        assertEquals(0, s.frames)
        assertEquals(0L, s.p95Ns)
        assertEquals(0.0, s.latePercent, 0.0)
    }

    @Test fun thresholdsAndPercentiles() {
        val stats = LumenFrameStats()
        listOf(10L, 20L, 30L, 40L).forEach { stats.record(it, 20L, 0) }
        val s = stats.snapshot()
        assertEquals(4, s.frames)
        assertEquals(2, s.late)
        assertEquals(50.0, s.latePercent, 0.0)
        assertEquals(20L, s.medianNs)
        assertEquals(40L, s.p95Ns)
        assertEquals(40L, s.maxNs)
    }

    @Test fun changingBudgetAndLostReports() {
        val stats = LumenFrameStats()
        stats.record(12, 16, 2)
        stats.record(12, 8, 1)
        stats.record(-1, 8, 3)
        stats.record(8, 0, 0)
        val s = stats.snapshot()
        assertEquals(2, s.frames)
        assertEquals(1, s.late)
        assertEquals(6L, s.lostReports)
        assertEquals(2, s.invalid)
    }

    @Test fun boundedSamples() {
        val stats = LumenFrameStats()
        repeat(5000) { stats.record(10, 20, 0) }
        val s = stats.snapshot()
        assertEquals(4096, s.frames)
        assertEquals(904, s.overflow)
    }
}
