package com.lanshare.app.core

/** Native adapter rows before the gallery are one item each; gallery adapter rows contain several cells. */
object LumenReflow {
    fun rowIndex(position: Int, galleryStart: Int, columns: Int, rowCount: Int): Int {
        if (position < 0 || galleryStart !in 0..rowCount || columns <= 0 || rowCount <= 0) return -1
        val index = if (position < galleryStart) position.toLong()
            else galleryStart.toLong() + (position - galleryStart).toLong() * columns
        return if (index in 0 until rowCount.toLong()) index.toInt() else -1
    }

    fun sameLocation(first: String, second: String): Boolean {
        val a = first.split('|', limit = 3)
        val b = second.split('|', limit = 3)
        return a.size == 3 && b.size == 3 && a[0] == b[0] && a[2] == b[2]
    }
}
