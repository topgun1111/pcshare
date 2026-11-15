package com.takipli.oynat.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryBankTest {
    private fun bank(n: Int) = MemoryBank<Int>().also { b -> b.reset(0); for (i in 1..n) b.push(i) }

    @Test fun onlyFirstEntryFillsEverySlot() {
        val b = bank(0)
        assertEquals(List(7) { 0 }, b.memSlots()); assertEquals(List(16) { 0 }, b.ptrSlots())
    }

    @Test fun shortHistoryPadsWithFirstEntry() {
        val b = bank(3)
        assertEquals(listOf(0, 3, 2, 1, 0, 0, 0), b.memSlots())
        assertEquals(listOf(0, 3, 2, 1) + List(12) { 0 }, b.ptrSlots())
    }

    @Test fun longHistoryKeepsRecentSlotsAndEvicts() {
        val b = bank(20)
        assertEquals(listOf(0, 20, 19, 18, 17, 16, 15), b.memSlots())
        assertEquals(listOf(0) + (20 downTo 6).toList(), b.ptrSlots())
        assertEquals(15, b.size)
    }

    @Test fun resetStartsOver() {
        val b = bank(5); b.reset(9)
        assertEquals(List(7) { 9 }, b.memSlots()); assertEquals(0, b.size)
    }
}
