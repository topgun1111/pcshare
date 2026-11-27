package com.lanshare.app.core

/** Viewport geometry shared by the native picture and video viewers. */
object LumenViewport {
    data class Image(val fit: Float, val scale: Float, val x: Float, val y: Float)

    fun image(ow: Int, oh: Int, w: Int, h: Int, bw: Int, bh: Int,
              scale: Float, x: Float, y: Float): Image? {
        if (ow <= 0 || oh <= 0 || w <= 0 || h <= 0 || bw <= 0 || bh <= 0 ||
            !scale.isFinite() || scale <= 0f || !x.isFinite() || !y.isFinite()) return null
        val oldFit = minOf(ow / bw.toFloat(), oh / bh.toFloat())
        val fit = minOf(w / bw.toFloat(), h / bh.toFloat())
        val relative = (scale / oldFit).coerceIn(1f, 8f)
        val next = fit * relative
        val cx = ((ow / 2f - x) / scale).coerceIn(0f, bw.toFloat())
        val cy = ((oh / 2f - y) / scale).coerceIn(0f, bh.toFloat())
        fun axis(size: Int, content: Int, center: Float): Float {
            val extent = content * next
            return if (extent <= size) (size - extent) / 2f
                else (size / 2f - center * next).coerceIn(size - extent, 0f)
        }
        return Image(fit, next, axis(w, bw, cx), axis(h, bh, cy))
    }

    fun rescaleOffset(offset: Float, oldSize: Int, newSize: Int): Float {
        if (!offset.isFinite()) return 0f
        if (oldSize <= 0 || newSize <= 0) return offset
        val result = offset * (newSize.toFloat() / oldSize)
        return if (result.isFinite()) result else 0f
    }
}
