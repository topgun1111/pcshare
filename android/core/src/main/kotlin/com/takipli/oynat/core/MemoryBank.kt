package com.takipli.oynat.core

import kotlin.math.max

/**
 * Static memory-slot layout of the EdgeTAM graphs (Python reference: `tracker/edgetam_bank.py`; export: `scripts/export_sam2.py`).
 * Slot 0 = the prompted (first) entry, slot d = the entry pushed d steps ago; a slot without history yet holds the first entry,
 * so the graph shapes never change. Only entries of tracked frames are pushed (a lost frame adds nothing).
 */
class MemoryBank<T : Any>(val numMem: Int = 7, val numPtr: Int = 16) {
    private var cond: T? = null
    private val hist = ArrayDeque<T>()

    fun reset(first: T) { cond = first; hist.clear() }

    fun push(entry: T) {
        hist.addLast(entry)
        while (hist.size > max(numMem, numPtr) - 1) hist.removeFirst()
    }

    val size: Int get() = hist.size

    fun slots(count: Int): List<T> {
        val c = checkNotNull(cond) { "bank not initialised" }
        val n = hist.size
        return List(count) { s -> if (s == 0 || s > n) c else hist[n - s] }
    }

    fun memSlots(): List<T> = slots(numMem)
    fun ptrSlots(): List<T> = slots(numPtr)
}
